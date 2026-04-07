package com.redstoneai.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.redstoneai.network.WorkspaceBoundarySyncPacket.WorkspaceBoundaryView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Shared world-space renderer for workspace bounds and temporary selection previews.
 */
public final class WorkspaceBoundaryRenderer {
    private static final float GLOW_WIDTH = 0.08f;
    private static final float GLOW_ALPHA = 0.25f;

    private WorkspaceBoundaryRenderer() {}

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        ResourceLocation dimensionId = mc.level.dimension().location();
        var views = ClientWorkspaceCache.get(dimensionId);
        if (views.isEmpty()) {
            return;
        }

        for (WorkspaceBoundaryView workspaceView : views) {
            BoundingBox bounds = workspaceView.bounds();
            double dx = mc.player.getX() - (bounds.minX() + bounds.maxX()) / 2.0;
            double dy = mc.player.getY() - (bounds.minY() + bounds.maxY()) / 2.0;
            double dz = mc.player.getZ() - (bounds.minZ() + bounds.maxZ()) / 2.0;
            if (dx * dx + dy * dy + dz * dz > 128 * 128) {
                continue;
            }

            float pulse = 1.0f;
            if (workspaceView.frozen()) {
                float time = (System.currentTimeMillis() % 2000L) / 2000.0f;
                pulse = 0.7f + 0.3f * (float) Math.sin(time * Math.PI * 2.0);
            }
            renderBoundaryBox(event, bounds, pulse);
        }
    }

    public static void renderBoundaryBox(RenderLevelStageEvent event, BoundingBox bounds, float pulse) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        float x0 = (float) (bounds.minX() - cam.x);
        float y0 = (float) (bounds.minY() - cam.y);
        float z0 = (float) (bounds.minZ() - cam.z);
        float x1 = (float) (bounds.maxX() + 1 - cam.x);
        float y1 = (float) (bounds.maxY() + 1 - cam.y);
        float z1 = (float) (bounds.maxZ() + 1 - cam.z);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder glow = Tesselator.getInstance().getBuilder();
        glow.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f pose = poseStack.last().pose();
        float alpha = GLOW_ALPHA * pulse;
        float glowWidth = GLOW_WIDTH;

        drawGlowEdgeX(glow, pose, x0, y0, z0, x1, glowWidth, 1f, 0.15f, 0.1f, alpha);
        drawGlowEdgeX(glow, pose, x0, y1, z0, x1, glowWidth, 1f, 0.15f, 0.1f, alpha);
        drawGlowEdgeX(glow, pose, x0, y0, z1, x1, glowWidth, 1f, 0.15f, 0.1f, alpha);
        drawGlowEdgeX(glow, pose, x0, y1, z1, x1, glowWidth, 1f, 0.15f, 0.1f, alpha);

        drawGlowEdgeY(glow, pose, x0, y0, z0, y1, glowWidth, 0.1f, 1f, 0.15f, alpha);
        drawGlowEdgeY(glow, pose, x1, y0, z0, y1, glowWidth, 0.1f, 1f, 0.15f, alpha);
        drawGlowEdgeY(glow, pose, x0, y0, z1, y1, glowWidth, 0.1f, 1f, 0.15f, alpha);
        drawGlowEdgeY(glow, pose, x1, y0, z1, y1, glowWidth, 0.1f, 1f, 0.15f, alpha);

        drawGlowEdgeZ(glow, pose, x0, y0, z0, z1, glowWidth, 0.15f, 0.2f, 1f, alpha);
        drawGlowEdgeZ(glow, pose, x1, y0, z0, z1, glowWidth, 0.15f, 0.2f, 1f, alpha);
        drawGlowEdgeZ(glow, pose, x0, y1, z0, z1, glowWidth, 0.15f, 0.2f, 1f, alpha);
        drawGlowEdgeZ(glow, pose, x1, y1, z0, z1, glowWidth, 0.15f, 0.2f, 1f, alpha);
        BufferUploader.drawWithShader(glow.end());

        RenderSystem.enableDepthTest();
        VertexConsumer lines = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        Matrix4f linePose = poseStack.last().pose();
        var normal = poseStack.last().normal();
        float brightness = Math.min(1.0f, pulse * 1.2f);

        float xr = brightness;
        float xg = 0.2f * brightness;
        float xb = 0.2f * brightness;
        line(lines, linePose, normal, x0, y0, z0, x1, y0, z0, xr, xg, xb);
        line(lines, linePose, normal, x0, y1, z0, x1, y1, z0, xr, xg, xb);
        line(lines, linePose, normal, x0, y0, z1, x1, y0, z1, xr, xg, xb);
        line(lines, linePose, normal, x0, y1, z1, x1, y1, z1, xr, xg, xb);

        float yr = 0.2f * brightness;
        float yg = brightness;
        float yb = 0.2f * brightness;
        line(lines, linePose, normal, x0, y0, z0, x0, y1, z0, yr, yg, yb);
        line(lines, linePose, normal, x1, y0, z0, x1, y1, z0, yr, yg, yb);
        line(lines, linePose, normal, x0, y0, z1, x0, y1, z1, yr, yg, yb);
        line(lines, linePose, normal, x1, y0, z1, x1, y1, z1, yr, yg, yb);

        float zr = 0.2f * brightness;
        float zg = 0.3f * brightness;
        float zb = brightness;
        line(lines, linePose, normal, x0, y0, z0, x0, y0, z1, zr, zg, zb);
        line(lines, linePose, normal, x1, y0, z0, x1, y0, z1, zr, zg, zb);
        line(lines, linePose, normal, x0, y1, z0, x0, y1, z1, zr, zg, zb);
        line(lines, linePose, normal, x1, y1, z0, x1, y1, z1, zr, zg, zb);

        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
        poseStack.popPose();
        RenderSystem.disableBlend();
    }

    private static void line(VertexConsumer vc,
                             Matrix4f pose,
                             org.joml.Matrix3f normal,
                             float x0,
                             float y0,
                             float z0,
                             float x1,
                             float y1,
                             float z1,
                             float r,
                             float g,
                             float b) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.001f) {
            return;
        }
        float nx = dx / length;
        float ny = dy / length;
        float nz = dz / length;
        vc.vertex(pose, x0, y0, z0).color(r, g, b, 1.0f).normal(normal, nx, ny, nz).endVertex();
        vc.vertex(pose, x1, y1, z1).color(r, g, b, 1.0f).normal(normal, nx, ny, nz).endVertex();
    }

    private static void drawGlowEdgeX(BufferBuilder buf, Matrix4f pose,
                                      float x0, float y, float z, float x1,
                                      float width, float r, float g, float b, float a) {
        buf.vertex(pose, x0, y - width, z).color(r, g, b, 0).endVertex();
        buf.vertex(pose, x0, y + width, z).color(r, g, b, a).endVertex();
        buf.vertex(pose, x1, y + width, z).color(r, g, b, a).endVertex();
        buf.vertex(pose, x1, y - width, z).color(r, g, b, 0).endVertex();

        buf.vertex(pose, x0, y, z - width).color(r, g, b, 0).endVertex();
        buf.vertex(pose, x0, y, z + width).color(r, g, b, a).endVertex();
        buf.vertex(pose, x1, y, z + width).color(r, g, b, a).endVertex();
        buf.vertex(pose, x1, y, z - width).color(r, g, b, 0).endVertex();
    }

    private static void drawGlowEdgeY(BufferBuilder buf, Matrix4f pose,
                                      float x, float y0, float z, float y1,
                                      float width, float r, float g, float b, float a) {
        buf.vertex(pose, x - width, y0, z).color(r, g, b, 0).endVertex();
        buf.vertex(pose, x + width, y0, z).color(r, g, b, a).endVertex();
        buf.vertex(pose, x + width, y1, z).color(r, g, b, a).endVertex();
        buf.vertex(pose, x - width, y1, z).color(r, g, b, 0).endVertex();

        buf.vertex(pose, x, y0, z - width).color(r, g, b, 0).endVertex();
        buf.vertex(pose, x, y0, z + width).color(r, g, b, a).endVertex();
        buf.vertex(pose, x, y1, z + width).color(r, g, b, a).endVertex();
        buf.vertex(pose, x, y1, z - width).color(r, g, b, 0).endVertex();
    }

    private static void drawGlowEdgeZ(BufferBuilder buf, Matrix4f pose,
                                      float x, float y, float z0, float z1,
                                      float width, float r, float g, float b, float a) {
        buf.vertex(pose, x - width, y, z0).color(r, g, b, 0).endVertex();
        buf.vertex(pose, x + width, y, z0).color(r, g, b, a).endVertex();
        buf.vertex(pose, x + width, y, z1).color(r, g, b, a).endVertex();
        buf.vertex(pose, x - width, y, z1).color(r, g, b, 0).endVertex();

        buf.vertex(pose, x, y - width, z0).color(r, g, b, 0).endVertex();
        buf.vertex(pose, x, y + width, z0).color(r, g, b, a).endVertex();
        buf.vertex(pose, x, y + width, z1).color(r, g, b, a).endVertex();
        buf.vertex(pose, x, y - width, z1).color(r, g, b, 0).endVertex();
    }
}
