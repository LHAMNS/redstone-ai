package com.redstoneai.network.websocket;

import com.redstoneai.network.rpc.JsonRpcDispatcher;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Initializes the Netty pipeline for WebSocket connections:
 * HTTP codec -> aggregator -> WS protocol handler -> our frame handler.
 */
public class WSInitializer extends ChannelInitializer<SocketChannel> {
    private final JsonRpcDispatcher dispatcher;
    private final String authToken;

    public WSInitializer(JsonRpcDispatcher dispatcher, String authToken) {
        this.dispatcher = dispatcher;
        this.authToken = authToken;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        pipeline.addLast(new WsAuthHandshakeHandler(authToken));
        pipeline.addLast(new WebSocketServerProtocolHandler("/", null, true));
        pipeline.addLast(new WSFrameHandler(dispatcher));
    }
}
