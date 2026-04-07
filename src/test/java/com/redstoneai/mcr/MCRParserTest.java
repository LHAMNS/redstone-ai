package com.redstoneai.mcr;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MCRParserTest {

    @Test
    void parseSimpleBlocks() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("# D R");
        assertEquals(3, blocks.size());
        assertEquals("stone", blocks.get(0).blockType());
        assertEquals("redstone_wire", blocks.get(1).blockType());
        assertEquals("repeater", blocks.get(2).blockType());
    }

    @Test
    void parsePositionAutoIncrements() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("# # #");
        assertEquals(0, blocks.get(0).x());
        assertEquals(1, blocks.get(1).x());
        assertEquals(2, blocks.get(2).x());
        // All on same y and z
        assertEquals(0, blocks.get(0).y());
        assertEquals(0, blocks.get(0).z());
    }

    @Test
    void parseModifiers() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("Rn2 Cex");
        MCRBlock repeater = blocks.get(0);
        assertEquals("repeater", repeater.blockType());
        assertEquals(Direction.NORTH, repeater.facing());
        assertEquals(2, repeater.delay());

        MCRBlock comparator = blocks.get(1);
        assertEquals("comparator", comparator.blockType());
        assertEquals(Direction.EAST, comparator.facing());
        assertEquals("subtract", comparator.mode());
    }

    @Test
    void parseOriginDirective() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("@origin 5,3,7 #");
        assertEquals(1, blocks.size());
        assertEquals(5, blocks.get(0).x());
        assertEquals(3, blocks.get(0).y());
        assertEquals(7, blocks.get(0).z());
    }

    @Test
    void parseRowDirective() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("@origin 1,0,0 # @row #");
        assertEquals(2, blocks.size());
        assertEquals(1, blocks.get(0).x());
        assertEquals(0, blocks.get(0).z());
        assertEquals(1, blocks.get(1).x()); // x reset
        assertEquals(1, blocks.get(1).z()); // z incremented
    }

    @Test
    void parseLayerDirective() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("@origin 2,1,3 # @layer #");
        assertEquals(2, blocks.size());
        assertEquals(1, blocks.get(0).y());
        assertEquals(3, blocks.get(0).z());
        assertEquals(2, blocks.get(1).y()); // y incremented
        assertEquals(3, blocks.get(1).z()); // z reset
    }

    @Test
    void parseFillDirective() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("@fill #");
        assertEquals(1, blocks.size());
        assertEquals("fill", blocks.get(0).extraProperties().get("_directive"));
        assertEquals("stone", blocks.get(0).blockType());
    }

    @Test
    void originRangeValidation() {
        assertThrows(MCRParser.MCRParseException.class, () ->
                MCRParser.parse("@origin 300,0,0 #"));
        assertThrows(MCRParser.MCRParseException.class, () ->
                MCRParser.parse("@origin 0,-300,0 #"));
    }

    @Test
    void invalidBlockCodeThrows() {
        MCRParser.MCRParseException ex = assertThrows(MCRParser.MCRParseException.class,
                () -> MCRParser.parse("Z"));
        assertTrue(ex.getMessage().contains("Unknown block code"));
    }

    @Test
    void invalidModifierThrows() {
        assertThrows(MCRParser.MCRParseException.class, () -> MCRParser.parse("Rq"));
    }

    @Test
    void unknownDirectiveThrows() {
        assertThrows(MCRParser.MCRParseException.class, () -> MCRParser.parse("@foo"));
    }

    @Test
    void originMissingCoordsThrows() {
        assertThrows(MCRParser.MCRParseException.class, () -> MCRParser.parse("@origin"));
    }

    @Test
    void allBlockCodesParse() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("D R C T W P K O H L B # G S N _ A X I J Q");
        assertEquals(21, blocks.size());
    }

    @Test
    void allFacingModifiers() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("Rn Re Rs Rw Pu Pd");
        assertEquals(Direction.NORTH, blocks.get(0).facing());
        assertEquals(Direction.EAST, blocks.get(1).facing());
        assertEquals(Direction.SOUTH, blocks.get(2).facing());
        assertEquals(Direction.WEST, blocks.get(3).facing());
        assertEquals(Direction.UP, blocks.get(4).facing());
        assertEquals(Direction.DOWN, blocks.get(5).facing());
    }

    @Test
    void allDelays() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("R1 R2 R3 R4");
        assertEquals(1, blocks.get(0).delay());
        assertEquals(2, blocks.get(1).delay());
        assertEquals(3, blocks.get(2).delay());
        assertEquals(4, blocks.get(3).delay());
    }

    @Test
    void comparatorModes() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("Cc Cx");
        assertEquals("compare", blocks.get(0).mode());
        assertEquals("subtract", blocks.get(1).mode());
    }

    @Test
    void emptyStringProducesNoBlocks() throws MCRParser.MCRParseException {
        List<MCRBlock> blocks = MCRParser.parse("");
        assertTrue(blocks.isEmpty());
    }

    @Test
    void referenceCardNotEmpty() {
        String card = MCRParser.getReferenceCard();
        assertFalse(card.isEmpty());
        assertTrue(card.contains("D=redstone_wire"));
        assertTrue(card.contains("@fill"));
    }
}
