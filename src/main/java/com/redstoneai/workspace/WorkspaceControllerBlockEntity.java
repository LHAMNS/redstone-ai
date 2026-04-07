package com.redstoneai.workspace;

import com.redstoneai.config.RAIConfig;
import com.redstoneai.registry.RAIBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Block entity for the workspace controller. Stores:
 * <ul>
 *   <li>Workspace name and configuration (size, protection mode)</li>
 *   <li>Initial state snapshot (for one-click revert)</li>
 *   <li>Operation log (AI actions history)</li>
 *   <li>AI chat messages (conversation history visible in GUI)</li>
 * </ul>
 * All data is persisted via NBT so it survives server restarts.
 */
public class WorkspaceControllerBlockEntity extends BlockEntity {
    private String workspaceName = "";
    @Nullable
    private java.util.UUID placerUUID;
    private int sizeX = 16;
    private int sizeY = 8;
    private int sizeZ = 16;
    private ProtectionMode protectionMode = ProtectionMode.AI_ONLY;
    private EntityFilterMode entityFilterMode = EntityFilterMode.ALL_NON_PLAYER;
    private final List<String> authorizedPlayers = new ArrayList<>();
    private final List<PlayerPermissionGrant> playerPermissionGrants = new ArrayList<>();
    private boolean allowVanillaCommands = false;
    private boolean allowFrozenEntityTeleport = false;
    private boolean allowFrozenEntityDamage = false;
    private boolean allowFrozenEntityCollision = false;

    @Nullable
    private InitialSnapshot initialSnapshot;
    private final OperationLog operationLog = new OperationLog();
    private final List<ChatMessage> chatHistory = new ArrayList<>();

    private static final int MAX_CHAT_MESSAGES = 200;

