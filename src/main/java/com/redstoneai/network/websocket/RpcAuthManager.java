package com.redstoneai.network.websocket;

import com.redstoneai.RedstoneAI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * Manages the shared token used by the local MCP bridge to authenticate to the
 * in-process WebSocket RPC server.
 */
public final class RpcAuthManager {
    public static final String AUTH_HEADER = "Authorization";
    private static final String TOKEN_FILE = "redstone_ai_mcp_token.txt";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static String cachedToken;

    private RpcAuthManager() {}

    public static synchronized String getOrCreateToken() {
        if (cachedToken != null) {
            return cachedToken;
        }

        Path tokenPath = getTokenPath();
        try {
            Files.createDirectories(tokenPath.getParent());
            if (Files.exists(tokenPath)) {
                cachedToken = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
            }
            if (cachedToken == null || cachedToken.isBlank()) {
                cachedToken = generateToken();
                Files.writeString(tokenPath, cachedToken + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            return cachedToken;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize RPC auth token at " + tokenPath, e);
        }
    }

    public static Path getTokenPath() {
        return Path.of("config", TOKEN_FILE).toAbsolutePath().normalize();
    }

    public static String toBearerToken(String token) {
        return "Bearer " + token;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
