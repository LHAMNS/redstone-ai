package com.redstoneai.network.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcRequestTest {

    @Test
    void parseValidRequest() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"workspace.create\",\"params\":{\"name\":\"test\"}}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertEquals("1", req.id());
        assertEquals("workspace.create", req.method());
        assertEquals("test", req.getStringParam("name"));
    }

    @Test
    void parseRequestWithoutParams() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"status\"}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertEquals("status", req.method());
        assertNull(req.params());
    }

    @Test
    void parseRequestWithoutId() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertNull(req.id());
        assertEquals("notify", req.method());
    }

    @Test
    void rejectInvalidVersion() {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"1.0\",\"id\":\"1\",\"method\":\"test\"}"
        ).getAsJsonObject();
        assertThrows(JsonRpcException.class, () -> JsonRpcRequest.parse(json));
    }

    @Test
    void rejectMissingMethod() {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\"}"
        ).getAsJsonObject();
        assertThrows(JsonRpcException.class, () -> JsonRpcRequest.parse(json));
    }

    @Test
    void getStringParamDefault() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"test\",\"params\":{}}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertEquals("fallback", req.getStringParam("missing", "fallback"));
    }

    @Test
    void getIntParam() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"test\",\"params\":{\"count\":42}}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertEquals(42, req.getIntParam("count"));
    }

    @Test
    void getIntParamDefault() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"test\",\"params\":{}}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertEquals(10, req.getIntParam("missing", 10));
    }

    @Test
    void getMissingStringParamThrows() throws JsonRpcException {
        JsonObject json = JsonParser.parseString(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"test\",\"params\":{}}"
        ).getAsJsonObject();
        JsonRpcRequest req = JsonRpcRequest.parse(json);
        assertThrows(JsonRpcException.class, () -> req.getStringParam("required"));
    }
}