    public record ChatMessage(long timestamp, String role, String content) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("time", timestamp);
            tag.putString("role", role);
            tag.putString("content", content);
            return tag;
        }

        public static ChatMessage load(CompoundTag tag) {
            return new ChatMessage(
                    tag.getLong("time"),
                    tag.getString("role"),
                    tag.getString("content")
            );
        }
    }

    public WorkspaceControllerBlockEntity(BlockPos pos, BlockState state) {
        super(RAIBlockEntities.WORKSPACE_CONTROLLER.get(), pos, state);
    }

    // ── Workspace Name ───────────────────────────────────────────────

    public String getWorkspaceName() { return workspaceName; }

    public void setWorkspaceName(String name) {
        this.workspaceName = name;
        setChanged();
    }

    @Nullable
    public java.util.UUID getPlacerUUID() { return placerUUID; }

    public void setPlacerUUID(@Nullable java.util.UUID uuid) {
        this.placerUUID = uuid;
        setChanged();
    }

    // ── Size Configuration ───────────────────────────────────────────

    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }

    public void setSize(int x, int y, int z) {
        int maxSize = RAIConfig.SERVER.maxWorkspaceSize.get();
        this.sizeX = Math.max(4, Math.min(maxSize, x));
        this.sizeY = Math.max(4, Math.min(maxSize, y));
        this.sizeZ = Math.max(4, Math.min(maxSize, z));
        setChanged();
    }

    public ProtectionMode getProtectionMode() { return protectionMode; }
    public EntityFilterMode getEntityFilterMode() { return entityFilterMode; }
    public List<String> getAuthorizedPlayers() { return Collections.unmodifiableList(authorizedPlayers); }
    public List<PlayerPermissionGrant> getPlayerPermissionGrants() { return List.copyOf(playerPermissionGrants); }
    public boolean isAllowVanillaCommands() { return allowVanillaCommands; }
    public boolean isAllowFrozenEntityTeleport() { return allowFrozenEntityTeleport; }
    public boolean isAllowFrozenEntityDamage() { return allowFrozenEntityDamage; }
    public boolean isAllowFrozenEntityCollision() { return allowFrozenEntityCollision; }

    public void setProtectionMode(ProtectionMode protectionMode) {
        this.protectionMode = protectionMode;
        setChanged();
    }

    public void setEntityFilterMode(EntityFilterMode entityFilterMode) {
        this.entityFilterMode = entityFilterMode;
        setChanged();
    }

    public void replaceAuthorizedPlayers(List<String> authorizedPlayers) {
        this.authorizedPlayers.clear();
        this.authorizedPlayers.addAll(authorizedPlayers);
        for (String playerName : authorizedPlayers) {
            setPlayerPermission(playerName, WorkspacePermission.BUILD, true);
            setPlayerPermission(playerName, WorkspacePermission.TIME_CONTROL, true);
            setPlayerPermission(playerName, WorkspacePermission.VIEW_HISTORY, true);
            setPlayerPermission(playerName, WorkspacePermission.CHAT, true);
        }
        setChanged();
    }

    public void replacePlayerPermissionGrants(List<PlayerPermissionGrant> grants) {
        this.playerPermissionGrants.clear();
        this.authorizedPlayers.clear();
        for (PlayerPermissionGrant grant : grants) {
            upsertGrant(grant);
        }
        setChanged();
    }

    public void setAllowVanillaCommands(boolean allowVanillaCommands) {
        this.allowVanillaCommands = allowVanillaCommands;
        setChanged();
    }

    public void setAllowFrozenEntityTeleport(boolean allowFrozenEntityTeleport) {
        this.allowFrozenEntityTeleport = allowFrozenEntityTeleport;
        setChanged();
    }

    public void setAllowFrozenEntityDamage(boolean allowFrozenEntityDamage) {
        this.allowFrozenEntityDamage = allowFrozenEntityDamage;
        setChanged();
    }

    public void setAllowFrozenEntityCollision(boolean allowFrozenEntityCollision) {
        this.allowFrozenEntityCollision = allowFrozenEntityCollision;
        setChanged();
    }

    public boolean hasPermission(String playerName, WorkspacePermission permission) {
        String normalized = WorkspaceAccessControl.normalizePlayerName(playerName);
        for (PlayerPermissionGrant grant : playerPermissionGrants) {
            if (grant.playerName().equals(normalized)) {
                return grant.has(permission);
            }
        }
        return false;
    }

    public void setPlayerPermission(String playerName, WorkspacePermission permission, boolean enabled) {
        String normalized = WorkspaceAccessControl.normalizePlayerName(playerName);
        if (normalized.isBlank()) {
            return;
        }

        EnumSet<WorkspacePermission> updatedPermissions = EnumSet.noneOf(WorkspacePermission.class);
        int existingIndex = -1;
        for (int i = 0; i < playerPermissionGrants.size(); i++) {
            PlayerPermissionGrant grant = playerPermissionGrants.get(i);
            if (grant.playerName().equals(normalized)) {
                updatedPermissions = grant.permissions();
                existingIndex = i;
                break;
            }
        }

        if (enabled) {
            updatedPermissions.add(permission);
        } else {
            updatedPermissions.remove(permission);
        }

        if (updatedPermissions.isEmpty()) {
            if (existingIndex >= 0) {
                playerPermissionGrants.remove(existingIndex);
            }
            authorizedPlayers.remove(normalized);
            setChanged();
            return;
        }

        PlayerPermissionGrant updated = new PlayerPermissionGrant(normalized, updatedPermissions);
        if (existingIndex >= 0) {
            playerPermissionGrants.set(existingIndex, updated);
        } else {
            playerPermissionGrants.add(updated);
        }
        if (!authorizedPlayers.contains(normalized)) {
            authorizedPlayers.add(normalized);
        }
        setChanged();
    }

    public void removePlayerPermissions(String playerName) {
        String normalized = WorkspaceAccessControl.normalizePlayerName(playerName);
        playerPermissionGrants.removeIf(grant -> grant.playerName().equals(normalized));
        authorizedPlayers.remove(normalized);
        setChanged();
    }

    // ── Initial Snapshot ─────────────────────────────────────────────

    @Nullable
    public InitialSnapshot getInitialSnapshot() { return initialSnapshot; }

    public void setInitialSnapshot(@Nullable InitialSnapshot snapshot) {
        this.initialSnapshot = snapshot;
        setChanged();
    }

    public boolean hasInitialSnapshot() { return initialSnapshot != null; }

    // ── Operation Log ────────────────────────────────────────────────

    public OperationLog getOperationLog() { return operationLog; }

    // ── Chat History ─────────────────────────────────────────────────

    public void addChatMessage(String role, String content) {
        chatHistory.add(new ChatMessage(System.currentTimeMillis(), role, content));
        trimChatHistory();
        setChanged();
    }

    public List<ChatMessage> getChatHistory() {
        return Collections.unmodifiableList(chatHistory);
    }

    public void replaceChatHistory(List<ChatMessage> messages) {
        chatHistory.clear();
        chatHistory.addAll(messages);
        trimChatHistory();
        setChanged();
    }

    public void copyStateFrom(WorkspaceControllerBlockEntity source) {
        this.workspaceName = source.workspaceName;
        this.sizeX = source.sizeX;
        this.sizeY = source.sizeY;
        this.sizeZ = source.sizeZ;
        this.protectionMode = source.protectionMode;
        this.entityFilterMode = source.entityFilterMode;
        this.authorizedPlayers.clear();
        this.authorizedPlayers.addAll(source.authorizedPlayers);
        this.playerPermissionGrants.clear();
        this.playerPermissionGrants.addAll(source.playerPermissionGrants);
        this.allowVanillaCommands = source.allowVanillaCommands;
        this.allowFrozenEntityTeleport = source.allowFrozenEntityTeleport;
        this.allowFrozenEntityDamage = source.allowFrozenEntityDamage;
        this.allowFrozenEntityCollision = source.allowFrozenEntityCollision;
        this.initialSnapshot = source.initialSnapshot;
        this.operationLog.replaceEntries(source.operationLog.getEntries());
        this.chatHistory.clear();
        this.chatHistory.addAll(source.chatHistory);
        trimChatHistory();
        setChanged();
    }

    public void clearWorkspaceBinding() {
        this.workspaceName = "";
        this.initialSnapshot = null;
        setChanged();
    }

    public void clearChatHistory() {
        chatHistory.clear();
        setChanged();
    }

    private void trimChatHistory() {
        while (chatHistory.size() > MAX_CHAT_MESSAGES) {
            chatHistory.remove(0);
        }
    }

    // ── NBT Persistence ──────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("workspaceName", workspaceName);
        if (placerUUID != null) {
            tag.putUUID("placerUUID", placerUUID);
        }
        tag.putInt("sizeX", sizeX);
        tag.putInt("sizeY", sizeY);
        tag.putInt("sizeZ", sizeZ);
        tag.putString("protectionMode", protectionMode.getSerializedName());
        tag.putString("entityFilterMode", entityFilterMode.getSerializedName());
        tag.putBoolean("allowVanillaCommands", allowVanillaCommands);
        tag.putBoolean("allowFrozenEntityTeleport", allowFrozenEntityTeleport);
        tag.putBoolean("allowFrozenEntityDamage", allowFrozenEntityDamage);
        tag.putBoolean("allowFrozenEntityCollision", allowFrozenEntityCollision);

        if (initialSnapshot != null) {
            tag.put("initialSnapshot", initialSnapshot.save());
        }

        tag.put("operationLog", operationLog.save());

        ListTag chatList = new ListTag();
        for (ChatMessage msg : chatHistory) {
            chatList.add(msg.save());
        }
        tag.put("chatHistory", chatList);

        ListTag authorizedPlayersTag = new ListTag();
        for (String playerName : authorizedPlayers) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putString("name", playerName);
            authorizedPlayersTag.add(playerTag);
        }
        tag.put("authorizedPlayers", authorizedPlayersTag);

        ListTag permissionGrantsTag = new ListTag();
        for (PlayerPermissionGrant grant : playerPermissionGrants) {
            permissionGrantsTag.add(grant.save());
        }
        tag.put("playerPermissionGrants", permissionGrantsTag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        workspaceName = tag.getString("workspaceName");
        placerUUID = tag.hasUUID("placerUUID") ? tag.getUUID("placerUUID") : null;
        sizeX = tag.contains("sizeX") ? tag.getInt("sizeX") : 16;
        sizeY = tag.contains("sizeY") ? tag.getInt("sizeY") : 8;
        sizeZ = tag.contains("sizeZ") ? tag.getInt("sizeZ") : 16;
        protectionMode = tag.contains("protectionMode")
                ? ProtectionMode.fromString(tag.getString("protectionMode"))
                : ProtectionMode.AI_ONLY;
        entityFilterMode = tag.contains("entityFilterMode")
                ? EntityFilterMode.fromString(tag.getString("entityFilterMode"))
                : EntityFilterMode.ALL_NON_PLAYER;
        allowVanillaCommands = tag.getBoolean("allowVanillaCommands");
        allowFrozenEntityTeleport = tag.getBoolean("allowFrozenEntityTeleport");
        allowFrozenEntityDamage = tag.getBoolean("allowFrozenEntityDamage");
        allowFrozenEntityCollision = tag.getBoolean("allowFrozenEntityCollision");

        initialSnapshot = tag.contains("initialSnapshot", Tag.TAG_COMPOUND)
                ? InitialSnapshot.load(tag.getCompound("initialSnapshot"))
                : null;

        operationLog.clear();
        if (tag.contains("operationLog", Tag.TAG_COMPOUND)) {
            OperationLog loaded = OperationLog.load(tag.getCompound("operationLog"));
            operationLog.replaceEntries(loaded.getEntries());
        }

        chatHistory.clear();
        if (tag.contains("chatHistory", Tag.TAG_LIST)) {
            ListTag chatList = tag.getList("chatHistory", Tag.TAG_COMPOUND);
            for (int i = 0; i < chatList.size(); i++) {
                chatHistory.add(ChatMessage.load(chatList.getCompound(i)));
            }
        }

        authorizedPlayers.clear();
        if (tag.contains("authorizedPlayers", Tag.TAG_LIST)) {
            ListTag playerList = tag.getList("authorizedPlayers", Tag.TAG_COMPOUND);
            for (int i = 0; i < playerList.size(); i++) {
                String playerName = playerList.getCompound(i).getString("name");
                if (!playerName.isBlank()) {
                    authorizedPlayers.add(playerName);
                }
            }
        }

        playerPermissionGrants.clear();
        if (tag.contains("playerPermissionGrants", Tag.TAG_LIST)) {
            ListTag grants = tag.getList("playerPermissionGrants", Tag.TAG_COMPOUND);
            for (int i = 0; i < grants.size(); i++) {
                upsertGrant(PlayerPermissionGrant.load(grants.getCompound(i)));
            }
        } else {
            for (String playerName : authorizedPlayers) {
                setPlayerPermission(playerName, WorkspacePermission.BUILD, true);
                setPlayerPermission(playerName, WorkspacePermission.TIME_CONTROL, true);
                setPlayerPermission(playerName, WorkspacePermission.VIEW_HISTORY, true);
                setPlayerPermission(playerName, WorkspacePermission.CHAT, true);
            }
        }
    }

    private void upsertGrant(PlayerPermissionGrant grant) {
        playerPermissionGrants.removeIf(existing -> existing.playerName().equals(grant.playerName()));
        playerPermissionGrants.add(grant);
        if (!authorizedPlayers.contains(grant.playerName())) {
            authorizedPlayers.add(grant.playerName());
        }
    }
}
