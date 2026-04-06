package com.redstoneai.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Renders an interactive 3D miniature of a workspace region using actual block models
 * and entity renderers, similar to the structure block preview.
 * <p>
 * Features:
 * <ul>
 *   <li>Real block textures via {@link BlockRenderDispatcher}</li>
 *   <li>Entity rendering (all non-player entities in the region)</li>
 *   <li>Drag to rotate, scroll to zoom</li>
 *   <li>Full lighting with isometric perspective</li>
 * </ul>
 */
public class WorkspacePreviewWidget extends AbstractWidget {

    @Nullable
    private BoundingBox bounds;
    private float rotationY = 225.0f;
    private float rotationX = 30.0f;
    private float zoom = 1.0f;
    private boolean dragging = false;
    private double lastMouseX, lastMouseY;

    private static final int BG_COLOR = 0xEE0A0A18;
    private static final int BORDER_COLOR = 0xFF1A4A3A;
    private static final int LIGHT = LightTexture.pack(15, 15); // Full brightness

    public WorkspacePreviewWidget(int x, int y, int width, int height, BoundingBox bounds) {
        super(x, y, width, height, Component.empty());
        this.bounds = copy(bounds);
    }

    public void setBounds(@Nullable BoundingBox bounds) {
        this.bounds = copy(bounds);
        this.visible = bounds != null;
    }

    public float getRotationY() {
        return rotationY;
    }

    public float getRotationX() {
        return rotationX;
    }

    public float getZoom() {
        return zoom;
    }

    public void setView(float rotationY, float rotationX, float zoom) {
        this.rotationY = rotationY;
        this.rotationX = Math.max(-90, Math.min(90, rotationX));
        this.zoom = Math.max(0.2f, Math.min(5.0f, zoom));
    }

    @Nullable
    public BoundingBox getBounds() {
        return bounds == null ? null : copy(bounds);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (bounds == null) {
            return;
        }

        // Background + border
        graphics.fill(getX(), getY(), getX() + width, getY() + height, BG_COLOR);
        drawBorder(graphics);

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        int spanX = bounds.getXSpan();
        int spanY = bounds.getYSpan();
        int spanZ = bounds.getZSpan();
        float maxSpan = Math.max(spanX, Math.max(spanY, spanZ));
        float scale = (Math.min(width, height) * 0.32f * zoom) / maxSpan;

        // Enable scissor to clip rendering to widget bounds
        graphics.enableScissor(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1);

        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        // Position at center of widget, deep enough in Z for depth buffer
        poseStack.translate(getX() + width / 2.0, getY() + height / 2.0, 500);

        // Apply user rotation
        poseStack.mulPose(new Quaternionf().rotateX((float) Math.toRadians(rotationX)));
        poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(rotationY)));

        // Scale and flip Y for screen coordinates
        poseStack.scale(scale, -scale, scale);

        // Center the model
        poseStack.translate(-spanX / 2.0, -spanY / 2.0, -spanZ / 2.0);

        // Setup lighting for 3D rendering
        Lighting.setupForEntityInInventory();
        RenderSystem.enableDepthTest();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        // ── Render blocks ─────────────────────────────────────────────────────
        for (int bx = bounds.minX(); bx <= bounds.maxX(); bx++) {
            for (int by = bounds.minY(); by <= bounds.maxY(); by++) {
                for (int bz = bounds.minZ(); bz <= bounds.maxZ(); bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (state.getRenderShape() == RenderShape.INVISIBLE) continue;

                    float lx = bx - bounds.minX();
                    float ly = by - bounds.minY();
                    float lz = bz - bounds.minZ();

                    poseStack.pushPose();
                    poseStack.translate(lx, ly, lz);

                    try {
                        blockRenderer.renderSingleBlock(state, poseStack, bufferSource, LIGHT, OverlayTexture.NO_OVERLAY);
                    } catch (Exception ignored) {
                        // Some blocks may fail to render in this context - skip them
                    }

                    poseStack.popPose();
                }
            }
        }

        // ── Render entities ───────────────────────────────────────────────────
        AABB aabb = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, aabb, e -> !(e instanceof Player));
        EntityRenderDispatcher entityRenderer = mc.getEntityRenderDispatcher();

        for (Entity entity : entities) {
            double ex = entity.getX() - bounds.minX();
            double ey = entity.getY() - bounds.minY();
            double ez = entity.getZ() - bounds.minZ();

            poseStack.pushPose();
            poseStack.translate(ex, ey, ez);

            try {
                entityRenderer.render(entity, 0, 0, 0, entity.getYRot(), partialTick,
                        poseStack, bufferSource, LIGHT);
            } catch (Exception ignored) {
                // Some entities may fail - skip
            }

            poseStack.popPose();
        }

        // Flush all buffered geometry
        bufferSource.endBatch();

        poseStack.popPose();

        graphics.disableScissor();
        Lighting.setupFor3DItems();
    }

    private void drawBorder(GuiGraphics g) {
        int x0 = getX(), y0 = getY(), x1 = getX() + width, y1 = getY() + height;
        g.fill(x0, y0, x1, y0 + 1, BORDER_COLOR);
        g.fill(x0, y1 - 1, x1, y1, BORDER_COLOR);
        g.fill(x0, y0, x0 + 1, y1, BORDER_COLOR);
        g.fill(x1 - 1, y0, x1, y1, BORDER_COLOR);
    }

    // ── Interaction ─────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseOver(mouseX, mouseY) && button == 0) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            rotationY += (float) (mouseX - lastMouseX) * 0.8f;
            rotationX += (float) (mouseY - lastMouseY) * 0.8f;
            rotationX = Math.max(-90, Math.min(90, rotationX));
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isMouseOver(mouseX, mouseY)) {
            zoom = Math.max(0.2f, Math.min(5.0f, zoom + (float) delta * 0.15f));
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("Workspace 3D Preview"));
    }

    @Nullable
    private static BoundingBox copy(@Nullable BoundingBox bounds) {
        if (bounds == null) {
            return null;
        }
        return new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}
