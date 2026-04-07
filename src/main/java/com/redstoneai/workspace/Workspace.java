package com.redstoneai.workspace;

import com.redstoneai.recording.IOMarker;
import com.redstoneai.recording.RecordingTimeline;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Core workspace state. Represents a bounded region where the AI can build and test redstone circuits.
 * Stored in WorkspaceManager's SavedData for persistence.
 */
public class Workspace {
    private final UUID id;
    private final UUID ownerUUID;
    private final String name;
    private BlockPos controllerPos;
    private BlockPos originPos;
    private BoundingBox bounds;
    private ProtectionMode protectionMode;
    private EntityFilterMode entityFilterMode;
    private final List<String> authorizedPlayers;
    private final List<PlayerPermissionGrant> playerPermissionGrants;
    private boolean allowVanillaCommands;
    private boolean allowFrozenEntityTeleport;
    private boolean allowFrozenEntityDamage;
    private boolean allowFrozenEntityCollision;
    private boolean frozen;
    private final List<IOMarker> ioMarkers;
    @Nullable
    private RecordingTimeline timeline;
    private int virtualTick;

    public Workspace(UUID id, UUID ownerUUID, String name, BlockPos controllerPos, BoundingBox bounds) {
        this(id, ownerUUID, name, controllerPos, WorkspaceRules.originFromBounds(bounds), bounds);
    }

    public Workspace(UUID id, UUID ownerUUID, String name, BlockPos controllerPos, BlockPos originPos, BoundingBox bounds) {
        this.id = id;
        this.ownerUUID = ownerUUID;
        this.name = name;
        this.controllerPos = controllerPos;
        this.originPos = originPos;
        this.bounds = bounds;
        this.protectionMode = ProtectionMode.AI_ONLY;
        this.entityFilterMode = EntityFilterMode.ALL_NON_PLAYER;
        this.authorizedPlayers = new ArrayList<>();
        this.playerPermissionGrants = new ArrayList<>();
        this.allowVanillaCommands = false;
        this.allowFrozenEntityTeleport = false;
        this.allowFrozenEntityDamage = false;
        this.allowFrozenEntityCollision = false;
        this.frozen = false;
        this.ioMarkers = new ArrayList<>();
        this.timeline = null;
        this.virtualTick = 0;
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getName() { return name; }
    public BlockPos getControllerPos() { return controllerPos; }
    public BlockPos getOriginPos() { return originPos; }
    public BoundingBox getBounds() { return bounds; }
    public ProtectionMode getProtectionMode() { return protectionMode; }
    public EntityFilterMode getEntityFilterMode() { return entityFilterMode; }
    public List<String> getAuthorizedPlayers() { return Collections.unmodifiableList(authorizedPlayers); }
    public List<PlayerPermissionGrant> getPlayerPermissionGrants() { return List.copyOf(playerPermissionGrants); }
    public boolean isAllowVanillaCommands() { return allowVanillaCommands; }
    public boolean isAllowFrozenEntityTeleport() { return allowFrozenEntityTeleport; }
    public boolean isAllowFrozenEntityDamage() { return allowFrozenEntityDamage; }
    public boolean isAllowFrozenEntityCollision() { return allowFrozenEntityCollision; }
    public boolean isFrozen() { return frozen; }
    public List<IOMarker> getIOMarkers() { return Collections.unmodifiableList(ioMarkers); }
    public int getVirtualTick() { return virtualTick; }

    @Nullable
    public RecordingTimeline getTimeline() { return timeline; }

    // --- Mutators ---

    public void setProtectionMode(ProtectionMode mode) { this.protectionMode = mode; }
    public void setEntityFilterMode(EntityFilterMode mode) { this.entityFilterMode = mode; }
    public void replaceAuthorizedPlayers(List<String> players) {
        this.authorizedPlayers.clear();
        this.authorizedPlayers.addAll(players);
        for (String playerName : players) {
            setPlayerPermission(playerName, WorkspacePermission.BUILD, true);
            setPlayerPermission(playerName, WorkspacePermission.TIME_CONTROL, true);
            setPlayerPermission(playerName, WorkspacePermission.VIEW_HISTORY, true);
            setPlayerPermission(playerName, WorkspacePermission.CHAT, true);
        }
    }
    public void setAllowVanillaCommands(boolean allowVanillaCommands) { this.allowVanillaCommands = allowVanillaCommands; }
    public void setAllowFrozenEntityTeleport(boolean allowFrozenEntityTeleport) { this.allowFrozenEntityTeleport = allowFrozenEntityTeleport; }
    public void setAllowFrozenEntityDamage(boolean allowFrozenEntityDamage) { this.allowFrozenEntityDamage = allowFrozenEntityDamage; }
    public void setAllowFrozenEntityCollision(boolean allowFrozenEntityCollision) { this.allowFrozenEntityCollision = allowFrozenEntityCollision; }
    public void replacePlayerPermissionGrants(List<PlayerPermissionGrant> grants) {
        this.playerPermissionGrants.clear();
        this.authorizedPlayers.clear();
        for (PlayerPermissionGrant grant : grants) {
            upsertGrant(grant);
        }
    }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public void setTimeline(@Nullable RecordingTimeline timeline) { this.timeline = timeline; }
    public void setControllerPos(BlockPos controllerPos) { this.controllerPos = controllerPos; }
    public void setOriginPos(BlockPos originPos) { this.originPos = originPos; }
    public void incrementVirtualTick() { this.virtualTick++; }
    public void setVirtualTick(int tick) { this.virtualTick = tick; }

    // --- Query ---

    public boolean contains(BlockPos pos) {
        return bounds.isInside(pos);
    }

    public boolean isControllerPos(BlockPos pos) {
        return controllerPos.equals(pos);
    }

    /**
     * Check if an entity's full bounding box is entirely inside the workspace.
     * The entity must be completely contained, not just partially overlapping.
     */
    public boolean containsEntityFully(net.minecraft.world.entity.Entity entity) {
        AABB entityBox = entity.getBoundingBox();
        return entityBox.minX >= bounds.minX()
                && entityBox.minY >= bounds.minY()
                && entityBox.minZ >= bounds.minZ()
                && entityBox.maxX <= bounds.maxX() + 1.0
                && entityBox.maxY <= bounds.maxY() + 1.0
                && entityBox.maxZ <= bounds.maxZ() + 1.0;
    }

    /** Get the workspace bounds as an AABB (block-inclusive). */
    public AABB getAABB() {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0);
    }

