package com.redstoneai.client;

import com.redstoneai.network.RAINetwork;
import com.redstoneai.network.WorkspaceActionPacket;
import com.redstoneai.workspace.WorkspaceControllerMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Command-block-style GUI for the Workspace Controller.
 * <p>
 * Layout (top to bottom):
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │  Redstone Nexus                              │
 * │  Name: ws_name    Frozen: YES   VTick: 42    │
 * │  Size: [16] x [8] x [16]   [Create] [Revert] │
 * ├──────────────────────────────────────────────┤
 * │  Log / History                               │
 * │  [System] create: Workspace 'test'           │
 * │  [AI] build: placed 12 blocks                │
 * │  [Player] revert: Reverted 45 blocks         │
 * │  ...                                         │
 * ├──────────────────────────────────────────────┤
 * │  Chat with AI                                │
 * │  You: build an AND gate                      │
 * │  AI: Building AND gate with 2 inputs...      │
 * │  ...                                         │
 * │  [________________________] [Send]           │
 * └──────────────────────────────────────────────┘
 * </pre>
 */
public class WorkspaceScreen extends AbstractContainerScreen<WorkspaceControllerMenu> {

    private static final int BG = 0xDD1A1A2E;
    private static final int BORDER = 0xFF00CCA0;
    private static final int SECTION_LINE = 0xFF2A3A4A;
    private static final int TEXT = 0xFFCCCCCC;
    private static final int LABEL = 0xFF88CCAA;
    private static final int HIGHLIGHT = 0xFF55FFDD;
    private static final int FROZEN_COLOR = 0xFF55FFFF;
    private static final int LOG_BG = 0x44000000;

    private EditBox sizeXField;
    private EditBox sizeYField;
    private EditBox sizeZField;
    private EditBox chatInput;
    private Button createButton;
    private Button revertButton;
    private Button sendButton;
    private Button freezeButton;
    private Button stepButton;
    private Button rewindButton;
    private Button ffButton;
    private Button selectRangeButton;
    private Button fullscreenButton;
    private WorkspacePreviewWidget previewWidget;

    private int logScrollOffset = 0;
    private int chatScrollOffset = 0;
    private boolean fullscreen = false;

    // Saved rotation/zoom state so it persists across init() calls
    private float savedRotY = 225f, savedRotX = 30f, savedZoom = 1f;

    public WorkspaceScreen(WorkspaceControllerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 440;
        this.imageHeight = 280;
    }

