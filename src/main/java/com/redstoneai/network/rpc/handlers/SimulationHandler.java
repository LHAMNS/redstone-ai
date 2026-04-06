package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.recording.RecordingSummarizer;
import com.redstoneai.recording.RecordingTimeline;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class SimulationHandler {

    public JsonElement freeze(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        enforceAiMutationAllowed(ws, "freeze");
        ServerLevel level = server.overworld();
        if (ws.isFrozen()) throw new JsonRpcException(-32602, "Already frozen");
        TickController.freeze(level, ws);

        JsonObject result = new JsonObject();
        result.addProperty("frozen", true);
        return result;
    }

    public JsonElement unfreeze(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        enforceAiMutationAllowed(ws, "unfreeze");
        ServerLevel level = server.overworld();
        if (!ws.isFrozen()) throw new JsonRpcException(-32602, "Not frozen");
        TickController.unfreeze(level, ws);

        JsonObject result = new JsonObject();
        result.addProperty("frozen", false);
        return result;
    }

    public JsonElement step(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        enforceAiMutationAllowed(ws, "step");
        ServerLevel level = server.overworld();
        if (!ws.isFrozen()) throw new JsonRpcException(-32602, "Must be frozen to step");

        int count = req.getIntParam("count", 1);
        if (!WorkspaceRules.isValidTickCount(count)) throw new JsonRpcException(-32602, "Count exceeds configured maximum");
        int stepped = TickController.step(level, ws, count);

        JsonObject result = new JsonObject();
        result.addProperty("stepped", stepped);
        result.addProperty("virtualTick", ws.getVirtualTick());
        result.addProperty("summary", RecordingSummarizer.level1(ws));
        return result;
    }

    public JsonElement rewind(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        enforceAiMutationAllowed(ws, "rewind");
        ServerLevel level = server.overworld();
        if (!ws.isFrozen()) throw new JsonRpcException(-32602, "Must be frozen");

        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) throw new JsonRpcException(-32602, "No recording");

        int count = req.getIntParam("count", 1);
        if (!WorkspaceRules.isValidTickCount(count)) throw new JsonRpcException(-32602, "Count exceeds configured maximum");
        if (!timeline.canRewind(count)) throw new JsonRpcException(-32602, "Cannot rewind " + count + " ticks");

        int rewound = TickController.rewind(level, ws, count);

        JsonObject result = new JsonObject();
        result.addProperty("rewound", rewound);
        result.addProperty("virtualTick", ws.getVirtualTick());
        return result;
    }

    public JsonElement fastForward(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        enforceAiMutationAllowed(ws, "fast-forward");
        ServerLevel level = server.overworld();
        if (!ws.isFrozen()) throw new JsonRpcException(-32602, "Must be frozen");

        int count = req.getIntParam("count", 1);
        if (!WorkspaceRules.isValidTickCount(count)) throw new JsonRpcException(-32602, "Count exceeds configured maximum");
        RecordingTimeline timeline = ws.getTimeline();

        int advanced = TickController.replayThenStep(level, ws, count);
        JsonObject result = new JsonObject();
        result.addProperty("advanced", advanced);
        result.addProperty("virtualTick", ws.getVirtualTick());
        return result;
    }

    public JsonElement summary(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        JsonObject result = new JsonObject();
        result.addProperty("summary", RecordingSummarizer.level1(ws));
        return result;
    }

    public JsonElement timing(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        int from = req.getIntParam("from", 0);
        int to = req.getIntParam("to", -1);
        JsonObject result = new JsonObject();
        result.addProperty("timing", RecordingSummarizer.level2(ws, from, to));
        return result;
    }

    public JsonElement detail(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        requireApiOwned(ws);
        int from = req.getIntParam("from", 0);
        int to = req.getIntParam("to", -1);
        JsonObject result = new JsonObject();
        result.addProperty("detail", RecordingSummarizer.level3(ws, from, to));
        return result;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("workspace");
        ServerLevel level = server.overworld();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) throw new JsonRpcException(-32602, "Workspace not found: " + name);
        return ws;
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
}