    public int getBlockCount() {
        return (bounds.maxX() - bounds.minX() + 1)
             * (bounds.maxY() - bounds.minY() + 1)
             * (bounds.maxZ() - bounds.minZ() + 1);
    }

    public int getSizeX() {
        return bounds.maxX() - bounds.minX() + 1;
    }

    public int getSizeY() {
        return bounds.maxY() - bounds.minY() + 1;
    }

    public int getSizeZ() {
        return bounds.maxZ() - bounds.minZ() + 1;
    }

    public void setBounds(BoundingBox bounds) {
        this.bounds = bounds;
    }

    public BlockPos toWorldPos(int x, int y, int z) {
        return originPos.offset(x, y, z);
    }

    public BlockPos toRelativePos(BlockPos worldPos) {
        return worldPos.subtract(originPos);
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
    }

    public void removePlayerPermissions(String playerName) {
        String normalized = WorkspaceAccessControl.normalizePlayerName(playerName);
        playerPermissionGrants.removeIf(grant -> grant.playerName().equals(normalized));
        authorizedPlayers.remove(normalized);
    }

    // --- IO Markers ---

    public void addIOMarker(IOMarker marker) {
        ioMarkers.removeIf(m -> m.pos().equals(marker.pos()));
        ioMarkers.add(marker);
    }

    public void removeIOMarker(BlockPos pos) {
        ioMarkers.removeIf(m -> m.pos().equals(pos));
    }

    /** Remove all IO markers that don't satisfy the predicate. */
    public void retainIOMarkers(java.util.function.Predicate<IOMarker> keepIf) {
        ioMarkers.removeIf(m -> !keepIf.test(m));
    }

    @Nullable
    public IOMarker getIOMarker(BlockPos pos) {
        return ioMarkers.stream()
                .filter(m -> m.pos().equals(pos))
                .findFirst()
                .orElse(null);
    }

