package com.redstoneai.workspace;

import com.redstoneai.recording.IOMarker;
import com.redstoneai.tick.TickController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies and reads labeled redstone IO signals inside a workspace.
 * Driving inputs is intentionally non-destructive: the marked block itself must
 * expose a controllable redstone state property such as {@code POWER} or
 * {@code POWERED}. If a marker targets an unsupported block, the caller must
 * fix the circuit fixture rather than silently replacing world blocks.
 */
public final class WorkspaceSignalController {
    private WorkspaceSignalController() {}

    public static void setInput(ServerLevel level, Workspace workspace, String label, int power) {
        setInputs(level, workspace, Map.of(label, power));
    }

    public static void setInputs(ServerLevel level, Workspace workspace, Map<String, Integer> inputs) {
        List<PlannedInputChange> changes = new ArrayList<>(inputs.size());
        for (var entry : inputs.entrySet()) {
            IOMarker marker = findInputMarker(workspace, entry.getKey());
            if (marker == null) {
                throw new IllegalArgumentException("Unknown INPUT marker: " + entry.getKey());
            }
            changes.add(planInputChange(level, marker, entry.getValue()));
        }

        boolean changed = false;
        for (PlannedInputChange change : changes) {
            changed |= change.apply(level);
        }
        if (changed) {
            TickController.invalidateRecording(level, workspace);
        }
    }

    public static void clearInputs(ServerLevel level, Workspace workspace) {
        List<PlannedInputChange> changes = new ArrayList<>();
        for (IOMarker marker : workspace.getIOMarkers()) {
            if (marker.role() == IOMarker.IORole.INPUT && !workspace.isControllerPos(marker.pos())) {
                changes.add(planInputChange(level, marker, 0));
            }
        }

        boolean changed = false;
        for (PlannedInputChange change : changes) {
            changed |= change.apply(level);
        }
        if (changed) {
            TickController.invalidateRecording(level, workspace);
        }
    }

    public static Map<String, Integer> readSignals(ServerLevel level, Workspace workspace, @Nullable IOMarker.IORole role) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (IOMarker marker : workspace.getIOMarkers()) {
            if (workspace.isControllerPos(marker.pos())) {
                continue;
            }
            if (role == null || marker.role() == role) {
                result.put(marker.label(), level.getBestNeighborSignal(marker.pos()));
            }
        }
        return result;
    }

    @Nullable
    private static IOMarker findInputMarker(Workspace workspace, String label) {
        for (IOMarker marker : workspace.getIOMarkers()) {
            if (marker.role() == IOMarker.IORole.INPUT
                    && !workspace.isControllerPos(marker.pos())
                    && marker.label().equals(label)) {
                return marker;
            }
        }
        return null;
    }

    private static PlannedInputChange planInputChange(ServerLevel level, IOMarker marker, int power) {
        if (power < 0 || power > 15) {
            throw new IllegalArgumentException(
                    "Input power for '" + marker.label() + "' must be between 0 and 15"
            );
        }

        BlockState current = level.getBlockState(marker.pos());
        if (current.hasProperty(BlockStateProperties.POWER)) {
            if (!BlockStateProperties.POWER.getPossibleValues().contains(power)) {
                throw new IllegalArgumentException(
                        "INPUT marker '" + marker.label() + "' does not accept analog power " + power
                );
            }
            return new PlannedInputChange(marker.pos(), current.setValue(BlockStateProperties.POWER, power));
        }

        if (current.hasProperty(BlockStateProperties.POWERED)) {
            if (power != 0 && power != 15) {
                throw new IllegalArgumentException(
                        "INPUT marker '" + marker.label()
                                + "' targets a binary source block and only accepts 0 or 15"
                );
            }
            boolean powered = power > 0;
            return new PlannedInputChange(marker.pos(), current.setValue(BlockStateProperties.POWERED, powered));
        }

        throw new IllegalArgumentException(
                "INPUT marker '" + marker.label()
                        + "' must target a controllable source block with POWER or POWERED state"
        );
    }

    private record PlannedInputChange(BlockPos pos, BlockState targetState) {
        private boolean apply(ServerLevel level) {
            BlockState current = level.getBlockState(pos);
            if (current.equals(targetState)) {
                return false;
            }
            level.setBlock(pos, targetState, 3);
            return true;
        }
    }
}
