package com.redstoneai.network;

import com.redstoneai.client.ClientWorkspaceCache;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Synchronizes workspace boundary views to clients for rendering.
 */
public class WorkspaceBoundarySyncPacket {
    public static final int MAX_VIEWS = 256;
    private static final int MAX_NAME_LENGTH = 64;
    private final ResourceLocation dimensionId;
    private final List<WorkspaceBoundaryView> views;

    public WorkspaceBoundarySyncPacket(ResourceLocation dimensionId, List<WorkspaceBoundaryView> views) {
        this.dimensionId = dimensionId;
        this.views = List.copyOf(views);
    }

    public static WorkspaceBoundarySyncPacket fromLevel(ServerLevel level, ServerPlayer viewer) {
        WorkspaceManager manager = WorkspaceManager.get(level);
        List<WorkspaceBoundaryView> views = new ArrayList<>();
        for (WorkspaceManager.WorkspaceView view : manager.getWorkspaceViewsSnapshot()) {
            Workspace workspace = manager.getByName(view.name());
            if (workspace == null || !WorkspaceRules.canPlayerManage(viewer, workspace)) {
                continue;
            }

            BoundingBox bounds = view.bounds();
            views.add(new WorkspaceBoundaryView(
                    view.name(),
                    new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                    view.frozen()
            ));
        }

        views.sort(Comparator
                .comparing(WorkspaceBoundaryView::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(view -> view.bounds().minX())
                .thenComparingInt(view -> view.bounds().minY())
                .thenComparingInt(view -> view.bounds().minZ()));
        if (views.size() > MAX_VIEWS) {
            views = List.copyOf(views.subList(0, MAX_VIEWS));
        }

        return new WorkspaceBoundarySyncPacket(level.dimension().location(), views);
    }

    public static void encode(WorkspaceBoundarySyncPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.dimensionId);
        buf.writeVarInt(packet.views.size());
        for (WorkspaceBoundaryView view : packet.views) {
            buf.writeUtf(view.name(), MAX_NAME_LENGTH);
            BoundingBox bounds = view.bounds();
            buf.writeInt(bounds.minX());
            buf.writeInt(bounds.minY());
            buf.writeInt(bounds.minZ());
            buf.writeInt(bounds.maxX());
            buf.writeInt(bounds.maxY());
            buf.writeInt(bounds.maxZ());
            buf.writeBoolean(view.frozen());
        }
    }

    public static WorkspaceBoundarySyncPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimensionId = buf.readResourceLocation();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_VIEWS) {
            throw new IllegalArgumentException("Too many workspace boundary views: " + size);
        }
        List<WorkspaceBoundaryView> views = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String name = buf.readUtf(MAX_NAME_LENGTH);
            BoundingBox bounds = new BoundingBox(
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt()
            );
            views.add(new WorkspaceBoundaryView(name, bounds, buf.readBoolean()));
        }
        return new WorkspaceBoundarySyncPacket(dimensionId, views);
    }

    public static void handle(WorkspaceBoundarySyncPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (!ctx.getDirection().getReceptionSide().isClient()) {
            ctx.setPacketHandled(true);
            return;
        }
        ctx.enqueueWork(() -> ClientWorkspaceCache.update(packet.dimensionId, packet.views));
        ctx.setPacketHandled(true);
    }

    public static void sync(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            syncTo(player);
        }
    }

    public static void syncTo(ServerPlayer player) {
        RAINetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), fromLevel(player.serverLevel(), player));
    }

    public record WorkspaceBoundaryView(String name, BoundingBox bounds, boolean frozen) {}
}
