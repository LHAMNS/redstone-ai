package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.mcr.MCRBlock;
import com.redstoneai.mcr.MCRParser;
import com.redstoneai.mcr.MCRPlacer;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Optional;

public class BuildHandler {

    public JsonElement buildMcr(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        enforceAiMutationAllowed(workspace);
        if (!workspace.getTemporalState().canBuild()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Cannot build in " + workspace.getTemporalState().getSerializedName() + " state — discard future or revert first");
        }
        String mcr = req.getStringParam("mcr");

        ServerLevel level = server.overworld();
        try {
            List<MCRBlock> blocks = MCRParser.parse(mcr);
            MCRPlacer.PlaceResult result = MCRPlacer.place(level, workspace, blocks);
            TickController.invalidateRecording(level, workspace, com.redstoneai.workspace.WorkspaceMutationSource.BUILD);

            JsonObject response = new JsonObject();
            response.addProperty("placed", result.placed());
            response.addProperty("skipped", result.skipped());
            return response;
        } catch (MCRParser.MCRParseException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "MCR parse error: " + e.getMessage());
        }
    }

    public JsonElement buildBlock(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        enforceAiMutationAllowed(workspace);
        if (!workspace.getTemporalState().canBuild()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Cannot build in " + workspace.getTemporalState().getSerializedName() + " state — discard future or revert first");
        }

        String blockId = req.getStringParam("block");
        int x = req.getIntParam("x");
        int y = req.getIntParam("y");
        int z = req.getIntParam("z");

        ResourceLocation resourceLocation = ResourceLocation.tryParse(blockId);
        if (resourceLocation == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid block id: " + blockId);
        }

        Block block = BuiltInRegistries.BLOCK.getOptional(resourceLocation)
                .orElseThrow(() -> new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Unknown block: " + blockId));

        ServerLevel level = server.overworld();
        BlockPos worldPos = workspace.toWorldPos(x, y, z);
        if (!WorkspaceRules.isEditableBlock(workspace, worldPos)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Position out of bounds or reserved for the controller");
        }

        BlockState state = block.defaultBlockState();
        JsonObject requestedProperties = null;
        if (req.params() != null && req.params().has("properties")) {
            if (!req.params().get("properties").isJsonObject()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "properties must be a JSON object");
            }
            requestedProperties = req.params().getAsJsonObject("properties");
            state = applyRequestedProperties(state, requestedProperties);
        }
        boolean changed = level.setBlock(worldPos, state, 3);
        BlockState actual = level.getBlockState(worldPos);
        if (!placementSatisfied(actual, state, requestedProperties)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Block placement did not persist with the requested state at " + x + "," + y + "," + z);
        }
        TickController.invalidateRecording(level, workspace, com.redstoneai.workspace.WorkspaceMutationSource.BUILD);

        JsonObject result = new JsonObject();
        result.addProperty("placed", blockId);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("changed", changed);
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

    private void enforceAiMutationAllowed(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.canAiModify(workspace)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + workspace.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
    }

    private BlockState applyRequestedProperties(BlockState state, JsonElement propertiesElement) throws JsonRpcException {
        if (!propertiesElement.isJsonObject()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "properties must be a JSON object");
        }

        BlockState updated = state;
        JsonObject properties = propertiesElement.getAsJsonObject();
        for (var entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            Property<?> property = updated.getBlock().getStateDefinition().getProperty(propertyName);
            if (property == null) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "Block does not support property '" + propertyName + "'");
            }
            if (!entry.getValue().isJsonPrimitive()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "Property '" + propertyName + "' must be a primitive value");
            }
            updated = applyPropertyValue(updated, property, entry.getValue().getAsString());
        }
        return updated;
    }

    private boolean placementSatisfied(BlockState actual, BlockState requestedState, JsonObject requestedProperties) {
        if (!actual.is(requestedState.getBlock())) {
            return false;
        }
        if (requestedProperties == null) {
            return true;
        }
        for (String propertyName : requestedProperties.keySet()) {
            Property<?> property = actual.getBlock().getStateDefinition().getProperty(propertyName);
            if (property == null) {
                return false;
            }
            if (!actual.getValue(property).equals(requestedState.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState applyPropertyValue(BlockState state, Property property, String rawValue) throws JsonRpcException {
        Optional parsed = property.getValue(rawValue);
        if (parsed.isEmpty()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Invalid value '" + rawValue + "' for property '" + property.getName() + "'");
        }
        return state.setValue(property, (Comparable) parsed.get());
    }
}
