package com.redstoneai.recording;

import com.redstoneai.workspace.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;

/**
 * Produces multi-level text summaries of simulation recordings.
 * <p>
 * Level 1 (~50 tokens): Pass/fail summary with IO values at final tick.
 * Level 2 (~200 tokens): ASCII timing diagram of IO signals over time.
 * Level 3 (~500 tokens): Tick-by-tick detail of all block changes.
 */
public final class RecordingSummarizer {

    private RecordingSummarizer() {}

    /**
     * Level 1: Compact summary. Final IO power levels + total ticks + change count.
     */
    public static String level1(Workspace ws) {
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) return "No recording.";

        StringBuilder sb = new StringBuilder();
        sb.append("Ticks: ").append(timeline.getLength());
        sb.append(" | Position: ").append(timeline.getCurrentIndex() + 1);
        sb.append("/").append(timeline.getLength()).append("\n");

        // Get latest IO power levels
        if (timeline.getCurrentIndex() >= 0) {
            TickSnapshot latest = timeline.getDelta(timeline.getCurrentIndex());
            if (latest != null && !latest.ioPowerLevels().isEmpty()) {
                sb.append("IO: ");
                List<IOMarker> markers = ws.getIOMarkers();
                for (IOMarker marker : markers) {
                    Integer power = latest.ioPowerLevels().get(marker.pos());
                    if (power != null) {
                        sb.append(marker.label()).append("=").append(power).append(" ");
                    }
                }
                sb.append("\n");
            }
        }

        // Count total changes
        int totalChanges = 0;
        for (int i = 0; i < timeline.getLength(); i++) {
            TickSnapshot delta = timeline.getDelta(i);
            if (delta != null) totalChanges += delta.blockChanges().size();
        }
        sb.append("Changes: ").append(totalChanges);

        return sb.toString();
    }

    /**
     * Level 2: ASCII timing diagram of IO signals.
     * Each row is an IO marker, each column is a tick.
     * Power levels shown as hex (0-F).
     */
    public static String level2(Workspace ws) {
        return level2(ws, 0, -1);
    }

    /**
     * Level 2 with tick range.
     */
    public static String level2(Workspace ws, int fromTick, int toTick) {
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) return "No recording.";
        if (timeline.getLength() == 0) return "Empty recording.";

        List<IOMarker> markers = ws.getIOMarkers();
        if (markers.isEmpty()) return "No IO markers defined.";

        int start = Math.max(0, fromTick);
        int end = toTick < 0 ? timeline.getLength() - 1 : Math.min(toTick, timeline.getLength() - 1);

        // Limit width to 64 ticks for readability
        if (end - start > 63) end = start + 63;

        // Find longest label for padding
        int maxLabel = markers.stream().mapToInt(m -> m.label().length()).max().orElse(3);
        maxLabel = Math.max(maxLabel, 4);

        StringBuilder sb = new StringBuilder();

        // Header with tick numbers
        sb.append(pad("tick", maxLabel)).append(" |");
        for (int t = start; t <= end; t++) {
            sb.append(t % 10);
        }
        sb.append("\n");
        sb.append("-".repeat(maxLabel)).append("-+").append("-".repeat(end - start + 1)).append("\n");

        // Each marker row
        for (IOMarker marker : markers) {
            String prefix = marker.role().getSerializedName().substring(0, 1).toUpperCase();
            sb.append(pad(prefix + ":" + marker.label(), maxLabel)).append(" |");
            for (int t = start; t <= end; t++) {
                TickSnapshot delta = timeline.getDelta(t);
                if (delta != null) {
                    Integer power = delta.ioPowerLevels().get(marker.pos());
                    sb.append(power != null ? Integer.toHexString(power).toUpperCase() : ".");
                } else {
                    sb.append(".");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Level 3: Tick-by-tick detail of all block changes.
     */
    public static String level3(Workspace ws) {
        return level3(ws, 0, -1);
    }

    /**
     * Level 3 with tick range.
     */
    public static String level3(Workspace ws, int fromTick, int toTick) {
        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) return "No recording.";
        if (timeline.getLength() == 0) return "Empty recording.";

        int start = Math.max(0, fromTick);
        int end = toTick < 0 ? timeline.getLength() - 1 : Math.min(toTick, timeline.getLength() - 1);

        // Limit to 20 ticks per call for token budget
        if (end - start > 19) end = start + 19;

        StringBuilder sb = new StringBuilder();

        for (int t = start; t <= end; t++) {
            TickSnapshot delta = timeline.getDelta(t);
            if (delta == null) continue;

            sb.append("T").append(delta.tickIndex()).append(":");
            if (delta.blockChanges().isEmpty()) {
                sb.append(" (no changes)\n");
                continue;
            }
            sb.append("\n");

            for (var entry : delta.blockChanges().entrySet()) {
                BlockPos pos = entry.getKey();
                TickSnapshot.BlockStateChange change = entry.getValue();
                sb.append("  [").append(pos.getX()).append(",")
                        .append(pos.getY()).append(",")
                        .append(pos.getZ()).append("] ");
                sb.append(summarizeState(change.oldState()));
                sb.append(" -> ");
                sb.append(summarizeState(change.newState()));
                sb.append("\n");
            }

            // IO levels at end of tick
            if (!delta.ioPowerLevels().isEmpty()) {
                sb.append("  IO:");
                for (var entry : delta.ioPowerLevels().entrySet()) {
                    sb.append(" ").append(entry.getValue());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static String summarizeState(BlockState state) {
        String name = state.getBlock().getName().getString();
        // Shorten common names
        name = name.replace("Block of ", "").replace(" Block", "");

        StringBuilder props = new StringBuilder();
        for (Property<?> prop : state.getProperties()) {
            Comparable<?> val = state.getValue(prop);
            String valStr = val.toString();
            // Only include interesting properties
            if (prop.getName().equals("waterlogged") && valStr.equals("false")) continue;
            if (props.length() > 0) props.append(",");
            props.append(prop.getName()).append("=").append(valStr);
        }

        if (props.length() > 0) {
            return name + "[" + props + "]";
        }
        return name;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
