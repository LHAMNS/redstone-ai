"""MCR (Minecraft Compact Redstone) format validator and reference."""

BLOCK_CODES = {
    "D": "redstone_wire",
    "R": "repeater",
    "C": "comparator",
    "T": "redstone_torch",
    "W": "redstone_wall_torch",
    "P": "piston",
    "K": "sticky_piston",
    "O": "observer",
    "H": "hopper",
    "L": "lever",
    "B": "stone_button",
    "#": "stone",
    "G": "glass",
    "S": "redstone_lamp",
    "N": "note_block",
    "_": "air",
    "A": "target",
    "X": "tnt",
    "I": "dropper",
    "J": "dispenser",
    "Q": "daylight_detector",
}

MODIFIERS = {
    "n": "facing=north",
    "e": "facing=east",
    "s": "facing=south",
    "w": "facing=west",
    "u": "facing=up",
    "d": "facing=down",
    "1": "delay=1",
    "2": "delay=2",
    "3": "delay=3",
    "4": "delay=4",
    "c": "mode=compare",
    "x": "mode=subtract",
}

DIRECTIVES = ["@origin", "@row", "@layer", "@fill"]
FACING_CODES = {"R", "C", "W", "P", "K", "O", "H", "L", "B", "I", "J"}
DELAY_CODES = {"R"}
MODE_CODES = {"C"}


def validate(
    mcr: str,
    *,
    size_x: int | None = None,
    size_y: int | None = None,
    size_z: int | None = None,
) -> tuple[bool, str]:
    """Validate MCR string syntax. Returns (valid, error_message)."""
    tokens = mcr.strip().split()
    i = 0
    cx = cy = cz = 0
    row_start_x = 0
    layer_start_z = 0

    def validate_cursor(x: int, y: int, z: int) -> tuple[bool, str]:
        if size_x is None or size_y is None or size_z is None:
            return True, ""
        if x < 0 or y < 0 or z < 0:
            return False, f"MCR coordinates must stay workspace-local and non-negative (got {x},{y},{z})"
        if x >= size_x or y >= size_y or z >= size_z:
            return False, f"MCR placement {x},{y},{z} exceeds workspace dimensions {size_x}x{size_y}x{size_z}"
        return True, ""

    while i < len(tokens):
        token = tokens[i]
        if token.startswith("@"):
            directive = token[1:].lower()
            if directive == "origin":
                i += 1
                if i >= len(tokens):
                    return False, "@origin requires x,y,z argument"
                parts = tokens[i].split(",")
                if len(parts) != 3:
                    return False, f"@origin requires x,y,z (got: {tokens[i]})"
                for p in parts:
                    try:
                        int(p)
                    except ValueError:
                        return False, f"Invalid coordinate in @origin: {p}"
                cx, cy, cz = (int(parts[0]), int(parts[1]), int(parts[2]))
                row_start_x = cx
                layer_start_z = cz
                ok, err = validate_cursor(cx, cy, cz)
                if not ok:
                    return False, err
            elif directive in ("row", "layer"):
                if directive == "row":
                    cz += 1
                    cx = row_start_x
                else:
                    cy += 1
                    cx = row_start_x
                    cz = layer_start_z
            elif directive == "fill":
                i += 1
                if i >= len(tokens):
                    return False, "@fill requires a block code argument"
                fill_token = tokens[i]
                if not fill_token or fill_token[0] not in BLOCK_CODES:
                    return False, f"Unknown block code in @fill: '{fill_token}'"
                if len(fill_token) != 1:
                    return False, f"@fill does not support modifiers: '{fill_token}'"
            else:
                return False, f"Unknown directive: @{directive}"
        else:
            if not token:
                i += 1
                continue
            code = token[0]
            if code not in BLOCK_CODES:
                return False, f"Unknown block code: '{code}' in '{token}'"
            for mod in token[1:]:
                if mod not in MODIFIERS:
                    return False, f"Unknown modifier '{mod}' in '{token}'"
                if mod in {"n", "e", "s", "w", "u", "d"} and code not in FACING_CODES:
                    return False, f"Block code '{code}' does not support facing modifiers"
                if mod in {"1", "2", "3", "4"} and code not in DELAY_CODES:
                    return False, f"Block code '{code}' does not support delay modifiers"
                if mod in {"c", "x"} and code not in MODE_CODES:
                    return False, f"Block code '{code}' does not support mode modifiers"
            ok, err = validate_cursor(cx, cy, cz)
            if not ok:
                return False, err
            cx += 1
        i += 1
    return True, ""


REFERENCE_CARD = """MCR Block Codes:
  D=redstone_wire  R=repeater  C=comparator  T=torch  W=wall_torch
  P=piston  K=sticky_piston  O=observer  H=hopper  L=lever  B=button
  #=stone  G=glass  S=lamp  N=noteblock  _=air
  A=target  X=tnt  I=dropper  J=dispenser  Q=daylight_detector
Modifiers:
  n/e/s/w/u/d = facing (north/east/south/west/up/down)
  1-4 = delay (repeater/comparator)
  c = compare mode, x = subtract mode (comparator)
Directives:
  @origin x,y,z  — set cursor position
  @row           — next row (+z, reset x)
  @layer         — next layer (+y, reset x and z)
  @fill code     — fill entire workspace with block
Example: @origin 0,1,0 # # # @row D Rn2 D @row # Cex #
"""
