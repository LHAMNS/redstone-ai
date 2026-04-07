package com.redstoneai.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Interactive structure-block-style preview of the selected workspace volume.
 * The widget renders real block/entity content plus a wireframe range box and a
 * highlighted controller anchor, so empty regions remain readable.
 */
public class WorkspacePreviewWidget extends AbstractWidget {
    private static final int BG_COLOR = 0xEE0A0A18;
    private static final int BORDER_COLOR = 0xFF1A4A3A;
    private static final int INFO_BG = 0x66000000;
    private static final int TEXT_COLOR = 0xFF9ED9C6;
    private static final int SUBTEXT_COLOR = 0xFF7AA89A;
    private static final int CONTROLLER_TEXT_COLOR = 0xFFE7C565;
    private static final int LIGHT = LightTexture.pack(15, 15);
    private static final float CONTROLLER_MARKER_HALF = 0.25f;

    @Nullable
    private BoundingBox bounds;
    @Nullable
    private BlockPos controllerPos;
    private float rotationY = 225.0f;
    private float rotationX = 30.0f;
    private float zoom = 1.0f;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    public WorkspacePreviewWidget(int x, int y, int width, int height, BoundingBox bounds, BlockPos controllerPos) {
        super(x, y, width, height, Component.empty());
        setPreview(bounds, controllerPos);
    }

    public void setPreview(@Nullable BoundingBox bounds, @Nullable BlockPos controllerPos) {
        this.bounds = copy(bounds);
        this.controllerPos = controllerPos != null ? controllerPos.immutable() : null;
        this.visible = bounds != null;
    }

    public void setBounds(@Nullable BoundingBox bounds) {
        setPreview(bounds, controllerPos);
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
        this.rotationX = Math.max(-90.0f, Math.min(90.0f, rotationX));
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

        graphics.fill(getX(), getY(), getX() + width, getY() + height, BG_COLOR);
        drawBorder(graphics);

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            renderOverlayText(graphics, bounds.getXSpan(), bounds.getYSpan(), bounds.getZSpan());
            return;
        }

        int spanX = bounds.getXSpan();
        int spanY = bounds.getYSpan();
        int spanZ = bounds.getZSpan();

        float renderMinX = 0.0f;
        float renderMinY = 0.0f;
        float renderMinZ = 0.0f;
        float renderMaxX = spanX;
        float renderMaxY = spanY;
        float renderMaxZ = spanZ;

        boolean hasControllerMarker = controllerPos != null;
        float controllerMinX = 0.0f;
        float controllerMinY = 0.0f;
        float controllerMinZ = 0.0f;
        if (hasControllerMarker) {
            controllerMinX = controllerPos.getX() - bounds.minX();
            controllerMinY = controllerPos.getY() - bounds.minY();
            controllerMinZ = controllerPos.getZ() - bounds.minZ();
            renderMinX = Math.min(renderMinX, controllerMinX - 0.5f);
            renderMinY = Math.min(renderMinY, controllerMinY - 0.5f);
            renderMinZ = Math.min(renderMinZ, controllerMinZ - 0.5f);
            renderMaxX = Math.max(renderMaxX, controllerMinX + 1.5f);
            renderMaxY = Math.max(renderMaxY, controllerMinY + 1.5f);
            renderMaxZ = Math.max(renderMaxZ, controllerMinZ + 1.5f);
        }

        float maxSpan = Math.max(renderMaxX - renderMinX, Math.max(renderMaxY - renderMinY, renderMaxZ - renderMinZ));
        float scale = (Math.min(width, height) * 0.29f * zoom) / Math.max(1.0f, maxSpan);

