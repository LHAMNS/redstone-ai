"""Higher-level redstone analysis helpers built on top of workspace.scan."""

from __future__ import annotations

from collections import Counter, deque
from typing import Any

from .mcr import BLOCK_CODES

_REVERSE_CODES = {v: k for k, v in BLOCK_CODES.items() if v != "air"}
_REDSTONE_COMPONENTS = {
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
    "minecraft:lever",
    "minecraft:stone_button",
    "minecraft:redstone_lamp",
    "minecraft:target",
    "minecraft:note_block",
    "minecraft:tnt",
    "minecraft:daylight_detector",
    "minecraft:rail",
    "minecraft:powered_rail",
    "minecraft:detector_rail",
    "minecraft:activator_rail",
    "minecraft:chest",
    "minecraft:barrel",
}
_SOURCE_COMPONENTS = {
    "minecraft:lever",
    "minecraft:stone_button",
    "minecraft:redstone_torch",
    "minecraft:redstone_wall_torch",
    "minecraft:target",
    "minecraft:daylight_detector",
}
_SINK_COMPONENTS = {
    "minecraft:redstone_lamp",
    "minecraft:piston",
    "minecraft:sticky_piston",
    "minecraft:note_block",
    "minecraft:tnt",
    "minecraft:dropper",
    "minecraft:dispenser",
    "minecraft:hopper",
    "minecraft:rail",
    "minecraft:powered_rail",
    "minecraft:detector_rail",
    "minecraft:activator_rail",
}
_ORTHO_NEIGHBORS = [
    (1, 0, 0),
    (-1, 0, 0),
    (0, 1, 0),
    (0, -1, 0),
    (0, 0, 1),
    (0, 0, -1),
]
_HORIZONTAL_NEIGHBORS = [
    (1, 0, 0),
    (-1, 0, 0),
    (0, 0, 1),
    (0, 0, -1),
]
_FACING_VECTORS = {
    "north": (0, 0, -1),
    "south": (0, 0, 1),
    "west": (-1, 0, 0),
    "east": (1, 0, 0),
    "up": (0, 1, 0),
    "down": (0, -1, 0),
}


def format_orthographic(scan: dict[str, Any], plane: str = "all") -> str:
    blocks = scan.get("blocks", [])
    view_from = scan.get("viewRange", {}).get("from", [0, 0, 0])
    view_to = scan.get("viewRange", {}).get("to", [0, 0, 0])
    sx = int(view_to[0]) - int(view_from[0]) + 1
    sy = int(view_to[1]) - int(view_from[1]) + 1
    sz = int(view_to[2]) - int(view_from[2]) + 1

    grid: dict[tuple[int, int, int], str] = {}
    legend: dict[str, str] = {}
    next_unknown = 0
    for block in blocks:
        key = (int(block["x"]), int(block["y"]), int(block["z"]))
        block_id = str(block["block"])
        token = _REVERSE_CODES.get(block_id.split(":")[-1])
        if token is None:
            token = f"u{next_unknown}"
            if token not in legend:
                next_unknown += 1
        grid[key] = token
        legend.setdefault(token, block_id)

    header = [
        f"Workspace: {scan.get('name', '?')}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        f"Valid range: {_format_range(scan.get('validRange', {}))}",
        f"View window: {_format_range(scan.get('viewRange', {}))}",
    ]
    sections: list[str] = []
    if plane in {"all", "top"}:
        sections.append(_projection_section("Top (looking down -Y)", sx, sz, lambda x, z: _top_token(grid, x, z, view_from, view_to)))
    if plane in {"all", "front"}:
        sections.append(_projection_section("Front (looking +Z)", sx, sy, lambda x, y: _front_token(grid, x, y, view_from, view_to)))
    if plane in {"all", "side"}:
        sections.append(_projection_section("Side (looking +X)", sz, sy, lambda z, y: _side_token(grid, z, y, view_from, view_to)))
    if legend:
        sections.append("Legend: " + ", ".join(f"{token}={meaning}" for token, meaning in sorted(legend.items())))
    return "\n".join(header + sections)


