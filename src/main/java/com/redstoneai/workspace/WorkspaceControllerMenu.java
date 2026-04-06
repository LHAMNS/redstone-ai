package com.redstoneai.workspace;

import com.redstoneai.network.RAINetwork;
import com.redstoneai.registry.RAIBlocks;
import com.redstoneai.registry.RAIMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side menu container for the Workspace Controller.
 * Carries workspace config and snapshot status to the client screen.
 * Sensitive history is only serialized when the opener is authorized for the
 * current workspace.
 */
public class WorkspaceControllerMenu extends AbstractContainerMenu {
    public static final int MAX_LOG_LINES = 20;
    public static final int MAX_CHAT_LINES = 20;

    private final ContainerLevelAccess access;
    @Nullable
    private final ServerPlayer opener;
    private final boolean stateVisibleAtOpen;

    private BlockPos controllerPos;
    private String workspaceName;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private boolean hasSnapshot;
    private boolean frozen;
    private int virtualTick;
    private int recordingLength;
    private boolean canViewHistory;
    private boolean hasWorkspaceBounds;
    private int boundsMinX;
    private int boundsMinY;
    private int boundsMinZ;
    private int boundsMaxX;
    private int boundsMaxY;
    private int boundsMaxZ;
    private List<String> logLines;
    private List<String> chatLines;

    @Nullable
    private RAINetwork.WorkspaceControllerStateSyncPacket lastSyncedState;

    /** Server-side constructor. */
    public WorkspaceControllerMenu(int containerId, Inventory playerInv, BlockPos pos,
                                   WorkspaceControllerBlockEntity be) {
        super(RAIMenus.WORKSPACE_CONTROLLER.get(), containerId);
        this.access = ContainerLevelAccess.create(playerInv.player.level(), pos);
        this.opener = playerInv.player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        this.controllerPos = pos;
        this.workspaceName = "";
        this.sizeX = 16;
        this.sizeY = 8;
        this.sizeZ = 16;
        this.hasSnapshot = false;
        this.frozen = false;
        this.virtualTick = 0;
        this.recordingLength = 0;
        this.canViewHistory = false;
        this.hasWorkspaceBounds = false;
        this.boundsMinX = pos.getX();
        this.boundsMinY = pos.getY();
        this.boundsMinZ = pos.getZ();
        this.boundsMaxX = pos.getX();
        this.boundsMaxY = pos.getY();
        this.boundsMaxZ = pos.getZ();
        this.logLines = List.of();
        this.chatLines = List.of();

        boolean visible = be.getWorkspaceName().isEmpty();
        Workspace workspace = null;
        if (opener != null && playerInv.player.level() instanceof ServerLevel serverLevel) {
            String currentWorkspaceName = be.getWorkspaceName();
            if (!currentWorkspaceName.isEmpty()) {
                workspace = WorkspaceManager.get(serverLevel).getByName(currentWorkspaceName);
                visible = workspace != null && WorkspaceRules.canPlayerManage(opener, workspace);
            }
        }

        this.stateVisibleAtOpen = visible;
        refreshFromServerState(be, workspace);
        this.lastSyncedState = captureSyncPacket();
    }

    /** Client-side factory - reads from network buffer. */
    public WorkspaceControllerMenu(int containerId, Inventory playerInv, FriendlyByteBuf buf) {
        super(RAIMenus.WORKSPACE_CONTROLLER.get(), containerId);
        this.access = ContainerLevelAccess.NULL;
        this.opener = null;
        this.stateVisibleAtOpen = false;
        this.controllerPos = buf.readBlockPos();
        this.workspaceName = buf.readUtf(256);
        this.sizeX = buf.readInt();
        this.sizeY = buf.readInt();
        this.sizeZ = buf.readInt();
        this.hasSnapshot = buf.readBoolean();
        this.frozen = buf.readBoolean();
        this.virtualTick = buf.readInt();
        this.recordingLength = buf.readInt();
        this.canViewHistory = buf.readBoolean();
        this.hasWorkspaceBounds = buf.readBoolean();
        if (hasWorkspaceBounds) {
            this.boundsMinX = buf.readInt();
            this.boundsMinY = buf.readInt();
            this.boundsMinZ = buf.readInt();
            this.boundsMaxX = buf.readInt();
            this.boundsMaxY = buf.readInt();
            this.boundsMaxZ = buf.readInt();
        } else {
            this.boundsMinX = this.controllerPos.getX();
            this.boundsMinY = this.controllerPos.getY();
            this.boundsMinZ = this.controllerPos.getZ();
            this.boundsMaxX = this.controllerPos.getX();
            this.boundsMaxY = this.controllerPos.getY();
            this.boundsMaxZ = this.controllerPos.getZ();
        }
        if (canViewHistory) {
            int logCount = buf.readInt();
            List<String> logs = new ArrayList<>(logCount);
            for (int i = 0; i < logCount; i++) {
                logs.add(buf.readUtf(512));
            }
            this.logLines = List.copyOf(logs);

            int chatCount = buf.readInt();
            List<String> chats = new ArrayList<>(chatCount);
            for (int i = 0; i < chatCount; i++) {
                chats.add(buf.readUtf(1024));
            }
            this.chatLines = List.copyOf(chats);
        } else {
            this.logLines = List.of();
            this.chatLines = List.of();
        }
        this.lastSyncedState = null;
    }