    @Override
    protected void init() {
        previewWidget = null;

        if (fullscreen) {
            this.imageWidth = this.width;
            this.imageHeight = this.height;
        } else {
            this.imageWidth = 440;
            this.imageHeight = 280;
        }

        super.init();
        this.inventoryLabelY = this.imageHeight + 100;
        this.titleLabelX = -999;

        int x = leftPos + 8;
        int midY = topPos;

        int sizeFieldY = midY + 38;
        sizeXField = new EditBox(font, x + 40, sizeFieldY, 30, 14, Component.literal("X"));
        sizeXField.setMaxLength(3);
        sizeXField.setFilter(s -> s.matches("\\d{0,3}"));
        addRenderableWidget(sizeXField);

        sizeYField = new EditBox(font, x + 88, sizeFieldY, 30, 14, Component.literal("Y"));
        sizeYField.setMaxLength(3);
        sizeYField.setFilter(s -> s.matches("\\d{0,3}"));
        addRenderableWidget(sizeYField);

        sizeZField = new EditBox(font, x + 136, sizeFieldY, 30, 14, Component.literal("Z"));
        sizeZField.setMaxLength(3);
        sizeZField.setFilter(s -> s.matches("\\d{0,3}"));
        addRenderableWidget(sizeZField);

        boolean hasWorkspace = !menu.getWorkspaceName().isEmpty();
        createButton = Button.builder(
                Component.literal(hasWorkspace ? "Reconfigure" : "Create"),
                btn -> onCreate()
        ).pos(x + 180, sizeFieldY - 1).size(50, 16).build();
        addRenderableWidget(createButton);

        revertButton = Button.builder(
                Component.literal("Revert"),
                btn -> onRevert()
        ).pos(x + 234, sizeFieldY - 1).size(50, 16).build();
        addRenderableWidget(revertButton);

        int tickBtnY = sizeFieldY + 20;
        int tickBtnW = 38;

        rewindButton = Button.builder(Component.literal("<<"), btn -> onRewindTick())
                .pos(x, tickBtnY).size(tickBtnW, 16).build();
        addRenderableWidget(rewindButton);

        freezeButton = Button.builder(Component.literal(menu.isFrozen() ? "Play" : "Freeze"), btn -> onToggleFreeze())
                .pos(x + tickBtnW + 4, tickBtnY).size(50, 16).build();
        addRenderableWidget(freezeButton);

        stepButton = Button.builder(Component.literal("Step>"), btn -> onStepTick())
                .pos(x + tickBtnW + 58, tickBtnY).size(tickBtnW, 16).build();
        addRenderableWidget(stepButton);

        ffButton = Button.builder(Component.literal(">>"), btn -> onFastForward())
                .pos(x + tickBtnW * 2 + 62, tickBtnY).size(tickBtnW, 16).build();
        addRenderableWidget(ffButton);

        selectRangeButton = Button.builder(
                Component.translatable("gui.redstone_ai.btn.select_range"),
                btn -> onSelectRange()
        ).pos(x + tickBtnW * 2 + 104, tickBtnY).size(70, 16).build();
        addRenderableWidget(selectRangeButton);

        if (menu.getWorkspaceBounds() != null && Minecraft.getInstance().getSingleplayerServer() != null) {
            previewWidget = createPreviewWidget();
            previewWidget.setView(savedRotY, savedRotX, savedZoom);
            addRenderableWidget(previewWidget);
        }

        int fsBtnX = fullscreen ? leftPos + 300 - 58 : leftPos + imageWidth - 58;
        fullscreenButton = Button.builder(
                Component.literal(fullscreen ? "[-]" : "[+]"),
                btn -> toggleFullscreen()
        ).pos(fsBtnX, topPos + 4).size(24, 14).build();
        addRenderableWidget(fullscreenButton);

        int panelRight = leftPos + 296;
        int chatInputY = midY + imageHeight - 22;
        int chatW = panelRight - x - 48;
        chatInput = new EditBox(font, x, chatInputY, chatW, 16, Component.literal("Chat"));
        chatInput.setMaxLength(512);
        chatInput.setHint(Component.translatable("gui.redstone_ai.chat.hint"));
        addRenderableWidget(chatInput);

        sendButton = Button.builder(
                Component.translatable("gui.redstone_ai.btn.send"),
                btn -> onSendChat()
        ).pos(panelRight - 44, chatInputY - 1).size(40, 18).build();
        addRenderableWidget(sendButton);

        syncWidgetsToMenuState();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        syncWidgetsToMenuState();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int panelW = 300;

        g.fill(leftPos, topPos, leftPos + panelW, topPos + imageHeight, BG);
        g.fill(leftPos, topPos, leftPos + panelW, topPos + 2, BORDER);
        g.fill(leftPos, topPos + imageHeight - 2, leftPos + panelW, topPos + imageHeight, BORDER);
        g.fill(leftPos, topPos, leftPos + 2, topPos + imageHeight, BORDER);
        g.fill(leftPos + panelW - 2, topPos, leftPos + panelW, topPos + imageHeight, BORDER);

        int logTop = topPos + 78;
        int chatTop = topPos + 178;
        g.fill(leftPos + 4, logTop, leftPos + panelW - 4, logTop + 1, SECTION_LINE);
        g.fill(leftPos + 4, chatTop, leftPos + panelW - 4, chatTop + 1, SECTION_LINE);

        g.fill(leftPos + 4, logTop + 2, leftPos + panelW - 4, chatTop - 2, LOG_BG);
        g.fill(leftPos + 4, chatTop + 2, leftPos + panelW - 4, topPos + imageHeight - 26, LOG_BG);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int x = 8;
        int y = 6;

        g.drawString(font, Component.translatable("gui.redstone_ai.title"), x, y, HIGHLIGHT, false);
        y += 14;

        String wsName = menu.getWorkspaceName();
        if (!wsName.isEmpty()) {
            g.drawString(font, Component.translatable("gui.redstone_ai.name", wsName), x, y, LABEL, false);
            Component frozenComp = menu.isFrozen()
                    ? Component.translatable("gui.redstone_ai.frozen")
                    : Component.translatable("gui.redstone_ai.active");
            int frozenX = 140;
            g.drawString(font, frozenComp, frozenX, y, menu.isFrozen() ? FROZEN_COLOR : TEXT, false);
            g.drawString(font, Component.translatable("gui.redstone_ai.vtick", menu.getVirtualTick()),
                    frozenX + 50, y, TEXT, false);
            if (menu.getRecordingLength() > 0) {
                g.drawString(font, Component.translatable("gui.redstone_ai.recording",
                        menu.getRecordingLength(), menu.getRecordingLength()),
                        frozenX + 120, y, TEXT, false);
            }
        } else {
            g.drawString(font, Component.translatable("gui.redstone_ai.no_workspace"), x, y, 0xFFAAAA55, false);
        }
        y += 14;

        g.drawString(font, Component.translatable("gui.redstone_ai.size"), x, y + 4, LABEL, false);
        g.drawString(font, "x", x + 73, y + 4, TEXT, false);
        g.drawString(font, "x", x + 121, y + 4, TEXT, false);

        int logTop = 80;
        g.drawString(font, Component.translatable("gui.redstone_ai.section.log"), x, logTop + 4, LABEL, false);

        int logY = logTop + 16;
        int maxLogLines = 8;
        if (menu.canViewHistory()) {
            List<String> logs = menu.getLogLines();
            int start = Math.max(0, logs.size() - maxLogLines - logScrollOffset);
            int end = Math.min(logs.size(), start + maxLogLines);
            for (int i = start; i < end; i++) {
                String line = logs.get(i);
                if (line.length() > 50) line = line.substring(0, 50) + "...";
                g.drawString(font, line, x + 2, logY, TEXT, false);
                logY += 10;
            }
        } else {
            g.drawString(font, Component.literal("History unavailable"), x + 2, logY, 0xFF888888, false);
        }

        int chatTop = 180;
        g.drawString(font, Component.translatable("gui.redstone_ai.section.chat"), x, chatTop + 4, LABEL, false);

        int chatY = chatTop + 16;
        int maxChatLines = 5;
        if (menu.canViewHistory()) {
            List<String> chats = menu.getChatLines();
            int cStart = Math.max(0, chats.size() - maxChatLines - chatScrollOffset);
            int cEnd = Math.min(chats.size(), cStart + maxChatLines);
            for (int i = cStart; i < cEnd; i++) {
                String line = chats.get(i);
                if (line.length() > 55) line = line.substring(0, 55) + "...";
                int color = line.startsWith("You:") ? 0xFFAAFFAA : 0xFF99DDFF;
                g.drawString(font, line, x + 2, chatY, color, false);
                chatY += 10;
            }
        } else {
            g.drawString(font, Component.literal("History unavailable"), x + 2, chatY, 0xFF888888, false);
        }
    }

