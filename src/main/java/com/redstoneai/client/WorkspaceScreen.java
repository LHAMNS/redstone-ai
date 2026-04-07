package com.redstoneai.client;

import com.redstoneai.network.RAINetwork;
import com.redstoneai.network.WorkspaceActionPacket;
import com.redstoneai.workspace.EntityFilterMode;
import com.redstoneai.workspace.ProtectionMode;
import com.redstoneai.workspace.WorkspaceControllerMenu;
import com.redstoneai.workspace.WorkspacePermission;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
    private EditBox authorizedPlayersField;
    private EditBox chatInput;
    private Button createButton;
    private Button revertButton;
    private Button sendButton;
    private Button freezeButton;
    private Button stepButton;
    private Button rewindButton;
    private Button ffButton;
    private Button selectRangeButton;
    private final List<Button> protectionModeButtons = new ArrayList<>();
    private final List<Button> entityFilterButtons = new ArrayList<>();
    private final List<Button> playerPermissionButtons = new ArrayList<>();
    private Button applyAuthorizedPlayersButton;
    private Button clearPlayerPermissionsButton;
    private Button allowCommandsButton;
    private Button allowFrozenTeleportButton;
    private Button allowFrozenDamageButton;
    private Button allowFrozenCollisionButton;
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
        this.imageHeight = 414;
    }

    @Override
    protected void init() {
        previewWidget = null;

        if (fullscreen) {
            this.imageWidth = this.width;
            this.imageHeight = this.height;
        } else {
            this.imageWidth = 440;
            this.imageHeight = 414;
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

        protectionModeButtons.clear();
        int modeY = tickBtnY + 18;
        int modeBtnX = x + 62;
        int modeBtnW = 52;
        int modeGap = 3;
        addModeButton(ProtectionMode.LOCKED, modeBtnX, modeY, modeBtnW);
        addModeButton(ProtectionMode.AI_ONLY, modeBtnX + (modeBtnW + modeGap), modeY, modeBtnW);
        addModeButton(ProtectionMode.PLAYER_ONLY, modeBtnX + (modeBtnW + modeGap) * 2, modeY, modeBtnW);
        addModeButton(ProtectionMode.COLLABORATIVE, modeBtnX + (modeBtnW + modeGap) * 3, modeY, 64);

        entityFilterButtons.clear();
        int filterY = modeY + 22;
        int filterBtnX = x + 62;
        addEntityFilterButton(EntityFilterMode.ALL_NON_PLAYER, filterBtnX, filterY, 70);
        addEntityFilterButton(EntityFilterMode.MECHANICAL_ONLY, filterBtnX + 73, filterY, 70);
        addEntityFilterButton(EntityFilterMode.NONE, filterBtnX + 146, filterY, 54);

        int playersY = filterY + 22;
        authorizedPlayersField = new EditBox(font, x + 62, playersY, 164, 16, Component.translatable("gui.redstone_ai.players.hint"));
        authorizedPlayersField.setMaxLength(256);
        authorizedPlayersField.setHint(Component.translatable("gui.redstone_ai.players.hint"));
        addRenderableWidget(authorizedPlayersField);

        applyAuthorizedPlayersButton = Button.builder(Component.translatable("gui.redstone_ai.btn.apply"),
                btn -> onApplyAuthorizedPlayers())
                .pos(x + 230, playersY - 1).size(54, 18).build();
        addRenderableWidget(applyAuthorizedPlayersButton);

        playerPermissionButtons.clear();
        int permissionY = playersY + 22;
        addPlayerPermissionButton(WorkspacePermission.BUILD, x + 62, permissionY, 50);
        addPlayerPermissionButton(WorkspacePermission.TIME_CONTROL, x + 115, permissionY, 50);
        addPlayerPermissionButton(WorkspacePermission.VIEW_HISTORY, x + 168, permissionY, 54);
        addPlayerPermissionButton(WorkspacePermission.CHAT, x + 225, permissionY, 38);
        addPlayerPermissionButton(WorkspacePermission.MANAGE_SETTINGS, x + 62, permissionY + 18, 82);

        clearPlayerPermissionsButton = Button.builder(Component.translatable("gui.redstone_ai.perm.clear"),
                btn -> onClearPlayerPermissions())
                .pos(x + 148, permissionY + 18).size(70, 16).build();
        addRenderableWidget(clearPlayerPermissionsButton);

        int securityY = permissionY + 40;
        allowCommandsButton = Button.builder(Component.empty(), btn -> onToggleAllowCommands())
                .pos(x + 62, securityY).size(110, 16).build();
        addRenderableWidget(allowCommandsButton);

        allowFrozenTeleportButton = Button.builder(Component.empty(), btn -> onToggleAllowFrozenTeleport())
                .pos(x + 176, securityY).size(108, 16).build();
        addRenderableWidget(allowFrozenTeleportButton);

        allowFrozenDamageButton = Button.builder(Component.empty(), btn -> onToggleAllowFrozenDamage())
                .pos(x + 62, securityY + 18).size(110, 16).build();
        addRenderableWidget(allowFrozenDamageButton);

        allowFrozenCollisionButton = Button.builder(Component.empty(), btn -> onToggleAllowFrozenCollision())
                .pos(x + 176, securityY + 18).size(108, 16).build();
        addRenderableWidget(allowFrozenCollisionButton);

        BoundingBox previewBounds = getPreviewBoundsForRender();
        if (previewBounds != null) {
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

        int logTop = topPos + 226;
        int chatTop = topPos + 324;
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

        g.drawString(font, Component.translatable("gui.redstone_ai.mode"), x, 82, LABEL, false);
        g.drawString(font, Component.translatable("gui.redstone_ai.entity_filter"), x, 104, LABEL, false);
        g.drawString(font, Component.translatable("gui.redstone_ai.players"), x, 126, LABEL, false);
        g.drawString(font, Component.translatable("gui.redstone_ai.permissions"), x, 148, LABEL, false);
        g.drawString(font, Component.translatable("gui.redstone_ai.security"), x, 190, LABEL, false);

        int logTop = 228;
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

        int chatTop = 326;
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

    private void onSetProtectionMode(ProtectionMode next) {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setProtectionMode(menu.getControllerPos(), next.getSerializedName()));
    }

    private void onSetEntityFilter(EntityFilterMode next) {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setEntityFilter(menu.getControllerPos(), next.getSerializedName()));
    }

    private void onApplyAuthorizedPlayers() {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setAuthorizedPlayers(menu.getControllerPos(), authorizedPlayersField.getValue().trim()));
    }

    private void onSetPlayerPermission(WorkspacePermission permission) {
        String playerName = authorizedPlayersField.getValue().trim();
        if (playerName.isEmpty()) {
            return;
        }
        boolean enabled = !menu.hasPlayerPermission(playerName, permission);
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setPlayerPermission(menu.getControllerPos(), playerName, permission, enabled));
    }

    private void onClearPlayerPermissions() {
        String playerName = authorizedPlayersField.getValue().trim();
        if (playerName.isEmpty()) {
            return;
        }
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.removePlayerPermissions(menu.getControllerPos(), playerName));
    }

    private void onToggleAllowCommands() {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setAllowCommands(menu.getControllerPos(), !menu.isAllowVanillaCommands()));
    }

    private void onToggleAllowFrozenTeleport() {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setAllowFrozenTeleport(menu.getControllerPos(), !menu.isAllowFrozenEntityTeleport()));
    }

    private void onToggleAllowFrozenDamage() {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setAllowFrozenDamage(menu.getControllerPos(), !menu.isAllowFrozenEntityDamage()));
    }

    private void onToggleAllowFrozenCollision() {
        RAINetwork.CHANNEL.sendToServer(
                WorkspaceActionPacket.setAllowFrozenCollision(menu.getControllerPos(), !menu.isAllowFrozenEntityCollision()));
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
        if (authorizedPlayersField != null && !authorizedPlayersField.isFocused()) {
            String players = menu.getAuthorizedPlayers();
            if (!players.equals(authorizedPlayersField.getValue())) {
                authorizedPlayersField.setValue(players);
            }
        }

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
        boolean settingsEditable = !hasWorkspace || !frozen;
        for (Button button : protectionModeButtons) {
            button.active = settingsEditable;
        }
        ProtectionMode currentProtectionMode = ProtectionMode.fromString(menu.getProtectionMode());
        for (Button button : protectionModeButtons) {
            Object mode = button.getMessage().getString();
            // no-op placeholder to keep buttons in sync via explicit helper below
        }
        syncModeButtons(currentProtectionMode, settingsEditable);

        EntityFilterMode currentEntityFilterMode = EntityFilterMode.fromString(menu.getEntityFilterMode());
        syncEntityFilterButtons(currentEntityFilterMode, settingsEditable);

        for (Button button : entityFilterButtons) {
            button.active = settingsEditable && button.active;
        }
        if (applyAuthorizedPlayersButton != null) {
            applyAuthorizedPlayersButton.active = settingsEditable;
        }
        String selectedPlayer = authorizedPlayersField != null ? authorizedPlayersField.getValue().trim() : "";
        boolean hasSelectedPlayer = !selectedPlayer.isEmpty();
        for (Button button : playerPermissionButtons) {
            button.active = settingsEditable && hasSelectedPlayer;
        }
        if (clearPlayerPermissionsButton != null) {
            clearPlayerPermissionsButton.active = settingsEditable && hasSelectedPlayer;
        }
        if (allowCommandsButton != null) {
            allowCommandsButton.setMessage(Component.translatable(
                    menu.isAllowVanillaCommands() ? "gui.redstone_ai.commands.allow" : "gui.redstone_ai.commands.block"));
            allowCommandsButton.active = settingsEditable;
        }
        if (allowFrozenTeleportButton != null) {
            allowFrozenTeleportButton.setMessage(Component.translatable(
                    menu.isAllowFrozenEntityTeleport() ? "gui.redstone_ai.freeze_tp.allow" : "gui.redstone_ai.freeze_tp.block"));
            allowFrozenTeleportButton.active = settingsEditable;
        }
        if (allowFrozenDamageButton != null) {
            allowFrozenDamageButton.setMessage(Component.translatable(
                    menu.isAllowFrozenEntityDamage() ? "gui.redstone_ai.freeze_damage.allow" : "gui.redstone_ai.freeze_damage.block"));
            allowFrozenDamageButton.active = settingsEditable;
        }
        if (allowFrozenCollisionButton != null) {
            allowFrozenCollisionButton.setMessage(Component.translatable(
                    menu.isAllowFrozenEntityCollision() ? "gui.redstone_ai.freeze_collision.allow" : "gui.redstone_ai.freeze_collision.block"));
            allowFrozenCollisionButton.active = settingsEditable;
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
        BoundingBox bounds = getPreviewBoundsForRender();
        if (bounds == null) {
            if (previewWidget != null) {
                previewWidget.setPreview(null, menu.getControllerPos());
            }
            return;
        }

        if (previewWidget == null) {
            previewWidget = createPreviewWidget();
            previewWidget.setView(savedRotY, savedRotX, savedZoom);
            addRenderableWidget(previewWidget);
            return;
        }

        previewWidget.setPreview(bounds, menu.getControllerPos());
    }

    @Nullable
    private BoundingBox getPreviewBoundsForRender() {
        int previewSizeX = sizeXField != null ? parseSize(sizeXField.getValue(), menu.getSizeX()) : menu.getSizeX();
        int previewSizeY = sizeYField != null ? parseSize(sizeYField.getValue(), menu.getSizeY()) : menu.getSizeY();
        int previewSizeZ = sizeZField != null ? parseSize(sizeZField.getValue(), menu.getSizeZ()) : menu.getSizeZ();

        BoundingBox liveBounds = menu.getWorkspaceBounds();
        if (liveBounds != null) {
            if (!WorkspaceRules.matchesDimensions(liveBounds, previewSizeX, previewSizeY, previewSizeZ)) {
                return WorkspaceRules.resizeBounds(liveBounds, menu.getControllerPos().getY(), previewSizeX, previewSizeY, previewSizeZ);
            }
            return liveBounds;
        }
        if (!menu.getWorkspaceName().isEmpty()) {
            return null;
        }
        return WorkspaceRules.createBoundsFromController(menu.getControllerPos(), previewSizeX, previewSizeY, previewSizeZ);
    }

    private WorkspacePreviewWidget createPreviewWidget() {
        BoundingBox bounds = getPreviewBoundsForRender();
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
        return new WorkspacePreviewWidget(previewX, previewY, previewW, previewH, bounds, menu.getControllerPos());
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
        if (menu.canViewHistory() && relY >= 226 && relY < 324) {
            logScrollOffset = Math.max(0, logScrollOffset + (delta > 0 ? 1 : -1));
            return true;
        } else if (menu.canViewHistory() && relY >= 324 && relY < imageHeight - 26) {
            chatScrollOffset = Math.max(0, chatScrollOffset + (delta > 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void addModeButton(ProtectionMode mode, int x, int y, int width) {
        Button button = Button.builder(Component.translatable("gui.redstone_ai.mode." + mode.getSerializedName()),
                        btn -> onSetProtectionMode(mode))
                .pos(x, y)
                .size(width, 16)
                .build();
        protectionModeButtons.add(button);
        addRenderableWidget(button);
    }

    private void addEntityFilterButton(EntityFilterMode mode, int x, int y, int width) {
        Button button = Button.builder(Component.translatable("gui.redstone_ai.entity_filter." + mode.getSerializedName()),
                        btn -> onSetEntityFilter(mode))
                .pos(x, y)
                .size(width, 16)
                .build();
        entityFilterButtons.add(button);
        addRenderableWidget(button);
    }

    private void addPlayerPermissionButton(WorkspacePermission permission, int x, int y, int width) {
        Button button = Button.builder(Component.translatable("gui.redstone_ai.perm." + permission.getSerializedName()),
                        btn -> onSetPlayerPermission(permission))
                .pos(x, y)
                .size(width, 16)
                .build();
        playerPermissionButtons.add(button);
        addRenderableWidget(button);
    }

    private void syncModeButtons(ProtectionMode currentMode, boolean settingsEditable) {
        for (int i = 0; i < protectionModeButtons.size(); i++) {
            ProtectionMode mode = ProtectionMode.values()[i];
            Button button = protectionModeButtons.get(i);
            button.active = settingsEditable && mode != currentMode;
        }
    }

    private void syncEntityFilterButtons(EntityFilterMode currentMode, boolean settingsEditable) {
        for (int i = 0; i < entityFilterButtons.size(); i++) {
            EntityFilterMode mode = EntityFilterMode.values()[i];
            Button button = entityFilterButtons.get(i);
            button.active = settingsEditable && mode != currentMode;
        }
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
