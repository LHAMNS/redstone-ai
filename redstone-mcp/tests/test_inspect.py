from redstone_mcp.inspect import format_components, format_layers, format_summary


def sample_scan():
    return {
        "name": "demo_gate",
        "size": [3, 2, 2],
        "nonAirBlocks": 4,
        "fromY": 0,
        "toY": 1,
        "blockCounts": {
            "minecraft:stone": 2,
            "minecraft:redstone_wire": 1,
            "minecraft:repeater": 1,
        },
        "blocks": [
            {
                "x": 0,
                "y": 0,
                "z": 0,
                "block": "minecraft:stone",
                "properties": {},
                "blockEntityType": "minecraft:chest",
                "nbt": "{Items:[{Slot:0b,id:\"minecraft:redstone\",Count:1b}]}",
            },
            {"x": 1, "y": 0, "z": 0, "block": "minecraft:redstone_wire", "properties": {}},
            {"x": 2, "y": 0, "z": 0, "block": "minecraft:stone", "properties": {}},
            {"x": 1, "y": 1, "z": 1, "block": "minecraft:repeater", "properties": {"facing": "north", "delay": "2"}},
        ],
        "entities": [{"type": "minecraft:armor_stand", "x": 1, "y": 0, "z": 1, "nbt": "{Invisible:1b}"}],
        "ioMarkers": [{"label": "OUT", "role": "output", "x": 2, "y": 0, "z": 0}],
    }


def test_format_summary_contains_key_sections():
    text = format_summary(sample_scan())
    assert "Workspace: demo_gate" in text
    assert "Size: 3x2x2" in text
    assert "0,0,0 = min corner" in text
    assert "Valid range: x=0..2, y=0..1, z=0..1" in text
    assert "Non-air blocks: 4" in text
    assert "Block entities: 1" in text
    assert "Entities: 1" in text
    assert "Entities with NBT: 1" in text
    assert "IO: OUT(output)" in text


def test_format_layers_renders_rows_and_legend():
    text = format_layers(sample_scan())
    assert "Layers Y=0..1" in text
    assert "0,0,0 = min corner" in text
    assert "Legend:" in text
    assert "# D #" in text
    assert "R" in text
    assert "Entities: armor_stand@(1,0,1)" in text


def test_format_components_lists_redstone_blocks_and_properties():
    text = format_components(sample_scan())
    assert "Redstone components:" in text
    assert "0,0,0 = min corner" in text
    assert "stone <chest>" in text
    assert "repeater [facing=north, delay=2]" in text
