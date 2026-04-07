package com.redstoneai.network.rpc.handlers;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BlockEntityHandler {

    public JsonElement write(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace);

        ServerLevel level = server.overworld();
        int x = req.getIntParam("x");
        int y = req.getIntParam("y");
        int z = req.getIntParam("z");
        String nbtString = req.getStringParam("nbt", "{}");
        String mode = req.getStringParam("mode", "merge");
        boolean replace = switch (mode) {
            case "merge" -> false;
            case "replace" -> true;
            default -> throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "mode must be merge or replace");
        };

        BlockPos worldPos = workspace.toWorldPos(x, y, z);
        if (!WorkspaceRules.isEditableBlock(workspace, worldPos)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Position out of bounds or reserved for the controller");
        }

        BlockEntity blockEntity = level.getBlockEntity(worldPos);
        if (blockEntity == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "No block entity exists at the requested position");
        }

        CompoundTag patch = parseCompoundTag(nbtString);
        CompoundTag updatedTag = replace ? patch.copy() : blockEntity.saveWithoutMetadata().merge(patch.copy());
        blockEntity.load(updatedTag);
        blockEntity.setChanged();

        BlockState state = level.getBlockState(worldPos);
        level.sendBlockUpdated(worldPos, state, state, 3);
        level.updateNeighbourForOutputSignal(worldPos, state.getBlock());
        TickController.invalidateRecording(level, workspace);

        JsonObject result = new JsonObject();
        result.addProperty("updated", true);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("blockEntityType",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
        result.addProperty("nbt", blockEntity.saveWithoutMetadata().toString());
        return result;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String workspaceName = req.getStringParam("workspace");
        ServerLevel level = server.overworld();
        Workspace workspace = WorkspaceManager.get(level).getByName(workspaceName);
        if (workspace == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace not found: " + workspaceName);
        }
        return workspace;
    }

    private void requireApiOwned(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.API_OWNER.equals(workspace.getOwnerUUID())) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Block entity RPC operations are only allowed on API-owned workspaces");
        }
    }

    private void enforceAiMutationAllowed(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.canAiModify(workspace)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + workspace.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
    }

    private CompoundTag parseCompoundTag(String raw) throws JsonRpcException {
        try {
            return TagParser.parseTag(raw);
        } catch (CommandSyntaxException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid SNBT: " + e.getMessage());
        }
    }
}
