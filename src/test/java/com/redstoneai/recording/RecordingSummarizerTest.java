package com.redstoneai.recording;

import com.redstoneai.workspace.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingSummarizerTest {

    @Test
    void level2AndLevel3HandleRangesPastCurrentCursor() {
        BlockPos controller = new BlockPos(0, 5, 0);
        BoundingBox bounds = BoundingBox.fromCorners(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
        Workspace workspace = new Workspace(UUID.randomUUID(), UUID.randomUUID(), "summary_test", controller, bounds);
        BlockPos markerPos = workspace.getOriginPos();
        workspace.addIOMarker(new IOMarker(markerPos, IOMarker.IORole.INPUT, "A"));

        RecordingTimeline timeline = new RecordingTimeline(16);
        timeline.addDelta(new TickSnapshot(
                0,
                Map.of(),
                Map.of(markerPos, 15),
                List.of(),
                List.of(),
                null,
                null
        ));
        workspace.setTimeline(timeline);

        assertEquals("No visible ticks in selected range.", RecordingSummarizer.level2(workspace, 5, -1));
        assertEquals("No visible ticks in selected range.", RecordingSummarizer.level3(workspace, 5, -1));
    }
}
