"""RedstoneAI MCP Server — 12 tools for AI-driven Minecraft redstone building."""

import asyncio
import json
import re
from typing import Any

from fastmcp import FastMCP

from .density import format_level1, format_level2, format_level3
from .errors import RedstoneConnectionError, RpcError
from .mcr import REFERENCE_CARD, validate
from .protocol import RedstoneProtocol
from .system_prompt import SYSTEM_PROMPT

mcp = FastMCP(
    "RedstoneAI",
    instructions=SYSTEM_PROMPT,
)

# Global protocol instance
_protocol = RedstoneProtocol()
_WORKSPACE_NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
_BLOCK_ID_RE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_/.-]+$")
_VALID_MODES = {"locked", "ai_only", "player_only", "collaborative"}
_VALID_IO_ROLES = {"input", "output", "monitor"}
_MAX_WORKSPACE_SIZE = 32
_MAX_TICK_COUNT = 10_000
_MAX_MCR_LENGTH = 8_192
_MAX_TEST_CASES = 128


def _require_workspace_name(name: str) -> None:
    if not _WORKSPACE_NAME_RE.fullmatch(name):
        raise ValueError("workspace name must match [A-Za-z0-9_-]{1,32}")


def _require_workspace_size(size_x: int, size_y: int, size_z: int) -> None:
    values = (size_x, size_y, size_z)
    if any(value < 4 or value > _MAX_WORKSPACE_SIZE for value in values):
        raise ValueError(f"workspace dimensions must be between 4 and {_MAX_WORKSPACE_SIZE}")


def _require_nonnegative_relative_coords(x: int, y: int, z: int) -> None:
    if min(x, y, z) < 0 or max(x, y, z) > _MAX_WORKSPACE_SIZE:
        raise ValueError(f"relative coordinates must be between 0 and {_MAX_WORKSPACE_SIZE}")


def _require_tick_count(count: int) -> None:
    if count < 1 or count > _MAX_TICK_COUNT:
        raise ValueError(f"count must be between 1 and {_MAX_TICK_COUNT}")


def _require_block_id(block_id: str) -> None:
    if not _BLOCK_ID_RE.fullmatch(block_id):
        raise ValueError("block must be a valid resource location like minecraft:stone")


def _require_mode(mode: str) -> None:
    if mode not in _VALID_MODES:
        raise ValueError(f"mode must be one of: {', '.join(sorted(_VALID_MODES))}")


def _require_role(role: str) -> None:
    if role not in _VALID_IO_ROLES:
        raise ValueError(f"role must be one of: {', '.join(sorted(_VALID_IO_ROLES))}")


def _require_range_window(from_tick: int, to_tick: int) -> None:
    if from_tick < 0:
        raise ValueError("from_tick must be >= 0")
    if to_tick != -1 and to_tick < from_tick:
        raise ValueError("to_tick must be -1 or >= from_tick")


def _require_test_cases(cases_list: Any) -> None:
    if not isinstance(cases_list, list):
        raise ValueError("cases must decode to a JSON array")
    if len(cases_list) > _MAX_TEST_CASES:
        raise ValueError(f"cases must contain at most {_MAX_TEST_CASES} entries")
    for case in cases_list:
        if not isinstance(case, dict):
            raise ValueError("each case must be an object")
        inputs = case.get("inputs", {})
        expected = case.get("expected", {})
        if not isinstance(inputs, dict) or not isinstance(expected, dict):
            raise ValueError("case inputs/expected must be objects")
        _require_tick_count(int(case.get("ticks", 10)))


def _tool_error(exc: Exception) -> str:
    return f"Validation error: {exc}"


async def _call(method: str, params: dict[str, Any] | None = None) -> Any:
    """Helper: call RPC through the synchronized protocol client."""
    return await _protocol.call(method, params)


async def _require_workspace_relative_coords(workspace: str, x: int, y: int, z: int) -> None:
    _require_nonnegative_relative_coords(x, y, z)
    info = await _call("workspace.info", {"name": workspace})
    size = info.get("size", [0, 0, 0])
    if len(size) != 3:
        raise ValueError("workspace size response is malformed")
    if x >= int(size[0]) or y >= int(size[1]) or z >= int(size[2]):
        raise ValueError("relative coordinates exceed the workspace dimensions")


# ── Tool 1: workspace ──────────────────────────────────────────────

