package com.redstoneai.network;

import com.redstoneai.client.ClientSelectionPreviewState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Synchronizes the temporary manual selection preview state to the client.
 */
public record SelectionPreviewSyncPacket(
        ResourceLocation dimensionId,
        BlockPos controllerPos,
        BlockPos firstCorner,
        int sizeY,
        boolean active
) {
    public static void encode(SelectionPreviewSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.dimensionId);
        buf.writeBlockPos(packet.controllerPos);
        buf.writeBoolean(packet.firstCorner != null);
        if (packet.firstCorner != null) {
            buf.writeBlockPos(packet.firstCorner);
        }
        buf.writeInt(packet.sizeY);
        buf.writeBoolean(packet.active);
    }

    public static SelectionPreviewSyncPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimensionId = buf.readResourceLocation();
        BlockPos controllerPos = buf.readBlockPos();
        BlockPos firstCorner = buf.readBoolean() ? buf.readBlockPos() : null;
        int sizeY = buf.readInt();
        boolean active = buf.readBoolean();
        return new SelectionPreviewSyncPacket(dimensionId, controllerPos, firstCorner, sizeY, active);
    }

    public static void handle(SelectionPreviewSyncPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (!ctx.getDirection().getReceptionSide().isClient()) {
            ctx.setPacketHandled(true);
            return;
        }

        ctx.enqueueWork(() -> ClientSelectionPreviewState.update(
                packet.dimensionId,
                packet.controllerPos,
                packet.firstCorner,
                packet.sizeY,
                packet.active
        ));
        ctx.setPacketHandled(true);
    }
}
