package com.redstoneai.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * Renders a temporary world-space selection box while the player is choosing a workspace range.
 */
public final class SelectionPreviewRenderer {
    private SelectionPreviewRenderer() {}

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        ClientSelectionPreviewState.Snapshot snapshot = ClientSelectionPreviewState.get();
        if (!snapshot.active()
                || snapshot.dimensionId() == null
                || !snapshot.dimensionId().equals(mc.level.dimension().location())
                || snapshot.controllerPos() == null
                || snapshot.sizeY() <= 0) {
            return;
        }

        BlockPos hovered = null;
        if (mc.hitResult instanceof BlockHitResult blockHitResult) {
            hovered = blockHitResult.getBlockPos();
        }

        BlockPos firstCorner = snapshot.firstCorner();
        BlockPos secondCorner = hovered != null ? hovered : firstCorner;
        if (firstCorner == null && secondCorner == null) {
            return;
        }
        if (firstCorner == null) {
            firstCorner = secondCorner;
        }
        if (secondCorner == null) {
            secondCorner = firstCorner;
        }

        int minX = Math.min(firstCorner.getX(), secondCorner.getX());
        int maxX = Math.max(firstCorner.getX(), secondCorner.getX());
        int minZ = Math.min(firstCorner.getZ(), secondCorner.getZ());
        int maxZ = Math.max(firstCorner.getZ(), secondCorner.getZ());
        int maxY = snapshot.controllerPos().getY() - 1;
        int minY = maxY - snapshot.sizeY() + 1;

        float time = (System.currentTimeMillis() % 1600L) / 1600.0f;
        float pulse = 0.8f + 0.2f * (float) Math.sin(time * Math.PI * 2.0);
        WorkspaceBoundaryRenderer.renderBoundaryBox(
                event,
                new net.minecraft.world.level.levelgen.structure.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ),
                pulse
        );
    }
}
