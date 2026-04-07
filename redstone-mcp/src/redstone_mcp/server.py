"""RedstoneAI MCP Server — 12 tools for AI-driven Minecraft redstone building."""

import asyncio
import json
import re
from typing import Any

from fastmcp import FastMCP

from .analysis import (
    component_graph_data,
    filter_component_graph,
    filter_signal_graph,
    format_baseline_diff,
    format_component_graph,
    format_impact,
    format_mechanisms,
    format_neighborhood,
    format_orthographic,
    format_signal_graph,
    format_trace_path,
    resolve_endpoint,
    signal_graph_data,
    watch_summary,
)
from .density import format_level1, format_level2, format_level3
from .errors import ConnectionError, RpcError
from .inspect import format_components, format_layers, format_summary
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
_VALID_ENTITY_FILTERS = {"all_non_player", "mechanical_only", "none"}
_VALID_WORKSPACE_PERMISSIONS = {"build", "time", "history", "chat", "settings"}
_VALID_NBT_MODES = {"merge", "replace"}
_MAX_WORKSPACE_SIZE = 64  # Must match RAIConfig.SERVER.maxWorkspaceSize upper limit
_MAX_TICK_COUNT = 10_000
_MAX_MCR_LENGTH = 8_192
_MAX_TEST_CASES = 128
_MAX_HISTORY_LIMIT = 200
_MODE_ALIASES = {
    "locked": "locked",
    "lock": "locked",
    "ai_only": "ai_only",
    "ai": "ai_only",
    "player_only": "player_only",
    "player": "player_only",
    "collaborative": "collaborative",
    "collab": "collaborative",
}
_ENTITY_FILTER_ALIASES = {
    "all_non_player": "all_non_player",
    "all": "all_non_player",
    "mechanical_only": "mechanical_only",
    "mechanical": "mechanical_only",
    "none": "none",
}
_PERMISSION_ALIASES = {
    "build": "build",
    "time": "time",
    "time_control": "time",
    "history": "history",
    "view_history": "history",
    "chat": "chat",
    "settings": "settings",
    "manage_settings": "settings",
}


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


def _require_entity_filter(entity_filter: str) -> None:
    if entity_filter not in _VALID_ENTITY_FILTERS:
        raise ValueError(f"entity_filter must be one of: {', '.join(sorted(_VALID_ENTITY_FILTERS))}")


def _require_nbt_mode(mode: str) -> None:
    if mode not in _VALID_NBT_MODES:
        raise ValueError(f"mode must be one of: {', '.join(sorted(_VALID_NBT_MODES))}")


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
        _require_signal_map(inputs, "inputs")
        _require_signal_map(expected, "expected")
        _require_tick_count(int(case.get("ticks", 10)))


def _require_history_limit(limit: int) -> None:
    if limit < 1 or limit > _MAX_HISTORY_LIMIT:
        raise ValueError(f"limit must be between 1 and {_MAX_HISTORY_LIMIT}")


def _require_power_level(power: int) -> None:
    if power < 0 or power > 15:
        raise ValueError("power must be between 0 and 15")


def _require_signal_map(raw: Any, field_name: str) -> None:
    if not isinstance(raw, dict):
        raise ValueError(f"{field_name} must be an object")
    for label, value in raw.items():
        if not isinstance(label, str) or not label.strip():
            raise ValueError(f"{field_name} labels must be non-empty strings")
        if not isinstance(value, int):
            raise ValueError(f"{field_name}.{label} must be an integer")
        _require_power_level(value)


def _normalize_mode(mode: str) -> str:
    return _MODE_ALIASES.get(mode.strip().lower(), mode.strip().lower())


def _normalize_entity_filter(entity_filter: str) -> str:
    return _ENTITY_FILTER_ALIASES.get(entity_filter.strip().lower(), entity_filter.strip().lower())


def _normalize_permission(permission: str) -> str:
    return _PERMISSION_ALIASES.get(permission.strip().lower(), permission.strip().lower())


