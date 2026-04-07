import asyncio
import json

import redstone_mcp.server as server


def test_workspace_configure_normalizes_aliases_and_calls_rpc(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"ok": True, "origin": [100, 64, 100], "controller": [100, 68, 100], "size": [16, 8, 16]}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(
        server.workspace(
            action="configure",
            name="demo_ws",
            config=json.dumps(
                {
                    "mode": "AI",
                    "entity_filter": "mechanical",
                    "permission_grants": [
                        {"player": "Alice", "permissions": ["build", "time_control", "view_history"]}
                    ],
                    "allow_commands": False,
                }
            ),
        )
    )

    assert json.loads(result) == {
        "ok": True,
        "size": [16, 8, 16],
        "coordinateSystem": "workspace_local",
        "localOrigin": [0, 0, 0],
        "localMax": [15, 7, 15],
        "validRange": {"from": [0, 0, 0], "to": [15, 7, 15]},
    }
    assert calls == [
        (
            "workspace.configure",
            {
                "name": "demo_ws",
                "mode": "ai_only",
                "entityFilter": "mechanical_only",
                "permissionGrants": [
                    {"player": "Alice", "permissions": ["build", "time", "history"]}
                ],
                "allowVanillaCommands": False,
            },
        )
    ]


def test_workspace_history_calls_rpc(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"history": [], "origin": [100, 64, 100], "size": [8, 4, 8]}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(server.workspace(action="history", name="demo_ws", limit=50))

    assert json.loads(result) == {
        "history": [],
        "size": [8, 4, 8],
        "coordinateSystem": "workspace_local",
        "localOrigin": [0, 0, 0],
        "localMax": [7, 3, 7],
        "validRange": {"from": [0, 0, 0], "to": [7, 3, 7]},
    }
    assert calls == [("workspace.history", {"name": "demo_ws", "limit": 50})]


def test_workspace_revert_calls_rpc(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"restoredBlocks": 12, "origin": [100, 64, 100], "size": [8, 4, 8]}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(server.workspace(action="revert", name="demo_ws"))

    assert json.loads(result) == {
        "restoredBlocks": 12,
        "size": [8, 4, 8],
        "coordinateSystem": "workspace_local",
        "localOrigin": [0, 0, 0],
        "localMax": [7, 3, 7],
        "validRange": {"from": [0, 0, 0], "to": [7, 3, 7]},
    }
    assert calls == [("workspace.revert", {"name": "demo_ws"})]


def test_workspace_configure_rejects_invalid_permission():
    result = asyncio.run(
        server.workspace(
            action="configure",
            name="demo_ws",
            config=json.dumps(
                {"permission_grants": [{"player": "Alice", "permissions": ["destroy"]}]}
            ),
        )
    )

    assert "Validation error:" in result
    assert "permission must be one of" in result


def test_help_mentions_revert_and_configure():
    text = asyncio.run(server.help())
    assert "workspace(action=\"configure\"" in text
    assert "workspace(action=\"revert\"" in text
    assert "inspect(..., view=\"raw\")" in text
    assert "inspect(..., view=\"components\")" in text
    assert "probe" in text
    assert "block_entity" in text
    assert "entity(...)" in text


def test_build_block_properties_are_forwarded(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"placed": "minecraft:repeater"}

    async def fake_require_workspace_relative_coords(workspace: str, x: int, y: int, z: int) -> None:
        return None

    monkeypatch.setattr(server, "_call", fake_call)
    monkeypatch.setattr(server, "_require_workspace_relative_coords", fake_require_workspace_relative_coords)

    result = asyncio.run(
        server.build(
            workspace="demo_ws",
            block="minecraft:repeater",
            x=1,
            y=2,
            z=3,
            properties=json.dumps({"facing": "east", "delay": 4}),
        )
    )

    assert json.loads(result) == {"placed": "minecraft:repeater"}
    assert calls == [
        (
            "build.block",
            {
                "workspace": "demo_ws",
                "block": "minecraft:repeater",
                "x": 1,
                "y": 2,
                "z": 3,
                "properties": {"facing": "east", "delay": "4"},
            },
        )
    ]


