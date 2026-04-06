package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.config.RAIConfig;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.UUID;

public class WorkspaceHandler {

    public JsonElement create(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("name");
        int sizeX = req.getIntParam("sizeX", 16);
        int sizeY = req.getIntParam("sizeY", 8);
        int sizeZ = req.getIntParam("sizeZ", 16);

        String nameError = WorkspaceRules.validateWorkspaceName(name);
        if (nameError != null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, nameError);
        }

        String sizeError = WorkspaceRules.validateDimensions(sizeX, sizeY, sizeZ);
        if (sizeError != null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, sizeError);
        }

        ServerLevel level = server.overworld();
        WorkspaceManager manager = WorkspaceManager.get(level);

        if (manager.hasWorkspace(name)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace '" + name + "' already exists");
        }
        if (manager.getWorkspacesOwnedBy(WorkspaceRules.API_OWNER).size() >= RAIConfig.SERVER.maxWorkspacesPerPlayer.get()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Too many API-managed workspaces");
        }

        BlockPos origin = findAvailableOrigin(level, manager, sizeX, sizeY, sizeZ);
        BoundingBox bounds = new BoundingBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sizeX - 1,
                origin.getY() + sizeY - 1,
                origin.getZ() + sizeZ - 1
        );

        level.setBlock(origin, com.redstoneai.registry.RAIBlocks.WORKSPACE_CONTROLLER.get().defaultBlockState(), 3);

        Workspace workspace = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, name, origin, bounds);
        manager.addWorkspace(workspace);

        if (level.getBlockEntity(origin) instanceof WorkspaceControllerBlockEntity controller) {
            controller.setWorkspaceName(name);
            controller.setSize(sizeX, sizeY, sizeZ);
            controller.setInitialSnapshot(InitialSnapshot.capture(level, bounds));
            controller.getOperationLog().logSystem("create", "RPC workspace '" + name + "' created");
        }

        JsonObject result = new JsonObject();
        result.addProperty("workspace", name);
        result.add("origin", toIntArray(origin));
        result.add("size", toSizeArray(sizeX, sizeY, sizeZ));
        return result;
    }

    public JsonElement delete(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        ServerLevel level = server.overworld();
        WorkspaceManager manager = WorkspaceManager.get(level);

        if (workspace.isFrozen()) {
            TickController.discardFrozenState(level, workspace);
        }
        TickController.removeQueue(workspace.getId());

        if (level.getBlockEntity(workspace.getControllerPos()) instanceof WorkspaceControllerBlockEntity controller) {
            controller.setWorkspaceName("");
            controller.setInitialSnapshot(null);
        }

        manager.removeWorkspace(workspace.getName());

        JsonObject result = new JsonObject();
        result.addProperty("deleted", workspace.getName());
        return result;
    }

    public JsonElement list(JsonRpcRequest req, MinecraftServer server) {
        ServerLevel level = server.overworld();
        WorkspaceManager manager = WorkspaceManager.get(level);

        JsonArray workspaces = new JsonArray();
        for (Workspace workspace : manager.getAllWorkspacesSnapshot()) {
            if (!WorkspaceRules.API_OWNER.equals(workspace.getOwnerUUID())) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("name", workspace.getName());
            obj.addProperty("size", workspace.getSizeX() + "x" + workspace.getSizeY() + "x" + workspace.getSizeZ());
            obj.addProperty("frozen", workspace.isFrozen());
            obj.addProperty("mode", workspace.getProtectionMode().getSerializedName());
            workspaces.add(obj);
        }

        JsonObject result = new JsonObject();
        result.add("workspaces", workspaces);
        return result;
    }

    public JsonElement info(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);

        JsonObject result = new JsonObject();
        result.addProperty("name", workspace.getName());
        result.addProperty("frozen", workspace.isFrozen());
        result.addProperty("mode", workspace.getProtectionMode().getSerializedName());
        result.addProperty("virtualTick", workspace.getVirtualTick());
        result.addProperty("ioMarkers", workspace.getIOMarkers().size());
        if (workspace.getTimeline() != null) {
            result.addProperty("recordingLength", workspace.getTimeline().getLength());
            result.addProperty("recordingPosition", workspace.getTimeline().getCurrentIndex() + 1);
        }
        result.add("origin", toIntArray(workspace.getControllerPos()));
        result.add("size", toSizeArray(workspace.getSizeX(), workspace.getSizeY(), workspace.getSizeZ()));
        return result;
    }

    public JsonElement clear(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace, "clear");

        ServerLevel level = server.overworld();
        BoundingBox bounds = workspace.getBounds();
        int cleared = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!WorkspaceRules.isEditableBlock(workspace, pos)) {
                        continue;
                    }
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        cleared++;
                    }
                }
            }
        }

        TickController.invalidateRecording(level, workspace);

        JsonObject result = new JsonObject();
        result.addProperty("cleared", cleared);
        return result;
    }

    public JsonElement setMode(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        ProtectionMode mode = ProtectionMode.tryParse(req.getStringParam("mode"));
        if (mode == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid protection mode");
        }

        workspace.setProtectionMode(mode);
        WorkspaceManager.get(server.overworld()).setDirty();

        JsonObject result = new JsonObject();
        result.addProperty("mode", workspace.getProtectionMode().getSerializedName());
        return result;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("name");
        ServerLevel level = server.overworld();
        Workspace workspace = WorkspaceManager.get(level).getByName(name);
        if (workspace == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace not found: " + name);
        }
        return workspace;
    }

    private void enforceAiMutationAllowed(Workspace workspace, String operation) throws JsonRpcException {
        if (!WorkspaceRules.canAiModify(workspace)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + workspace.getProtectionMode().getSerializedName()
                            + "' does not allow AI to " + operation);
        }
    }

    private void requireApiOwned(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.API_OWNER.equals(workspace.getOwnerUUID())) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "This RPC operation is only allowed on API-owned workspaces");
        }
    }

    private static BlockPos findAvailableOrigin(ServerLevel level, WorkspaceManager manager,
                                                int sizeX, int sizeY, int sizeZ) throws JsonRpcException {
        int spacing = Math.max(96, sizeX + 16);
        for (int attempt = 0; attempt < 128; attempt++) {
            BlockPos origin = new BlockPos(1000 + attempt * spacing, 64, 1000);
            BoundingBox bounds = new BoundingBox(
                    origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + sizeX - 1,
                    origin.getY() + sizeY - 1,
                    origin.getZ() + sizeZ - 1
            );
            if (manager.checkOverlap(bounds) == null) {
                return origin;
            }
        }
        throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR, "Unable to allocate workspace location");
    }

    private static JsonArray toIntArray(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static JsonArray toSizeArray(int sizeX, int sizeY, int sizeZ) {
        JsonArray size = new JsonArray();
        size.add(sizeX);
        size.add(sizeY);
        size.add(sizeZ);
        return size;
    }
}
