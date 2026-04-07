package com.redstoneai.workspace;

import com.redstoneai.RedstoneAI;
import com.redstoneai.network.WorkspaceBoundarySyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension workspace registry. Stored as SavedData for persistence.
 * Provides fast lookup by name, UUID, and spatial position.
 */
public class WorkspaceManager extends SavedData {
    private static final String DATA_KEY = RedstoneAI.ID + "_workspaces";
    private static final Map<ServerLevel, WorkspaceManager> INSTANCES = new ConcurrentHashMap<>();

    @Nullable
    private transient volatile ServerLevel ownerLevel;
    private final Map<UUID, Workspace> workspacesById = new LinkedHashMap<>();
    private final Map<String, Workspace> workspacesByName = new LinkedHashMap<>();
    private final Map<Long, List<Workspace>> workspacesByChunk = new HashMap<>();

    public WorkspaceManager() {}

    // --- Static access ---

    public static WorkspaceManager get(ServerLevel level) {
        WorkspaceManager manager = INSTANCES.computeIfAbsent(level, l ->
                l.getDataStorage().computeIfAbsent(
                        WorkspaceManager::load,
                        WorkspaceManager::new,
                        DATA_KEY
                ));
        manager.attachLevel(level);
        return manager;
    }

    public static void onLevelUnload(ServerLevel level) {
        INSTANCES.remove(level);
    }

    // --- Workspace CRUD ---

    public synchronized void addWorkspace(Workspace workspace) {
        workspacesById.put(workspace.getId(), workspace);
        workspacesByName.put(workspace.getName(), workspace);
        indexWorkspace(workspace);
        setDirty();
        syncBoundaries();
    }

    /**
     * Check whether a proposed bounding box overlaps any existing workspace.
     * Used to prevent workspace creation that would violate isolation.
     *
     * @return the name of the overlapping workspace, or null if no overlap
     */
    @Nullable
    public synchronized String checkOverlap(BoundingBox proposed) {
        return checkOverlap(proposed, null);
    }

    @Nullable
    public synchronized String checkOverlap(BoundingBox proposed, @Nullable UUID ignoredWorkspaceId) {
        for (Workspace ws : workspacesById.values()) {
            if (ignoredWorkspaceId != null && ws.getId().equals(ignoredWorkspaceId)) {
                continue;
            }
            if (ws.getBounds().intersects(proposed)) {
                return ws.getName();
            }
        }
        return null;
    }

    public synchronized boolean removeWorkspace(String name) {
        Workspace ws = workspacesByName.remove(name);
        if (ws != null) {
            workspacesById.remove(ws.getId());
            unindexWorkspace(ws);
            setDirty();
            syncBoundaries();
            return true;
        }
        return false;
    }

    @Nullable
    public synchronized Workspace getByName(String name) {
        return workspacesByName.get(name);
    }

    @Nullable
    public synchronized Workspace getById(UUID id) {
        return workspacesById.get(id);
    }

    @Nullable
    public synchronized Workspace getByControllerPos(BlockPos controllerPos) {
        for (Workspace workspace : workspacesById.values()) {
            if (workspace.isControllerPos(controllerPos)) {
                return workspace;
            }
        }
        return null;
    }

    @Nullable
    public synchronized Workspace getWorkspaceContainingAABB(AABB box) {
        Set<UUID> visited = new HashSet<>();
        int minChunkX = ((int) Math.floor(box.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(box.maxX - 1.0E-6D)) >> 4;
        int minChunkZ = ((int) Math.floor(box.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(box.maxZ - 1.0E-6D)) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                List<Workspace> candidates = workspacesByChunk.get(ChunkPos.asLong(chunkX, chunkZ));
                if (candidates == null) {
                    continue;
                }
                for (Workspace workspace : candidates) {
                    if (!visited.add(workspace.getId())) {
                        continue;
                    }
                    BoundingBox bounds = workspace.getBounds();
                    if (box.minX >= bounds.minX()
                            && box.minY >= bounds.minY()
                            && box.minZ >= bounds.minZ()
                            && box.maxX <= bounds.maxX() + 1.0
                            && box.maxY <= bounds.maxY() + 1.0
                            && box.maxZ <= bounds.maxZ() + 1.0) {
                        return workspace;
                    }
                }
            }
        }
        return null;
    }

    public synchronized Collection<Workspace> getAllWorkspaces() {
        return Collections.unmodifiableCollection(workspacesById.values());
    }

    public synchronized List<Workspace> getAllWorkspacesSnapshot() {
        return List.copyOf(workspacesById.values());
    }

