package com.redstoneai.network;

import com.redstoneai.RedstoneAI;
import com.redstoneai.workspace.WorkspaceControllerMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Mod networking channel for GUI packets.
 */
public class RAINetwork {
    private static final String PROTOCOL_VERSION = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RedstoneAI.ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, WorkspaceActionPacket.class,
                WorkspaceActionPacket::encode,
                WorkspaceActionPacket::decode,
                WorkspaceActionPacket::handle);
        CHANNEL.registerMessage(id++, WorkspaceBoundarySyncPacket.class,
                WorkspaceBoundarySyncPacket::encode,
                WorkspaceBoundarySyncPacket::decode,
                WorkspaceBoundarySyncPacket::handle);
        CHANNEL.registerMessage(id++, WorkspaceControllerStateSyncPacket.class,
                WorkspaceControllerStateSyncPacket::encode,
                WorkspaceControllerStateSyncPacket::decode,
                WorkspaceControllerStateSyncPacket::handle);
        CHANNEL.registerMessage(id++, SelectionPreviewSyncPacket.class,
                SelectionPreviewSyncPacket::encode,
                SelectionPreviewSyncPacket::decode,
                SelectionPreviewSyncPacket::handle);
    }

    public static void writeNullableBoundingBox(FriendlyByteBuf buf, @Nullable BoundingBox bounds) {
        buf.writeBoolean(bounds != null);
        if (bounds != null) {
            buf.writeInt(bounds.minX());
            buf.writeInt(bounds.minY());
            buf.writeInt(bounds.minZ());
            buf.writeInt(bounds.maxX());
            buf.writeInt(bounds.maxY());
            buf.writeInt(bounds.maxZ());
        }
    }

    @Nullable
    public static BoundingBox readNullableBoundingBox(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return new BoundingBox(
                buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readInt(), buf.readInt(), buf.readInt()
        );
    }

    public record WorkspaceControllerStateSyncPacket(
            BlockPos controllerPos,
            String workspaceName,
            int sizeX,
            int sizeY,
            int sizeZ,
            boolean hasSnapshot,
            boolean frozen,
            int virtualTick,
            int recordingLength,
            String temporalState,
            String lastMutationSource,
            String protectionMode,
            String entityFilterMode,
            String authorizedPlayers,
            List<String> playerPermissionEntries,
            boolean allowVanillaCommands,
            boolean allowFrozenEntityTeleport,
            boolean allowFrozenEntityDamage,
            boolean allowFrozenEntityCollision,
            boolean canViewHistory,
            boolean hasWorkspaceBounds,
            int boundsMinX,
            int boundsMinY,
            int boundsMinZ,
            int boundsMaxX,
            int boundsMaxY,
            int boundsMaxZ,
            List<String> logLines,
            List<String> chatLines
    ) {
        public WorkspaceControllerStateSyncPacket {
            logLines = List.copyOf(logLines);
            chatLines = List.copyOf(chatLines);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(controllerPos);
            buf.writeUtf(workspaceName, 256);
            buf.writeInt(sizeX);
            buf.writeInt(sizeY);
            buf.writeInt(sizeZ);
            buf.writeBoolean(hasSnapshot);
            buf.writeBoolean(frozen);
            buf.writeInt(virtualTick);
            buf.writeInt(recordingLength);
            buf.writeUtf(temporalState, 64);
            buf.writeUtf(lastMutationSource, 64);
            buf.writeUtf(protectionMode, 64);
            buf.writeUtf(entityFilterMode, 64);
            buf.writeUtf(authorizedPlayers, 512);
            buf.writeInt(playerPermissionEntries.size());
            for (String entry : playerPermissionEntries) {
                buf.writeUtf(entry, 512);
            }
            buf.writeBoolean(allowVanillaCommands);
            buf.writeBoolean(allowFrozenEntityTeleport);
            buf.writeBoolean(allowFrozenEntityDamage);
            buf.writeBoolean(allowFrozenEntityCollision);
            buf.writeBoolean(canViewHistory);
            buf.writeBoolean(hasWorkspaceBounds);
            if (hasWorkspaceBounds) {
                buf.writeInt(boundsMinX);
                buf.writeInt(boundsMinY);
                buf.writeInt(boundsMinZ);
                buf.writeInt(boundsMaxX);
                buf.writeInt(boundsMaxY);
                buf.writeInt(boundsMaxZ);
            }
            if (canViewHistory) {
                buf.writeInt(logLines.size());
                for (String line : logLines) {
                    buf.writeUtf(line, 512);
                }

                buf.writeInt(chatLines.size());
                for (String line : chatLines) {
                    buf.writeUtf(line, 1024);
                }
            }
        }

        public static WorkspaceControllerStateSyncPacket decode(FriendlyByteBuf buf) {
            BlockPos controllerPos = buf.readBlockPos();
            String workspaceName = buf.readUtf(256);
            int sizeX = buf.readInt();
            int sizeY = buf.readInt();
            int sizeZ = buf.readInt();
            boolean hasSnapshot = buf.readBoolean();
            boolean frozen = buf.readBoolean();
            int virtualTick = buf.readInt();
            int recordingLength = buf.readInt();
            String temporalState = buf.readUtf(64);
            String lastMutationSource = buf.readUtf(64);
            String protectionMode = buf.readUtf(64);
            String entityFilterMode = buf.readUtf(64);
            String authorizedPlayers = buf.readUtf(512);
            int permissionEntryCount = buf.readInt();
            List<String> playerPermissionEntries = new ArrayList<>(permissionEntryCount);
            for (int i = 0; i < permissionEntryCount; i++) {
                playerPermissionEntries.add(buf.readUtf(512));
            }
            boolean allowVanillaCommands = buf.readBoolean();
            boolean allowFrozenEntityTeleport = buf.readBoolean();
            boolean allowFrozenEntityDamage = buf.readBoolean();
            boolean allowFrozenEntityCollision = buf.readBoolean();
            boolean canViewHistory = buf.readBoolean();
            boolean hasWorkspaceBounds = buf.readBoolean();
            int boundsMinX = controllerPos.getX();
            int boundsMinY = controllerPos.getY();
            int boundsMinZ = controllerPos.getZ();
            int boundsMaxX = controllerPos.getX();
            int boundsMaxY = controllerPos.getY();
            int boundsMaxZ = controllerPos.getZ();
            if (hasWorkspaceBounds) {
                boundsMinX = buf.readInt();
                boundsMinY = buf.readInt();
                boundsMinZ = buf.readInt();
                boundsMaxX = buf.readInt();
                boundsMaxY = buf.readInt();
                boundsMaxZ = buf.readInt();
            }

            List<String> logLines = List.of();
            List<String> chatLines = List.of();
            if (canViewHistory) {
                int logCount = buf.readInt();
                if (logCount < 0 || logCount > WorkspaceControllerMenu.MAX_LOG_LINES) {
                    throw new IllegalArgumentException("Too many workspace log lines: " + logCount);
                }
                List<String> logs = new ArrayList<>(logCount);
                for (int i = 0; i < logCount; i++) {
                    logs.add(buf.readUtf(512));
                }
                logLines = List.copyOf(logs);

                int chatCount = buf.readInt();
                if (chatCount < 0 || chatCount > WorkspaceControllerMenu.MAX_CHAT_LINES) {
                    throw new IllegalArgumentException("Too many workspace chat lines: " + chatCount);
                }
                List<String> chats = new ArrayList<>(chatCount);
                for (int i = 0; i < chatCount; i++) {
                    chats.add(buf.readUtf(1024));
                }
                chatLines = List.copyOf(chats);
            }

            return new WorkspaceControllerStateSyncPacket(
                    controllerPos,
                    workspaceName,
                    sizeX,
                    sizeY,
                    sizeZ,
                    hasSnapshot,
                    frozen,
                    virtualTick,
                    recordingLength,
                    temporalState,
                    lastMutationSource,
                    protectionMode,
                    entityFilterMode,
                    authorizedPlayers,
                    playerPermissionEntries,
                    allowVanillaCommands,
                    allowFrozenEntityTeleport,
                    allowFrozenEntityDamage,
                    allowFrozenEntityCollision,
                    canViewHistory,
                    hasWorkspaceBounds,
                    boundsMinX,
                    boundsMinY,
                    boundsMinZ,
                    boundsMaxX,
                    boundsMaxY,
                    boundsMaxZ,
                    logLines,
                    chatLines
            );
        }

        public static void handle(WorkspaceControllerStateSyncPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
            net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
            if (!ctx.getDirection().getReceptionSide().isClient()) {
                ctx.setPacketHandled(true);
                return;
            }

            ctx.enqueueWork(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || !(mc.player.containerMenu instanceof WorkspaceControllerMenu menu)) {
                    return;
                }
                if (!menu.getControllerPos().equals(packet.controllerPos())) {
                    return;
                }
                menu.applySyncPacket(packet);
            });
            ctx.setPacketHandled(true);
        }
    }
}
