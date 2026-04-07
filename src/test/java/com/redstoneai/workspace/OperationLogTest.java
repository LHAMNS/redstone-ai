package com.redstoneai.workspace;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationLogTest {

    @Test
    void logAndRetrieve() {
        OperationLog log = new OperationLog();
        log.logAI("build", "placed 5 blocks");
        log.logPlayer("freeze", "");
        log.logSystem("create", "workspace test");

        assertEquals(3, log.size());
        assertEquals("AI", log.getEntries().get(0).source());
        assertEquals("build", log.getEntries().get(0).action());
        assertEquals("placed 5 blocks", log.getEntries().get(0).detail());
    }

    @Test
    void getLastN() {
        OperationLog log = new OperationLog();
        for (int i = 0; i < 10; i++) {
            log.logSystem("action" + i, "");
        }
        var last3 = log.getLastN(3);
        assertEquals(3, last3.size());
        assertEquals("action7", last3.get(0).action());
        assertEquals("action9", last3.get(2).action());
    }

    @Test
    void maxEntriesTrimmed() {
        OperationLog log = new OperationLog();
        for (int i = 0; i < 600; i++) {
            log.logSystem("action" + i, "");
        }
        assertTrue(log.size() <= 500);
    }

    @Test
    void nbtRoundTrip() {
        OperationLog log = new OperationLog();
        log.logAI("build", "test");
        log.logPlayer("revert", "42 blocks");

        CompoundTag tag = log.save();
        OperationLog loaded = OperationLog.load(tag);

        assertEquals(2, loaded.size());
        assertEquals("AI", loaded.getEntries().get(0).source());
        assertEquals("build", loaded.getEntries().get(0).action());
        assertEquals("Player", loaded.getEntries().get(1).source());
    }

    @Test
    void clearWorks() {
        OperationLog log = new OperationLog();
        log.logSystem("test", "");
        assertEquals(1, log.size());
        log.clear();
        assertEquals(0, log.size());
    }

    @Test
    void entryDisplayString() {
        OperationLog.Entry entry = new OperationLog.Entry(0, "AI", "build", "5 blocks");
        assertEquals("[AI] build: 5 blocks", entry.toDisplayString());
    }

    @Test
    void entryDisplayStringNoDetail() {
        OperationLog.Entry entry = new OperationLog.Entry(0, "System", "create", "");
        assertEquals("[System] create", entry.toDisplayString());
    }
}
