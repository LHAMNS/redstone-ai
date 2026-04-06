import asyncio
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

    token_file = protocol._discover_token_file()
    assert token_file is None


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