    private void onCreate() {
        int sx = parseSize(sizeXField.getValue(), 16);
        int sy = parseSize(sizeYField.getValue(), 8);
        int sz = parseSize(sizeZField.getValue(), 16);

        boolean hasWorkspace = !menu.getWorkspaceName().isEmpty();
        RAINetwork.CHANNEL.sendToServer(hasWorkspace
                ? WorkspaceActionPacket.updateSize(menu.getControllerPos(), sx, sy, sz)
                : WorkspaceActionPacket.create(menu.getControllerPos(), sx, sy, sz));
    }

    private void onRevert() {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.revert(menu.getControllerPos()));
    }

    private void onToggleFreeze() {
        if (menu.isFrozen()) {
            RAINetwork.CHANNEL.sendToServer(WorkspaceActionPacket.unfreeze(menu.getControllerPos()));
        } else {
            RAINetwork.CHANNEL.sendToServer(WorkspaceActionPacket.freeze(menu.getControllerPos()));
        }
    }

    private void onStepTick() {
        RAINetwork.CHANNEL.sendToServer(WorkspaceActionPacket.step(menu.getControllerPos(), 1));
    }

    private void onRewindTick() {
        RAINetwork.CHANNEL.sendToServer(WorkspaceActionPacket.rewind(menu.getControllerPos(), 1));
    }

    private void onFastForward() {
        RAINetwork.CHANNEL.sendToServer(WorkspaceActionPacket.fastForward(menu.getControllerPos(), 10));
    }

    private void toggleFullscreen() {
        if (previewWidget != null) {
            savedRotY = previewWidget.getRotationY();
            savedRotX = previewWidget.getRotationX();
            savedZoom = previewWidget.getZoom();
        }
        fullscreen = !fullscreen;
        rebuildWidgets();
    }

    private void onSelectRange() {
        RAINetwork.CHANNEL.sendToServer(WorkspaceActionPacket.selectRange(menu.getControllerPos()));
    }

    private void onSendChat() {
        String msg = chatInput.getValue().trim();
        if (!msg.isEmpty()) {
            RAINetwork.CHANNEL.sendToServer(
                    WorkspaceActionPacket.sendChat(menu.getControllerPos(), msg));
            chatInput.setValue("");
        }
    }

    private void syncWidgetsToMenuState() {
        updateSizeField(sizeXField, menu.getSizeX());
        updateSizeField(sizeYField, menu.getSizeY());
        updateSizeField(sizeZField, menu.getSizeZ());

        boolean hasWorkspace = !menu.getWorkspaceName().isEmpty();
        if (createButton != null) {
            createButton.setMessage(Component.literal(hasWorkspace ? "Reconfigure" : "Create"));
        }
        if (revertButton != null) {
            revertButton.active = hasWorkspace && menu.hasSnapshot();
        }
        if (freezeButton != null) {
            freezeButton.setMessage(Component.literal(menu.isFrozen() ? "Play" : "Freeze"));
            freezeButton.active = hasWorkspace;
        }
        boolean frozen = menu.isFrozen();
        if (rewindButton != null) {
            rewindButton.active = hasWorkspace && frozen;
        }
        if (stepButton != null) {
            stepButton.active = hasWorkspace && frozen;
        }
        if (ffButton != null) {
            ffButton.active = hasWorkspace && frozen;
        }
        if (selectRangeButton != null) {
            selectRangeButton.active = true;
        }
        if (sendButton != null) {
            sendButton.active = !chatInput.getValue().trim().isEmpty();
        }

        syncPreviewWidget();
    }

    private void updateSizeField(EditBox field, int value) {
        if (field == null || field.isFocused()) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.equals(field.getValue())) {
            field.setValue(text);
        }
    }

    private void syncPreviewWidget() {
        boolean singleplayerPreview = Minecraft.getInstance().getSingleplayerServer() != null;
        BoundingBox bounds = menu.getWorkspaceBounds();
        if (!singleplayerPreview || bounds == null) {
            if (previewWidget != null) {
                previewWidget.setBounds(null);
            }
            return;
        }

        if (previewWidget == null) {
            previewWidget = createPreviewWidget();
            previewWidget.setView(savedRotY, savedRotX, savedZoom);
            addRenderableWidget(previewWidget);
            return;
        }

        previewWidget.setBounds(bounds);
    }

    private WorkspacePreviewWidget createPreviewWidget() {
        BoundingBox bounds = menu.getWorkspaceBounds();
        if (bounds == null) {
            throw new IllegalStateException("Preview widget requested without workspace bounds");
        }

        int previewX;
        int previewY;
        int previewW;
        int previewH;
        if (fullscreen) {
            int panelW = 300;
            previewX = leftPos + panelW + 2;
            previewY = topPos + 2;
            previewW = imageWidth - panelW - 4;
            previewH = imageHeight - 4;
        } else {
            previewX = leftPos + 302;
            previewY = topPos + 4;
            previewW = 134;
            previewH = imageHeight - 8;
        }
        return new WorkspacePreviewWidget(previewX, previewY, previewW, previewH, bounds);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && chatInput.isFocused()) {
            onSendChat();
            return true;
        }
        if (sizeXField.isFocused() || sizeYField.isFocused() || sizeZField.isFocused() || chatInput.isFocused()) {
            if (keyCode == 256) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            return sizeXField.keyPressed(keyCode, scanCode, modifiers)
                    || sizeYField.keyPressed(keyCode, scanCode, modifiers)
                    || sizeZField.keyPressed(keyCode, scanCode, modifiers)
                    || chatInput.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int relY = (int) mouseY - topPos;
        if (menu.canViewHistory() && relY >= 78 && relY < 178) {
            logScrollOffset = Math.max(0, logScrollOffset + (delta > 0 ? 1 : -1));
            return true;
        } else if (menu.canViewHistory() && relY >= 178 && relY < imageHeight - 26) {
            chatScrollOffset = Math.max(0, chatScrollOffset + (delta > 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int parseSize(String s, int fallback) {
        try {
            int val = Integer.parseInt(s);
            return Math.max(4, Math.min(32, val));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