def _normalize_workspace_config(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("config must decode to a JSON object")

    config: dict[str, Any] = {}
    alias_map = {
        "mode": "mode",
        "entity_filter": "entityFilter",
        "entityfilter": "entityFilter",
        "authorized_players": "authorizedPlayers",
        "authorizedplayers": "authorizedPlayers",
        "permission_grants": "permissionGrants",
        "permissiongrants": "permissionGrants",
        "allow_commands": "allowVanillaCommands",
        "allowvanillacommands": "allowVanillaCommands",
        "allow_frozen_teleport": "allowFrozenEntityTeleport",
        "allowfrozenentityteleport": "allowFrozenEntityTeleport",
        "allow_frozen_damage": "allowFrozenEntityDamage",
        "allowfrozenentitydamage": "allowFrozenEntityDamage",
        "allow_frozen_collision": "allowFrozenEntityCollision",
        "allowfrozenentitycollision": "allowFrozenEntityCollision",
    }

    for key, value in raw.items():
        canonical = alias_map.get(key, key)
        config[canonical] = value

    if "mode" in config:
        if not isinstance(config["mode"], str):
            raise ValueError("config.mode must be a string")
        config["mode"] = _normalize_mode(config["mode"])
        _require_mode(config["mode"])

    if "entityFilter" in config:
        if not isinstance(config["entityFilter"], str):
            raise ValueError("config.entityFilter must be a string")
        config["entityFilter"] = _normalize_entity_filter(config["entityFilter"])
        _require_entity_filter(config["entityFilter"])

    if "authorizedPlayers" in config:
        players = config["authorizedPlayers"]
        if not isinstance(players, (str, list)):
            raise ValueError("config.authorizedPlayers must be a string or array")
        if isinstance(players, list):
            normalized_players: list[str] = []
            for player in players:
                if not isinstance(player, str):
                    raise ValueError("config.authorizedPlayers entries must be strings")
                stripped = player.strip()
                if stripped:
                    normalized_players.append(stripped)
            config["authorizedPlayers"] = normalized_players

    if "permissionGrants" in config:
        grants = config["permissionGrants"]
        if not isinstance(grants, list):
            raise ValueError("config.permissionGrants must be an array")
        normalized_grants: list[dict[str, Any]] = []
        for grant in grants:
            if not isinstance(grant, dict):
                raise ValueError("config.permissionGrants entries must be objects")
            player = grant.get("player")
            permissions = grant.get("permissions")
            if not isinstance(player, str) or not player.strip():
                raise ValueError("each permission grant must include a non-empty player")
            if isinstance(permissions, str):
                permission_tokens = [token.strip() for token in permissions.split(",") if token.strip()]
            elif isinstance(permissions, list):
                permission_tokens = []
                for token in permissions:
                    if not isinstance(token, str):
                        raise ValueError("permission values must be strings")
                    stripped = token.strip()
                    if stripped:
                        permission_tokens.append(stripped)
            else:
                raise ValueError("each permission grant must include permissions")

            normalized_permissions: list[str] = []
            for token in permission_tokens:
                normalized = _normalize_permission(token)
                if normalized not in _VALID_WORKSPACE_PERMISSIONS:
                    raise ValueError(
                        f"permission must be one of: {', '.join(sorted(_VALID_WORKSPACE_PERMISSIONS))}"
                    )
                normalized_permissions.append(normalized)
            if not normalized_permissions:
                raise ValueError("each permission grant must include at least one permission")
            normalized_grants.append({"player": player.strip(), "permissions": normalized_permissions})
        config["permissionGrants"] = normalized_grants

    for bool_key in (
        "allowVanillaCommands",
        "allowFrozenEntityTeleport",
        "allowFrozenEntityDamage",
        "allowFrozenEntityCollision",
    ):
        if bool_key in config and not isinstance(config[bool_key], bool):
            raise ValueError(f"config.{bool_key} must be true or false")

    return config


def _tool_error(exc: Exception) -> str:
    return f"Validation error: {exc}"


async def _call(method: str, params: dict[str, Any] | None = None) -> Any:
    """Helper: call RPC through the synchronized protocol client."""
    return await _protocol.call(method, params)


def _sanitize_workspace_result(result: Any) -> Any:
    if not isinstance(result, dict):
        return result

    sanitized = dict(result)
    sanitized.pop("origin", None)
    sanitized.pop("controller", None)
    sanitized["coordinateSystem"] = "workspace_local"
    sanitized["localOrigin"] = [0, 0, 0]
    if "size" in sanitized:
        local_max = [max(0, int(axis) - 1) for axis in sanitized["size"]]
        sanitized["localMax"] = local_max
        sanitized["validRange"] = {"from": [0, 0, 0], "to": local_max}
    return sanitized


def _sanitize_workspace_list_result(result: Any) -> Any:
    if not isinstance(result, dict):
        return result

    sanitized = dict(result)
    workspaces = sanitized.get("workspaces")
    if isinstance(workspaces, list):
        sanitized["workspaces"] = [_sanitize_workspace_result(item) for item in workspaces]
    sanitized["coordinateSystem"] = "workspace_local"
    sanitized["localOrigin"] = [0, 0, 0]
    return sanitized


def _sanitize_scan_result(result: Any) -> Any:
    if not isinstance(result, dict):
        return result

    sanitized = dict(result)
    sanitized.pop("origin", None)
    sanitized.pop("controller", None)
    sanitized["coordinateSystem"] = "workspace_local"
    sanitized["localOrigin"] = [0, 0, 0]
    size = sanitized.get("size", [0, 0, 0])
    if isinstance(size, list) and len(size) == 3:
        local_max = [max(0, int(axis) - 1) for axis in size]
        sanitized["localMax"] = local_max
        sanitized["validRange"] = {"from": [0, 0, 0], "to": local_max}
    sanitized["viewRange"] = {
        "from": [
            int(sanitized.get("fromX", 0)),
            int(sanitized.get("fromY", 0)),
            int(sanitized.get("fromZ", 0)),
        ],
        "to": [
            int(sanitized.get("toX", 0)),
            int(sanitized.get("toY", 0)),
            int(sanitized.get("toZ", 0)),
        ],
    }
    return sanitized


async def _require_workspace_relative_coords(workspace: str, x: int, y: int, z: int) -> None:
    _require_nonnegative_relative_coords(x, y, z)
    info = await _call("workspace.info", {"name": workspace})
    size = info.get("size", [0, 0, 0])
    if len(size) != 3:
        raise ValueError("workspace size response is malformed")
    if x >= int(size[0]) or y >= int(size[1]) or z >= int(size[2]):
        raise ValueError("relative coordinates exceed the workspace dimensions")


async def _scan_workspace(
    workspace: str,
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
) -> dict[str, Any]:
    return _sanitize_scan_result(
        await _call(
            "workspace.scan",
            {
                "name": workspace,
                "fromX": from_x,
                "toX": to_x,
                "fromY": from_y,
                "toY": to_y,
                "fromZ": from_z,
                "toZ": to_z,
            },
        )
    )


async def _scan_neighborhood(workspace: str, x: int, y: int, z: int, radius: int) -> dict[str, Any]:
    info = _sanitize_workspace_result(await _call("workspace.info", {"name": workspace}))
    size = info.get("size", [0, 0, 0])
    max_x = int(size[0]) - 1
    max_y = int(size[1]) - 1
    max_z = int(size[2]) - 1
    return await _scan_workspace(
        workspace,
        from_x=max(0, x - radius),
        to_x=min(max_x, x + radius),
        from_y=max(0, y - radius),
        to_y=min(max_y, y + radius),
        from_z=max(0, z - radius),
        to_z=min(max_z, z + radius),
    )


def _filter_timing_rows(timing_text: str, labels: list[str]) -> str:
    if not labels:
        return timing_text
    wanted = {f"M:{label}" for label in labels}
    lines = timing_text.splitlines()
    filtered: list[str] = []
    for line in lines:
        if line.startswith("tick ") or line.startswith("----"):
            filtered.append(line)
            continue
        prefix = line.split("|", 1)[0].strip()
        if prefix in wanted:
            filtered.append(line)
    return "\n".join(filtered) if filtered else "No matching watch rows"


def _classify_stability(samples: list[tuple[tuple[str, int], ...]], quiet_ticks: int, period_limit: int) -> tuple[str, int | None]:
    if not samples:
        return "unsettled", None
    if len(samples) >= quiet_ticks and len(set(samples[-quiet_ticks:])) == 1:
        return "stable", None
    for period in range(1, min(period_limit, len(samples) // 2) + 1):
        tail = samples[-period:]
        if len(samples) >= period * 3 and samples[-period * 3 : -period * 2] == tail and samples[-period * 2 : -period] == tail:
            return "oscillating", period
    return "unsettled", None


def _state_signature(scan: dict[str, Any]) -> tuple[Any, ...]:
    block_sig = tuple(
        sorted(
            (
                int(block["x"]),
                int(block["y"]),
                int(block["z"]),
                str(block["block"]),
                tuple(sorted((str(k), str(v)) for k, v in block.get("properties", {}).items())),
                str(block.get("nbt", "")),
            )
            for block in scan.get("blocks", [])
        )
    )
    entity_sig = tuple(
        sorted(
            (
                str(entity.get("uuid", "")),
                str(entity["type"]),
                round(float(entity.get("exactX", entity["x"])), 4),
                round(float(entity.get("exactY", entity["y"])), 4),
                round(float(entity.get("exactZ", entity["z"])), 4),
                str(entity.get("nbt", "")),
            )
            for entity in scan.get("entities", [])
        )
    )
    return block_sig, entity_sig


# ── Tool 1: workspace ──────────────────────────────────────────────

@mcp.tool()
async def workspace(
    action: str,
    name: str = "",
    sizeX: int = 16,
    sizeY: int = 8,
    sizeZ: int = 16,
    mode: str = "",
    config: str = "{}",
    limit: int = 20,
) -> str:
    """Manage workspaces: create, delete, clear, list, info, set_mode, configure, history, revert.

    Actions:
    - create: Create new workspace (name, sizeX, sizeY, sizeZ required)
    - delete: Delete workspace (name required)
    - clear: Clear all blocks in workspace (name required)
    - list: List all workspaces
    - info: Get workspace details (name required)
    - set_mode: Set protection mode (name, mode required; modes: locked/ai_only/player_only/collaborative)
    - configure: Update detailed workspace settings using JSON in config
    - history: Read operation log and chat history (name required, optional limit)
    - revert: Restore the workspace to its initial snapshot (name required)
    """
    try:
        if action in {"create", "delete", "clear", "info", "set_mode", "configure", "history", "revert"}:
            _require_workspace_name(name)
        if action == "create":
            _require_workspace_size(sizeX, sizeY, sizeZ)
        if action == "set_mode":
            mode = _normalize_mode(mode)
            _require_mode(mode)
        if action == "history":
            _require_history_limit(limit)
    except ValueError as exc:
        return _tool_error(exc)

    match action:
        case "create":
            result = _sanitize_workspace_result(
                await _call("workspace.create", {"name": name, "sizeX": sizeX, "sizeY": sizeY, "sizeZ": sizeZ})
            )
        case "delete":
            result = await _call("workspace.delete", {"name": name})
        case "clear":
            result = await _call("workspace.clear", {"name": name})
        case "list":
            result = _sanitize_workspace_list_result(await _call("workspace.list"))
        case "info":
            result = _sanitize_workspace_result(await _call("workspace.info", {"name": name}))
        case "set_mode":
            result = _sanitize_workspace_result(await _call("workspace.set_mode", {"name": name, "mode": mode}))
        case "configure":
            try:
                config_dict = _normalize_workspace_config(json.loads(config))
            except json.JSONDecodeError:
                return "Invalid JSON in config parameter"
            except ValueError as exc:
                return _tool_error(exc)
            params = {"name": name}
            params.update(config_dict)
            result = _sanitize_workspace_result(await _call("workspace.configure", params))
        case "history":
            result = _sanitize_workspace_result(await _call("workspace.history", {"name": name, "limit": limit}))
        case "revert":
            result = _sanitize_workspace_result(await _call("workspace.revert", {"name": name}))
        case _:
            return (
                "Unknown action: "
                f"{action}. Use: create, delete, clear, list, info, set_mode, configure, history, revert"
            )
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
    properties: str = "{}",
) -> str:
    """Build blocks in a workspace using MCR notation or individual block placement.

    MCR mode (mcr parameter): Place multiple blocks using compact notation.
      Example: mcr="@origin 0,1,0 # # # @row D Rn2 D"
    Block mode (block parameter): Place a single block at x,y,z.
      Example: block="minecraft:repeater", x=0, y=1, z=0, properties="{\"facing\":\"east\",\"delay\":4}"
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
        try:
            property_dict = json.loads(properties)
        except json.JSONDecodeError:
            return "Invalid JSON in properties parameter"
        if not isinstance(property_dict, dict):
            return "Validation error: properties must decode to a JSON object"
        normalized_properties: dict[str, str] = {}
        for key, value in property_dict.items():
            if not isinstance(key, str):
                return "Validation error: property names must be strings"
            if isinstance(value, (str, int, float, bool)):
                normalized_properties[key] = str(value).lower() if isinstance(value, bool) else str(value)
            else:
                return f"Validation error: property '{key}' must be a string/number/bool"
        result = await _call(
            "build.block",
            {
                "workspace": workspace,
                "block": block,
                "x": x,
                "y": y,
                "z": z,
                "properties": normalized_properties,
            },
        )
    else:
        return "Provide either 'mcr' string or 'block' + coordinates"
    output = json.dumps(result, indent=2)
    skipped = result.get("skipped", 0) if isinstance(result, dict) else 0
    if skipped > 0:
        output += f"\nWARNING: {skipped} block(s) were out of workspace bounds and skipped"
    return output


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
    result = _sanitize_workspace_result(await _call("workspace.info", {"name": workspace}))
    return json.dumps(result, indent=2)


@mcp.tool()
async def block_entity(
    workspace: str,
    x: int,
    y: int,
    z: int,
    nbt: str,
    mode: str = "merge",
) -> str:
    """Write block-entity NBT inside a workspace.

    `nbt` must be SNBT. Example:
    `{Items:[{Slot:0b,id:"minecraft:redstone",Count:32b}]}`
    """
    try:
        _require_workspace_name(workspace)
        await _require_workspace_relative_coords(workspace, x, y, z)
        _require_nbt_mode(mode)
    except ValueError as exc:
        return _tool_error(exc)

    result = await _call(
        "block_entity.write",
        {
            "workspace": workspace,
            "x": x,
            "y": y,
            "z": z,
            "nbt": nbt,
            "mode": mode,
        },
    )
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
    power: int = 15,
    values: str = "{}",
) -> str:
    """Manage IO markers for monitoring redstone signals.

    Actions:
    - mark: Add IO marker (x, y, z, role, label required; role: input/output/monitor)
    - unmark: Remove IO marker at position (x, y, z required)
    - list: List all IO markers
    - status: Get current power levels at all IO markers
    - drive: Set one input by label/power or many inputs via values JSON
    - clear_inputs: Reset every INPUT marker to 0
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
        case "drive":
            if label:
                try:
                    _require_power_level(power)
                except ValueError as exc:
                    return _tool_error(exc)
                params.update({"label": label, "power": power})
            else:
                try:
                    parsed_values = json.loads(values)
                except json.JSONDecodeError:
                    return "Invalid JSON in values parameter"
                try:
                    _require_signal_map(parsed_values, "values")
                except ValueError as exc:
                    return _tool_error(exc)
                params["values"] = parsed_values
            result = await _call("io.drive", params)
        case "clear_inputs":
            result = await _call("io.clear_inputs", params)
        case _:
            return f"Unknown action: {action}. Use: mark, unmark, list, status, drive, clear_inputs"
    return json.dumps(result, indent=2)


# ── Tool 5: time ───────────────────────────────────────────────────

@mcp.tool()
async def time(
    workspace: str,
    action: str,
    count: int = 1,
    quiet_ticks: int = 2,
) -> str:
    """Control time in a workspace.

    Actions:
    - freeze: Stop time (all redstone activity pauses)
    - unfreeze: Resume normal time
    - step: Advance by N ticks while frozen (count parameter)
    - rewind: Go back N ticks (count parameter)
    - fast_forward: Go forward N ticks or replay stored ticks (count parameter)
    - settle: Advance until the circuit is quiet for quiet_ticks consecutive ticks, up to count ticks
    """
    try:
        _require_workspace_name(workspace)
        if action in {"step", "rewind", "fast_forward", "settle"}:
            _require_tick_count(count)
        if action == "settle":
            _require_tick_count(quiet_ticks)
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
        case "settle":
            params["count"] = count
            params["quietTicks"] = quiet_ticks
            result = await _call("sim.settle", params)
        case _:
            return f"Unknown action: {action}. Use: freeze, unfreeze, step, rewind, fast_forward, settle"
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


@mcp.tool()
async def probe(
    workspace: str,
    ticks: int = 10,
    inputs: str = "{}",
    expected: str = "{}",
) -> str:
    """Run one focused input vector and return the actual outputs quickly."""
    try:
        inputs_map = json.loads(inputs)
        expected_map = json.loads(expected)
    except json.JSONDecodeError:
        return "Invalid JSON in inputs or expected parameter"
    try:
        _require_workspace_name(workspace)
        _require_tick_count(ticks)
        _require_signal_map(inputs_map, "inputs")
        _require_signal_map(expected_map, "expected")
    except ValueError as exc:
        return _tool_error(exc)

    result = await _call(
        "test.run",
        {
            "workspace": workspace,
            "ticks": ticks,
            "inputs": inputs_map,
            "expected": expected_map,
        },
    )
    actual = result.get("actual", {})
    summary = f"Probe after {ticks} tick(s): actual={actual}"
    if expected_map:
        summary += f" expected={expected_map} pass={result.get('pass', False)}"
    return summary


@mcp.tool()
async def entity(
    workspace: str,
    action: str,
    entity_type: str = "",
    uuid: str = "",
    x: float | None = None,
    y: float | None = None,
    z: float | None = None,
    yaw: float | None = None,
    pitch: float | None = None,
    nbt: str = "{}",
    mode: str = "merge",
) -> str:
    """Manage non-player entities inside a workspace.

    Actions:
    - spawn: create an entity at relative x/y/z
    - update: edit an entity by uuid, optionally moving it and/or applying SNBT
    - remove: remove an entity by uuid
    - clear: remove all non-player entities in the workspace, optionally filtered by entity_type
    """
    try:
        _require_workspace_name(workspace)
        if action in {"spawn", "clear"} and entity_type:
            _require_block_id(entity_type)
        if action == "spawn" and not entity_type:
            raise ValueError("entity_type is required for spawn")
        if action == "spawn" and (x is None or y is None or z is None):
            raise ValueError("x, y, and z are required for spawn")
        if action == "update":
            _require_nbt_mode(mode)
            if not uuid:
                raise ValueError("uuid is required for update")
        if action == "remove" and not uuid:
            raise ValueError("uuid is required for remove")
    except ValueError as exc:
        return _tool_error(exc)

    match action:
        case "spawn":
            result = await _call(
                "entity.spawn",
                {
                    "workspace": workspace,
                    "entityType": entity_type,
                    "x": x,
                    "y": y,
                    "z": z,
                    "nbt": nbt,
                    **({"yaw": yaw} if yaw is not None else {}),
                    **({"pitch": pitch} if pitch is not None else {}),
                },
            )
        case "update":
            params = {
                "workspace": workspace,
                "uuid": uuid,
                "nbt": nbt,
                "mode": mode,
            }
            if x is not None:
                params["x"] = x
            if y is not None:
                params["y"] = y
            if z is not None:
                params["z"] = z
            if yaw is not None:
                params["yaw"] = yaw
            if pitch is not None:
                params["pitch"] = pitch
            result = await _call("entity.update", params)
        case "remove":
            result = await _call("entity.remove", {"workspace": workspace, "uuid": uuid})
        case "clear":
            params = {"workspace": workspace}
            if entity_type:
                params["entityType"] = entity_type
            result = await _call("entity.clear", params)
        case _:
            return "Unknown action: use spawn, update, remove, or clear"
    return json.dumps(result, indent=2)


@mcp.tool()
async def orthographic(
    workspace: str,
    plane: str = "all",
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
) -> str:
    """Show a top/front/side orthographic projection of a workspace region."""
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_workspace(workspace, from_x, to_x, from_y, to_y, from_z, to_z)
    if plane == "raw":
        return json.dumps(scan, indent=2)
    return format_orthographic(scan, plane)


@mcp.tool()
async def neighborhood(
    workspace: str,
    x: int,
    y: int,
    z: int,
    radius: int = 2,
    view: str = "summary",
) -> str:
    """Inspect a small local cube around one workspace-local coordinate."""
    try:
        _require_workspace_name(workspace)
        _require_nonnegative_relative_coords(x, y, z)
        if radius < 0 or radius > _MAX_WORKSPACE_SIZE:
            raise ValueError(f"radius must be between 0 and {_MAX_WORKSPACE_SIZE}")
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_neighborhood(workspace, x, y, z, radius)
    if view == "summary":
        return format_neighborhood(scan, (x, y, z), radius)
    if view == "layers":
        return format_layers(scan)
    if view == "components":
        return format_components(scan)
    if view == "raw":
        return json.dumps(scan, indent=2)
    return "Unknown view: use summary, layers, components, or raw"


@mcp.tool()
async def component_graph(
    workspace: str,
    root_label: str = "",
    root_node: str = "",
    max_hops: int = 3,
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
    view: str = "summary",
    limit: int = 40,
) -> str:
    """Build a redstone component adjacency graph for a workspace region."""
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_workspace(workspace, from_x, to_x, from_y, to_y, from_z, to_z)
    graph = component_graph_data(scan)
    root_id = root_node or (resolve_endpoint(scan, root_label) if root_label else None)
    filtered = filter_component_graph(graph, root_id, max_hops, limit)
    if view == "raw":
        return json.dumps(
            {
                "coordinateSystem": "workspace_local",
                "validRange": scan.get("validRange"),
                "viewRange": scan.get("viewRange"),
                "graph": filtered,
            },
            indent=2,
        )
    return format_component_graph(
        {
            "name": scan["name"],
            "validRange": scan.get("validRange"),
            "viewRange": scan.get("viewRange"),
            "nodes": filtered["nodes"],
            "edges": filtered["edges"],
        }
    )


@mcp.tool()
async def signal_graph(
    workspace: str,
    root_label: str = "",
    root_node: str = "",
    max_hops: int = 3,
    active_only: bool = True,
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
    view: str = "summary",
    limit: int = 40,
) -> str:
    """Build a simplified directed signal graph for a workspace region."""
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_workspace(workspace, from_x, to_x, from_y, to_y, from_z, to_z)
    graph = signal_graph_data(scan)
    root_id = root_node or (resolve_endpoint(scan, root_label) if root_label else None)
    filtered = filter_signal_graph(graph, root_id, max_hops, limit, active_only)
    if view == "raw":
        return json.dumps(
            {
                "coordinateSystem": "workspace_local",
                "validRange": scan.get("validRange"),
                "viewRange": scan.get("viewRange"),
                "graph": filtered,
            },
            indent=2,
        )
    return format_signal_graph(
        {
            "name": scan["name"],
            "validRange": scan.get("validRange"),
            "viewRange": scan.get("viewRange"),
            "nodes": filtered["nodes"],
            "edges": filtered["edges"],
        }
    )


@mcp.tool()
async def trace_path(
    workspace: str,
    start_label: str = "",
    start_node: str = "",
    end_label: str = "",
    end_node: str = "",
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
) -> str:
    """Trace a likely signal path between two labels or local coordinates."""
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_workspace(workspace, from_x, to_x, from_y, to_y, from_z, to_z)
    start = start_node or start_label
    end = end_node or end_label
    if not start or not end:
        return "Validation error: provide start_label/start_node and end_label/end_node"
    start_resolved = resolve_endpoint(scan, start)
    end_resolved = resolve_endpoint(scan, end)
    if start_resolved is None:
        return f"Unknown trace source within current view window: {start}"
    if end_resolved is None:
        return f"Unknown trace target within current view window: {end}"
    return format_trace_path(scan, start_resolved, end_resolved)


@mcp.tool()
async def watch_nodes(
    workspace: str,
    action: str,
    label: str = "",
    x: int = 0,
    y: int = 0,
    z: int = 0,
    targets: str = "[]",
    from_tick: int = 0,
    to_tick: int = -1,
    view: str = "timing",
) -> str:
    """Manage and inspect internal monitor nodes (role=monitor)."""
    try:
        _require_workspace_name(workspace)
        if action in {"mark", "unmark"}:
            await _require_workspace_relative_coords(workspace, x, y, z)
        if action == "mark" and not label:
            raise ValueError("label is required for mark")
        _require_range_window(from_tick, to_tick)
    except ValueError as exc:
        return _tool_error(exc)

    if action == "mark":
        return await io(workspace=workspace, action="mark", x=x, y=y, z=z, role="monitor", label=label)
    if action == "unmark":
        return await io(workspace=workspace, action="unmark", x=x, y=y, z=z)

    scan = await _scan_workspace(workspace)
    if action == "list":
        return watch_summary(scan)
    if action == "status":
        status_text = json.loads(await io(workspace=workspace, action="status"))
        monitor_labels = {marker["label"] for marker in scan.get("ioMarkers", []) if marker.get("role") == "monitor"}
        filtered = {label: power for label, power in status_text.items() if label in monitor_labels}
        return json.dumps(filtered, indent=2) if view == "raw" else "\n".join(
            [f"Workspace: {workspace}", "Internal watch status"]
            + [f"- {name}={value}" for name, value in sorted(filtered.items())]
        )
    if action == "timing":
        try:
            selected = json.loads(targets)
        except json.JSONDecodeError:
            return "Invalid JSON in targets parameter"
        if not isinstance(selected, list):
            return "Validation error: targets must decode to a JSON array"
        timing_text = await timing(workspace=workspace, from_tick=from_tick, to_tick=to_tick)
        return _filter_timing_rows(timing_text, [str(item) for item in selected if isinstance(item, str)])
    return "Unknown action: use mark, unmark, list, status, or timing"


@mcp.tool()
async def stability(
    workspace: str,
    count: int = 128,
    quiet_ticks: int = 2,
    period_limit: int = 16,
    inputs: str = "{}",
    isolated: bool = True,
    view: str = "summary",
) -> str:
    """Classify whether the observed workspace state stabilizes, oscillates, or remains unsettled."""
    try:
        inputs_map = json.loads(inputs)
    except json.JSONDecodeError:
        return "Invalid JSON in inputs parameter"
    try:
        _require_workspace_name(workspace)
        _require_tick_count(count)
        _require_tick_count(quiet_ticks)
        _require_signal_map(inputs_map, "inputs")
    except ValueError as exc:
        return _tool_error(exc)

    info = _sanitize_workspace_result(await _call("workspace.info", {"name": workspace}))
    if isolated and not info.get("hasSnapshot", False):
        return "Validation error: isolated stability analysis requires a baseline snapshot"
    if isolated:
        await _call("workspace.revert", {"name": workspace})

    if inputs_map:
        await _call("io.drive", {"workspace": workspace, "values": inputs_map})
    if not info.get("frozen", False) or isolated:
        try:
            await _call("sim.freeze", {"workspace": workspace})
        except Exception:
            pass

    samples: list[tuple[Any, ...]] = []
    for _ in range(count):
        await _call("sim.step", {"workspace": workspace, "count": 1})
        scan = await _scan_workspace(workspace)
        sample = _state_signature(scan)
        samples.append(sample)
        verdict, period = _classify_stability(samples, quiet_ticks, period_limit)
        if verdict == "stable":
            if isolated:
                await _call("workspace.revert", {"name": workspace})
            if view == "raw":
                return json.dumps({"verdict": "stable", "ticks": len(samples)}, indent=2)
            return f"stable after {len(samples)} tick(s) based on observed workspace state"
        if verdict == "oscillating":
            if isolated:
                await _call("workspace.revert", {"name": workspace})
            if view == "raw":
                return json.dumps({"verdict": "oscillating", "period": period, "ticks": len(samples)}, indent=2)
            return f"oscillating(period={period}) after {len(samples)} observed tick(s)"

    if isolated:
        await _call("workspace.revert", {"name": workspace})
    if view == "raw":
        return json.dumps({"verdict": "unsettled", "ticks": len(samples)}, indent=2)
    return f"unsettled after {len(samples)} tick(s)"


@mcp.tool()
async def mechanisms(
    workspace: str,
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
    view: str = "summary",
) -> str:
    """Detect likely redstone mechanism motifs in a workspace region."""
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_workspace(workspace, from_x, to_x, from_y, to_y, from_z, to_z)
    return json.dumps(scan, indent=2) if view == "raw" else format_mechanisms(scan)


@mcp.tool()
async def baseline_diff(
    workspace: str,
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
    view: str = "summary",
) -> str:
    """Compare the current workspace block state against its initial baseline snapshot."""
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)
    result = await _call(
        "workspace.baseline_diff",
        {
            "name": workspace,
            "fromX": from_x,
            "toX": to_x,
            "fromY": from_y,
            "toY": to_y,
            "fromZ": from_z,
            "toZ": to_z,
        },
    )
    if view == "raw":
        return json.dumps(result, indent=2)
    return format_baseline_diff(result)


@mcp.tool()
async def impact(
    workspace: str,
    x: int,
    y: int,
    z: int,
    radius: int = 6,
    view: str = "summary",
) -> str:
    """Estimate what nearby redstone components may be affected by changing one block."""
    try:
        _require_workspace_name(workspace)
        _require_nonnegative_relative_coords(x, y, z)
        if radius < 1 or radius > _MAX_WORKSPACE_SIZE:
            raise ValueError(f"radius must be between 1 and {_MAX_WORKSPACE_SIZE}")
    except ValueError as exc:
        return _tool_error(exc)
    scan = await _scan_neighborhood(workspace, x, y, z, radius)
    if view == "raw":
        return json.dumps(scan, indent=2)
    return format_impact(scan, x, y, z, radius)


# ── Tool 11: status ───────────────────────────────────────────────

@mcp.tool()
async def inspect(
    workspace: str,
    view: str = "summary",
    from_x: int = 0,
    to_x: int = -1,
    from_y: int = 0,
    to_y: int = -1,
    from_z: int = 0,
    to_z: int = -1,
) -> str:
    """Inspect an existing workspace build so AI can understand what is already present.

    Views:
    - summary: compact counts and metadata
    - layers: layer-by-layer block map with a legend
    - components: redstone-relevant blocks with positions and key properties
    - raw: full JSON scan result
    """
    try:
        _require_workspace_name(workspace)
        _require_range_window(from_x, to_x)
        _require_range_window(from_y, to_y)
        _require_range_window(from_z, to_z)
    except ValueError as exc:
        return _tool_error(exc)

    result = _sanitize_scan_result(
        await _call(
            "workspace.scan",
            {
                "name": workspace,
                "fromX": from_x,
                "toX": to_x,
                "fromY": from_y,
                "toY": to_y,
                "fromZ": from_z,
                "toZ": to_z,
            },
        )
    )
    if view == "summary":
        return format_summary(result)
    if view == "layers":
        return format_layers(result)
    if view == "components":
        return format_components(result)
    if view == "raw":
        return json.dumps(result, indent=2)
    return "Unknown view: use summary, layers, components, or raw"


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
    """Get the full AI usage prompt plus the complete MCR reference card."""
    return SYSTEM_PROMPT.strip() + "\n\n## Full MCR Reference\n" + REFERENCE_CARD


def main():
    """Entry point for the MCP server."""
    mcp.run()


if __name__ == "__main__":
    main()