    public synchronized List<WorkspaceView> getWorkspaceViewsSnapshot() {
        List<WorkspaceView> views = new ArrayList<>(workspacesById.size());
        for (Workspace workspace : workspacesById.values()) {
            BoundingBox bounds = workspace.getBounds();
            views.add(new WorkspaceView(
                    workspace.getName(),
                    new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                    workspace.isFrozen()
            ));
        }
        return views;
    }

    public synchronized List<Workspace> getWorkspacesOwnedBy(UUID playerUUID) {
        return workspacesById.values().stream()
                .filter(ws -> ws.getOwnerUUID().equals(playerUUID))
                .toList();
    }

    public synchronized boolean hasWorkspace(String name) {
        return workspacesByName.containsKey(name);
    }

    // --- Spatial queries ---

    /**
     * Find all workspaces whose bounds contain the given position.
     * Linear scan is acceptable for small workspace counts (<20).
     */
    @Nullable
    public synchronized Workspace getWorkspaceAt(BlockPos pos) {
        List<Workspace> candidates = workspacesByChunk.get(ChunkPos.asLong(pos));
        if (candidates == null) {
            return null;
        }
        for (Workspace ws : candidates) {
            if (ws.contains(pos)) {
                return ws;
            }
        }
        return null;
    }

    /**
     * Get all frozen workspaces in this dimension.
     * Called by TickInterceptor every tick — keep it fast.
     */
    public synchronized List<Workspace> getFrozenWorkspaces() {
        List<Workspace> frozen = new ArrayList<>();
        for (Workspace ws : workspacesById.values()) {
            if (ws.isFrozen()) {
                frozen.add(ws);
            }
        }
        return frozen;
    }

    public synchronized void updateWorkspaceBounds(Workspace workspace, BoundingBox newBounds) {
        updateWorkspaceGeometry(workspace, newBounds, null, null);
    }

    public synchronized void updateWorkspaceGeometry(Workspace workspace, BoundingBox newBounds, @Nullable BlockPos newControllerPos) {
        updateWorkspaceGeometry(workspace, newBounds, newControllerPos, null);
    }

    public synchronized void updateWorkspaceGeometry(Workspace workspace,
                                                     BoundingBox newBounds,
                                                     @Nullable BlockPos newControllerPos,
                                                     @Nullable BlockPos newOriginPos) {
        unindexWorkspace(workspace);
        workspace.setBounds(newBounds);
        if (newControllerPos != null) {
            workspace.setControllerPos(newControllerPos);
        }
        if (newOriginPos != null) {
            workspace.setOriginPos(newOriginPos);
        }
        indexWorkspace(workspace);
        setDirty();
        syncBoundaries();
    }

    // --- Persistence ---

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Workspace ws : workspacesById.values()) {
            list.add(ws.save());
        }
        tag.put("workspaces", list);
        return tag;
    }

    public static WorkspaceManager load(CompoundTag tag) {
        WorkspaceManager manager = new WorkspaceManager();
        ListTag list = tag.getList("workspaces", Tag.TAG_COMPOUND);
        int skipped = 0;
        for (int i = 0; i < list.size(); i++) {
            try {
                Workspace ws = Workspace.load(list.getCompound(i));
                manager.workspacesById.put(ws.getId(), ws);
                manager.workspacesByName.put(ws.getName(), ws);
                manager.indexWorkspace(ws);
            } catch (Exception e) {
                skipped++;
                RedstoneAI.LOGGER.error("[RedstoneAI] Skipped corrupted workspace at index {}", i, e);
            }
        }
        RedstoneAI.LOGGER.info("[RedstoneAI] Loaded {} workspace(s){}",
                manager.workspacesById.size(),
                skipped > 0 ? " (" + skipped + " skipped due to corruption)" : "");
        return manager;
    }

    private void indexWorkspace(Workspace workspace) {
        BoundingBox bounds = workspace.getBounds();
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = ChunkPos.asLong(chunkX, chunkZ);
                workspacesByChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(workspace);
            }
        }
    }

    private void unindexWorkspace(Workspace workspace) {
        Iterator<Map.Entry<Long, List<Workspace>>> iterator = workspacesByChunk.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, List<Workspace>> entry = iterator.next();
            entry.getValue().removeIf(existing -> existing.getId().equals(workspace.getId()));
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void attachLevel(ServerLevel level) {
        this.ownerLevel = level;
    }

    private void syncBoundaries() {
        if (ownerLevel != null) {
            WorkspaceBoundarySyncPacket.sync(ownerLevel);
        }
    }

    public record WorkspaceView(String name, BoundingBox bounds, boolean frozen) {}
}