        graphics.enableScissor(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1);

        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        poseStack.translate(getX() + width / 2.0, getY() + height / 2.0, 500.0);
        poseStack.mulPose(new Quaternionf().rotateX((float) Math.toRadians(rotationX)));
        poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(rotationY)));
        poseStack.scale(scale, -scale, scale);
        poseStack.translate(
                -(renderMinX + renderMaxX) / 2.0f,
                -(renderMinY + renderMaxY) / 2.0f,
                -(renderMinZ + renderMaxZ) / 2.0f
        );

        Lighting.setupForEntityInInventory();
        RenderSystem.enableDepthTest();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        for (int bx = bounds.minX(); bx <= bounds.maxX(); bx++) {
            for (int by = bounds.minY(); by <= bounds.maxY(); by++) {
                for (int bz = bounds.minZ(); bz <= bounds.maxZ(); bz++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) {
                        continue;
                    }

                    poseStack.pushPose();
                    poseStack.translate(bx - bounds.minX(), by - bounds.minY(), bz - bounds.minZ());
                    try {
                        blockRenderer.renderSingleBlock(state, poseStack, bufferSource, LIGHT, OverlayTexture.NO_OVERLAY);
                    } catch (Exception ignored) {
                        // Skip blocks that fail to render in the miniature context.
                    }
                    poseStack.popPose();
                }
            }
        }

        AABB aabb = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, aabb, entity -> !(entity instanceof Player));
        EntityRenderDispatcher entityRenderer = mc.getEntityRenderDispatcher();
        for (Entity entity : entities) {
            poseStack.pushPose();
            poseStack.translate(entity.getX() - bounds.minX(), entity.getY() - bounds.minY(), entity.getZ() - bounds.minZ());
            try {
                entityRenderer.render(entity, 0.0, 0.0, 0.0, entity.getYRot(), partialTick, poseStack, bufferSource, LIGHT);
            } catch (Exception ignored) {
                // Skip entities that fail to render in the miniature context.
            }
            poseStack.popPose();
        }

        if (hasControllerMarker) {
            BlockState controllerState = level.getBlockState(controllerPos);
            if (!controllerState.isAir() && controllerState.getRenderShape() != RenderShape.INVISIBLE) {
                poseStack.pushPose();
                poseStack.translate(controllerMinX, controllerMinY, controllerMinZ);
                try {
                    blockRenderer.renderSingleBlock(controllerState, poseStack, bufferSource, LIGHT, OverlayTexture.NO_OVERLAY);
                } catch (Exception ignored) {
                    // Keep preview rendering resilient even if the controller model fails.
                }
                poseStack.popPose();
            }
        }

        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        Matrix4f lineMatrix = poseStack.last().pose();
        Matrix3f normalMatrix = poseStack.last().normal();
        drawWireBox(lines, lineMatrix, normalMatrix, 0.0f, 0.0f, 0.0f, spanX, spanY, spanZ, 0.25f, 0.92f, 1.0f, 0.95f);

        if (hasControllerMarker) {
            float centerX = controllerMinX + 0.5f;
            float centerY = controllerMinY + 0.5f;
            float centerZ = controllerMinZ + 0.5f;
            drawWireBox(
                    lines,
                    lineMatrix,
                    normalMatrix,
                    centerX - CONTROLLER_MARKER_HALF,
                    centerY - CONTROLLER_MARKER_HALF,
                    centerZ - CONTROLLER_MARKER_HALF,
                    centerX + CONTROLLER_MARKER_HALF,
                    centerY + CONTROLLER_MARKER_HALF,
                    centerZ + CONTROLLER_MARKER_HALF,
                    1.0f, 0.82f, 0.25f, 1.0f
            );
            drawLine(
                    lines,
                    lineMatrix,
                    normalMatrix,
                    centerX,
                    centerY,
                    centerZ,
                    clamp(centerX, 0.0f, spanX),
                    clamp(centerY, 0.0f, spanY),
                    clamp(centerZ, 0.0f, spanZ),
                    1.0f, 0.72f, 0.2f, 0.85f
            );
        }

        bufferSource.endBatch();
        poseStack.popPose();

        graphics.disableScissor();
        Lighting.setupFor3DItems();
        renderOverlayText(graphics, spanX, spanY, spanZ);
    }

    private void drawBorder(GuiGraphics graphics) {
        int x0 = getX();
        int y0 = getY();
        int x1 = getX() + width;
        int y1 = getY() + height;
        graphics.fill(x0, y0, x1, y0 + 1, BORDER_COLOR);
        graphics.fill(x0, y1 - 1, x1, y1, BORDER_COLOR);
        graphics.fill(x0, y0, x0 + 1, y1, BORDER_COLOR);
        graphics.fill(x1 - 1, y0, x1, y1, BORDER_COLOR);
    }

    private void renderOverlayText(GuiGraphics graphics, int spanX, int spanY, int spanZ) {
        Minecraft mc = Minecraft.getInstance();
        int left = getX() + 6;
        int top = getY() + 6;
        graphics.fill(left - 3, top - 3, left + 108, top + 28, INFO_BG);
        graphics.drawString(mc.font, Component.translatable("gui.redstone_ai.preview.title"), left, top, TEXT_COLOR, false);
        graphics.drawString(mc.font, Component.translatable("gui.redstone_ai.preview.size", spanX, spanY, spanZ), left, top + 10, SUBTEXT_COLOR, false);

        int hintY = getY() + height - 18;
        if (controllerPos != null) {
            graphics.fill(left - 3, hintY - 13, left + 122, hintY + 18, INFO_BG);
            graphics.drawString(mc.font, Component.translatable("gui.redstone_ai.preview.controller"), left, hintY - 10, CONTROLLER_TEXT_COLOR, false);
        } else {
            graphics.fill(left - 3, hintY - 3, left + 122, hintY + 18, INFO_BG);
        }
        graphics.drawString(mc.font, Component.translatable("gui.redstone_ai.preview.controls"), left, hintY, SUBTEXT_COLOR, false);
    }

    private static void drawWireBox(VertexConsumer vc,
                                    Matrix4f matrix,
                                    Matrix3f normal,
                                    float minX,
                                    float minY,
                                    float minZ,
                                    float maxX,
                                    float maxY,
                                    float maxZ,
                                    float r,
                                    float g,
                                    float b,
                                    float a) {
        drawLine(vc, matrix, normal, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        drawLine(vc, matrix, normal, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        drawLine(vc, matrix, normal, minX, minY, maxZ, maxX, minY, maxZ, r, g, b, a);
        drawLine(vc, matrix, normal, minX, maxY, maxZ, maxX, maxY, maxZ, r, g, b, a);

        drawLine(vc, matrix, normal, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        drawLine(vc, matrix, normal, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        drawLine(vc, matrix, normal, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
        drawLine(vc, matrix, normal, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);

        drawLine(vc, matrix, normal, minX, minY, minZ, minX, minY, maxZ, r, g, b, a);
        drawLine(vc, matrix, normal, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        drawLine(vc, matrix, normal, minX, maxY, minZ, minX, maxY, maxZ, r, g, b, a);
        drawLine(vc, matrix, normal, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    private static void drawLine(VertexConsumer vc,
                                 Matrix4f matrix,
                                 Matrix3f normal,
                                 float x0,
                                 float y0,
                                 float z0,
                                 float x1,
                                 float y1,
                                 float z1,
                                 float r,
                                 float g,
                                 float b,
                                 float a) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0.0001f) {
            return;
        }

        float nx = dx / length;
        float ny = dy / length;
        float nz = dz / length;
        vc.vertex(matrix, x0, y0, z0).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
        vc.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normal, nx, ny, nz).endVertex();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

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
            rotationX = Math.max(-90.0f, Math.min(90.0f, rotationX));
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
        output.add(NarratedElementType.TITLE, Component.translatable("gui.redstone_ai.preview.title"));
    }

    @Nullable
    private static BoundingBox copy(@Nullable BoundingBox bounds) {
        if (bounds == null) {
            return null;
        }
        return new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}