    // --- NBT Persistence ---

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("owner", ownerUUID);
        tag.putString("name", name);
        tag.putIntArray("controllerPos", new int[]{controllerPos.getX(), controllerPos.getY(), controllerPos.getZ()});
        tag.putIntArray("originPos", new int[]{originPos.getX(), originPos.getY(), originPos.getZ()});
        tag.putIntArray("bounds", new int[]{
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()
        });
        tag.putString("protectionMode", protectionMode.getSerializedName());
        tag.putString("entityFilterMode", entityFilterMode.getSerializedName());
        tag.putBoolean("allowVanillaCommands", allowVanillaCommands);
        tag.putBoolean("allowFrozenEntityTeleport", allowFrozenEntityTeleport);
        tag.putBoolean("allowFrozenEntityDamage", allowFrozenEntityDamage);
        tag.putBoolean("allowFrozenEntityCollision", allowFrozenEntityCollision);
        tag.putBoolean("frozen", frozen);
        tag.putInt("virtualTick", virtualTick);

        ListTag markerList = new ListTag();
        for (IOMarker marker : ioMarkers) {
            markerList.add(marker.save());
        }
        tag.put("ioMarkers", markerList);

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

        return tag;
    }

    public static Workspace load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        UUID owner = tag.getUUID("owner");
        String name = tag.getString("name");
        int[] cpos = tag.getIntArray("controllerPos");
        if (cpos.length < 3) throw new IllegalStateException("Corrupted workspace NBT: controllerPos array too short for '" + name + "'");
        BlockPos controllerPos = new BlockPos(cpos[0], cpos[1], cpos[2]);
        int[] b = tag.getIntArray("bounds");
        if (b.length < 6) throw new IllegalStateException("Corrupted workspace NBT: bounds array too short for '" + name + "'");
        BoundingBox bounds = new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);

        BlockPos originPos = WorkspaceRules.originFromBounds(bounds);
        if (tag.contains("originPos", Tag.TAG_INT_ARRAY)) {
            int[] opos = tag.getIntArray("originPos");
            if (opos.length >= 3) {
                originPos = new BlockPos(opos[0], opos[1], opos[2]);
            }
        }

        Workspace ws = new Workspace(id, owner, name, controllerPos, originPos, bounds);
        ws.protectionMode = ProtectionMode.fromString(tag.getString("protectionMode"));
        ws.entityFilterMode = tag.contains("entityFilterMode")
                ? EntityFilterMode.fromString(tag.getString("entityFilterMode"))
                : EntityFilterMode.ALL_NON_PLAYER;
        ws.allowVanillaCommands = tag.getBoolean("allowVanillaCommands");
        ws.allowFrozenEntityTeleport = tag.getBoolean("allowFrozenEntityTeleport");
        ws.allowFrozenEntityDamage = tag.getBoolean("allowFrozenEntityDamage");
        ws.allowFrozenEntityCollision = tag.getBoolean("allowFrozenEntityCollision");
        ws.frozen = tag.getBoolean("frozen");
        ws.virtualTick = tag.getInt("virtualTick");

        ListTag markerList = tag.getList("ioMarkers", Tag.TAG_COMPOUND);
        for (int i = 0; i < markerList.size(); i++) {
            ws.ioMarkers.add(IOMarker.load(markerList.getCompound(i)));
        }

        ListTag authorizedPlayersTag = tag.getList("authorizedPlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < authorizedPlayersTag.size(); i++) {
            String playerName = authorizedPlayersTag.getCompound(i).getString("name");
            if (!playerName.isBlank()) {
                ws.authorizedPlayers.add(playerName);
            }
        }

        if (tag.contains("playerPermissionGrants", Tag.TAG_LIST)) {
            ListTag permissionGrantsTag = tag.getList("playerPermissionGrants", Tag.TAG_COMPOUND);
            ws.playerPermissionGrants.clear();
            for (int i = 0; i < permissionGrantsTag.size(); i++) {
                ws.upsertGrant(PlayerPermissionGrant.load(permissionGrantsTag.getCompound(i)));
            }
        } else {
            for (String playerName : ws.authorizedPlayers) {
                ws.setPlayerPermission(playerName, WorkspacePermission.BUILD, true);
                ws.setPlayerPermission(playerName, WorkspacePermission.TIME_CONTROL, true);
                ws.setPlayerPermission(playerName, WorkspacePermission.VIEW_HISTORY, true);
                ws.setPlayerPermission(playerName, WorkspacePermission.CHAT, true);
            }
        }

        return ws;
    }

    private void upsertGrant(PlayerPermissionGrant grant) {
        playerPermissionGrants.removeIf(existing -> existing.playerName().equals(grant.playerName()));
        playerPermissionGrants.add(grant);
        if (!authorizedPlayers.contains(grant.playerName())) {
            authorizedPlayers.add(grant.playerName());
        }
    }
}