def test_probe_calls_test_run(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"actual": {"OUT": 15}, "pass": True}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(
        server.probe(
            workspace="demo_ws",
            ticks=8,
            inputs=json.dumps({"A": 15}),
            expected=json.dumps({"OUT": 15}),
        )
    )

    assert "actual={'OUT': 15}" in result
    assert "pass=True" in result
    assert calls == [
        (
            "test.run",
            {
                "workspace": "demo_ws",
                "ticks": 8,
                "inputs": {"A": 15},
                "expected": {"OUT": 15},
            },
        )
    ]


def test_time_settle_forwards_quiet_ticks(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"stable": True, "stepped": 3}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(server.time(workspace="demo_ws", action="settle", count=12, quiet_ticks=3))

    assert json.loads(result) == {"stable": True, "stepped": 3}
    assert calls == [("sim.settle", {"workspace": "demo_ws", "count": 12, "quietTicks": 3})]


def test_block_entity_forwards_snbt_payload(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"updated": True}

    async def fake_require_workspace_relative_coords(workspace: str, x: int, y: int, z: int) -> None:
        return None

    monkeypatch.setattr(server, "_call", fake_call)
    monkeypatch.setattr(server, "_require_workspace_relative_coords", fake_require_workspace_relative_coords)

    result = asyncio.run(
        server.block_entity(
            workspace="demo_ws",
            x=1,
            y=2,
            z=3,
            nbt='{Items:[{Slot:0b,id:"minecraft:redstone",Count:32b}]}',
            mode="merge",
        )
    )

    assert json.loads(result) == {"updated": True}
    assert calls == [
        (
            "block_entity.write",
            {
                "workspace": "demo_ws",
                "x": 1,
                "y": 2,
                "z": 3,
                "nbt": '{Items:[{Slot:0b,id:"minecraft:redstone",Count:32b}]}',
                "mode": "merge",
            },
        )
    ]


def test_entity_spawn_forwards_relative_position(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"spawned": True}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(
        server.entity(
            workspace="demo_ws",
            action="spawn",
            entity_type="minecraft:hopper_minecart",
            x=4.5,
            y=1.0,
            z=2.5,
            nbt="{}",
        )
    )

    assert json.loads(result) == {"spawned": True}
    assert calls == [
        (
            "entity.spawn",
            {
                "workspace": "demo_ws",
                "entityType": "minecraft:hopper_minecart",
                "x": 4.5,
                "y": 1.0,
                "z": 2.5,
                "nbt": "{}",
            },
        )
    ]


def test_entity_update_omits_position_when_not_provided(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"updated": True}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(
        server.entity(
            workspace="demo_ws",
            action="update",
            uuid="11111111-1111-1111-1111-111111111111",
            nbt='{NoGravity:1b}',
            mode="merge",
        )
    )

    assert json.loads(result) == {"updated": True}
    assert calls == [
        (
            "entity.update",
            {
                "workspace": "demo_ws",
                "uuid": "11111111-1111-1111-1111-111111111111",
                "nbt": '{NoGravity:1b}',
                "mode": "merge",
            },
        )
    ]


def test_entity_remove_forwards_uuid(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"removed": True}

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(
        server.entity(
            workspace="demo_ws",
            action="remove",
            uuid="11111111-1111-1111-1111-111111111111",
        )
    )

    assert json.loads(result) == {"removed": True}
    assert calls == [
        ("entity.remove", {"workspace": "demo_ws", "uuid": "11111111-1111-1111-1111-111111111111"})
    ]


def test_workspace_info_is_sanitized_to_workspace_local(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [8, 4, 8],
        }

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(server.workspace(action="info", name="demo_ws"))
    parsed = json.loads(result)

    assert parsed == {
        "name": "demo_ws",
        "size": [8, 4, 8],
        "coordinateSystem": "workspace_local",
        "localOrigin": [0, 0, 0],
        "localMax": [7, 3, 7],
        "validRange": {"from": [0, 0, 0], "to": [7, 3, 7]},
    }
    assert calls == [("workspace.info", {"name": "demo_ws"})]


