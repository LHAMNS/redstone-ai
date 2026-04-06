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
    private BoundingBox bounds;
    private ProtectionMode protectionMode;
    private EntityFilterMode entityFilterMode;
    private boolean frozen;
    private final List<IOMarker> ioMarkers;
    @Nullable
    private RecordingTimeline timeline;
    private int virtualTick;

    public Workspace(UUID id, UUID ownerUUID, String name, BlockPos controllerPos, BoundingBox bounds) {
        this.id = id;
        this.ownerUUID = ownerUUID;
        this.name = name;
        this.controllerPos = controllerPos;
        this.bounds = bounds;
        this.protectionMode = ProtectionMode.AI_ONLY;
        this.entityFilterMode = EntityFilterMode.ALL_NON_PLAYER;
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
    public BoundingBox getBounds() { return bounds; }
    public ProtectionMode getProtectionMode() { return protectionMode; }
    public EntityFilterMode getEntityFilterMode() { return entityFilterMode; }
    public boolean isFrozen() { return frozen; }
    public List<IOMarker> getIOMarkers() { return ioMarkers; }
    public int getVirtualTick() { return virtualTick; }

    @Nullable
    public RecordingTimeline getTimeline() { return timeline; }

    // --- Mutators ---

    public void setProtectionMode(ProtectionMode mode) { this.protectionMode = mode; }
    public void setEntityFilterMode(EntityFilterMode mode) { this.entityFilterMode = mode; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public void setTimeline(@Nullable RecordingTimeline timeline) { this.timeline = timeline; }
    public void setControllerPos(BlockPos controllerPos) { this.controllerPos = controllerPos; }
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

    // --- IO Markers ---

    public void addIOMarker(IOMarker marker) {
        ioMarkers.removeIf(m -> m.pos().equals(marker.pos()));
        ioMarkers.add(marker);
    }

    public void removeIOMarker(BlockPos pos) {
        ioMarkers.removeIf(m -> m.pos().equals(pos));
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
        tag.putIntArray("bounds", new int[]{
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()
        });
        tag.putString("protectionMode", protectionMode.getSerializedName());
        tag.putString("entityFilterMode", entityFilterMode.getSerializedName());
        tag.putBoolean("frozen", frozen);
        tag.putInt("virtualTick", virtualTick);

        ListTag markerList = new ListTag();
        for (IOMarker marker : ioMarkers) {
            markerList.add(marker.save());
        }
        tag.put("ioMarkers", markerList);

        return tag;
    }

    public static Workspace load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        UUID owner = tag.getUUID("owner");
        String name = tag.getString("name");
        int[] cpos = tag.getIntArray("controllerPos");
        BlockPos controllerPos = new BlockPos(cpos[0], cpos[1], cpos[2]);
        int[] b = tag.getIntArray("bounds");
        BoundingBox bounds = new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);

        Workspace ws = new Workspace(id, owner, name, controllerPos, bounds);
        ws.protectionMode = ProtectionMode.fromString(tag.getString("protectionMode"));
        ws.entityFilterMode = tag.contains("entityFilterMode")
                ? EntityFilterMode.fromString(tag.getString("entityFilterMode"))
                : EntityFilterMode.ALL_NON_PLAYER;
        ws.frozen = tag.getBoolean("frozen");
        ws.virtualTick = tag.getInt("virtualTick");

        ListTag markerList = tag.getList("ioMarkers", Tag.TAG_COMPOUND);
        for (int i = 0; i < markerList.size(); i++) {
            ws.ioMarkers.add(IOMarker.load(markerList.getCompound(i)));
        }

        return ws;
    }
}
