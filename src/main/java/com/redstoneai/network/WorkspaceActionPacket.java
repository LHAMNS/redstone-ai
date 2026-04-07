package com.redstoneai.network;

import com.redstoneai.config.RAIConfig;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.*;
import com.redstoneai.workspace.SelectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client->Server packet for workspace GUI actions.
 */
public class WorkspaceActionPacket {

    public enum Action {
        UPDATE_SIZE,
        REVERT,
        SEND_CHAT,
        CREATE,
        FREEZE,
        UNFREEZE,
        STEP,
        REWIND,
        FAST_FORWARD,
        SELECT_RANGE,
        SET_PROTECTION_MODE,
        SET_ENTITY_FILTER,
        SET_AUTHORIZED_PLAYERS,
        SET_PLAYER_PERMISSION,
        REMOVE_PLAYER_PERMISSIONS,
        SET_ALLOW_COMMANDS,
        SET_ALLOW_FROZEN_TELEPORT,
        SET_ALLOW_FROZEN_DAMAGE,
        SET_ALLOW_FROZEN_COLLISION
    }

    private final BlockPos controllerPos;
    private final Action action;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String chatMessage;

    public WorkspaceActionPacket(BlockPos pos, Action action, int sizeX, int sizeY, int sizeZ, String chatMessage) {
        this.controllerPos = pos;
        this.action = action;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.chatMessage = chatMessage;
    }

    public static WorkspaceActionPacket updateSize(BlockPos pos, int x, int y, int z) {
        return new WorkspaceActionPacket(pos, Action.UPDATE_SIZE, x, y, z, "");
    }

    public static WorkspaceActionPacket revert(BlockPos pos) {
        return new WorkspaceActionPacket(pos, Action.REVERT, 0, 0, 0, "");
    }

    public static WorkspaceActionPacket sendChat(BlockPos pos, String message) {
        return new WorkspaceActionPacket(pos, Action.SEND_CHAT, 0, 0, 0, message);
    }

    public static WorkspaceActionPacket create(BlockPos pos, int x, int y, int z) {
        return new WorkspaceActionPacket(pos, Action.CREATE, x, y, z, "");
    }

    public static WorkspaceActionPacket freeze(BlockPos pos) {
        return new WorkspaceActionPacket(pos, Action.FREEZE, 0, 0, 0, "");
    }

    public static WorkspaceActionPacket unfreeze(BlockPos pos) {
        return new WorkspaceActionPacket(pos, Action.UNFREEZE, 0, 0, 0, "");
    }

    public static WorkspaceActionPacket step(BlockPos pos, int count) {
        return new WorkspaceActionPacket(pos, Action.STEP, count, 0, 0, "");
    }

    public static WorkspaceActionPacket rewind(BlockPos pos, int count) {
        return new WorkspaceActionPacket(pos, Action.REWIND, count, 0, 0, "");
    }

    public static WorkspaceActionPacket fastForward(BlockPos pos, int count) {
        return new WorkspaceActionPacket(pos, Action.FAST_FORWARD, count, 0, 0, "");
    }

    public static WorkspaceActionPacket selectRange(BlockPos pos) {
        return new WorkspaceActionPacket(pos, Action.SELECT_RANGE, 0, 0, 0, "");
    }

    public static WorkspaceActionPacket setProtectionMode(BlockPos pos, String protectionMode) {
        return new WorkspaceActionPacket(pos, Action.SET_PROTECTION_MODE, 0, 0, 0, protectionMode);
    }

    public static WorkspaceActionPacket setEntityFilter(BlockPos pos, String entityFilterMode) {
        return new WorkspaceActionPacket(pos, Action.SET_ENTITY_FILTER, 0, 0, 0, entityFilterMode);
    }

    public static WorkspaceActionPacket setAuthorizedPlayers(BlockPos pos, String authorizedPlayers) {
        return new WorkspaceActionPacket(pos, Action.SET_AUTHORIZED_PLAYERS, 0, 0, 0, authorizedPlayers);
    }