def format_neighborhood(scan: dict[str, Any], center: tuple[int, int, int], radius: int) -> str:
    blocks = scan.get("blocks", [])
    entities = scan.get("entities", [])
    lines = [
        f"Neighborhood around ({center[0]},{center[1]},{center[2]}) radius={radius}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        f"View window: {_format_range(scan.get('viewRange', {}))}",
        f"Blocks: {len(blocks)}",
        f"Entities: {len(entities)}",
    ]
    for block in sorted(blocks, key=lambda item: (int(item["y"]), int(item["z"]), int(item["x"]))):
        props = block.get("properties", {})
        prop_text = ""
        interesting = [f"{k}={v}" for k, v in sorted(props.items()) if k in {"facing", "delay", "mode", "powered", "face"}]
        if interesting:
            prop_text = " [" + ", ".join(interesting) + "]"
        lines.append(f"- ({block['x']},{block['y']},{block['z']}) {block['block'].split(':')[-1]}{prop_text}")
    if entities:
        lines.append(
            "Entity list: "
            + ", ".join(
                f"{entity['type'].split(':')[-1]}#{entity.get('uuid', '?')}@({entity['x']},{entity['y']},{entity['z']})"
                for entity in entities
            )
        )
    return "\n".join(lines[:120])


def component_graph_data(scan: dict[str, Any]) -> dict[str, Any]:
    blocks = _component_blocks(scan)
    nodes = {_coord_key(block): _node_record(block) for block in blocks}
    edges: list[dict[str, Any]] = []
    for block in blocks:
        src = _coord_key(block)
        x, y, z = _coords(block)
        for dx, dy, dz in _ORTHO_NEIGHBORS:
            neighbor_key = f"{x + dx},{y + dy},{z + dz}"
            if neighbor_key in nodes and src < neighbor_key:
                edges.append({"a": src, "b": neighbor_key, "kind": "adjacent"})
    return {"nodes": list(nodes.values()), "edges": edges}


def filter_component_graph(graph: dict[str, Any], root_id: str | None, max_hops: int, limit: int) -> dict[str, Any]:
    if not root_id:
        return {
            "nodes": graph["nodes"][:limit],
            "edges": graph["edges"][: limit * 4],
        }

    neighbors: dict[str, set[str]] = {}
    for edge in graph["edges"]:
        neighbors.setdefault(edge["a"], set()).add(edge["b"])
        neighbors.setdefault(edge["b"], set()).add(edge["a"])

    queue = deque([(root_id, 0)])
    visited = {root_id}
    while queue:
        current, hops = queue.popleft()
        if hops >= max_hops:
            continue
        for nxt in neighbors.get(current, set()):
            if nxt in visited:
                continue
            visited.add(nxt)
            queue.append((nxt, hops + 1))

    return {
        "nodes": [node for node in graph["nodes"] if node["id"] in visited][:limit],
        "edges": [edge for edge in graph["edges"] if edge["a"] in visited and edge["b"] in visited][: limit * 4],
    }


def format_component_graph(scan_or_graph: dict[str, Any]) -> str:
    graph = scan_or_graph if "nodes" in scan_or_graph and "edges" in scan_or_graph else component_graph_data(scan_or_graph)
    lines = [
        f"Workspace: {scan_or_graph.get('name', '?')}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        "Component graph (workspace-local)",
        f"Valid range: {_format_range(scan_or_graph.get('validRange', {}))}" if scan_or_graph.get("validRange") else "",
        f"View window: {_format_range(scan_or_graph.get('viewRange', {}))}" if scan_or_graph.get("viewRange") else "",
        f"Nodes: {len(graph['nodes'])}",
        f"Edges: {len(graph['edges'])}",
    ]
    lines = [line for line in lines if line]
    for node in graph["nodes"][:80]:
        lines.append(f"- {node['id']} {node['kind']}")
    if graph["edges"]:
        lines.append("Connections:")
        for edge in graph["edges"][:120]:
            lines.append(f"- {edge['a']} <-> {edge['b']} ({edge['kind']})")
    return "\n".join(lines)


