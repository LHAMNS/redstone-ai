package com.redstoneai.mcr;

import net.minecraft.core.Direction;

import java.util.*;

/**
 * Parses MCR (Minecraft Compact Redstone) notation into a list of {@link MCRBlock} objects.
 * <p>
 * Format: Space-separated tokens, each starting with a block code character,
 * optionally followed by modifiers (facing, delay, mode).
 * Position is tracked via cursor movement or explicit coordinates.
 * <p>
 * <b>Block codes:</b>
 * <pre>
 * D=redstone_wire  R=repeater  C=comparator  T=redstone_torch  W=redstone_wall_torch
 * P=piston  K=sticky_piston  O=observer  H=hopper  L=lever  B=stone_button
 * #=stone  G=glass  S=redstone_lamp  N=note_block  _=air
 * A=target  X=tnt  I=dropper  J=dispenser  Q=daylight_detector
 * </pre>
 * <b>Modifiers:</b> n/e/s/w = facing, 1-4 = delay, c/s = compare/subtract mode
 * <p>
 * <b>Directives:</b>
 * <pre>
 * @origin x,y,z   — set cursor position
 * @row             — advance cursor to next row (+z, reset x)
 * @layer           — advance to next layer (+y, reset x and z)
 * @fill code       — fill remaining workspace with block code
 * </pre>
 * <p>
 * <b>Example:</b> {@code @origin 0,1,0 # # # @row D Rn2 D @row # Ces #}
 */
public final class MCRParser {

    private static final Map<Character, String> BLOCK_CODES = Map.ofEntries(
            Map.entry('D', "redstone_wire"),
            Map.entry('R', "repeater"),
            Map.entry('C', "comparator"),
            Map.entry('T', "redstone_torch"),
            Map.entry('W', "redstone_wall_torch"),
            Map.entry('P', "piston"),
            Map.entry('K', "sticky_piston"),
            Map.entry('O', "observer"),
            Map.entry('H', "hopper"),
            Map.entry('L', "lever"),
            Map.entry('B', "stone_button"),
            Map.entry('#', "stone"),
            Map.entry('G', "glass"),
            Map.entry('S', "redstone_lamp"),
            Map.entry('N', "note_block"),
            Map.entry('_', "air"),
            Map.entry('A', "target"),
            Map.entry('X', "tnt"),
            Map.entry('I', "dropper"),
            Map.entry('J', "dispenser"),
            Map.entry('Q', "daylight_detector")
    );

    private MCRParser() {}

    public static List<MCRBlock> parse(String mcr) throws MCRParseException {
        List<MCRBlock> blocks = new ArrayList<>();
        String[] tokens = mcr.trim().split("\\s+");

        int cx = 0, cy = 0, cz = 0;
        int rowStartX = 0;
        int layerStartZ = 0;

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];

            if (token.startsWith("@")) {
                // Directive
                String directive = token.substring(1).toLowerCase();
                switch (directive) {
                    case "origin" -> {
                        if (i + 1 >= tokens.length) throw new MCRParseException("@origin requires x,y,z argument");
                        String[] coords = tokens[++i].split(",");
                        if (coords.length != 3) throw new MCRParseException("@origin requires x,y,z (got: " + tokens[i] + ")");
                        try {
                            cx = Integer.parseInt(coords[0]);
                            cy = Integer.parseInt(coords[1]);
                            cz = Integer.parseInt(coords[2]);
                        } catch (NumberFormatException e) {
                            throw new MCRParseException("Invalid @origin coordinates: " + tokens[i]);
                        }
                        if (Math.abs(cx) > 256 || Math.abs(cy) > 256 || Math.abs(cz) > 256) {
                            throw new MCRParseException("@origin coordinates out of range (max +/-256): " + tokens[i]);
                        }
                        rowStartX = cx;
                        layerStartZ = cz;
                    }
                    case "row" -> {
                        cz++;
                        cx = rowStartX;
                    }
                    case "layer" -> {
                        cy++;
                        cx = rowStartX;
                        cz = layerStartZ;
                    }
                    case "fill" -> {
                        if (i + 1 >= tokens.length) throw new MCRParseException("@fill requires a block code argument");
                        String fillToken = tokens[++i];
                        if (fillToken.isEmpty()) throw new MCRParseException("@fill requires a non-empty block code");
                        char fillCode = fillToken.charAt(0);
                        String fillType = BLOCK_CODES.get(fillCode);
                        if (fillType == null) throw new MCRParseException("Unknown block code: '" + fillCode + "' in @fill");
                        // Emit a fill marker that MCRPlacer interprets as "fill entire workspace"
                        blocks.add(new MCRBlock(fillType, -1, -1, -1, null, 0, null,
                                Map.of("_directive", "fill")));
                    }
                    default -> throw new MCRParseException("Unknown directive: @" + directive);
                }
                continue;
            }

            // Parse block token
            if (token.isEmpty()) continue;

            char code = token.charAt(0);
            String blockType = BLOCK_CODES.get(code);
            if (blockType == null) {
                throw new MCRParseException("Unknown block code: '" + code + "' in token '" + token + "'");
            }

            Direction facing = null;
            int delay = 0;
            String mode = null;

            // Parse modifiers (remaining chars after block code)
            for (int j = 1; j < token.length(); j++) {
                char mod = token.charAt(j);
                switch (mod) {
                    case 'n' -> facing = Direction.NORTH;
                    case 'e' -> facing = Direction.EAST;
                    case 's' -> facing = Direction.SOUTH;
                    case 'w' -> facing = Direction.WEST;
                    case 'u' -> facing = Direction.UP;
                    case 'd' -> facing = Direction.DOWN;
                    case '1', '2', '3', '4' -> delay = mod - '0';
                    case 'c' -> mode = "compare";
                    case 'x' -> mode = "subtract";
                    default -> throw new MCRParseException("Unknown modifier '" + mod + "' in token '" + token + "'");
                }
            }

            blocks.add(new MCRBlock(blockType, cx, cy, cz, facing, delay, mode, Map.of()));
            cx++;
        }

        return blocks;
    }

    /**
     * Get a reference card of all block codes and modifiers.
     */
    public static String getReferenceCard() {
        return """
                MCR Block Codes:
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
                """;
    }

    public static class MCRParseException extends Exception {
        public MCRParseException(String message) {
            super(message);
        }
    }
}
