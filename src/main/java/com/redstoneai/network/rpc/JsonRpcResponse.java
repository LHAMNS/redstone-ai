package com.redstoneai.network.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * JSON-RPC 2.0 response builder.
 */
public final class JsonRpcResponse {

    private JsonRpcResponse() {}

    public static String success(@Nullable String id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) response.addProperty("id", id);
        response.add("result", result);
        return response.toString();
    }

    public static String error(@Nullable String id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) response.addProperty("id", id);
        JsonObject errorObj = new JsonObject();
        errorObj.addProperty("code", code);
        errorObj.addProperty("message", message);
        response.add("error", errorObj);
        return response.toString();
    }

    public static String error(@Nullable String id, JsonRpcException ex) {
        return error(id, ex.getCode(), ex.getMessage());
    }
}
