package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class IOHandler {

    public JsonElement mark(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        if (!WorkspaceRules.canAiModify(ws)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + ws.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
        ServerLevel level = server.overworld();
        int x = req.getIntParam("x");
        int y = req.getIntParam("y");
        int z = req.getIntParam("z");
        String role = req.getStringParam("role");
        String label = req.getStringParam("label");

        BlockPos worldPos = ws.getControllerPos().offset(x, y, z);
        if (ws.isControllerPos(worldPos)) {
            throw new JsonRpcException(-32602, "Position out of bounds or is the controller block");
        }
        if (!WorkspaceRules.isEditableBlock(ws, worldPos)) {
            throw new JsonRpcException(-32602, "Position out of bounds or is the controller block");
        }

        IOMarker.IORole parsedRole = IOMarker.IORole.tryParse(role);
        if (parsedRole == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid IO role");
        }

        ws.addIOMarker(new IOMarker(worldPos, parsedRole, label));
        WorkspaceManager.get(level).setDirty();

        JsonObject result = new JsonObject();
        result.addProperty("marked", label);
        result.addProperty("role", role);
        return result;
    }

    public JsonElement unmark(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        if (!WorkspaceRules.canAiModify(ws)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + ws.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
        ServerLevel level = server.overworld();
        int x = req.getIntParam("x");
        int y = req.getIntParam("y");
        int z = req.getIntParam("z");

        BlockPos worldPos = ws.getControllerPos().offset(x, y, z);
        if (ws.isControllerPos(worldPos)) {
            throw new JsonRpcException(-32602, "Position out of bounds or is the controller block");
        }
        if (!WorkspaceRules.isEditableBlock(ws, worldPos)) {
            throw new JsonRpcException(-32602, "Position out of bounds or is the controller block");
        }
        ws.removeIOMarker(worldPos);
        WorkspaceManager.get(level).setDirty();

        JsonObject result = new JsonObject();
        result.addProperty("unmarked", true);
        return result;
    }

    public JsonElement list(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        JsonArray arr = new JsonArray();
        for (IOMarker m : ws.getIOMarkers()) {
            if (ws.isControllerPos(m.pos())) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("label", m.label());
            obj.addProperty("role", m.role().getSerializedName());
            BlockPos rel = m.pos().subtract(ws.getControllerPos());
            obj.addProperty("x", rel.getX());
            obj.addProperty("y", rel.getY());
            obj.addProperty("z", rel.getZ());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.add("markers", arr);
        return result;
    }

    public JsonElement status(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        ServerLevel level = server.overworld();

        JsonObject result = new JsonObject();
        for (IOMarker m : ws.getIOMarkers()) {
            if (ws.isControllerPos(m.pos())) {
                continue;
            }
            int power = level.getBestNeighborSignal(m.pos());
            result.addProperty(m.label(), power);
        }
        return result;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("workspace");
        ServerLevel level = server.overworld();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) throw new JsonRpcException(-32602, "Workspace not found: " + name);
        return ws;
    }

    private void requireApiOwned(Workspace ws) throws JsonRpcException {
        if (!WorkspaceRules.API_OWNER.equals(ws.getOwnerUUID())) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "RPC IO operations are only allowed on API-owned workspaces");
        }
    }
}
