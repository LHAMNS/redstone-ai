package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.config.RAIConfig;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.test.TestCase;
import com.redstoneai.test.TestResult;
import com.redstoneai.test.TestRunner;
import com.redstoneai.test.TestSuite;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/**
 * Handles test suite operations via JSON-RPC.
 * Delegates to {@link TestRunner} for actual execution.
 */
public class TestHandler {

    public JsonElement run(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        enforceAiMutationAllowed(ws);
        ServerLevel level = server.overworld();
        JsonObject params = req.params() != null ? req.params() : new JsonObject();

        JsonObject inputs = params.has("inputs") ? params.getAsJsonObject("inputs") : new JsonObject();
        JsonObject expected = params.has("expected") ? params.getAsJsonObject("expected") : new JsonObject();
        int ticks = req.getIntParam("ticks", 10);
        validateTickCount(ticks, "Tick count exceeds configured maximum");
        validateNoControllerMarkers(ws, inputs, "input");
        validateNoControllerMarkers(ws, expected, "expected output");

        TestCase testCase = new TestCase("", toIntMap(inputs), toIntMap(expected), ticks);
        TestResult result = TestRunner.runSingle(level, ws, testCase);

        JsonObject response = new JsonObject();
        response.addProperty("pass", result.passed());
        response.add("actual", toJsonObject(result.actual()));
        response.add("expected", expected);
        response.addProperty("ticks", ticks);
        return response;
    }

    public JsonElement define(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace ws = getWorkspace(req, server);
        enforceAiMutationAllowed(ws);
        ServerLevel level = server.overworld();
        JsonObject params = req.params() != null ? req.params() : new JsonObject();

        if (!params.has("cases") || !params.get("cases").isJsonArray()) {
            throw new JsonRpcException(-32602, "Missing 'cases' array");
        }

        JsonArray casesJson = params.getAsJsonArray("cases");
        if (casesJson.size() > 128) {
            throw new JsonRpcException(-32602, "Too many test cases (max 128)");
        }
        int ticks = params.has("ticks") ? params.get("ticks").getAsInt() : 10;
        validateTickCount(ticks, "Tick count exceeds configured maximum");

        TestSuite.Builder builder = new TestSuite.Builder("rpc_suite");
        long totalTicks = 0L;
        for (JsonElement elem : casesJson) {
            JsonObject caseObj = elem.getAsJsonObject();
            JsonObject inputs = caseObj.has("inputs") ? caseObj.getAsJsonObject("inputs") : new JsonObject();
            JsonObject expected = caseObj.has("expected") ? caseObj.getAsJsonObject("expected") : new JsonObject();
            int caseTicks = caseObj.has("ticks") ? caseObj.get("ticks").getAsInt() : ticks;
            validateTickCount(caseTicks, "Test case tick count exceeds configured maximum");
            validateNoControllerMarkers(ws, inputs, "input");
            validateNoControllerMarkers(ws, expected, "expected output");
            totalTicks += caseTicks;
            validateSuiteWorkBudget(totalTicks);
            builder.addCase(new TestCase(toIntMap(inputs), toIntMap(expected), caseTicks));
        }

        List<TestResult> results;
        try {
            results = TestRunner.runSuite(level, ws, builder.build());
        } catch (IllegalArgumentException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, e.getMessage());
        }

        int passed = (int) results.stream().filter(TestResult::passed).count();
        int failed = results.size() - passed;

        JsonArray resultsArray = new JsonArray();
        for (TestResult r : results) {
            JsonObject obj = new JsonObject();
            obj.addProperty("pass", r.passed());
            obj.add("inputs", toJsonObject(r.testCase().inputs()));
            obj.add("expected", toJsonObject(r.testCase().expected()));
            obj.add("actual", toJsonObject(r.actual()));
            resultsArray.add(obj);
        }

        JsonObject response = new JsonObject();
        response.addProperty("total", results.size());
        response.addProperty("passed", passed);
        response.addProperty("failed", failed);
        response.add("results", resultsArray);
        return response;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("workspace");
        ServerLevel level = server.overworld();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) throw new JsonRpcException(-32602, "Workspace not found: " + name);
        return ws;
    }

    private void enforceAiMutationAllowed(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.canAiModify(workspace)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + workspace.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
    }

    private static void validateTickCount(int ticks, String message) throws JsonRpcException {
        if (!WorkspaceRules.isValidTickCount(ticks)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, message);
        }
    }

    private static void validateNoControllerMarkers(Workspace ws, JsonObject spec, String roleLabel) throws JsonRpcException {
        Set<String> controllerLabels = controllerMarkerLabels(ws);
        for (String label : spec.keySet()) {
            if (controllerLabels.contains(label)) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "IO marker '" + label + "' is reserved for the controller and cannot be used as an " + roleLabel);
            }
        }
    }

    private static Set<String> controllerMarkerLabels(Workspace ws) {
        Set<String> labels = new HashSet<>();
        for (IOMarker marker : ws.getIOMarkers()) {
            if (ws.isControllerPos(marker.pos())) {
                labels.add(marker.label());
            }
        }
        return labels;
    }

    private static void validateSuiteWorkBudget(long totalTicks) throws JsonRpcException {
        if (totalTicks > RAIConfig.SERVER.maxRecordingTicks.get()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Suite total tick budget exceeds configured maximum");
        }
    }

    private static Map<String, Integer> toIntMap(JsonObject obj) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (var entry : obj.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsInt());
        }
        return map;
    }

    private static JsonObject toJsonObject(Map<String, Integer> map) {
        JsonObject obj = new JsonObject();
        for (var entry : map.entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        return obj;
    }
}
