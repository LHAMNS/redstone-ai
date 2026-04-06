package com.redstoneai.network.websocket;

import com.redstoneai.RedstoneAI;
import com.redstoneai.network.rpc.JsonRpcDispatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import net.minecraft.server.MinecraftServer;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Netty-based WebSocket server for JSON-RPC communication with the Python MCP server.
 * Uses a separate EventLoopGroup from Minecraft's to avoid interference.
 * Started on ServerStartedEvent, stopped on ServerStoppingEvent.
 */
public class WebSocketServer {
    // Read from config at start(). Default 0.0.0.0 for WSL compat; users can set 127.0.0.1 for local-only.
    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static Channel serverChannel;
    private static JsonRpcDispatcher dispatcher;

    public static synchronized void start(MinecraftServer server, int port, String bindHost) {
        if (isRunning()) {
            RedstoneAI.LOGGER.warn("[RedstoneAI] WebSocket server already running");
            return;
        }
        if (serverChannel != null || bossGroup != null || workerGroup != null) {
            stop();
        }

        try {
            dispatcher = new JsonRpcDispatcher(server);
            String authToken = RpcAuthManager.getOrCreateToken();
            String requestedBindHost = bindHost == null ? "" : bindHost.trim();
            String resolvedBindHost = resolveBindHost(requestedBindHost);
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(2);
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new WSInitializer(dispatcher, authToken));

            serverChannel = bootstrap.bind(resolvedBindHost, port).sync().channel();
            if (!requestedBindHost.isEmpty() && !resolvedBindHost.equals(requestedBindHost)) {
                RedstoneAI.LOGGER.warn("[RedstoneAI] Requested WebSocket bind host '{}' was narrowed to '{}' to keep the RPC endpoint local-only",
                        requestedBindHost, resolvedBindHost);
            }
            RedstoneAI.LOGGER.info("[RedstoneAI] WebSocket server started on {}:{} (token file: {})",
                    resolvedBindHost, port, RpcAuthManager.getTokenPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            RedstoneAI.LOGGER.error("[RedstoneAI] Failed to start WebSocket server", e);
            stop();
        } catch (Exception e) {
            RedstoneAI.LOGGER.error("[RedstoneAI] Failed to bind WebSocket server on port {}", port, e);
            stop();
        }
    }

    public static synchronized void stop() {
        Channel channel = serverChannel;
        EventLoopGroup boss = bossGroup;
        EventLoopGroup worker = workerGroup;
        boolean hadState = channel != null || boss != null || worker != null || SessionManager.getConnectionCount() > 0;

        serverChannel = null;
        bossGroup = null;
        workerGroup = null;
        dispatcher = null;

        SessionManager.closeAndClear();

        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully().syncUninterruptibly();
        }
        if (worker != null) {
            worker.shutdownGracefully().syncUninterruptibly();
        }

        if (hadState) {
            RedstoneAI.LOGGER.info("[RedstoneAI] WebSocket server stopped");
        }
    }

    public static boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    private static String resolveBindHost(String bindHost) {
        if (bindHost == null || bindHost.isEmpty()) {
            return "127.0.0.1";
        }
        // Allow explicit wildcard bind (needed for WSL)
        if ("0.0.0.0".equals(bindHost) || "::".equals(bindHost)) {
            return bindHost;
        }
        try {
            InetAddress address = InetAddress.getByName(bindHost);
            if (address.isLoopbackAddress()) {
                return bindHost;
            }
        } catch (UnknownHostException ignored) {
        }
        return "127.0.0.1";
    }
}