def signal_graph_data(scan: dict[str, Any]) -> dict[str, Any]:
    nodes = {_coord_key(block): _node_record(block) for block in _component_blocks(scan)}
    edges: list[dict[str, Any]] = []
    for node_id, node in nodes.items():
        x, y, z = _parse_coord_key(node_id)
        block_id = node["block"]
        props = node.get("properties", {})
        if block_id in {"minecraft:repeater", "minecraft:comparator", "minecraft:observer"}:
            facing = props.get("facing")
            vector = _FACING_VECTORS.get(facing or "")
            if vector is not None:
                out_key = f"{x + vector[0]},{y + vector[1]},{z + vector[2]}"
                in_key = f"{x - vector[0]},{y - vector[1]},{z - vector[2]}"
                if out_key in nodes:
                    edges.append({"from": node_id, "to": out_key, "reason": "facing_output"})
                if in_key in nodes:
                    edges.append({"from": in_key, "to": node_id, "reason": "facing_input"})
            continue

        neighbor_vectors = _HORIZONTAL_NEIGHBORS if block_id == "minecraft:redstone_wire" else _ORTHO_NEIGHBORS
        for dx, dy, dz in neighbor_vectors:
            neighbor_key = f"{x + dx},{y + dy},{z + dz}"
            if neighbor_key not in nodes:
                continue
            if block_id in _SINK_COMPONENTS and nodes[neighbor_key]["block"] in _SINK_COMPONENTS:
                continue
            edges.append({"from": node_id, "to": neighbor_key, "reason": "adjacent_signal"})

    deduped = []
    seen = set()
    for edge in edges:
        key = (edge["from"], edge["to"], edge["reason"])
        if key not in seen:
            seen.add(key)
            deduped.append(edge)
    return {"nodes": list(nodes.values()), "edges": deduped}


def filter_signal_graph(graph: dict[str, Any], root_id: str | None, max_hops: int, limit: int, active_only: bool) -> dict[str, Any]:
    node_lookup = {node["id"]: node for node in graph["nodes"]}
    edges = graph["edges"]
    if active_only:
        edges = [
            edge
            for edge in edges
            if node_lookup.get(edge["from"], {}).get("active") or node_lookup.get(edge["to"], {}).get("active")
        ]

    if not root_id:
        return {
            "nodes": [node for node in graph["nodes"] if not active_only or node.get("active") or any(edge["from"] == node["id"] or edge["to"] == node["id"] for edge in edges)][:limit],
            "edges": edges[: limit * 4],
        }

    adjacency: dict[str, set[str]] = {}
    for edge in edges:
        adjacency.setdefault(edge["from"], set()).add(edge["to"])
        adjacency.setdefault(edge["to"], set()).add(edge["from"])

    queue = deque([(root_id, 0)])
    visited = {root_id}
    while queue:
        current, hops = queue.popleft()
        if hops >= max_hops:
            continue
        for nxt in adjacency.get(current, set()):
            if nxt in visited:
                continue
            visited.add(nxt)
            queue.append((nxt, hops + 1))

    return {
        "nodes": [node for node in graph["nodes"] if node["id"] in visited][:limit],
        "edges": [edge for edge in edges if edge["from"] in visited and edge["to"] in visited][: limit * 4],
    }


def format_signal_graph(scan_or_graph: dict[str, Any]) -> str:
    graph = scan_or_graph if "nodes" in scan_or_graph and "edges" in scan_or_graph else signal_graph_data(scan_or_graph)
    lines = [
        f"Workspace: {scan_or_graph.get('name', '?')}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
        "Signal graph (workspace-local)",
        f"Valid range: {_format_range(scan_or_graph.get('validRange', {}))}" if scan_or_graph.get("validRange") else "",
        f"View window: {_format_range(scan_or_graph.get('viewRange', {}))}" if scan_or_graph.get("viewRange") else "",
        f"Nodes: {len(graph['nodes'])}",
        f"Directed edges: {len(graph['edges'])}",
    ]
    lines = [line for line in lines if line]
    for edge in graph["edges"][:160]:
        lines.append(f"- {edge['from']} -> {edge['to']} ({edge['reason']})")
    return "\n".join(lines)


