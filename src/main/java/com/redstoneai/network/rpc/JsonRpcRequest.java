package com.redstoneai.network.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * A parsed JSON-RPC 2.0 request.
 */
public record JsonRpcRequest(
        @Nullable String id,
        String method,
        @Nullable JsonObject params
) {
    public static JsonRpcRequest parse(JsonObject json) throws JsonRpcException {
        String jsonrpc = json.has("jsonrpc") ? json.get("jsonrpc").getAsString() : null;
        if (!"2.0".equals(jsonrpc)) {
            throw new JsonRpcException(-32600, "Invalid JSON-RPC version");
        }

        String id = null;
        if (json.has("id")) {
            JsonElement idElem = json.get("id");
            if (idElem.isJsonPrimitive()) {
                id = idElem.getAsString();
            }
        }

        if (!json.has("method") || !json.get("method").isJsonPrimitive()) {
            throw new JsonRpcException(-32600, "Missing or invalid 'method'");
        }
        String method = json.get("method").getAsString();

        JsonObject params = null;
        if (json.has("params") && json.get("params").isJsonObject()) {
            params = json.getAsJsonObject("params");
        }

        return new JsonRpcRequest(id, method, params);
    }

    public String getStringParam(String key) throws JsonRpcException {
        if (params == null || !params.has(key)) throw new JsonRpcException(-32602, "Missing param: " + key);
        JsonElement element = params.get(key);
        if (!element.isJsonPrimitive()) {
            throw new JsonRpcException(-32602, "Param '" + key + "' must be a string");
        }
        return element.getAsString();
    }

    public String getStringParam(String key, String defaultValue) {
        if (params == null || !params.has(key)) return defaultValue;
        JsonElement element = params.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : defaultValue;
    }

    public int getIntParam(String key) throws JsonRpcException {
        if (params == null || !params.has(key)) throw new JsonRpcException(-32602, "Missing param: " + key);
        JsonElement element = params.get(key);
        if (!element.isJsonPrimitive()) {
            throw new JsonRpcException(-32602, "Param '" + key + "' must be an integer");
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new JsonRpcException(-32602, "Param '" + key + "' must be an integer");
        }
    }

    public int getIntParam(String key, int defaultValue) {
        if (params == null || !params.has(key)) return defaultValue;
        JsonElement element = params.get(key);
        if (!element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return defaultValue;
        }
    }

    public double getDoubleParam(String key) throws JsonRpcException {
        if (params == null || !params.has(key)) throw new JsonRpcException(-32602, "Missing param: " + key);
        JsonElement element = params.get(key);
        if (!element.isJsonPrimitive()) {
            throw new JsonRpcException(-32602, "Param '" + key + "' must be a number");
        }
        try {
            return element.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new JsonRpcException(-32602, "Param '" + key + "' must be a number");
        }
    }

    public double getDoubleParam(String key, double defaultValue) {
        if (params == null || !params.has(key)) return defaultValue;
        JsonElement element = params.get(key);
        if (!element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return defaultValue;
        }
    }
}
