package com.redstoneai.recording;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecordingTimelineTest {

    private TickSnapshot makeDelta(int tickIndex) {
        return new TickSnapshot(tickIndex, Map.of(), Map.of(), List.of(), List.of(), null, null);
    }

    private TickSnapshot makeDeltaWithChange(int tickIndex, BlockPos pos, BlockState oldState, BlockState newState) {
        return new TickSnapshot(tickIndex,
                Map.of(pos, new TickSnapshot.BlockStateChange(oldState, newState, null, null)),
                Map.of(), List.of(), List.of(), null, null);
    }

    @Test
    void addDeltaAndGetLength() {
        RecordingTimeline timeline = new RecordingTimeline(100);
        assertEquals(0, timeline.getLength());
        assertEquals(-1, timeline.getCurrentIndex());

        timeline.addDelta(makeDelta(0));
        assertEquals(1, timeline.getLength());
        assertEquals(0, timeline.getCurrentIndex());

        timeline.addDelta(makeDelta(1));
        assertEquals(2, timeline.getLength());
        assertEquals(1, timeline.getCurrentIndex());
    }

    @Test
    void rewindAndFastForward() {
        RecordingTimeline timeline = new RecordingTimeline(100);
        for (int i = 0; i < 5; i++) {
            timeline.addDelta(makeDelta(i));
        }
        assertEquals(4, timeline.getCurrentIndex());

        // Rewind 3
        assertTrue(timeline.canRewind(3));
        List<TickSnapshot> undone = timeline.rewind(3);
        assertEquals(3, undone.size());
        assertEquals(1, timeline.getCurrentIndex());

        // Fast forward 2
        assertTrue(timeline.canFastForward(2));
        List<TickSnapshot> replayed = timeline.fastForward(2);
        assertEquals(2, replayed.size());
        assertEquals(3, timeline.getCurrentIndex());
    }

    @Test
    void rewindBeyondStartFails() {
        RecordingTimeline timeline = new RecordingTimeline(100);
        timeline.addDelta(makeDelta(0));
        assertFalse(timeline.canRewind(5));
    }

    @Test
    void addDeltaAfterRewindTruncatesFuture() {
        RecordingTimeline timeline = new RecordingTimeline(100);
        for (int i = 0; i < 5; i++) {
            timeline.addDelta(makeDelta(i));
        }
        timeline.rewind(3); // currentIndex = 1
        assertEquals(1, timeline.getCurrentIndex());

        timeline.addDelta(makeDelta(10));
        assertEquals(3, timeline.getLength()); // deltas 0, 1, 10
        assertEquals(2, timeline.getCurrentIndex());
    }

    @Test
    void maxTicksEviction() {
        RecordingTimeline timeline = new RecordingTimeline(3);
        for (int i = 0; i < 5; i++) {
            timeline.addDelta(makeDelta(i));
        }
        assertEquals(3, timeline.getLength()); // Only last 3 kept
    }

    @Test
    void getDeltaAtIndex() {
        RecordingTimeline timeline = new RecordingTimeline(100);
        timeline.addDelta(makeDelta(42));
        TickSnapshot delta = timeline.getDelta(0);
        assertNotNull(delta);
        assertEquals(42, delta.tickIndex());
    }

    @Test
    void getDeltaOutOfRange() {
        RecordingTimeline timeline = new RecordingTimeline(100);
        assertNull(timeline.getDelta(0));
        assertNull(timeline.getDelta(-1));
    }
}
