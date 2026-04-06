package com.redstoneai.network.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.redstoneai.RedstoneAI;
import com.redstoneai.network.rpc.JsonRpcDispatcher;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.network.rpc.JsonRpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles incoming WebSocket text frames: parses JSON-RPC, dispatches to handler,
 * and sends the response back.
 */
public class WSFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final int MAX_TEXT_FRAME_LENGTH = 65536;
    private static final String SERVER_BUSY_MESSAGE = "Server busy: another request is still being processed";
    private final JsonRpcDispatcher dispatcher;
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);

    public WSFrameHandler(JsonRpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    JsonRpcResponse.error(null, JsonRpcException.INVALID_REQUEST, "Only text frames supported")));
            return;
        }

        String text = textFrame.text();
        if (text.length() > MAX_TEXT_FRAME_LENGTH) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    JsonRpcResponse.error(null, JsonRpcException.INVALID_REQUEST, "Request too large")));
            return;
        }

        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonRpcRequest request = JsonRpcRequest.parse(json);
            if (!requestInFlight.compareAndSet(false, true)) {
                sendResponse(ctx, JsonRpcResponse.error(request.id(), JsonRpcException.INTERNAL_ERROR, SERVER_BUSY_MESSAGE));
                return;
            }

            try {
                dispatcher.dispatchAsync(request).whenComplete((responseJson, throwable) -> {
                    String payload = responseJson;
                    if (throwable != null) {
                        RedstoneAI.LOGGER.error("[RedstoneAI] WebSocket RPC dispatch failed", throwable);
                        payload = JsonRpcResponse.error(request.id(), JsonRpcException.INTERNAL_ERROR,
                                "Internal error: " + (throwable.getMessage() != null ? throwable.getMessage() : "unknown"));
                    }

                    String finalPayload = payload;
                    try {
                        ctx.executor().execute(() ->
                                ctx.writeAndFlush(new TextWebSocketFrame(finalPayload))
                                        .addListener(future -> requestInFlight.set(false)));
                    } catch (Exception e) {
                        requestInFlight.set(false);
                        RedstoneAI.LOGGER.error("[RedstoneAI] Failed to schedule RPC response write", e);
                        ctx.close();
                    }
                });
            } catch (Exception e) {
                requestInFlight.set(false);
                throw e;
            }
            return;
        } catch (JsonSyntaxException | IllegalStateException e) {
            sendResponse(ctx, JsonRpcResponse.error(null, JsonRpcException.PARSE_ERROR, "Parse error: " + e.getMessage()));
            return;
        } catch (JsonRpcException e) {
            sendResponse(ctx, JsonRpcResponse.error(null, e));
            return;
        } catch (Exception e) {
            RedstoneAI.LOGGER.error("[RedstoneAI] WebSocket handler error", e);
            sendResponse(ctx, JsonRpcResponse.error(null, JsonRpcException.INTERNAL_ERROR, "Internal error"));
            return;
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            RedstoneAI.LOGGER.info("[RedstoneAI] WebSocket client connected: {}", ctx.channel().remoteAddress());
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        SessionManager.remove(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        boolean wasTracked = SessionManager.contains(ctx.channel());
        SessionManager.remove(ctx.channel());
        if (wasTracked) {
            RedstoneAI.LOGGER.info("[RedstoneAI] WebSocket client disconnected: {}", ctx.channel().remoteAddress());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        RedstoneAI.LOGGER.error("[RedstoneAI] WebSocket error from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    private static void sendResponse(ChannelHandlerContext ctx, String responseJson) {
        ctx.channel().writeAndFlush(new TextWebSocketFrame(responseJson));
    }
}
