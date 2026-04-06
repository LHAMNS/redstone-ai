package com.redstoneai.network.websocket;

import io.netty.channel.Channel;

/**
 * Tracks connected WebSocket clients.
 *
 * This bridge is intentionally single-client. Keeping the active channel in one
 * place makes it easier to enforce that only one authenticated MCP session can
 * control the server at a time.
 */
public final class SessionManager {
    private static Channel activeChannel;

    private SessionManager() {}

    public static synchronized boolean tryAdd(Channel channel) {
        if (activeChannel != null) {
            if (activeChannel == channel) {
                return true;
            }
            if (activeChannel.isActive()) {
                return false;
            }
            activeChannel = null;
        }
        if (!channel.isActive()) {
            return false;
        }
        activeChannel = channel;
        return true;
    }

    public static synchronized void remove(Channel channel) {
        if (activeChannel == channel) {
            activeChannel = null;
        }
    }

    public static synchronized boolean contains(Channel channel) {
        return activeChannel == channel && channel.isActive();
    }

    public static synchronized int getConnectionCount() {
        return activeChannel != null && activeChannel.isActive() ? 1 : 0;
    }

    public static synchronized void clear() {
        activeChannel = null;
    }

    public static void closeAndClear() {
        Channel channel;
        synchronized (SessionManager.class) {
            channel = activeChannel;
            activeChannel = null;
        }
        if (channel != null && channel.isOpen()) {
            channel.close().syncUninterruptibly();
        }
    }

    public static void broadcast(String message) {
        Channel channel;
        synchronized (SessionManager.class) {
            channel = activeChannel;
        }
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(message));
        }
    }
}
