package com.redstoneai.network.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcResponseTest {

    @Test
    void successResponse() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        String json = JsonRpcResponse.success("1", result);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("2.0", parsed.get("jsonrpc").getAsString());
        assertEquals("1", parsed.get("id").getAsString());
        assertTrue(parsed.has("result"));
        assertEquals("ok", parsed.getAsJsonObject("result").get("status").getAsString());
    }

    @Test
    void errorResponse() {
        String json = JsonRpcResponse.error("2", -32602, "Invalid params");

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("2.0", parsed.get("jsonrpc").getAsString());
        assertEquals("2", parsed.get("id").getAsString());
        assertTrue(parsed.has("error"));
        assertEquals(-32602, parsed.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("Invalid params", parsed.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void errorResponseFromException() {
        JsonRpcException ex = new JsonRpcException(-32601, "Method not found");
        String json = JsonRpcResponse.error("3", ex);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(-32601, parsed.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void nullIdOmitted() {
        String json = JsonRpcResponse.success(null, new JsonObject());
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(parsed.has("id"));
    }
}