    public static WorkspaceActionPacket setPlayerPermission(BlockPos pos, String playerName, WorkspacePermission permission, boolean enabled) {
        return new WorkspaceActionPacket(pos, Action.SET_PLAYER_PERMISSION, enabled ? 1 : 0, 0, 0,
                WorkspaceAccessControl.normalizePlayerName(playerName) + "|" + permission.getSerializedName());
    }

    public static WorkspaceActionPacket removePlayerPermissions(BlockPos pos, String playerName) {
        return new WorkspaceActionPacket(pos, Action.REMOVE_PLAYER_PERMISSIONS, 0, 0, 0,
                WorkspaceAccessControl.normalizePlayerName(playerName));
    }

    public static WorkspaceActionPacket setAllowCommands(BlockPos pos, boolean enabled) {
        return new WorkspaceActionPacket(pos, Action.SET_ALLOW_COMMANDS, enabled ? 1 : 0, 0, 0, "");
    }

    public static WorkspaceActionPacket setAllowFrozenTeleport(BlockPos pos, boolean enabled) {
        return new WorkspaceActionPacket(pos, Action.SET_ALLOW_FROZEN_TELEPORT, enabled ? 1 : 0, 0, 0, "");
    }

    public static WorkspaceActionPacket setAllowFrozenDamage(BlockPos pos, boolean enabled) {
        return new WorkspaceActionPacket(pos, Action.SET_ALLOW_FROZEN_DAMAGE, enabled ? 1 : 0, 0, 0, "");
    }