def format_trace_path(scan: dict[str, Any], source: str, target: str) -> str:
    graph = signal_graph_data(scan)
    source_id = _resolve_endpoint(scan, source)
    target_id = _resolve_endpoint(scan, target)
    if source_id is None:
        return f"Unknown trace source: {source}"
    if target_id is None:
        return f"Unknown trace target: {target}"

    adjacency: dict[str, list[dict[str, Any]]] = {}
    for edge in graph["edges"]:
        adjacency.setdefault(edge["from"], []).append(edge)

    queue = deque([source_id])
    previous: dict[str, tuple[str, dict[str, Any]]] = {}
    visited = {source_id}
    while queue:
        current = queue.popleft()
        if current == target_id:
            break
        for edge in adjacency.get(current, []):
            nxt = edge["to"]
            if nxt in visited:
                continue
            visited.add(nxt)
            previous[nxt] = (current, edge)
            queue.append(nxt)

    if target_id not in visited:
        return f"No signal path found from {source} to {target}."

    path_nodes = [target_id]
    path_edges: list[dict[str, Any]] = []
    current = target_id
    while current != source_id:
        prev, edge = previous[current]
        path_nodes.append(prev)
        path_edges.append(edge)
        current = prev
    path_nodes.reverse()
    path_edges.reverse()

    repeater_delays = 0
    node_lookup = {node["id"]: node for node in graph["nodes"]}
    for node_id in path_nodes:
        node = node_lookup.get(node_id)
        if node and node["block"] == "minecraft:repeater":
            repeater_delays += int(node.get("properties", {}).get("delay", "1"))

    lines = [
        f"Trace path from {source} to {target}",
        f"Resolved source: {source_id}",
        f"Resolved target: {target_id}",
        f"Hop count: {max(0, len(path_nodes) - 1)}",
        f"Repeater delay sum: {repeater_delays}",
        "Path:",
    ]
    for node_id in path_nodes:
        node = node_lookup.get(node_id, {"kind": "unknown"})
        lines.append(f"- {node_id} {node['kind']}")
    return "\n".join(lines)


def watch_summary(scan: dict[str, Any]) -> str:
    markers = [marker for marker in scan.get("ioMarkers", []) if marker.get("role") == "monitor"]
    lines = [
        f"Workspace: {scan.get('name', '?')}",
        "Internal watch nodes (role=monitor)",
        f"Count: {len(markers)}",
    ]
    for marker in markers:
        lines.append(f"- {marker['label']} @ ({marker['x']},{marker['y']},{marker['z']})")
    return "\n".join(lines)


def format_mechanisms(scan: dict[str, Any]) -> str:
    components = _component_blocks(scan)
    by_block = Counter(block["block"] for block in components)
    lines = [
        f"Workspace: {scan.get('name', '?')}",
        "Mechanism candidates (workspace-local)",
    ]
    piston_nodes = [block for block in components if block["block"] in {"minecraft:piston", "minecraft:sticky_piston"}]
    if piston_nodes:
        clusters = _connected_clusters(piston_nodes)
        lines.append(f"- piston clusters: {len(clusters)}")
        for idx, cluster in enumerate(clusters[:8], start=1):
            lines.append(f"  cluster {idx}: {len(cluster)} piston blocks")
    hopper_nodes = [block for block in components if block["block"] == "minecraft:hopper"]
    if hopper_nodes:
        lines.append(f"- hopper line candidates: {len(_connected_clusters(hopper_nodes))}")
    rail_nodes = [block for block in components if block["block"].endswith("rail")]
    if rail_nodes:
        minecart_count = sum(1 for entity in scan.get("entities", []) if "minecart" in entity["type"])
        lines.append(f"- rail lane candidates: {len(_connected_clusters(rail_nodes))}, nearby minecarts={minecart_count}")
    observer_nodes = [block for block in components if block["block"] == "minecraft:observer"]
    if observer_nodes:
        lines.append(f"- observer chains: {len(_connected_clusters(observer_nodes))}")
    if not components:
        lines.append("- no redstone-relevant components detected")
    else:
        lines.append("Top component counts: " + ", ".join(f"{block.split(':')[-1]}={count}" for block, count in by_block.most_common(8)))
    return "\n".join(lines)


