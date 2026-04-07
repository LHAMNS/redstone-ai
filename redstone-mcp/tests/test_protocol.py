import asyncio
import os
from importlib import util
from pathlib import Path
from types import SimpleNamespace

import pytest

import redstone_mcp.protocol as protocol


def _posix_os():
    return SimpleNamespace(name="posix", getenv=protocol.os.getenv)


def test_windows_token_path_is_translated_under_wsl():
    mount_root = Path("/mnt/c")
    if not mount_root.exists():
        pytest.skip("WSL mount not available")

    token_file = mount_root / "dev" / "redstone-mcp" / "tests" / "_protocol_token.txt"
    token_file.write_text("secret", encoding="utf-8")
    original_os = protocol.os
    try:
        monkey_os = _posix_os()
        protocol.os = monkey_os  # type: ignore[assignment]
        path = protocol._normalize_explicit_token_path(r"C:\dev\redstone-mcp\tests\_protocol_token.txt")
        assert path == token_file
    finally:
        protocol.os = original_os  # type: ignore[assignment]
        token_file.unlink(missing_ok=True)


def test_explicit_missing_token_path_is_authoritative(monkeypatch, tmp_path):
    fallback = tmp_path / "config" / protocol.DEFAULT_TOKEN_FILE_NAME
    fallback.parent.mkdir(parents=True)
    fallback.write_text("secret", encoding="utf-8")

    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv(protocol.AUTH_FILE_ENV_VAR, str(tmp_path / "missing" / "token.txt"))

    token_files = protocol._discover_token_files()
    assert token_files == []


def test_wsl_gateway_host_is_discovered_from_default_route(monkeypatch):
    monkeypatch.setattr(protocol, "_running_under_wsl", lambda: True)

    def fake_run(*args, **kwargs):
        return SimpleNamespace(stdout="default via 172.22.112.1 dev eth0 proto kernel\n")

    monkeypatch.setattr(protocol.subprocess, "run", fake_run)

    assert protocol._discover_wsl_gateway_host() == "172.22.112.1"


def test_discover_host_candidates_does_not_probe_wsl_on_posix(monkeypatch):
    monkeypatch.setattr(protocol, "_running_under_wsl", lambda: False)

    def should_not_run():
        raise AssertionError("WSL discovery should not run outside WSL")

    monkeypatch.setattr(protocol, "_discover_wsl_gateway_host", should_not_run)

    assert protocol.discover_host_candidates("localhost") == ["localhost"]


def test_discover_token_files_prefers_newest(monkeypatch, tmp_path):
    repo_root = tmp_path / "redstone-ai"
    run_config = repo_root / "run" / "config"
    gametest_config = repo_root / "run" / "gametest" / "config"
    run_config.mkdir(parents=True)
    gametest_config.mkdir(parents=True)
    older = run_config / protocol.DEFAULT_TOKEN_FILE_NAME
    newer = gametest_config / protocol.DEFAULT_TOKEN_FILE_NAME
    older.write_text("old", encoding="utf-8")
    newer.write_text("new", encoding="utf-8")
    os.utime(older, (1_700_000_000, 1_700_000_000))
    os.utime(newer, (1_700_000_100, 1_700_000_100))

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(protocol, "__file__", str((tmp_path / "redstone-mcp" / "src" / "redstone_mcp" / "protocol.py")))

    discovered = protocol._discover_token_files()
    assert discovered[0] == newer.resolve()
    assert older.resolve() in discovered


def test_load_auth_token_candidates_uses_env_token(monkeypatch):
    monkeypatch.setenv(protocol.AUTH_ENV_VAR, " secret ")
    candidates = protocol.load_auth_token_candidates()
    assert candidates == [("env:REDSTONE_AI_AUTH_TOKEN", "secret")]


def test_connect_locked_retries_next_token_candidate(monkeypatch):
    attempts = []

    class DummyConnection:
        state = protocol.State.OPEN

    async def fake_connect(uri, additional_headers=None, open_timeout=None, max_size=None):
        attempts.append((uri, additional_headers["Authorization"]))
        if additional_headers["Authorization"] == "Bearer stale":
            raise Exception("server rejected WebSocket connection: HTTP 401")
        return DummyConnection()

    monkeypatch.setattr(protocol, "load_auth_token_candidates", lambda: [("old", "stale"), ("new", "fresh")])
    monkeypatch.setattr(protocol, "discover_host_candidates", lambda host: ["127.0.0.1"])
    monkeypatch.setattr(protocol, "connect", fake_connect)

    client = protocol.RedstoneProtocol(timeout=1.0)
    asyncio.run(client.connect())

    assert attempts == [
        ("ws://127.0.0.1:4711/", "Bearer stale"),
        ("ws://127.0.0.1:4711/", "Bearer fresh"),
    ]


def test_recv_response_skips_notifications():
    class DummyWS:
        def __init__(self) -> None:
            self.state = object()
            self._messages = [
                '{"jsonrpc":"2.0","method":"log.message","params":{"text":"ignored"}}',
                '{"jsonrpc":"2.0","id":"other","result":{"ok":true}}',
                '{"jsonrpc":"2.0","id":"request-1","result":{"ok":true}}',
            ]

        async def recv(self) -> str:
            return self._messages.pop(0)

    async def run() -> dict[str, object]:
        client = protocol.RedstoneProtocol(timeout=1.0)
        client._ws = DummyWS()  # type: ignore[assignment]
        return await client._recv_response_locked("request-1")

    response = asyncio.run(run())
    assert response["result"] == {"ok": True}


def test_recv_response_rejects_malformed_json():
    class DummyWS:
        def __init__(self) -> None:
            self.state = object()

        async def recv(self) -> str:
            return "not-json"

    async def run() -> None:
        client = protocol.RedstoneProtocol(timeout=1.0)
        client._ws = DummyWS()  # type: ignore[assignment]
        with pytest.raises(protocol.ConnectionError, match="Malformed JSON-RPC response"):
            await client._recv_response_locked("request-1")

    asyncio.run(run())


def test_root_scripts_import_without_side_effects():
    root = Path(__file__).resolve().parents[1]
    for name in ("ws_test", "stress_test"):
        spec = util.spec_from_file_location(name, root / f"{name}.py")
        assert spec is not None and spec.loader is not None
        module = util.module_from_spec(spec)
        spec.loader.exec_module(module)
        assert getattr(module, "__test__", True) is False
