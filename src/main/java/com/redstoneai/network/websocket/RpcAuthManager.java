package com.redstoneai.network.websocket;

import com.redstoneai.RedstoneAI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;

/**
 * Manages the shared token used by the local MCP bridge to authenticate to the
 * in-process WebSocket RPC server. Token is regenerated on every server start
 * to limit the window of a leaked token.
 */
public final class RpcAuthManager {
    public static final String AUTH_HEADER = "Authorization";
    private static final String TOKEN_FILE = "redstone_ai_mcp_token.txt";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static String cachedToken;

    private RpcAuthManager() {}

    /**
     * Generate a fresh token on every server start. Old tokens are invalidated.
     */
    public static synchronized String getOrCreateToken() {
        // Always regenerate on each server start for security
        cachedToken = generateToken();
        Path tokenPath = getTokenPath();
        try {
            Files.createDirectories(tokenPath.getParent());
            Files.writeString(tokenPath, cachedToken + System.lineSeparator(), StandardCharsets.UTF_8);
            trySetOwnerOnlyPermissions(tokenPath);
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

    /**
     * Attempt to restrict the token file to owner-only read/write (chmod 600).
     * Silently ignored on Windows where POSIX permissions are not supported.
     */
    private static void trySetOwnerOnlyPermissions(Path path) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or other non-POSIX FS — best effort
        }
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
