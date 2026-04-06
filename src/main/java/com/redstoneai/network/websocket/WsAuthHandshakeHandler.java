package com.redstoneai.network.websocket;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * Validates the shared bearer token before the WebSocket upgrade is accepted.
 */
public class WsAuthHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String expectedAuthorizationHeader;

    public WsAuthHandshakeHandler(String token) {
        this.expectedAuthorizationHeader = RpcAuthManager.toBearerToken(token);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!isWebSocketUpgradeRequest(request)) {
            sendAndClose(ctx, HttpResponseStatus.BAD_REQUEST, "WebSocket upgrade required");
            return;
        }

        String authorization = request.headers().get(RpcAuthManager.AUTH_HEADER);
        if (!expectedAuthorizationHeader.equals(authorization)) {
            sendAndClose(ctx, HttpResponseStatus.UNAUTHORIZED, "Missing or invalid RedstoneAI auth token");
            return;
        }

        if (!SessionManager.tryAdd(ctx.channel())) {
            sendAndClose(ctx, HttpResponseStatus.TOO_MANY_REQUESTS,
                    "RedstoneAI already has an authenticated MCP client");
            return;
        }

        ctx.fireChannelRead(request.retain());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        SessionManager.remove(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SessionManager.remove(ctx.channel());
        super.channelInactive(ctx);
    }

    private static boolean isWebSocketUpgradeRequest(FullHttpRequest request) {
        return request.decoderResult().isSuccess()
                && HttpMethod.GET.equals(request.method())
                && request.headers().contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true)
                && request.headers().contains(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true);
    }

    private static void sendAndClose(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(message, CharsetUtil.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
