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
    private String temporalState;
    private String lastMutationSource;
    private String protectionMode;
    private String entityFilterMode;
    private String authorizedPlayers;
    private List<String> playerPermissionEntries;
    private boolean allowVanillaCommands;
    private boolean allowFrozenEntityTeleport;
    private boolean allowFrozenEntityDamage;
    private boolean allowFrozenEntityCollision;
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
        this.temporalState = WorkspaceTemporalState.LIVE.getSerializedName();
        this.lastMutationSource = WorkspaceMutationSource.NONE.getSerializedName();
        this.protectionMode = ProtectionMode.AI_ONLY.getSerializedName();
        this.entityFilterMode = EntityFilterMode.ALL_NON_PLAYER.getSerializedName();
        this.authorizedPlayers = "";
        this.playerPermissionEntries = List.of();
        this.allowVanillaCommands = false;
        this.allowFrozenEntityTeleport = false;
        this.allowFrozenEntityDamage = false;
        this.allowFrozenEntityCollision = false;
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
                visible = workspace != null && WorkspaceAccessControl.canPlayerOpenMenu(opener, workspace);
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
        this.temporalState = buf.readUtf(64);
        this.lastMutationSource = buf.readUtf(64);
        this.protectionMode = buf.readUtf(64);
        this.entityFilterMode = buf.readUtf(64);
        this.authorizedPlayers = buf.readUtf(512);
        int permissionEntryCount = buf.readInt();
        List<String> permissionEntries = new ArrayList<>(permissionEntryCount);
        for (int i = 0; i < permissionEntryCount; i++) {
            permissionEntries.add(buf.readUtf(512));
        }
        this.playerPermissionEntries = List.copyOf(permissionEntries);
        this.allowVanillaCommands = buf.readBoolean();
        this.allowFrozenEntityTeleport = buf.readBoolean();
        this.allowFrozenEntityDamage = buf.readBoolean();
        this.allowFrozenEntityCollision = buf.readBoolean();
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
        buf.writeUtf(temporalState, 64);
        buf.writeUtf(lastMutationSource, 64);
        buf.writeUtf(protectionMode, 64);
        buf.writeUtf(entityFilterMode, 64);
        buf.writeUtf(authorizedPlayers, 512);
        buf.writeInt(playerPermissionEntries.size());
        for (String entry : playerPermissionEntries) {
            buf.writeUtf(entry, 512);
        }
        buf.writeBoolean(allowVanillaCommands);
        buf.writeBoolean(allowFrozenEntityTeleport);
        buf.writeBoolean(allowFrozenEntityDamage);
        buf.writeBoolean(allowFrozenEntityCollision);
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
        this.temporalState = packet.temporalState();
        this.lastMutationSource = packet.lastMutationSource();
        this.protectionMode = packet.protectionMode();
        this.entityFilterMode = packet.entityFilterMode();
        this.authorizedPlayers = packet.authorizedPlayers();
        this.playerPermissionEntries = packet.playerPermissionEntries().isEmpty() ? List.of() : List.copyOf(packet.playerPermissionEntries());
        this.allowVanillaCommands = packet.allowVanillaCommands();
        this.allowFrozenEntityTeleport = packet.allowFrozenEntityTeleport();
        this.allowFrozenEntityDamage = packet.allowFrozenEntityDamage();
        this.allowFrozenEntityCollision = packet.allowFrozenEntityCollision();
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

        if (currentWorkspaceName.isEmpty() || workspace != null && WorkspaceAccessControl.canPlayerOpenMenu(opener, workspace)) {
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
        this.protectionMode = controller.getProtectionMode().getSerializedName();
        this.entityFilterMode = controller.getEntityFilterMode().getSerializedName();
        this.authorizedPlayers = WorkspaceAccessControl.formatAuthorizedPlayers(controller.getAuthorizedPlayers());
        this.playerPermissionEntries = WorkspaceAccessControl.serializePermissionGrants(controller.getPlayerPermissionGrants());
        this.allowVanillaCommands = controller.isAllowVanillaCommands();
        this.allowFrozenEntityTeleport = controller.isAllowFrozenEntityTeleport();
        this.allowFrozenEntityDamage = controller.isAllowFrozenEntityDamage();
        this.allowFrozenEntityCollision = controller.isAllowFrozenEntityCollision();

        if (workspace != null) {
            this.frozen = workspace.isFrozen();
            this.virtualTick = workspace.getVirtualTick();
            this.recordingLength = workspace.getTimeline() != null ? workspace.getTimeline().getLength() : 0;
            this.temporalState = workspace.getTemporalState().getSerializedName();
            this.lastMutationSource = workspace.getLastMutationSource().getSerializedName();
            this.protectionMode = workspace.getProtectionMode().getSerializedName();
            this.entityFilterMode = workspace.getEntityFilterMode().getSerializedName();
            this.authorizedPlayers = WorkspaceAccessControl.formatAuthorizedPlayers(workspace.getAuthorizedPlayers());
            this.playerPermissionEntries = WorkspaceAccessControl.serializePermissionGrants(workspace.getPlayerPermissionGrants());
            this.allowVanillaCommands = workspace.isAllowVanillaCommands();
            this.allowFrozenEntityTeleport = workspace.isAllowFrozenEntityTeleport();
            this.allowFrozenEntityDamage = workspace.isAllowFrozenEntityDamage();
            this.allowFrozenEntityCollision = workspace.isAllowFrozenEntityCollision();
            this.canViewHistory = opener != null && WorkspaceAccessControl.canPlayerViewHistory(opener, workspace);
            setWorkspaceBounds(workspace.getBounds());
            this.logLines = this.canViewHistory
                    ? trimLast(controller.getOperationLog().getLastN(MAX_LOG_LINES), MAX_LOG_LINES).stream()
                        .map(OperationLog.Entry::toDisplayString)
                        .toList()
                    : List.of();
            this.chatLines = this.canViewHistory
                    ? trimLast(controller.getChatHistory(), MAX_CHAT_LINES).stream()
                        .map(msg -> ("player".equals(msg.role()) ? "You: " : "AI: ") + msg.content())
                        .toList()
                    : List.of();
        } else {
            this.frozen = false;
            this.virtualTick = 0;
            this.recordingLength = 0;
            this.temporalState = WorkspaceTemporalState.LIVE.getSerializedName();
            this.lastMutationSource = WorkspaceMutationSource.NONE.getSerializedName();
            this.canViewHistory = false;
            if (this.workspaceName.isEmpty() && controller.getInitialSnapshot() != null) {
                setWorkspaceBounds(controller.getInitialSnapshot().getBounds());
            } else {
                clearWorkspaceBounds();
            }
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
        this.temporalState = WorkspaceTemporalState.LIVE.getSerializedName();
        this.lastMutationSource = WorkspaceMutationSource.NONE.getSerializedName();
        this.protectionMode = ProtectionMode.AI_ONLY.getSerializedName();
        this.entityFilterMode = EntityFilterMode.ALL_NON_PLAYER.getSerializedName();
        this.authorizedPlayers = "";
        this.playerPermissionEntries = List.of();
        this.allowVanillaCommands = false;
        this.allowFrozenEntityTeleport = false;
        this.allowFrozenEntityDamage = false;
        this.allowFrozenEntityCollision = false;
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
                temporalState,
                lastMutationSource,
                protectionMode,
                entityFilterMode,
                authorizedPlayers,
                playerPermissionEntries,
                allowVanillaCommands,
                allowFrozenEntityTeleport,
                allowFrozenEntityDamage,
                allowFrozenEntityCollision,
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
    public String getTemporalState() { return temporalState; }
    public String getLastMutationSource() { return lastMutationSource; }
    public String getProtectionMode() { return protectionMode; }
    public String getEntityFilterMode() { return entityFilterMode; }
    public String getAuthorizedPlayers() { return authorizedPlayers; }
    public List<String> getPlayerPermissionEntries() { return playerPermissionEntries; }
    public boolean hasPlayerPermission(String playerName, WorkspacePermission permission) {
        String normalized = WorkspaceAccessControl.normalizePlayerName(playerName);
        for (PlayerPermissionGrant grant : WorkspaceAccessControl.deserializePermissionGrants(playerPermissionEntries)) {
            if (grant.playerName().equals(normalized)) {
                return grant.has(permission);
            }
        }
        return false;
    }
    public boolean isAllowVanillaCommands() { return allowVanillaCommands; }
    public boolean isAllowFrozenEntityTeleport() { return allowFrozenEntityTeleport; }
    public boolean isAllowFrozenEntityDamage() { return allowFrozenEntityDamage; }
    public boolean isAllowFrozenEntityCollision() { return allowFrozenEntityCollision; }
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
