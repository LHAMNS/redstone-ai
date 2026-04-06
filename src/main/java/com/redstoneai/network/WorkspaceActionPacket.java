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
        SELECT_RANGE
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
            controller.getOperationLog().logPlayer("config", "Pending size set to " + sizeX + "x" + sizeY + "x" + sizeZ);
            return;
        }

        if (!WorkspaceRules.canPlayerManage(player, workspace) || workspace.isFrozen()) {
            return;
        }

        WorkspaceManager manager = WorkspaceManager.get(level);
        BoundingBox newBounds = new BoundingBox(
                controllerPos.getX(), controllerPos.getY(), controllerPos.getZ(),
                controllerPos.getX() + sizeX - 1,
                controllerPos.getY() + sizeY - 1,
                controllerPos.getZ() + sizeZ - 1
        );
        if (manager.checkOverlap(newBounds, workspace.getId()) != null) {
            return;
        }

        controller.setSize(sizeX, sizeY, sizeZ);
        manager.updateWorkspaceGeometry(workspace, newBounds, null);
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

        BoundingBox bounds = new BoundingBox(
                controllerPos.getX(), controllerPos.getY(), controllerPos.getZ(),
                controllerPos.getX() + sizeX - 1,
                controllerPos.getY() + sizeY - 1,
                controllerPos.getZ() + sizeZ - 1
        );
        if (manager.checkOverlap(bounds) != null) {
            return;
        }

        Workspace workspace = new Workspace(UUID.randomUUID(), player.getUUID(), name, controllerPos, bounds);
        manager.addWorkspace(workspace);
        controller.setWorkspaceName(name);
        controller.setSize(sizeX, sizeY, sizeZ);
        controller.setInitialSnapshot(InitialSnapshot.capture(level, bounds));
        controller.getOperationLog().logSystem("create", "Workspace '" + name + "' created");
    }

    private void handleRevert(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null || !WorkspaceRules.canPlayerManage(player, workspace)) {
            return;
        }

        boolean wasFrozen = workspace.isFrozen();

        InitialSnapshot snapshot = controller.getInitialSnapshot();
        if (snapshot == null) {
            return;
        }

        int changed = snapshot.restore(level);
        BlockPos restoredControllerPos = snapshot.getControllerPos() != null ? snapshot.getControllerPos() : controllerPos;
        WorkspaceControllerBlockEntity restoredController = level.getBlockEntity(restoredControllerPos) instanceof WorkspaceControllerBlockEntity be
                ? be
                : controller;

        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.updateWorkspaceGeometry(workspace, snapshot.getBounds(), restoredControllerPos);
        workspace.setTimeline(null);
        workspace.setVirtualTick(0);
        TickController.removeQueue(workspace.getId());
        if (wasFrozen) {
            TickController.discardFrozenState(level, workspace);
        }
        restoredController.setInitialSnapshot(null);
        restoredController.getOperationLog().logPlayer("revert", "Reverted " + changed + " blocks");
    }

    private void handleSendChat(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && !WorkspaceRules.canPlayerManage(player, workspace)) {
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
        if (workspace != null && WorkspaceRules.canPlayerManage(player, workspace) && !workspace.isFrozen()) {
            TickController.freeze(level, workspace);
            controller.getOperationLog().logPlayer("freeze", "");
        }
    }

    private void handleUnfreeze(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && WorkspaceRules.canPlayerManage(player, workspace) && workspace.isFrozen()) {
            TickController.unfreeze(level, workspace);
            controller.getOperationLog().logPlayer("unfreeze", "");
        }
    }

    private void handleStep(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace != null && WorkspaceRules.canPlayerManage(player, workspace) && workspace.isFrozen()) {
            int count = Math.max(1, Math.min(sizeX, RAIConfig.SERVER.maxStepsPerCall.get()));
            int stepped = TickController.step(level, workspace, count);
            controller.getOperationLog().logPlayer("step", stepped + " tick(s)");
        }
    }

    private void handleRewind(ServerPlayer player, ServerLevel level, WorkspaceControllerBlockEntity controller) {
        Workspace workspace = getBoundWorkspace(level, controller);
        if (workspace == null || !WorkspaceRules.canPlayerManage(player, workspace) || !workspace.isFrozen()) {
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
        if (workspace == null || !WorkspaceRules.canPlayerManage(player, workspace) || !workspace.isFrozen()) {
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
        }
        SelectionManager.beginSelection(player, controllerPos, controller.getWorkspaceName());
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
}