    public static WorkspaceActionPacket setAllowFrozenCollision(BlockPos pos, boolean enabled) {
        return new WorkspaceActionPacket(pos, Action.SET_ALLOW_FROZEN_COLLISION, enabled ? 1 : 0, 0, 0, "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controllerPos);
        buf.writeEnum(action);
        buf.writeInt(sizeX);
        buf.writeInt(sizeY);
        buf.writeInt(sizeZ);
        buf.writeUtf(chatMessage, 1024);
    }

    public static WorkspaceActionPacket decode(FriendlyByteBuf buf) {
        return new WorkspaceActionPacket(
                buf.readBlockPos(),
                buf.readEnum(Action.class),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(1024)
        );
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }

            ServerLevel level = player.serverLevel();
            WorkspaceControllerBlockEntity controller = getAuthorizedController(player, level);
            if (controller == null) {
                return;
            }

            switch (action) {
                case UPDATE_SIZE -> handleUpdateSize(player, level, controller);
                case CREATE -> handleCreate(player, level, controller);
                case REVERT -> handleRevert(player, level, controller);
                case SEND_CHAT -> handleSendChat(player, level, controller);
                case FREEZE -> handleFreeze(player, level, controller);
                case UNFREEZE -> handleUnfreeze(player, level, controller);
                case STEP -> handleStep(player, level, controller);
                case REWIND -> handleRewind(player, level, controller);
                case FAST_FORWARD -> handleFastForward(player, level, controller);
                case SELECT_RANGE -> handleSelectRange(player, level, controller);
                case SET_PROTECTION_MODE -> handleSetProtectionMode(player, level, controller);
                case SET_ENTITY_FILTER -> handleSetEntityFilter(player, level, controller);
                case SET_AUTHORIZED_PLAYERS -> handleSetAuthorizedPlayers(player, level, controller);
                case SET_PLAYER_PERMISSION -> handleSetPlayerPermission(player, level, controller);
                case REMOVE_PLAYER_PERMISSIONS -> handleRemovePlayerPermissions(player, level, controller);
                case SET_ALLOW_COMMANDS -> handleSetAllowCommands(player, level, controller);
                case SET_ALLOW_FROZEN_TELEPORT -> handleSetAllowFrozenTeleport(player, level, controller);
                case SET_ALLOW_FROZEN_DAMAGE -> handleSetAllowFrozenDamage(player, level, controller);
                case SET_ALLOW_FROZEN_COLLISION -> handleSetAllowFrozenCollision(player, level, controller);
            }
        });
        ctx.setPacketHandled(true);
    }

    private void handleUpdateSize(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        String error = WorkspaceRules.validateDimensions(sizeX, sizeY, sizeZ);
        if (error != null) {
            return;
        }

        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controller.setSize(sizeX, sizeY, sizeZ);
            controller.setInitialSnapshot(null);
            controller.getOperationLog().logPlayer("config", "Pending size set to " + sizeX + "x" + sizeY + "x" + sizeZ);
            return;
        }

        if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace) || workspace.isFrozen()) {
            return;
        }

        WorkspaceManager manager = WorkspaceManager.get(level);
        BoundingBox newBounds = WorkspaceRules.resizeBounds(workspace.getBounds(), controllerPos.getY(), sizeX, sizeY, sizeZ);
        if (manager.checkOverlap(newBounds, workspace.getId()) != null) {
            return;
        }

        controller.setSize(sizeX, sizeY, sizeZ);
        manager.updateWorkspaceGeometry(workspace, newBounds, null, WorkspaceRules.originFromBounds(newBounds));
        controller.setInitialSnapshot(InitialSnapshot.capture(level, newBounds));
        workspace.retainIOMarkers(marker -> workspace.contains(marker.pos()));
        TickController.invalidateRecording(level, workspace);
        controller.getOperationLog().logPlayer("config", "Workspace resized to " + sizeX + "x" + sizeY + "x" + sizeZ);
    }

    private void handleCreate(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        String sizeError = WorkspaceRules.validateDimensions(sizeX, sizeY, sizeZ);
        if (sizeError != null) {
            return;
        }

        if (!controller.getWorkspaceName().isEmpty()) {
            return;
        }

        WorkspaceManager manager = WorkspaceManager.get(level);
        String name = "ws_" + controllerPos.toShortString().replace(", ", "_");
        String nameError = WorkspaceRules.validateWorkspaceName(name);
        if (nameError != null || manager.hasWorkspace(name)) {
            return;
        }
        if (manager.getWorkspacesOwnedBy(player.getUUID()).size() >= RAIConfig.SERVER.maxWorkspacesPerPlayer.get()) {
            return;
        }

        BoundingBox bounds = resolveInitialBounds(controller, sizeX, sizeY, sizeZ);
        if (manager.checkOverlap(bounds) != null) {
            return;
        }

        Workspace workspace = new Workspace(
                UUID.randomUUID(),
                player.getUUID(),
                name,
                controllerPos,
                WorkspaceRules.originFromBounds(bounds),
                bounds
        );
        manager.addWorkspace(workspace);
        controller.setWorkspaceName(name);
        controller.setSize(sizeX, sizeY, sizeZ);
        workspace.setProtectionMode(controller.getProtectionMode());
        workspace.setEntityFilterMode(controller.getEntityFilterMode());
        workspace.replaceAuthorizedPlayers(controller.getAuthorizedPlayers());
        workspace.replacePlayerPermissionGrants(controller.getPlayerPermissionGrants());
        workspace.setAllowVanillaCommands(controller.isAllowVanillaCommands());
        workspace.setAllowFrozenEntityTeleport(controller.isAllowFrozenEntityTeleport());
        workspace.setAllowFrozenEntityDamage(controller.isAllowFrozenEntityDamage());
        workspace.setAllowFrozenEntityCollision(controller.isAllowFrozenEntityCollision());
        controller.setInitialSnapshot(InitialSnapshot.capture(level, bounds));
        controller.getOperationLog().logSystem("create", "Workspace '" + name + "' created");
    }

    private void handleRevert(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null || !WorkspaceAccessControl.canPlayerManageSettings(player, workspace)) {
            return;
        }

        boolean wasFrozen = workspace.isFrozen();

        InitialSnapshot snapshot = controller.getInitialSnapshot();
        if (snapshot == null) {
            return;
        }

        BoundingBox previousBounds = workspace.getBounds();
        BlockPos previousOrigin = workspace.getOriginPos();
        int changed = snapshot.restore(level);
        BlockPos restoredControllerPos = snapshot.getControllerPos() != null ? snapshot.getControllerPos() : controllerPos;
        WorkspaceControllerBlockEntity restoredController = level.getBlockEntity(restoredControllerPos) instanceof WorkspaceControllerBlockEntity be
                ? be
                : controller;

        if (wasFrozen) {
            TickController.discardFrozenState(level, workspace, previousBounds, previousOrigin);
        } else {
            TickController.removeQueue(workspace.getId());
        }

        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.updateWorkspaceGeometry(
                workspace,
                snapshot.getBounds(),
                restoredControllerPos,
                WorkspaceRules.originFromBounds(snapshot.getBounds())
        );
        workspace.setTimeline(null);
        workspace.setVirtualTick(0);
        workspace.setProtectionMode(restoredController.getProtectionMode());
        workspace.setEntityFilterMode(restoredController.getEntityFilterMode());
        workspace.replaceAuthorizedPlayers(restoredController.getAuthorizedPlayers());
        workspace.replacePlayerPermissionGrants(restoredController.getPlayerPermissionGrants());
        workspace.setAllowVanillaCommands(restoredController.isAllowVanillaCommands());
        workspace.setAllowFrozenEntityTeleport(restoredController.isAllowFrozenEntityTeleport());
        workspace.setAllowFrozenEntityDamage(restoredController.isAllowFrozenEntityDamage());
        workspace.setAllowFrozenEntityCollision(restoredController.isAllowFrozenEntityCollision());
        restoredController.setInitialSnapshot(snapshot);
        restoredController.getOperationLog().logPlayer("revert", "Reverted " + changed + " blocks");
    }

    private void handleSendChat(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && !WorkspaceAccessControl.canPlayerChat(player, workspace)) {
            return;
        }
        if (workspace == null && !controller.getWorkspaceName().isEmpty()) {
            return;
        }
        if (workspace == null && !player.hasPermissions(2)) {
            return;
        }
        if (!chatMessage.isEmpty()) {
            controller.addChatMessage("player", chatMessage);
            controller.getOperationLog().logPlayer("chat",
                    chatMessage.length() > 50 ? chatMessage.substring(0, 50) + "..." : chatMessage);
        }
    }

    private void handleFreeze(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && WorkspaceAccessControl.canPlayerUseTimeControls(player, workspace) && !workspace.isFrozen()) {
            TickController.freeze(level, workspace);
            controller.getOperationLog().logPlayer("freeze", "");
        }
    }

    private void handleUnfreeze(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && WorkspaceAccessControl.canPlayerUseTimeControls(player, workspace) && workspace.isFrozen()) {
            TickController.unfreeze(level, workspace);
            controller.getOperationLog().logPlayer("unfreeze", "");
        }
    }

    private void handleStep(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && WorkspaceAccessControl.canPlayerUseTimeControls(player, workspace) && workspace.isFrozen()) {
            int count = Math.max(1, Math.min(sizeX, RAIConfig.SERVER.maxStepsPerCall.get()));
            int stepped = TickController.step(level, workspace, count);
            controller.getOperationLog().logPlayer("step", stepped + " tick(s)");
        }
    }

    private void handleRewind(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null || !WorkspaceAccessControl.canPlayerUseTimeControls(player, workspace) || !workspace.isFrozen()) {
            return;
        }
        if (workspace.getTimeline() == null) {
            return;
        }

        int count = Math.max(1, Math.min(sizeX, RAIConfig.SERVER.maxStepsPerCall.get()));
        if (workspace.getTimeline().canRewind(count)) {
            int rewound = TickController.rewind(level, workspace, count);
            controller.getOperationLog().logPlayer("rewind", rewound + " tick(s)");
        }
    }

    private void handleFastForward(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null || !WorkspaceAccessControl.canPlayerUseTimeControls(player, workspace) || !workspace.isFrozen()) {
            return;
        }

        int count = Math.max(1, Math.min(sizeX, RAIConfig.SERVER.maxStepsPerCall.get()));
        int advanced = TickController.replayThenStep(level, workspace, count);
        controller.getOperationLog().logPlayer("ff", advanced + " tick(s)");
    }

    private void handleSelectRange(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (!controller.getWorkspaceName().isEmpty()) {
            if (workspace == null || workspace.isFrozen() || !WorkspaceRules.canPlayerManage(player, workspace)) {
                return;
            }
            if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace)) {
                return;
            }
        }
        SelectionManager.beginSelection(player, controllerPos, controller.getWorkspaceName());
    }

    private void handleSetProtectionMode(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        ProtectionMode mode = ProtectionMode.tryParse(chatMessage);
        if (mode == null) {
            return;
        }

        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controller.setProtectionMode(mode);
            controller.getOperationLog().logPlayer("config", "Pending protection mode set to " + mode.getSerializedName());
            return;
        }

        if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace) || workspace.isFrozen()) {
            return;
        }

        workspace.setProtectionMode(mode);
        controller.setProtectionMode(mode);
        WorkspaceManager.get(level).setDirty();
        controller.getOperationLog().logPlayer("config", "Protection mode set to " + mode.getSerializedName());
    }

    private void handleSetEntityFilter(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        EntityFilterMode mode = EntityFilterMode.fromString(chatMessage);
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controller.setEntityFilterMode(mode);
            controller.getOperationLog().logPlayer("config", "Pending entity filter set to " + mode.getSerializedName());
            return;
        }

        if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace) || workspace.isFrozen()) {
            return;
        }

        workspace.setEntityFilterMode(mode);
        controller.setEntityFilterMode(mode);
        WorkspaceManager.get(level).setDirty();
        TickController.invalidateRecording(level, workspace);
        controller.getOperationLog().logPlayer("config", "Entity filter set to " + mode.getSerializedName());
    }

    private void handleSetAuthorizedPlayers(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        List<String> players = WorkspaceAccessControl.parseAuthorizedPlayers(chatMessage);
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controller.replaceAuthorizedPlayers(players);
            controller.getOperationLog().logPlayer("config", "Pending authorized players updated");
            return;
        }

        if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace) || workspace.isFrozen()) {
            return;
        }
        workspace.replaceAuthorizedPlayers(players);
        controller.replaceAuthorizedPlayers(players);
        WorkspaceManager.get(level).setDirty();
        controller.getOperationLog().logPlayer("config", "Authorized players updated");
    }

    private void handleSetPlayerPermission(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        String[] split = chatMessage.split("\\|", 2);
        if (split.length != 2) {
            return;
        }
        WorkspacePermission permission = WorkspacePermission.tryParse(split[1]);
        if (permission == null) {
            return;
        }
        boolean enabled = sizeX > 0;
        String playerName = WorkspaceAccessControl.normalizePlayerName(split[0]);
        if (playerName.isBlank()) {
            return;
        }

        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controller.setPlayerPermission(playerName, permission, enabled);
            controller.getOperationLog().logPlayer("config", "Pending permission " + permission.getSerializedName() + " for " + playerName + " = " + enabled);
            return;
        }
        if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace) || workspace.isFrozen()) {
            return;
        }
        controller.setPlayerPermission(playerName, permission, enabled);
        workspace.setPlayerPermission(playerName, permission, enabled);
        WorkspaceManager.get(level).setDirty();
        controller.getOperationLog().logPlayer("config", "Permission " + permission.getSerializedName() + " for " + playerName + " = " + enabled);
    }

    private void handleRemovePlayerPermissions(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        String playerName = WorkspaceAccessControl.normalizePlayerName(chatMessage);
        if (playerName.isBlank()) {
            return;
        }
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controller.removePlayerPermissions(playerName);
            controller.getOperationLog().logPlayer("config", "Pending permissions removed for " + playerName);
            return;
        }
        if (!WorkspaceAccessControl.canPlayerManageSettings(player, workspace) || workspace.isFrozen()) {
            return;
        }
        controller.removePlayerPermissions(playerName);
        workspace.removePlayerPermissions(playerName);
        WorkspaceManager.get(level).setDirty();
        controller.getOperationLog().logPlayer("config", "Permissions removed for " + playerName);
    }

    private void handleSetAllowCommands(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        handleBooleanSetting(player, level, controller, sizeX > 0,
                WorkspaceControllerBlockEntity::setAllowVanillaCommands,
                Workspace::setAllowVanillaCommands,
                "Vanilla commands");
    }

    private void handleSetAllowFrozenTeleport(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        handleBooleanSetting(player, level, controller, sizeX > 0,
                WorkspaceControllerBlockEntity::setAllowFrozenEntityTeleport,
                Workspace::setAllowFrozenEntityTeleport,
                "Frozen entity teleport");
    }

    private void handleSetAllowFrozenDamage(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        handleBooleanSetting(player, level, controller, sizeX > 0,
                WorkspaceControllerBlockEntity::setAllowFrozenEntityDamage,
                Workspace::setAllowFrozenEntityDamage,
                "Frozen entity damage");
    }

    private void handleSetAllowFrozenCollision(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        handleBooleanSetting(player, level, controller, sizeX > 0,
                WorkspaceControllerBlockEntity::setAllowFrozenEntityCollision,
                Workspace::setAllowFrozenEntityCollision,
                "Frozen entity collision");
    }

    private void handleBooleanSetting(ServerPlayer player,
                                      ServerLevel level,
                                      WorkspaceControllerBlockEntity controller,
                                      boolean enabled,
                                      java.util.function.BiConsumer<WorkspaceControllerBlockEntity, Boolean> controllerSetter,
                                      java.util.function.BiConsumer<Workspace, Boolean> workspaceSetter,
                                      String label) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null) {
            if (!controller.getWorkspaceName().isEmpty()) {
                return;
            }
            controllerSetter.accept(controller, enabled);
            controller.getOperationLog().logPlayer("config", "Pending " + label + " set to " + enabled);
            return;
        }

        if (!WorkspaceRules.canPlayerManage(player, workspace) || workspace.isFrozen()) {
            return;
        }

        controllerSetter.accept(controller, enabled);
        workspaceSetter.accept(workspace, enabled);
        WorkspaceManager.get(level).setDirty();
        controller.getOperationLog().logPlayer("config", label + " set to " + enabled);
    }

    private WorkspaceControllerBlockEntity getAuthorizedController(ServerPlayer player, ServerLevel level) {
        if (!(level.getBlockEntity(controllerPos) instanceof WorkspaceControllerBlockEntity controller)) {
            return null;
        }
        if (!(player.containerMenu instanceof WorkspaceControllerMenu menu)) {
            return null;
        }
        if (!menu.getControllerPos().equals(controllerPos)) {
            return null;
        }
        if (player.distanceToSqr(controllerPos.getX() + 0.5D, controllerPos.getY() + 0.5D, controllerPos.getZ() + 0.5D) > 64.0D) {
            return null;
        }
        if (controller.getWorkspaceName().isEmpty() && !player.hasPermissions(2)) {
            if (controller.getPlacerUUID() == null || !controller.getPlacerUUID().equals(player.getUUID())) {
                return null;
            }
        }
        return controller;
    }

    private Workspace getBoundWorkspace(ServerLevel level, WorkspaceControllerBlockEntity controller) {
        String name = controller.getWorkspaceName();
        if (name.isEmpty()) {
            return null;
        }
        Workspace workspace = WorkspaceManager.get(level).getByName(name);
        if (workspace == null || !workspace.isControllerPos(controllerPos)) {
            return null;
        }
        return workspace;
    }

    private BoundingBox resolveInitialBounds(WorkspaceControllerBlockEntity controller, int requestedSizeX, int requestedSizeY, int requestedSizeZ) {
        InitialSnapshot snapshot = controller.getInitialSnapshot();
        if (snapshot != null) {
            return WorkspaceRules.resizeBounds(snapshot.getBounds(), controllerPos.getY(), requestedSizeX, requestedSizeY, requestedSizeZ);
        }
        return WorkspaceRules.createBoundsFromController(controllerPos, requestedSizeX, requestedSizeY, requestedSizeZ);
    }
}
