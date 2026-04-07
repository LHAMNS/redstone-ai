package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.workspace.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;

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

        BlockPos worldPos = ws.toWorldPos(x, y, z);
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

        BlockPos worldPos = ws.toWorldPos(x, y, z);
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
            BlockPos rel = ws.toRelativePos(m.pos());
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
        for (var entry : WorkspaceSignalController.readSignals(level, ws, null).entrySet()) {
            result.addProperty(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public JsonElement drive(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        if (!WorkspaceRules.canAiModify(ws)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + ws.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
        ServerLevel level = server.overworld();
        Map<String, Integer> inputs = parseInputValues(req);
        try {
            WorkspaceSignalController.setInputs(level, ws, inputs);
        } catch (IllegalArgumentException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, e.getMessage());
        }

        JsonObject result = new JsonObject();
        result.addProperty("driven", inputs.size());
        JsonObject status = new JsonObject();
        for (var entry : WorkspaceSignalController.readSignals(level, ws, null).entrySet()) {
            status.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("status", status);
        return result;
    }

    public JsonElement clearInputs(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        if (!WorkspaceRules.canAiModify(ws)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + ws.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
        ServerLevel level = server.overworld();
        WorkspaceSignalController.clearInputs(level, ws);

        JsonObject result = new JsonObject();
        result.addProperty("clearedInputs", true);
        JsonObject status = new JsonObject();
        for (var entry : WorkspaceSignalController.readSignals(level, ws, null).entrySet()) {
            status.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("status", status);
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

    private Map<String, Integer> parseInputValues(JsonRpcRequest req) throws JsonRpcException {
        JsonObject params = req.params() != null ? req.params() : new JsonObject();
        Map<String, Integer> inputs = new LinkedHashMap<>();
        if (params.has("label")) {
            String label = req.getStringParam("label");
            int power = req.getIntParam("power", 15);
            validatePower(label, power);
            inputs.put(label, power);
            return inputs;
        }
        if (!params.has("values") || !params.get("values").isJsonObject()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Provide either label/power or a values object");
        }
        JsonObject values = params.getAsJsonObject("values");
        for (var entry : values.entrySet()) {
            try {
                int power = entry.getValue().getAsInt();
                validatePower(entry.getKey(), power);
                inputs.put(entry.getKey(), power);
            } catch (RuntimeException e) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "Input value for '" + entry.getKey() + "' must be an integer");
            }
        }
        if (inputs.isEmpty()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "values must contain at least one input");
        }
        return inputs;
    }

    private void validatePower(String label, int power) throws JsonRpcException {
        if (power < 0 || power > 15) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Input value for '" + label + "' must be between 0 and 15");
        }
    }
}