@mcp.tool()
async def workspace(
    action: str,
    name: str = "",
    sizeX: int = 16,
    sizeY: int = 8,
    sizeZ: int = 16,
    mode: str = "",
) -> str:
    """Manage workspaces: create, delete, clear, list, info, set_mode.

    Actions:
    - create: Create new workspace (name, sizeX, sizeY, sizeZ required)
    - delete: Delete workspace (name required)
    - clear: Clear all blocks in workspace (name required)
    - list: List all workspaces
    - info: Get workspace details (name required)
    - set_mode: Set protection mode (name, mode required; modes: locked/ai_only/player_only/collaborative)
    """
    try:
        if action in {"create", "delete", "clear", "info", "set_mode"}:
            _require_workspace_name(name)
        if action == "create":
            _require_workspace_size(sizeX, sizeY, sizeZ)
        if action == "set_mode":
            _require_mode(mode)
    except ValueError as exc:
        return _tool_error(exc)

    match action:
        case "create":
            result = await _call("workspace.create", {"name": name, "sizeX": sizeX, "sizeY": sizeY, "sizeZ": sizeZ})
        case "delete":
            result = await _call("workspace.delete", {"name": name})
        case "clear":
            result = await _call("workspace.clear", {"name": name})
        case "list":
            result = await _call("workspace.list")
        case "info":
            result = await _call("workspace.info", {"name": name})
        case "set_mode":
            result = await _call("workspace.set_mode", {"name": name, "mode": mode})
        case _:
            return f"Unknown action: {action}. Use: create, delete, clear, list, info, set_mode"
    return json.dumps(result, indent=2)


# ── Tool 2: build ──────────────────────────────────────────────────

@mcp.tool()
async def build(
    workspace: str,
    mcr: str = "",
    block: str = "",
    x: int = 0,
    y: int = 0,
    z: int = 0,
) -> str:
    """Build blocks in a workspace using MCR notation or individual block placement.

    MCR mode (mcr parameter): Place multiple blocks using compact notation.
      Example: mcr="@origin 0,1,0 # # # @row D Rn2 D"
    Block mode (block parameter): Place a single block at x,y,z.
      Example: block="minecraft:stone", x=0, y=1, z=0
    """
    try:
        _require_workspace_name(workspace)
        if mcr:
            if len(mcr) > _MAX_MCR_LENGTH:
                raise ValueError(f"mcr must be at most {_MAX_MCR_LENGTH} characters")
        elif block:
            _require_block_id(block)
            await _require_workspace_relative_coords(workspace, x, y, z)
    except ValueError as exc:
        return _tool_error(exc)

    if mcr:
        valid, err = validate(mcr)
        if not valid:
            return f"MCR validation error: {err}"
        result = await _call("build.mcr", {"workspace": workspace, "mcr": mcr})
    elif block:
        result = await _call("build.block", {"workspace": workspace, "block": block, "x": x, "y": y, "z": z})
    else:
        return "Provide either 'mcr' string or 'block' + coordinates"
    return json.dumps(result, indent=2)


# ── Tool 3: snapshot ───────────────────────────────────────────────

@mcp.tool()
async def snapshot(
    workspace: str,
    action: str = "info",
) -> str:
    """Workspace snapshot operations: info (shows current state and recording status).

    Actions: info
    """
    if action != "info":
        return "Validation error: snapshot only supports action='info'"
    try:
        _require_workspace_name(workspace)
    except ValueError as exc:
        return _tool_error(exc)
    result = await _call("workspace.info", {"name": workspace})
    return json.dumps(result, indent=2)


# ── Tool 4: io ─────────────────────────────────────────────────────

@mcp.tool()
async def io(
    workspace: str,
    action: str,
    x: int = 0,
    y: int = 0,
    z: int = 0,
    role: str = "output",
    label: str = "",
) -> str:
    """Manage IO markers for monitoring redstone signals.

    Actions:
    - mark: Add IO marker (x, y, z, role, label required; role: input/output/monitor)
    - unmark: Remove IO marker at position (x, y, z required)
    - list: List all IO markers
    - status: Get current power levels at all IO markers
    """
    try:
        _require_workspace_name(workspace)
        if action in {"mark", "unmark"}:
            await _require_workspace_relative_coords(workspace, x, y, z)
        if action == "mark":
            _require_role(role)
            if not label or len(label) > 64:
                raise ValueError("label must be 1-64 characters")
    except ValueError as exc:
        return _tool_error(exc)

    params = {"workspace": workspace}
    match action:
        case "mark":
            params.update({"x": x, "y": y, "z": z, "role": role, "label": label})
            result = await _call("io.mark", params)
        case "unmark":
            params.update({"x": x, "y": y, "z": z})
            result = await _call("io.unmark", params)
        case "list":
            result = await _call("io.list", params)
        case "status":
            result = await _call("io.status", params)
        case _:
            return f"Unknown action: {action}. Use: mark, unmark, list, status"
    return json.dumps(result, indent=2)


# ── Tool 5: time ───────────────────────────────────────────────────

