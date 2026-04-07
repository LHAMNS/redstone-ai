from redstone_mcp.analysis import (
    component_graph_data,
    format_impact,
    format_mechanisms,
    format_neighborhood,
    format_orthographic,
    format_signal_graph,
    format_trace_path,
    watch_summary,
)


def sample_scan():
    return {
        "name": "demo_ws",
        "size": [6, 4, 6],
        "validRange": {"from": [0, 0, 0], "to": [5, 3, 5]},
        "viewRange": {"from": [0, 0, 0], "to": [5, 3, 5]},
        "blocks": [
            {"x": 0, "y": 0, "z": 0, "block": "minecraft:lever", "properties": {"powered": "false", "facing": "east"}},
            {"x": 1, "y": 0, "z": 0, "block": "minecraft:redstone_wire", "properties": {}},
            {"x": 2, "y": 0, "z": 0, "block": "minecraft:repeater", "properties": {"facing": "east", "delay": "2"}},
            {"x": 3, "y": 0, "z": 0, "block": "minecraft:redstone_lamp", "properties": {}},
            {"x": 4, "y": 0, "z": 0, "block": "minecraft:sticky_piston", "properties": {"facing": "west"}},
            {"x": 4, "y": 1, "z": 0, "block": "minecraft:sticky_piston", "properties": {"facing": "west"}},
        ],
        "entities": [],
        "ioMarkers": [
            {"label": "IN", "role": "input", "x": 0, "y": 0, "z": 0},
            {"label": "OUT", "role": "output", "x": 3, "y": 0, "z": 0},
            {"label": "MID", "role": "monitor", "x": 2, "y": 0, "z": 0},
        ],
        "blockCounts": {},
        "nonAirBlocks": 6,
    }


def test_format_orthographic_contains_headers():
    text = format_orthographic(sample_scan(), plane="all")
    assert "0,0,0 = min corner" in text
    assert "Top (looking down -Y)" in text
    assert "Front (looking +Z)" in text
    assert "Side (looking +X)" in text


def test_neighborhood_contains_focus_and_blocks():
    text = format_neighborhood(sample_scan(), (2, 0, 0), 2)
    assert "Neighborhood around (2,0,0) radius=2" in text
    assert "(2,0,0) repeater" in text


def test_component_graph_has_nodes_and_edges():
    graph = component_graph_data(sample_scan())
    assert len(graph["nodes"]) >= 4
    assert len(graph["edges"]) >= 3


def test_signal_graph_formatter_mentions_directed_edges():
    text = format_signal_graph(sample_scan())
    assert "Signal graph" in text
    assert "->" in text


def test_trace_path_resolves_labels():
    text = format_trace_path(sample_scan(), "IN", "OUT")
    assert "Resolved source: 0,0,0" in text
    assert "Resolved target: 3,0,0" in text


def test_watch_summary_lists_monitor_markers():
    text = watch_summary(sample_scan())
    assert "Internal watch nodes" in text
    assert "MID @ (2,0,0)" in text


def test_mechanisms_detects_piston_clusters():
    text = format_mechanisms(sample_scan())
    assert "piston clusters" in text


def test_impact_mentions_target_and_neighbors():
    text = format_impact(sample_scan(), 2, 0, 0, 3)
    assert "Impact estimate for (2,0,0)" in text
    assert "target block: repeater" in text