def format_baseline_diff(result: dict[str, Any]) -> str:
    lines = [
        f"Workspace: {result.get('name', '?')}",
        "Baseline diff (workspace-local)",
        f"Changed blocks: {result.get('changedBlocks', 0)}",
        f"Window: {_format_range({'from': [result.get('fromX', 0), result.get('fromY', 0), result.get('fromZ', 0)], 'to': [result.get('toX', 0), result.get('toY', 0), result.get('toZ', 0)]})}",
    ]
    for change in result.get("changes", [])[:120]:
        line = (
            f"- ({change['x']},{change['y']},{change['z']}) "
            f"{change['baselineBlock'].split(':')[-1]} -> {change['currentBlock'].split(':')[-1]}"
        )
        if "baselineNbt" in change or "currentNbt" in change:
            line += " [NBT changed]"
        lines.append(line)
    return "\n".join(lines)


def format_impact(scan: dict[str, Any], x: int, y: int, z: int, radius: int) -> str:
    graph = signal_graph_data(scan)
    node_lookup = {node["id"]: node for node in graph["nodes"]}
    center_id = f"{x},{y},{z}"
    lines = [
        f"Impact estimate for ({x},{y},{z}) radius={radius}",
        "Coordinates: workspace-local; 0,0,0 = min corner",
    ]
    block_lookup = {_coord_key(block): block for block in scan.get("blocks", [])}
    block = block_lookup.get(center_id)
    if block is None:
        return "\n".join(lines + ["- no block exists at this coordinate"])

    lines.append(f"- target block: {block['block'].split(':')[-1]}")
    affected_components = []
    for node_id, node in node_lookup.items():
        nx, ny, nz = _parse_coord_key(node_id)
        if abs(nx - x) + abs(ny - y) + abs(nz - z) <= radius:
            affected_components.append(node)
    lines.append(f"- nearby redstone components: {len(affected_components)}")
    for node in affected_components[:80]:
        lines.append(f"  - {node['id']} {node['kind']}")

    direct_signal_edges = [
        edge for edge in graph["edges"]
        if edge["from"] == center_id or edge["to"] == center_id
    ]
    if direct_signal_edges:
        lines.append("- direct signal relationships:")
        for edge in direct_signal_edges[:40]:
            lines.append(f"  - {edge['from']} -> {edge['to']} ({edge['reason']})")
    if block["block"] in {"minecraft:piston", "minecraft:sticky_piston"}:
        lines.append("- piston risk: moving this block can affect pushed/pulled neighbors across the facing axis")
    if block["block"] == "minecraft:redstone_wire":
        lines.append("- wire risk: changing this wire can alter all adjacent signal paths")
    if block["block"] in {"minecraft:repeater", "minecraft:comparator", "minecraft:observer"}:
        lines.append("- directional risk: input/output behavior depends on facing")
    return "\n".join(lines)


def _component_blocks(scan: dict[str, Any]) -> list[dict[str, Any]]:
    return [block for block in scan.get("blocks", []) if _is_component(block)]


def _is_component(block: dict[str, Any]) -> bool:
    return str(block.get("block", "")) in _REDSTONE_COMPONENTS or bool(block.get("blockEntityType"))


def _node_record(block: dict[str, Any]) -> dict[str, Any]:
    active, power_level = _node_activity(block)
    return {
        "id": _coord_key(block),
        "kind": _component_kind(block),
        "block": block["block"],
        "properties": dict(block.get("properties", {})),
        "active": active,
        "powerLevel": power_level,
    }


