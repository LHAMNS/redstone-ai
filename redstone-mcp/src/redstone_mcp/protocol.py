"""WebSocket client for JSON-RPC 2.0 communication with the RedstoneAI Forge mod."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import os
import re
import subprocess
import uuid
from pathlib import Path
from typing import Any, Optional

import websockets
from websockets.asyncio.client import connect
from websockets.protocol import State

from .errors import ConnectionError, RpcError

AUTH_ENV_VAR = "REDSTONE_AI_AUTH_TOKEN"
AUTH_FILE_ENV_VAR = "REDSTONE_AI_AUTH_TOKEN_FILE"
DEFAULT_TOKEN_FILE_NAME = "redstone_ai_mcp_token.txt"
HOST_ENV_VAR = "REDSTONE_AI_HOST"
WINDOWS_DRIVE_PATH_RE = re.compile(r"^(?P<drive>[A-Za-z]):[\\/](?P<rest>.*)$")


def _discover_token_file() -> Optional[Path]:
    explicit = os.getenv(AUTH_FILE_ENV_VAR)
    if explicit:
        explicit_path = _normalize_explicit_token_path(explicit)
        if explicit_path is None or not explicit_path.exists():
            return None
        return explicit_path

    candidates = [
        Path.cwd() / "config" / DEFAULT_TOKEN_FILE_NAME,
        Path.cwd() / "run" / "config" / DEFAULT_TOKEN_FILE_NAME,
        Path(__file__).resolve().parents[3] / "redstone-ai" / "run" / "config" / DEFAULT_TOKEN_FILE_NAME,
        Path(__file__).resolve().parents[2] / "config" / DEFAULT_TOKEN_FILE_NAME,
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def _is_ipv4_address(candidate: str) -> bool:
    try:
        return ipaddress.ip_address(candidate).version == 4
    except ValueError:
        return False


def _running_under_wsl() -> bool:
    if os.name == "nt":
        return False

    if os.getenv("WSL_INTEROP") or os.getenv("WSL_DISTRO_NAME"):
        return True

    try:
        release = Path("/proc/sys/kernel/osrelease").read_text(encoding="utf-8").strip().lower()
    except OSError:
        return False

    return "microsoft" in release or "wsl" in release


def _discover_wsl_gateway_host(resolv_conf: Path = Path("/etc/resolv.conf")) -> Optional[str]:
    _ = resolv_conf
    if not _running_under_wsl():
        return None

    try:
        result = subprocess.run(
            ["ip", "route", "show", "default"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        for line in result.stdout.splitlines():
            line = line.strip()
            if not line.startswith("default via "):
                continue
            try:
                candidate = line.split()[2]
            except IndexError:
                continue
            if candidate and _is_ipv4_address(candidate) and not candidate.startswith("127."):
                return candidate
    except (OSError, subprocess.SubprocessError):
        return None

    return None


def _normalize_explicit_token_path(raw_path: str) -> Optional[Path]:
    path = Path(raw_path).expanduser()
    if path.exists():
        return path
    if os.name != "nt":
        match = WINDOWS_DRIVE_PATH_RE.match(raw_path)
        if match:
            drive = match.group("drive").lower()
            rest = match.group("rest").replace("\\", "/")
            translated = Path("/mnt") / drive / rest
            return translated
    return path


def load_auth_token() -> Optional[str]:
    token = os.getenv(AUTH_ENV_VAR)
    if token:
        return token.strip()

    token_file = _discover_token_file()
    if token_file is None:
        return None
    return token_file.read_text(encoding="utf-8").strip() or None


def discover_host_candidates(host: str) -> list[str]:
    explicit = os.getenv(HOST_ENV_VAR)
    if explicit:
        return [explicit]

    candidates = [host]
    if host in {"127.0.0.1", "localhost"} and _running_under_wsl():
        gateway = _discover_wsl_gateway_host()
        if gateway and gateway not in candidates:
            candidates.append(gateway)
    return candidates


class RedstoneProtocol:
    """Manages WebSocket connection and JSON-RPC 2.0 messaging."""

    def __init__(self, host: str = "127.0.0.1", port: int = 4711, timeout: float = 60.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._ws: Optional[websockets.asyncio.client.ClientConnection] = None
        self._lock = asyncio.Lock()

    async def connect(self) -> None:
        """Establish WebSocket connection to the Forge mod."""
        async with self._lock:
            await self._connect_locked()

    async def disconnect(self) -> None:
        """Close the WebSocket connection."""
        async with self._lock:
            await self._disconnect_locked()

    async def call(self, method: str, params: dict[str, Any] | None = None) -> Any:
        """Send a JSON-RPC 2.0 request and return the result."""
        async with self._lock:
            await self._connect_locked()
            assert self._ws is not None

            request_id = str(uuid.uuid4())
            request: dict[str, Any] = {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": method,
            }
            if params is not None:
                request["params"] = params

            try:
                await self._ws.send(json.dumps(request))
                response = await self._recv_response_locked(request_id)
            except asyncio.TimeoutError as exc:
                await self._disconnect_locked()
                raise ConnectionError("RPC call timed out") from exc
            except RpcError:
                raise
            except Exception as exc:
                await self._disconnect_locked()
                raise ConnectionError(f"WebSocket error: {exc}") from exc

            if "error" in response:
                err = response["error"]
                raise RpcError(err.get("code", -1), err.get("message", "Unknown error"))

            return response.get("result")

    @property
    def connected(self) -> bool:
        if self._ws is None:
            return False
        try:
            return self._ws.state is State.OPEN
        except Exception:
            return False

    async def _connect_locked(self) -> None:
        if self.connected:
            return

        token = load_auth_token()
        if not token:
            raise ConnectionError(
                "Missing RedstoneAI auth token. Set REDSTONE_AI_AUTH_TOKEN or provide the token file."
            )

        errors: list[str] = []
        for host_candidate in discover_host_candidates(self.host):
            uri = f"ws://{host_candidate}:{self.port}/"
            try:
                self._ws = await connect(
                    uri,
                    additional_headers={"Authorization": f"Bearer {token}"},
                    open_timeout=10.0,
                    max_size=65536,
                )
                return
            except Exception as exc:
                self._ws = None
                errors.append(f"{uri}: {exc}")

        raise ConnectionError("Failed to connect to RedstoneAI server: " + " | ".join(errors))

    async def _disconnect_locked(self) -> None:
        if self._ws is not None:
            await self._ws.close()
            self._ws = None

    async def _recv_response_locked(self, request_id: str) -> dict[str, Any]:
        assert self._ws is not None
        deadline = asyncio.get_running_loop().time() + self.timeout
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise asyncio.TimeoutError
            response_text = await asyncio.wait_for(self._ws.recv(), timeout=remaining)
            try:
                response = json.loads(response_text)
            except (TypeError, ValueError) as exc:
                raise ConnectionError("Malformed JSON-RPC response") from exc

            if not isinstance(response, dict):
                raise ConnectionError("Malformed JSON-RPC response")
            if response.get("jsonrpc") != "2.0":
                raise ConnectionError("Invalid JSON-RPC version in response")

            response_id = response.get("id")
            if response_id != request_id:
                if "result" in response or "error" in response or "method" in response:
                    continue
                raise ConnectionError("Malformed JSON-RPC response")

            if "result" not in response and "error" not in response:
                raise ConnectionError("Malformed JSON-RPC response")
            return response