def test_inspect_forwards_local_crop_window(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [8, 4, 8],
            "fromX": 2,
            "toX": 5,
            "fromY": 0,
            "toY": 2,
            "fromZ": 1,
            "toZ": 4,
            "blocks": [],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 0,
        }

    monkeypatch.setattr(server, "_call", fake_call)

    result = asyncio.run(
        server.inspect(
            workspace="demo_ws",
            view="raw",
            from_x=2,
            to_x=5,
            from_y=0,
            to_y=2,
            from_z=1,
            to_z=4,
        )
    )
    parsed = json.loads(result)

    assert parsed["coordinateSystem"] == "workspace_local"
    assert parsed["validRange"] == {"from": [0, 0, 0], "to": [7, 3, 7]}
    assert parsed["viewRange"] == {"from": [2, 0, 1], "to": [5, 2, 4]}
    assert "origin" not in parsed
    assert "controller" not in parsed
    assert calls == [
        (
            "workspace.scan",
            {
                "name": "demo_ws",
                "fromX": 2,
                "toX": 5,
                "fromY": 0,
                "toY": 2,
                "fromZ": 1,
                "toZ": 4,
            },
        )
    ]


def test_orthographic_uses_scan_window(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [4, 4, 4],
            "fromX": 1,
            "toX": 2,
            "fromY": 0,
            "toY": 1,
            "fromZ": 1,
            "toZ": 2,
            "blocks": [],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 0,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.orthographic("demo_ws", from_x=1, to_x=2, from_y=0, to_y=1, from_z=1, to_z=2))

    assert "Top (looking down -Y)" in text
    assert calls == [
        (
            "workspace.scan",
            {"name": "demo_ws", "fromX": 1, "toX": 2, "fromY": 0, "toY": 1, "fromZ": 1, "toZ": 2},
        )
    ]


def test_neighborhood_scans_centered_crop(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        if method == "workspace.info":
            return {"size": [8, 4, 8]}
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [8, 4, 8],
            "fromX": 1,
            "toX": 5,
            "fromY": 0,
            "toY": 3,
            "fromZ": 2,
            "toZ": 6,
            "blocks": [],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 0,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.neighborhood("demo_ws", x=3, y=1, z=4, radius=2))

    assert "Neighborhood around (3,1,4) radius=2" in text
    assert calls[1] == (
        "workspace.scan",
        {"name": "demo_ws", "fromX": 1, "toX": 5, "fromY": 0, "toY": 3, "fromZ": 2, "toZ": 6},
    )


def test_baseline_diff_forwards_crop_window(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {
            "name": "demo_ws",
            "fromX": 0,
            "toX": 2,
            "fromY": 0,
            "toY": 1,
            "fromZ": 0,
            "toZ": 2,
            "changedBlocks": 1,
            "changes": [{"x": 1, "y": 0, "z": 1, "baselineBlock": "minecraft:air", "currentBlock": "minecraft:stone"}],
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.baseline_diff("demo_ws", from_x=0, to_x=2, from_y=0, to_y=1, from_z=0, to_z=2))

    assert "Baseline diff" in text
    assert calls == [
        (
            "workspace.baseline_diff",
            {"name": "demo_ws", "fromX": 0, "toX": 2, "fromY": 0, "toY": 1, "fromZ": 0, "toZ": 2},
        )
    ]


def test_component_graph_uses_scan(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [4, 4, 4],
            "fromX": 0,
            "toX": 3,
            "fromY": 0,
            "toY": 3,
            "fromZ": 0,
            "toZ": 3,
            "blocks": [
                {"x": 0, "y": 0, "z": 0, "block": "minecraft:lever", "properties": {}},
                {"x": 1, "y": 0, "z": 0, "block": "minecraft:redstone_wire", "properties": {}},
            ],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 2,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.component_graph("demo_ws"))
    assert "Component graph" in text
    assert calls[0][0] == "workspace.scan"


def test_signal_graph_uses_scan(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [4, 4, 4],
            "fromX": 0,
            "toX": 3,
            "fromY": 0,
            "toY": 3,
            "fromZ": 0,
            "toZ": 3,
            "blocks": [
                {"x": 0, "y": 0, "z": 0, "block": "minecraft:lever", "properties": {}},
                {"x": 1, "y": 0, "z": 0, "block": "minecraft:redstone_wire", "properties": {}},
            ],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 2,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.signal_graph("demo_ws"))
    assert "Signal graph" in text
    assert calls[0][0] == "workspace.scan"


def test_trace_path_uses_labels(monkeypatch):
    async def fake_call(method: str, params: dict | None = None):
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [4, 4, 4],
            "fromX": 0,
            "toX": 3,
            "fromY": 0,
            "toY": 3,
            "fromZ": 0,
            "toZ": 3,
            "blocks": [
                {"x": 0, "y": 0, "z": 0, "block": "minecraft:lever", "properties": {}},
                {"x": 1, "y": 0, "z": 0, "block": "minecraft:redstone_wire", "properties": {}},
                {"x": 2, "y": 0, "z": 0, "block": "minecraft:redstone_lamp", "properties": {}},
            ],
            "entities": [],
            "ioMarkers": [
                {"label": "IN", "role": "input", "x": 0, "y": 0, "z": 0},
                {"label": "OUT", "role": "output", "x": 2, "y": 0, "z": 0},
            ],
            "blockCounts": {},
            "nonAirBlocks": 3,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.trace_path("demo_ws", start_label="IN", end_label="OUT"))
    assert "Resolved source: 0,0,0" in text
    assert "Resolved target: 2,0,0" in text


