"""Tests for the MCR parser and validator."""

from redstone_mcp.mcr import validate


def test_valid_simple():
    valid, err = validate("# # # D D D")
    assert valid, err


def test_valid_with_modifiers():
    valid, err = validate("Rn2 Cex Ww")
    assert valid, err


def test_valid_with_directives():
    valid, err = validate("@origin 0,1,0 # # # @row D Rn2 D @layer # # #")
    assert valid, err


def test_invalid_block_code():
    valid, err = validate("Z")
    assert not valid
    assert "Unknown block code" in err


def test_invalid_modifier():
    valid, err = validate("Rq")
    assert not valid
    assert "Unknown modifier" in err


def test_invalid_facing_modifier_for_stone():
    valid, err = validate("#n")
    assert not valid
    assert "does not support facing modifiers" in err


def test_fill_rejects_modifiers():
    valid, err = validate("@fill Rn2")
    assert not valid
    assert "@fill does not support modifiers" in err


def test_origin_missing_coords():
    valid, err = validate("@origin")
    assert not valid
    assert "requires" in err


def test_origin_bad_coords():
    valid, err = validate("@origin a,b,c")
    assert not valid
    assert "Invalid" in err


def test_unknown_directive():
    valid, err = validate("@foo")
    assert not valid
    assert "Unknown directive" in err


def test_empty_string():
    valid, err = validate("")
    assert valid, err


def test_all_block_codes():
    valid, err = validate("D R C T W P K O H L B # G S N _ A X I J Q")
    assert valid, err


def test_fill_directive():
    valid, err = validate("@fill #")
    assert valid, err


def test_fill_missing_arg():
    valid, err = validate("@fill")
    assert not valid
    assert "requires" in err


def test_fill_invalid_code():
    valid, err = validate("@fill Z")
    assert not valid
    assert "Unknown block code" in err


def test_validate_rejects_negative_workspace_local_origin():
    valid, err = validate("@origin -1,0,0 #", size_x=8, size_y=4, size_z=8)
    assert not valid
    assert "workspace-local and non-negative" in err


def test_validate_rejects_out_of_bounds_workspace_local_origin():
    valid, err = validate("@origin 8,0,0 #", size_x=8, size_y=4, size_z=8)
    assert not valid
    assert "exceeds workspace dimensions 8x4x8" in err
