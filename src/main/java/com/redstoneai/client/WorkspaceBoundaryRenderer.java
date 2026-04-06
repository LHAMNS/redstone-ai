package com.redstoneai.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import com.redstoneai.network.WorkspaceBoundarySyncPacket.WorkspaceBoundaryView;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Renders workspace boundaries with RGB axis-colored edges and a glow effect.
 * <p>
 * Each axis is color-coded like structure blocks:
 * <ul>
 *   <li>X axis edges: <b>Red</b> (1.0, 0.2, 0.2)</li>
 *   <li>Y axis edges: <b>Green</b> (0.2, 1.0, 0.2)</li>
 *   <li>Z axis edges: <b>Blue</b> (0.3, 0.3, 1.0)</li>
 * </ul>
 * <p>
 * The glow effect is achieved by rendering translucent quads behind each edge,
 * creating a soft halo. Frozen workspaces have brighter, pulsing edges.
 */
public final class WorkspaceBoundaryRenderer {

    private static final float GLOW_WIDTH = 0.08f;
    private static final float GLOW_ALPHA = 0.25f;

    private WorkspaceBoundaryRenderer() {}

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        ResourceLocation dimensionId = mc.level.dimension().location();
        var views = ClientWorkspaceCache.get(dimensionId);
        if (views.isEmpty()) return;

        Vec3 cam = event.getCamera().getPosition();

