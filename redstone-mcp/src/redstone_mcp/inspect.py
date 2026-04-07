"""Helpers for formatting workspace scan results for AI consumption."""

from __future__ import annotations

from collections import Counter
from typing import Any

from .mcr import BLOCK_CODES

_REVERSE_CODES = {v: k for k, v in BLOCK_CODES.items() if v != "air"}


def format_summary(scan: dict[str, Any]) -> str:
    size = scan.get("size", [0, 0, 0])
    blocks = scan.get("blocks", [])
    entities = scan.get("entities", [])
    io_markers = scan.get("ioMarkers", [])
    counts = scan.get("blockCounts", {})
    block_entities = sum(1 for block in blocks if block.get("blockEntityType"))
    entities_with_nbt = sum(1 for entity in entities if entity.get("nbt"))
    valid_to = scan.get("validRange", {}).get("to", [max(0, int(size[0]) - 1), max(0, int(size[1]) - 1), max(0, int(size[2]) - 1)])
    view_from = scan.get("viewRange", {}).get("from", [0, 0, 0])
    view_to = scan.get("viewRange", {}).get("to", valid_to)

    lines = [
        f"Workspace: {scan.get('name', '?')}",
        f"Size: {size[0]}x{size[1]}x{size[2]}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        f"Valid range: x=0..{valid_to[0]}, y=0..{valid_to[1]}, z=0..{valid_to[2]}",
        f"View window: x={view_from[0]}..{view_to[0]}, y={view_from[1]}..{view_to[1]}, z={view_from[2]}..{view_to[2]}",
        f"Non-air blocks: {scan.get('nonAirBlocks', len(blocks))}",
        f"Block entities: {block_entities}",
        f"Entities: {len(entities)}",
        f"Entities with NBT: {entities_with_nbt}",
        f"IO markers: {len(io_markers)}",
    ]

    if counts:
        top = Counter(counts).most_common(8)
        lines.append(
            "Top blocks: " + ", ".join(f"{block.split(':')[-1]}={count}" for block, count in top)
        )

    if entities:
        entity_counts = Counter(entity["type"].split(":")[-1] for entity in entities)
        lines.append(
            "Entity types: " + ", ".join(f"{name}={count}" for name, count in entity_counts.most_common(6))
        )

    if io_markers:
        lines.append(
            "IO: " + ", ".join(f"{marker['label']}({marker['role']})" for marker in io_markers)
        )

    return "\n".join(lines)


def format_layers(scan: dict[str, Any]) -> str:
    size = scan.get("size", [0, 0, 0])
    valid_to = scan.get("validRange", {}).get("to", [max(0, int(size[0]) - 1), max(0, int(size[1]) - 1), max(0, int(size[2]) - 1)])
    view_from = scan.get("viewRange", {}).get("from", [0, 0, 0])
    view_to = scan.get("viewRange", {}).get("to", valid_to)
    sx = int(view_to[0]) - int(view_from[0]) + 1
    sz = int(view_to[2]) - int(view_from[2]) + 1
    from_y = int(scan.get("fromY", 0))
    to_y = int(scan.get("toY", max(0, int(size[1]) - 1)))
    blocks = scan.get("blocks", [])

    grid: dict[tuple[int, int, int], str] = {}
    legend: dict[str, str] = {}
    unknown_index = 0

    for block in blocks:
        key = (
            int(block["x"]) - int(view_from[0]),
            int(block["y"]),
            int(block["z"]) - int(view_from[2]),
        )
        block_id = str(block["block"])
        short_name = block_id.split(":")[-1]
        token = _REVERSE_CODES.get(short_name)
        if token is None:
            token = f"u{unknown_index}"
            if token not in legend:
                unknown_index += 1
        grid[key] = token
        if token not in legend:
            props = block.get("properties", {})
            if props:
                prop_text = ",".join(f"{k}={v}" for k, v in sorted(props.items()))
                legend[token] = f"{block_id}[{prop_text}]"
            else:
                legend[token] = block_id

    lines = [
        f"Workspace: {scan.get('name', '?')}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        f"Valid range: x=0..{valid_to[0]}, y=0..{valid_to[1]}, z=0..{valid_to[2]}",
        f"View window: x={view_from[0]}..{view_to[0]}, y={from_y}..{to_y}, z={view_from[2]}..{view_to[2]}",
        f"Layers Y={from_y}..{to_y}",
        "Legend: " + ", ".join(f"{token}={meaning}" for token, meaning in sorted(legend.items())),
    ]

    for y in range(to_y, from_y - 1, -1):
        lines.append(f"Y={y}")
        for z in range(sz):
            row = [grid.get((x, y, z), "_") for x in range(sx)]
            lines.append(" ".join(row))
        lines.append("")

    if scan.get("entities"):
        lines.append(
            "Entities: "
            + ", ".join(
                f"{entity['type'].split(':')[-1]}@({entity['x']},{entity['y']},{entity['z']})"
                for entity in scan["entities"]
            )
        )

    return "\n".join(lines).strip()


def format_components(scan: dict[str, Any]) -> str:
    blocks = scan.get("blocks", [])
    components: list[str] = []
    size = scan.get("size", [0, 0, 0])
    valid_to = scan.get("validRange", {}).get("to", [max(0, int(size[0]) - 1), max(0, int(size[1]) - 1), max(0, int(size[2]) - 1)])
    view_from = scan.get("viewRange", {}).get("from", [0, 0, 0])
    view_to = scan.get("viewRange", {}).get("to", valid_to)
    redstone_blocks = {
        "minecraft:redstone_wire",
        "minecraft:repeater",
        "minecraft:comparator",
        "minecraft:redstone_torch",
        "minecraft:redstone_wall_torch",
        "minecraft:observer",
        "minecraft:piston",
        "minecraft:sticky_piston",
        "minecraft:hopper",
        "minecraft:dropper",
        "minecraft:dispenser",
        "minecraft:note_block",
        "minecraft:target",
        "minecraft:lever",
        "minecraft:stone_button",
        "minecraft:redstone_lamp",
        "minecraft:tnt",
        "minecraft:daylight_detector",
        "minecraft:chest",
        "minecraft:barrel",
    }

    for block in sorted(
        blocks,
        key=lambda item: (
            int(item.get("y", 0)),
            int(item.get("z", 0)),
            int(item.get("x", 0)),
            str(item.get("block", "")),
        ),
    ):
        block_id = str(block.get("block", ""))
        if block_id not in redstone_blocks and not block.get("blockEntityType"):
            continue
        props = block.get("properties", {})
        prop_text = ""
        interesting = []
        for key in ("facing", "delay", "mode", "powered", "face"):
            if key in props:
                interesting.append(f"{key}={props[key]}")
        if interesting:
            prop_text = " [" + ", ".join(interesting) + "]"
        suffix = ""
        if block.get("blockEntityType"):
            suffix = f" <{block['blockEntityType'].split(':')[-1]}>"
        components.append(
            f"- ({block['x']},{block['y']},{block['z']}) {block_id.split(':')[-1]}{prop_text}{suffix}"
        )

    if not components:
        return "No redstone-relevant components found."

    header = [
        f"Workspace: {scan.get('name', '?')}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        f"Valid range: x=0..{valid_to[0]}, y=0..{valid_to[1]}, z=0..{valid_to[2]}",
        f"View window: x={view_from[0]}..{view_to[0]}, y={view_from[1]}..{view_to[1]}, z={view_from[2]}..{view_to[2]}",
        f"Redstone components: {len(components)}",
    ]
    return "\n".join(header + components[:80])