def _component_kind(block: dict[str, Any]) -> str:
    block_id = str(block.get("block", ""))
    return block_id.split(":")[-1]


def _coord_key(block: dict[str, Any]) -> str:
    return f"{int(block['x'])},{int(block['y'])},{int(block['z'])}"


def _coords(block: dict[str, Any]) -> tuple[int, int, int]:
    return int(block["x"]), int(block["y"]), int(block["z"])


def _parse_coord_key(key: str) -> tuple[int, int, int]:
    x_str, y_str, z_str = key.split(",")
    return int(x_str), int(y_str), int(z_str)


def _resolve_endpoint(scan: dict[str, Any], endpoint: str) -> str | None:
    if endpoint.count(",") == 2:
        return endpoint
    for marker in scan.get("ioMarkers", []):
        if marker.get("label") == endpoint:
            return f"{marker['x']},{marker['y']},{marker['z']}"
    return None


def resolve_endpoint(scan: dict[str, Any], endpoint: str) -> str | None:
    return _resolve_endpoint(scan, endpoint)


def _connected_clusters(blocks: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    remaining = {_coord_key(block): block for block in blocks}
    clusters: list[list[dict[str, Any]]] = []
    while remaining:
        start_key = next(iter(remaining))
        queue = deque([start_key])
        cluster_keys = {start_key}
        while queue:
            current_key = queue.popleft()
            cx, cy, cz = _parse_coord_key(current_key)
            for dx, dy, dz in _ORTHO_NEIGHBORS:
                neighbor_key = f"{cx + dx},{cy + dy},{cz + dz}"
                if neighbor_key in remaining and neighbor_key not in cluster_keys:
                    cluster_keys.add(neighbor_key)
                    queue.append(neighbor_key)
        cluster = [remaining.pop(key) for key in list(cluster_keys)]
        clusters.append(cluster)
    return clusters


def _projection_section(title: str, width: int, height: int, token_getter) -> str:
    lines = [title]
    for row in range(height):
        tokens = [token_getter(col, row) for col in range(width)]
        lines.append(" ".join(tokens))
    return "\n".join(lines)


def _top_token(grid: dict[tuple[int, int, int], str], x: int, z: int, view_from: list[int], view_to: list[int]) -> str:
    for y in range(int(view_to[1]), int(view_from[1]) - 1, -1):
        token = grid.get((x + int(view_from[0]), y, z + int(view_from[2])))
        if token is not None:
            return token
    return "_"


def _front_token(grid: dict[tuple[int, int, int], str], x: int, y_row: int, view_from: list[int], view_to: list[int]) -> str:
    y = int(view_to[1]) - y_row
    for z in range(int(view_from[2]), int(view_to[2]) + 1):
        token = grid.get((x + int(view_from[0]), y, z))
        if token is not None:
            return token
    return "_"


def _side_token(grid: dict[tuple[int, int, int], str], z: int, y_row: int, view_from: list[int], view_to: list[int]) -> str:
    y = int(view_to[1]) - y_row
    for x in range(int(view_from[0]), int(view_to[0]) + 1):
        token = grid.get((x, y, z + int(view_from[2])))
        if token is not None:
            return token
    return "_"


def _format_range(range_obj: dict[str, Any]) -> str:
    start = range_obj.get("from", [0, 0, 0])
    end = range_obj.get("to", [0, 0, 0])
    return f"x={start[0]}..{end[0]}, y={start[1]}..{end[1]}, z={start[2]}..{end[2]}"


def _node_activity(block: dict[str, Any]) -> tuple[bool, int]:
    props = {str(k): str(v) for k, v in block.get("properties", {}).items()}
    if "power" in props:
        try:
            power_level = int(props["power"])
        except ValueError:
            power_level = 0
        return power_level > 0, power_level
    if props.get("powered") == "true":
        return True, 15
    block_id = str(block.get("block", ""))
    if block_id in {"minecraft:redstone_torch", "minecraft:redstone_wall_torch"}:
        return True, 15
    return False, 0