def test_watch_nodes_mark_uses_monitor_role(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        return {"marked": "MID"}

    async def fake_require_workspace_relative_coords(workspace: str, x: int, y: int, z: int) -> None:
        return None

    monkeypatch.setattr(server, "_call", fake_call)
    monkeypatch.setattr(server, "_require_workspace_relative_coords", fake_require_workspace_relative_coords)
    result = asyncio.run(server.watch_nodes("demo_ws", action="mark", label="MID", x=1, y=2, z=3))
    assert json.loads(result) == {"marked": "MID"}
    assert calls == [("io.mark", {"workspace": "demo_ws", "x": 1, "y": 2, "z": 3, "role": "monitor", "label": "MID"})]


def test_mechanisms_uses_scan(monkeypatch):
    async def fake_call(method: str, params: dict | None = None):
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [4, 4, 4],
            "fromX": 0,
            "toX": 3,
            "fromY": 0,
            "toY": 3,
            "fromZ": 0,
            "toZ": 3,
            "blocks": [
                {"x": 0, "y": 0, "z": 0, "block": "minecraft:sticky_piston", "properties": {}},
                {"x": 0, "y": 1, "z": 0, "block": "minecraft:sticky_piston", "properties": {}},
            ],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 2,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.mechanisms("demo_ws"))
    assert "Mechanism candidates" in text
    assert "piston clusters" in text


def test_impact_uses_neighborhood(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        if method == "workspace.info":
            return {"size": [8, 4, 8]}
        return {
            "name": "demo_ws",
            "origin": [1000, 64, 1000],
            "controller": [1000, 72, 1000],
            "size": [8, 4, 8],
            "fromX": 1,
            "toX": 5,
            "fromY": 0,
            "toY": 3,
            "fromZ": 1,
            "toZ": 5,
            "blocks": [{"x": 3, "y": 1, "z": 3, "block": "minecraft:repeater", "properties": {"facing": "east"}}],
            "entities": [],
            "ioMarkers": [],
            "blockCounts": {},
            "nonAirBlocks": 1,
        }

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.impact("demo_ws", x=3, y=1, z=3, radius=2))
    assert "Impact estimate for (3,1,3)" in text


def test_stability_reverts_when_isolated(monkeypatch):
    calls = []

    async def fake_call(method: str, params: dict | None = None):
        calls.append((method, params))
        if method == "workspace.info":
            return {"hasSnapshot": True, "frozen": False, "size": [8, 4, 8]}
        if method == "workspace.revert":
            return {"restoredBlocks": 0}
        if method == "sim.freeze":
            return {"frozen": True}
        if method == "sim.step":
            return {"stepped": 1}
        if method == "workspace.scan":
            return {
                "name": "demo_ws",
                "origin": [1000, 64, 1000],
                "controller": [1000, 72, 1000],
                "size": [8, 4, 8],
                "fromX": 0,
                "toX": 7,
                "fromY": 0,
                "toY": 3,
                "fromZ": 0,
                "toZ": 7,
                "blocks": [],
                "entities": [],
                "ioMarkers": [],
                "blockCounts": {},
                "nonAirBlocks": 0,
            }
        raise AssertionError(method)

    monkeypatch.setattr(server, "_call", fake_call)
    text = asyncio.run(server.stability("demo_ws", count=3, quiet_ticks=2, isolated=True))
    assert "stable after 2 tick(s)" in text
    assert ("workspace.revert", {"name": "demo_ws"}) in calls
