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


def validate(mcr: str) -> tuple[bool, str]:
    """Validate MCR string syntax. Returns (valid, error_message)."""
    tokens = mcr.strip().split()
    i = 0
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
            elif directive in ("row", "layer"):
                pass
            elif directive == "fill":
                i += 1
                if i >= len(tokens):
                    return False, "@fill requires a block code argument"
                fill_token = tokens[i]
                if not fill_token or fill_token[0] not in BLOCK_CODES:
                    return False, f"Unknown block code in @fill: '{fill_token}'"
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
