"""Tests for the density formatters (Level 1/2/3)."""

from redstone_mcp.density import format_level1, format_level2, format_level3


def test_level1_with_summary():
    result = {"summary": "Ticks: 10 | Position: 10/10\nIO: A=15 B=0\nChanges: 5"}
    output = format_level1(result)
    assert "Ticks: 10" in output
    assert "A=15" in output


def test_level1_fallback():
    result = {"stepped": 5, "virtualTick": 5}
    output = format_level1(result)
    assert "Stepped: 5" in output
    assert "VTick: 5" in output


def test_level1_empty():
    result = {}
    output = format_level1(result)
    assert output == "No data"


def test_level2_with_timing():
    result = {"timing": "tick |0123456789\n-----+---------\nI:A  |000FFFF000"}
    output = format_level2(result)
    assert "I:A" in output
    assert "FFFF" in output


def test_level2_missing():
    result = {}
    output = format_level2(result)
    assert "No timing data" in output


def test_level3_with_detail():
    result = {"detail": "T0: [1,2,3] air -> stone\nT1: (no changes)"}
    output = format_level3(result)
    assert "air -> stone" in output
    assert "no changes" in output


def test_level3_missing():
    result = {}
    output = format_level3(result)
    assert "No detail data" in output