        for (WorkspaceBoundaryView ws : views) {
            BoundingBox bounds = ws.bounds();

            // Distance check
            double dx = mc.player.getX() - (bounds.minX() + bounds.maxX()) / 2.0;
            double dy = mc.player.getY() - (bounds.minY() + bounds.maxY()) / 2.0;
            double dz = mc.player.getZ() - (bounds.minZ() + bounds.maxZ()) / 2.0;
            if (dx * dx + dy * dy + dz * dz > 128 * 128) continue;

            // Compute brightness pulse for frozen workspaces
            float pulse = 1.0f;
            if (ws.frozen()) {
                float time = (System.currentTimeMillis() % 2000) / 2000.0f;
                pulse = 0.7f + 0.3f * (float) Math.sin(time * Math.PI * 2);
            }

            float x0 = (float) (bounds.minX() - cam.x);
            float y0 = (float) (bounds.minY() - cam.y);
            float z0 = (float) (bounds.minZ() - cam.z);
            float x1 = (float) (bounds.maxX() + 1 - cam.x);
            float y1 = (float) (bounds.maxY() + 1 - cam.y);
            float z1 = (float) (bounds.maxZ() + 1 - cam.z);

            PoseStack poseStack = event.getPoseStack();
            poseStack.pushPose();

            // ── Pass 1: Glow quads (translucent) ──────────────────────

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            BufferBuilder buf = Tesselator.getInstance().getBuilder();
            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            Matrix4f mat = poseStack.last().pose();

            float ga = GLOW_ALPHA * pulse;
            float gw = GLOW_WIDTH;

            // X-axis edges (red glow) — 4 edges parallel to X
            drawGlowEdgeX(buf, mat, x0, y0, z0, x1, gw, 1f, 0.15f, 0.1f, ga);
            drawGlowEdgeX(buf, mat, x0, y1, z0, x1, gw, 1f, 0.15f, 0.1f, ga);
            drawGlowEdgeX(buf, mat, x0, y0, z1, x1, gw, 1f, 0.15f, 0.1f, ga);
            drawGlowEdgeX(buf, mat, x0, y1, z1, x1, gw, 1f, 0.15f, 0.1f, ga);

            // Y-axis edges (green glow) — 4 edges parallel to Y
            drawGlowEdgeY(buf, mat, x0, y0, z0, y1, gw, 0.1f, 1f, 0.15f, ga);
            drawGlowEdgeY(buf, mat, x1, y0, z0, y1, gw, 0.1f, 1f, 0.15f, ga);
            drawGlowEdgeY(buf, mat, x0, y0, z1, y1, gw, 0.1f, 1f, 0.15f, ga);
            drawGlowEdgeY(buf, mat, x1, y0, z1, y1, gw, 0.1f, 1f, 0.15f, ga);

            // Z-axis edges (blue glow) — 4 edges parallel to Z
            drawGlowEdgeZ(buf, mat, x0, y0, z0, z1, gw, 0.15f, 0.2f, 1f, ga);
            drawGlowEdgeZ(buf, mat, x1, y0, z0, z1, gw, 0.15f, 0.2f, 1f, ga);
            drawGlowEdgeZ(buf, mat, x0, y1, z0, z1, gw, 0.15f, 0.2f, 1f, ga);
            drawGlowEdgeZ(buf, mat, x1, y1, z0, z1, gw, 0.15f, 0.2f, 1f, ga);

            BufferUploader.drawWithShader(buf.end());

            // ── Pass 2: Solid colored lines ───────────────────────────

            RenderSystem.enableDepthTest();

            VertexConsumer lines = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
            Matrix4f lineMat = poseStack.last().pose();
            var normal = poseStack.last().normal();

            float lp = Math.min(1.0f, pulse * 1.2f); // line brightness

            // X-axis edges (red)
            float xr = lp, xg = 0.2f * lp, xb = 0.2f * lp;
            line(lines, lineMat, normal, x0, y0, z0, x1, y0, z0, xr, xg, xb);
            line(lines, lineMat, normal, x0, y1, z0, x1, y1, z0, xr, xg, xb);
            line(lines, lineMat, normal, x0, y0, z1, x1, y0, z1, xr, xg, xb);
            line(lines, lineMat, normal, x0, y1, z1, x1, y1, z1, xr, xg, xb);

            // Y-axis edges (green)
            float yr = 0.2f * lp, yg = lp, yb = 0.2f * lp;
            line(lines, lineMat, normal, x0, y0, z0, x0, y1, z0, yr, yg, yb);
            line(lines, lineMat, normal, x1, y0, z0, x1, y1, z0, yr, yg, yb);
            line(lines, lineMat, normal, x0, y0, z1, x0, y1, z1, yr, yg, yb);
            line(lines, lineMat, normal, x1, y0, z1, x1, y1, z1, yr, yg, yb);

            // Z-axis edges (blue)
            float zr = 0.2f * lp, zg = 0.3f * lp, zb = lp;
            line(lines, lineMat, normal, x0, y0, z0, x0, y0, z1, zr, zg, zb);
            line(lines, lineMat, normal, x1, y0, z0, x1, y0, z1, zr, zg, zb);
            line(lines, lineMat, normal, x0, y1, z0, x0, y1, z1, zr, zg, zb);
            line(lines, lineMat, normal, x1, y1, z0, x1, y1, z1, zr, zg, zb);

            mc.renderBuffers().bufferSource().endBatch(RenderType.lines());

            poseStack.popPose();
            RenderSystem.disableBlend();
        }
    }

    // ── Line helper ──────────────────────────────────────────────────

    private static void line(VertexConsumer vc, Matrix4f mat, org.joml.Matrix3f normal,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float r, float g, float b) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;

        vc.vertex(mat, x0, y0, z0).color(r, g, b, 1.0f).normal(normal, nx, ny, nz).endVertex();
        vc.vertex(mat, x1, y1, z1).color(r, g, b, 1.0f).normal(normal, nx, ny, nz).endVertex();
    }

    // ── Glow quad helpers ────────────────────────────────────────────
    // Each edge gets a thin quad strip perpendicular to the camera-facing direction.
    // We render two perpendicular quads to create a cross-shaped glow visible from any angle.

    private static void drawGlowEdgeX(BufferBuilder buf, Matrix4f mat,
                                      float x0, float y, float z, float x1,
                                      float w, float r, float g, float b, float a) {
        // Vertical quad along X
        buf.vertex(mat, x0, y - w, z).color(r, g, b, 0).endVertex();
        buf.vertex(mat, x0, y + w, z).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y + w, z).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y - w, z).color(r, g, b, 0).endVertex();
        // Horizontal quad along X
        buf.vertex(mat, x0, y, z - w).color(r, g, b, 0).endVertex();
        buf.vertex(mat, x0, y, z + w).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y, z + w).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y, z - w).color(r, g, b, 0).endVertex();
    }

    private static void drawGlowEdgeY(BufferBuilder buf, Matrix4f mat,
                                      float x, float y0, float z, float y1,
                                      float w, float r, float g, float b, float a) {
        buf.vertex(mat, x - w, y0, z).color(r, g, b, 0).endVertex();
        buf.vertex(mat, x + w, y0, z).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w, y1, z).color(r, g, b, a).endVertex();
        buf.vertex(mat, x - w, y1, z).color(r, g, b, 0).endVertex();

        buf.vertex(mat, x, y0, z - w).color(r, g, b, 0).endVertex();
        buf.vertex(mat, x, y0, z + w).color(r, g, b, a).endVertex();
        buf.vertex(mat, x, y1, z + w).color(r, g, b, a).endVertex();
        buf.vertex(mat, x, y1, z - w).color(r, g, b, 0).endVertex();
    }

    private static void drawGlowEdgeZ(BufferBuilder buf, Matrix4f mat,
                                      float x, float y, float z0, float z1,
                                      float w, float r, float g, float b, float a) {
        buf.vertex(mat, x - w, y, z0).color(r, g, b, 0).endVertex();
        buf.vertex(mat, x + w, y, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w, y, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x - w, y, z1).color(r, g, b, 0).endVertex();

        buf.vertex(mat, x, y - w, z0).color(r, g, b, 0).endVertex();
        buf.vertex(mat, x, y + w, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x, y + w, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x, y - w, z1).color(r, g, b, 0).endVertex();
    }
}