    /** Write data to network buffer (called on server side). */
    public void writeToBuffer(FriendlyByteBuf buf) {
        buf.writeBlockPos(controllerPos);
        buf.writeUtf(workspaceName, 256);
        buf.writeInt(sizeX);
        buf.writeInt(sizeY);
        buf.writeInt(sizeZ);
        buf.writeBoolean(hasSnapshot);
        buf.writeBoolean(frozen);
        buf.writeInt(virtualTick);
        buf.writeInt(recordingLength);
        buf.writeBoolean(canViewHistory);
        buf.writeBoolean(hasWorkspaceBounds);
        if (hasWorkspaceBounds) {
            buf.writeInt(boundsMinX);
            buf.writeInt(boundsMinY);
            buf.writeInt(boundsMinZ);
            buf.writeInt(boundsMaxX);
            buf.writeInt(boundsMaxY);
            buf.writeInt(boundsMaxZ);
        }
        if (canViewHistory) {
            buf.writeInt(logLines.size());
            for (String line : logLines) {
                buf.writeUtf(line, 512);
            }

            buf.writeInt(chatLines.size());
            for (String line : chatLines) {
                buf.writeUtf(line, 1024);
            }
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (opener == null) {
            return;
        }

        refreshFromLiveController();
        RAINetwork.WorkspaceControllerStateSyncPacket snapshot = captureSyncPacket();
        if (!snapshot.equals(lastSyncedState)) {
            lastSyncedState = snapshot;
            RAINetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> opener), snapshot);
        }
    }

    public void applySyncPacket(RAINetwork.WorkspaceControllerStateSyncPacket packet) {
        this.controllerPos = packet.controllerPos();
        this.workspaceName = packet.workspaceName();
        this.sizeX = packet.sizeX();
        this.sizeY = packet.sizeY();
        this.sizeZ = packet.sizeZ();
        this.hasSnapshot = packet.hasSnapshot();
        this.frozen = packet.frozen();
        this.virtualTick = packet.virtualTick();
        this.recordingLength = packet.recordingLength();
        this.canViewHistory = packet.canViewHistory();
        this.hasWorkspaceBounds = packet.hasWorkspaceBounds();
        if (hasWorkspaceBounds) {
            this.boundsMinX = packet.boundsMinX();
            this.boundsMinY = packet.boundsMinY();
            this.boundsMinZ = packet.boundsMinZ();
            this.boundsMaxX = packet.boundsMaxX();
            this.boundsMaxY = packet.boundsMaxY();
            this.boundsMaxZ = packet.boundsMaxZ();
        }
        this.logLines = packet.logLines().isEmpty() ? List.of() : List.copyOf(packet.logLines());
        this.chatLines = packet.chatLines().isEmpty() ? List.of() : List.copyOf(packet.chatLines());
    }

    private void refreshFromLiveController() {
        if (!stateVisibleAtOpen || opener == null) {
            return;
        }

        ServerLevel level = opener.serverLevel();
        if (!(level.getBlockEntity(controllerPos) instanceof WorkspaceControllerBlockEntity controller)) {
            setNeutralState();
            return;
        }

        Workspace workspace = null;
        String currentWorkspaceName = controller.getWorkspaceName();
        if (!currentWorkspaceName.isEmpty()) {
            workspace = WorkspaceManager.get(level).getByName(currentWorkspaceName);
        }

        if (currentWorkspaceName.isEmpty() || workspace != null && WorkspaceRules.canPlayerManage(opener, workspace)) {
            refreshFromServerState(controller, workspace);
        } else {
            setNeutralState();
        }
    }

    private void refreshFromServerState(WorkspaceControllerBlockEntity controller, @Nullable Workspace workspace) {
        this.controllerPos = controller.getBlockPos();

        if (!stateVisibleAtOpen) {
            setNeutralState();
            return;
        }

        this.workspaceName = controller.getWorkspaceName();
        this.sizeX = controller.getSizeX();
        this.sizeY = controller.getSizeY();
        this.sizeZ = controller.getSizeZ();
        this.hasSnapshot = controller.hasInitialSnapshot();

        if (workspace != null) {
            this.frozen = workspace.isFrozen();
            this.virtualTick = workspace.getVirtualTick();
            this.recordingLength = workspace.getTimeline() != null ? workspace.getTimeline().getLength() : 0;
            this.canViewHistory = true;
            setWorkspaceBounds(workspace.getBounds());
            this.logLines = trimLast(controller.getOperationLog().getLastN(MAX_LOG_LINES), MAX_LOG_LINES).stream()
                    .map(OperationLog.Entry::toDisplayString)
                    .toList();
            this.chatLines = trimLast(controller.getChatHistory(), MAX_CHAT_LINES).stream()
                    .map(msg -> ("player".equals(msg.role()) ? "You: " : "AI: ") + msg.content())
                    .toList();
        } else {
            this.frozen = false;
            this.virtualTick = 0;
            this.recordingLength = 0;
            this.canViewHistory = false;
            clearWorkspaceBounds();
            this.logLines = List.of();
            this.chatLines = List.of();
        }
    }

    private void setNeutralState() {
        this.workspaceName = "";
        this.sizeX = 16;
        this.sizeY = 8;
        this.sizeZ = 16;
        this.hasSnapshot = false;
        this.frozen = false;
        this.virtualTick = 0;
        this.recordingLength = 0;
        this.canViewHistory = false;
        clearWorkspaceBounds();
        this.logLines = List.of();
        this.chatLines = List.of();
    }

    private void setWorkspaceBounds(BoundingBox bounds) {
        this.hasWorkspaceBounds = true;
        this.boundsMinX = bounds.minX();
        this.boundsMinY = bounds.minY();
        this.boundsMinZ = bounds.minZ();
        this.boundsMaxX = bounds.maxX();
        this.boundsMaxY = bounds.maxY();
        this.boundsMaxZ = bounds.maxZ();
    }

    private void clearWorkspaceBounds() {
        this.hasWorkspaceBounds = false;
        this.boundsMinX = controllerPos.getX();
        this.boundsMinY = controllerPos.getY();
        this.boundsMinZ = controllerPos.getZ();
        this.boundsMaxX = controllerPos.getX();
        this.boundsMaxY = controllerPos.getY();
        this.boundsMaxZ = controllerPos.getZ();
    }

    private RAINetwork.WorkspaceControllerStateSyncPacket captureSyncPacket() {
        return new RAINetwork.WorkspaceControllerStateSyncPacket(
                controllerPos,
                workspaceName,
                sizeX,
                sizeY,
                sizeZ,
                hasSnapshot,
                frozen,
                virtualTick,
                recordingLength,
                canViewHistory,
                hasWorkspaceBounds,
                boundsMinX,
                boundsMinY,
                boundsMinZ,
                boundsMaxX,
                boundsMaxY,
                boundsMaxZ,
                logLines,
                chatLines
        );
    }

    private static <T> List<T> trimLast(List<T> entries, int maxEntries) {
        if (entries.size() <= maxEntries) {
            return List.copyOf(entries);
        }
        return List.copyOf(entries.subList(entries.size() - maxEntries, entries.size()));
    }

    // -- Getters --

    public BlockPos getControllerPos() { return controllerPos; }
    public String getWorkspaceName() { return workspaceName; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public boolean hasSnapshot() { return hasSnapshot; }
    public boolean isFrozen() { return frozen; }
    public int getVirtualTick() { return virtualTick; }
    public int getRecordingLength() { return recordingLength; }
    public boolean canViewHistory() { return canViewHistory; }
    public List<String> getLogLines() { return logLines; }
    public List<String> getChatLines() { return chatLines; }

    @Nullable
    public BoundingBox getWorkspaceBounds() {
        return hasWorkspaceBounds
                ? new BoundingBox(boundsMinX, boundsMinY, boundsMinZ, boundsMaxX, boundsMaxY, boundsMaxZ)
                : null;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, RAIBlocks.WORKSPACE_CONTROLLER.get());
    }
}