@mcp.tool()
async def time(
    workspace: str,
    action: str,
    count: int = 1,
) -> str:
    """Control time in a workspace.

    Actions:
    - freeze: Stop time (all redstone activity pauses)
    - unfreeze: Resume normal time
    - step: Advance by N ticks while frozen (count parameter)
    - rewind: Go back N ticks (count parameter)
    - fast_forward: Go forward N ticks or replay stored ticks (count parameter)
    """
    try:
        _require_workspace_name(workspace)
        if action in {"step", "rewind", "fast_forward"}:
            _require_tick_count(count)
    except ValueError as exc:
        return _tool_error(exc)

    params = {"workspace": workspace}
    match action:
        case "freeze":
            result = await _call("sim.freeze", params)
        case "unfreeze":
            result = await _call("sim.unfreeze", params)
        case "step":
            params["count"] = count
            result = await _call("sim.step", params)
        case "rewind":
            params["count"] = count
            result = await _call("sim.rewind", params)
        case "fast_forward":
            params["count"] = count
            result = await _call("sim.ff", params)
        case _:
            return f"Unknown action: {action}. Use: freeze, unfreeze, step, rewind, fast_forward"
    return json.dumps(result, indent=2)


# ── Tool 6: simulate ──────────────────────────────────────────────

@mcp.tool()
async def simulate(
    workspace: str,
    ticks: int = 10,
) -> str:
    """Run simulation: freeze, step N ticks, return Level 1 summary (~50 tokens).

    This is the most token-efficient way to test a circuit. Always start here.
    """
    try:
        _require_workspace_name(workspace)
        _require_tick_count(ticks)
    except ValueError as exc:
        return _tool_error(exc)
    if not (await _call("workspace.info", {"name": workspace})).get("frozen"):
        await _call("sim.freeze", {"workspace": workspace})
    result = await _call("sim.step", {"workspace": workspace, "count": ticks})
    return format_level1(result)


# ── Tool 7: timing ────────────────────────────────────────────────

@mcp.tool()
async def timing(
    workspace: str,
    from_tick: int = 0,
    to_tick: int = -1,
) -> str:
    """Get Level 2 ASCII timing diagram (~200 tokens). Shows IO signal changes over time.

    Use this when Level 1 (simulate) shows unexpected results.
    """
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_tick, to_tick)
    except ValueError as exc:
        return _tool_error(exc)
    params = {"workspace": workspace, "from": from_tick, "to": to_tick}
    result = await _call("sim.timing", params)
    return format_level2(result)


# ── Tool 8: detail ────────────────────────────────────────────────

@mcp.tool()
async def detail(
    workspace: str,
    from_tick: int = 0,
    to_tick: int = -1,
) -> str:
    """Get Level 3 tick-by-tick block changes (~500 tokens). Shows every block state change.

    Use this only when timing diagram doesn't explain the issue.
    """
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_tick, to_tick)
    except ValueError as exc:
        return _tool_error(exc)
    params = {"workspace": workspace, "from": from_tick, "to": to_tick}
    result = await _call("sim.detail", params)
    return format_level3(result)


# ── Tool 9: trace ─────────────────────────────────────────────────

@mcp.tool()
async def trace(
    workspace: str,
) -> str:
    """Get signal path and power map (~300 tokens). Shows current power levels at all IO markers."""
    try:
        _require_workspace_name(workspace)
    except ValueError as exc:
        return _tool_error(exc)
    result = await _call("io.status", {"workspace": workspace})
    return json.dumps(result, indent=2)


# ── Tool 10: test_suite ───────────────────────────────────────────

@mcp.tool()
async def test_suite(
    workspace: str,
    ticks: int = 10,
    cases: str = "[]",
) -> str:
    """Run batch truth-table tests (~100 tokens).

    cases: JSON array of test cases, each with "inputs" and "expected" dicts.
    Example: [{"inputs": {"A": 15, "B": 0}, "expected": {"OUT": 0}}]
    """
    try:
        cases_list = json.loads(cases)
    except json.JSONDecodeError:
        return "Invalid JSON in cases parameter"
    try:
        _require_workspace_name(workspace)
        _require_tick_count(ticks)
        _require_test_cases(cases_list)
    except ValueError as exc:
        return _tool_error(exc)

    result = await _call("test.define", {
        "workspace": workspace,
        "ticks": ticks,
        "cases": cases_list,
    })
    total = result.get("total", 0)
    passed = result.get("passed", 0)
    failed = result.get("failed", 0)
    summary = f"Tests: {passed}/{total} passed"
    if failed > 0:
        summary += f" ({failed} FAILED)"
        for r in result.get("results", []):
            if not r.get("pass"):
                summary += f"\n  FAIL: in={r.get('inputs')} expected={r.get('expected')} actual={r.get('actual')}"
    return summary


# ── Tool 11: status ───────────────────────────────────────────────

@mcp.tool()
async def status() -> str:
    """Check connection health and server status (~20 tokens)."""
    try:
        result = await _call("status")
        return json.dumps(result, indent=2)
    except Exception as e:
        return f"Disconnected: {e}"


# ── Tool 12: help ─────────────────────────────────────────────────

@mcp.tool()
async def help() -> str:
    """Get MCR format reference card (~200 tokens)."""
    return REFERENCE_CARD


def main():
    """Entry point for the MCP server."""
    mcp.run()


if __name__ == "__main__":
    main()
