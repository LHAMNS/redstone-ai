package com.redstoneai.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * Queues deferred world work for frozen workspaces.
 */
public class FrozenTickQueue {

    public record QueuedBlockTick(BlockPos pos, Block block, TickPriority priority) {}
    public record QueuedFluidTick(BlockPos pos, Fluid fluid, TickPriority priority) {}
    public record QueuedBlockEvent(BlockEventData event) {}
    public record QueuedNeighborUpdate(BlockPos pos, BlockState state, Block sourceBlock, BlockPos sourcePos, boolean movedByPiston) {}

    public record DelayedTickView<T>(BlockPos pos, T type, int remainingDelay, TickPriority priority, long order) {}
    public record DelayedBlockEventView(BlockEventData event, int remainingDelay, long order) {}
    public record QueueState(
            List<DelayedTickView<Block>> blockTicks,
            List<DelayedTickView<Fluid>> fluidTicks,
            List<DelayedBlockEventView> blockEvents,
            List<QueuedNeighborUpdate> neighborUpdates
    ) {}

    private record ScheduledTickKey(BlockPos pos, Object type) {}

    private static final class DelayedBlockTick {
        private final BlockPos pos;
        private final Block block;
        private final TickPriority priority;
        private final long order;
        private int remainingDelay;

        private DelayedBlockTick(BlockPos pos, Block block, int remainingDelay, TickPriority priority, long order) {
            this.pos = pos.immutable();
            this.block = block;
            this.remainingDelay = remainingDelay;
            this.priority = priority;
            this.order = order;
        }
    }

    private static final class DelayedFluidTick {
        private final BlockPos pos;
        private final Fluid fluid;
        private final TickPriority priority;
        private final long order;
        private int remainingDelay;

        private DelayedFluidTick(BlockPos pos, Fluid fluid, int remainingDelay, TickPriority priority, long order) {
            this.pos = pos.immutable();
            this.fluid = fluid;
            this.remainingDelay = remainingDelay;
            this.priority = priority;
            this.order = order;
        }
    }

    private static final class DelayedBlockEvent {
        private final BlockEventData event;
        private final long order;
        private int remainingDelay;

        private DelayedBlockEvent(BlockEventData event, int remainingDelay, long order) {
            this.event = event;
            this.remainingDelay = remainingDelay;
            this.order = order;
        }
    }

    private final Map<ScheduledTickKey, DelayedBlockTick> blockTicks = new LinkedHashMap<>();
    private final Map<ScheduledTickKey, DelayedFluidTick> fluidTicks = new LinkedHashMap<>();
    private final Map<BlockEventData, DelayedBlockEvent> blockEvents = new LinkedHashMap<>();
    private final List<QueuedNeighborUpdate> neighborUpdates = new ArrayList<>();
    private long nextOrder = 0L;

    public void addBlockTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        addBlockTick(pos, block, delay, priority, nextOrder++);
    }

    public void addBlockTick(BlockPos pos, Block block, int delay, TickPriority priority, long order) {
        ScheduledTickKey key = new ScheduledTickKey(pos.immutable(), block);
        if (blockTicks.containsKey(key)) {
            return;
        }
        blockTicks.put(key, new DelayedBlockTick(pos, block, Math.max(0, delay), priority, order));
        nextOrder = Math.max(nextOrder, order + 1);
    }

    public void addFluidTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        addFluidTick(pos, fluid, delay, priority, nextOrder++);
    }

    public void addFluidTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority, long order) {
        ScheduledTickKey key = new ScheduledTickKey(pos.immutable(), fluid);
        if (fluidTicks.containsKey(key)) {
            return;
        }
        fluidTicks.put(key, new DelayedFluidTick(pos, fluid, Math.max(0, delay), priority, order));
        nextOrder = Math.max(nextOrder, order + 1);
    }

    public void addBlockEvent(BlockEventData event, int delay) {
        if (blockEvents.containsKey(event)) {
            return;
        }
        blockEvents.put(event, new DelayedBlockEvent(event, Math.max(0, delay), nextOrder++));
    }

    public void addNeighborUpdate(BlockPos pos, BlockState state, Block sourceBlock, BlockPos sourcePos, boolean movedByPiston) {
        neighborUpdates.add(new QueuedNeighborUpdate(pos.immutable(), state, sourceBlock, sourcePos.immutable(), movedByPiston));
    }

    /**
     * Decrement remaining delay for all queued block ticks and return those that are due.
     * IMPORTANT: Each call represents one virtual tick advance. Do NOT call for diagnostics
     * without advancing virtual time, as delays are consumed on every call.
     */
    public List<QueuedBlockTick> pollDueBlockTicks() {
        List<DelayedBlockTick> due = new ArrayList<>();
        Iterator<Map.Entry<ScheduledTickKey, DelayedBlockTick>> iterator = blockTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            DelayedBlockTick tick = iterator.next().getValue();
            tick.remainingDelay--;
            if (tick.remainingDelay <= 0) {
                due.add(tick);
                iterator.remove();
            }
        }
        due.sort(Comparator.<DelayedBlockTick, TickPriority>comparing(tick -> tick.priority)
                .thenComparingLong(tick -> tick.order));
        List<QueuedBlockTick> result = new ArrayList<>(due.size());
        for (DelayedBlockTick tick : due) {
            result.add(new QueuedBlockTick(tick.pos, tick.block, tick.priority));
        }
        return result;
    }

    public List<QueuedFluidTick> pollDueFluidTicks() {
        List<DelayedFluidTick> due = new ArrayList<>();
        Iterator<Map.Entry<ScheduledTickKey, DelayedFluidTick>> iterator = fluidTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            DelayedFluidTick tick = iterator.next().getValue();
            tick.remainingDelay--;
            if (tick.remainingDelay <= 0) {
                due.add(tick);
                iterator.remove();
            }
        }
        due.sort(Comparator.<DelayedFluidTick, TickPriority>comparing(tick -> tick.priority)
                .thenComparingLong(tick -> tick.order));
        List<QueuedFluidTick> result = new ArrayList<>(due.size());
        for (DelayedFluidTick tick : due) {
            result.add(new QueuedFluidTick(tick.pos, tick.fluid, tick.priority));
        }
        return result;
    }

    public List<QueuedBlockEvent> pollDueBlockEvents() {
        List<DelayedBlockEvent> due = new ArrayList<>();
        Iterator<Map.Entry<BlockEventData, DelayedBlockEvent>> iterator = blockEvents.entrySet().iterator();
        while (iterator.hasNext()) {
            DelayedBlockEvent event = iterator.next().getValue();
            event.remainingDelay--;
            if (event.remainingDelay <= 0) {
                due.add(event);
                iterator.remove();
            }
        }
        due.sort(Comparator.comparingLong(event -> event.order));
        List<QueuedBlockEvent> result = new ArrayList<>(due.size());
        for (DelayedBlockEvent event : due) {
            result.add(new QueuedBlockEvent(event.event));
        }
        return result;
    }

    public List<QueuedNeighborUpdate> drainNeighborUpdates() {
        List<QueuedNeighborUpdate> result = List.copyOf(neighborUpdates);
        neighborUpdates.clear();
        return result;
    }

    public QueueState snapshot() {
        return new QueueState(snapshotBlockTicks(), snapshotFluidTicks(), snapshotBlockEvents(), List.copyOf(neighborUpdates));
    }

    public void restore(QueueState state) {
        clear();
        for (DelayedTickView<Block> tick : state.blockTicks()) {
            addBlockTick(tick.pos(), tick.type(), tick.remainingDelay(), tick.priority(), tick.order());
        }
        for (DelayedTickView<Fluid> tick : state.fluidTicks()) {
            addFluidTick(tick.pos(), tick.type(), tick.remainingDelay(), tick.priority(), tick.order());
        }
        for (DelayedBlockEventView event : state.blockEvents()) {
            blockEvents.put(event.event(), new DelayedBlockEvent(event.event(), event.remainingDelay(), event.order()));
            nextOrder = Math.max(nextOrder, event.order() + 1);
        }
        neighborUpdates.addAll(state.neighborUpdates());
    }

    public List<DelayedTickView<Block>> snapshotBlockTicks() {
        return blockTicks.values().stream()
                .map(tick -> new DelayedTickView<>(tick.pos, tick.block, tick.remainingDelay, tick.priority, tick.order))
                .toList();
    }

    public List<DelayedTickView<Fluid>> snapshotFluidTicks() {
        return fluidTicks.values().stream()
                .map(tick -> new DelayedTickView<>(tick.pos, tick.fluid, tick.remainingDelay, tick.priority, tick.order))
                .toList();
    }

    public List<DelayedBlockEventView> snapshotBlockEvents() {
        return blockEvents.values().stream()
                .map(event -> new DelayedBlockEventView(event.event, event.remainingDelay, event.order))
                .toList();
    }

    public void clear() {
        blockTicks.clear();
        fluidTicks.clear();
        blockEvents.clear();
        neighborUpdates.clear();
        nextOrder = 0L;
    }

    public boolean isEmpty() {
        return blockTicks.isEmpty() && fluidTicks.isEmpty()
                && blockEvents.isEmpty() && neighborUpdates.isEmpty();
    }

    public static CompoundTag saveQueueState(QueueState state) {
        CompoundTag tag = new CompoundTag();

        ListTag blockTickList = new ListTag();
        for (DelayedTickView<Block> tick : state.blockTicks()) {
            CompoundTag tickTag = new CompoundTag();
            tickTag.putIntArray("pos", posArray(tick.pos()));
            tickTag.putString("block", BuiltInRegistries.BLOCK.getKey(tick.type()).toString());
            tickTag.putInt("remainingDelay", tick.remainingDelay());
            tickTag.putString("priority", tick.priority().name());
            tickTag.putLong("order", tick.order());
            blockTickList.add(tickTag);
        }
        tag.put("blockTicks", blockTickList);

        ListTag fluidTickList = new ListTag();
        for (DelayedTickView<Fluid> tick : state.fluidTicks()) {
            CompoundTag tickTag = new CompoundTag();
            tickTag.putIntArray("pos", posArray(tick.pos()));
            tickTag.putString("fluid", BuiltInRegistries.FLUID.getKey(tick.type()).toString());
            tickTag.putInt("remainingDelay", tick.remainingDelay());
            tickTag.putString("priority", tick.priority().name());
            tickTag.putLong("order", tick.order());
            fluidTickList.add(tickTag);
        }
        tag.put("fluidTicks", fluidTickList);

        ListTag eventList = new ListTag();
        for (DelayedBlockEventView event : state.blockEvents()) {
            CompoundTag eventTag = new CompoundTag();
            eventTag.putIntArray("pos", posArray(event.event().pos()));
            eventTag.putString("block", BuiltInRegistries.BLOCK.getKey(event.event().block()).toString());
            eventTag.putInt("paramA", event.event().paramA());
            eventTag.putInt("paramB", event.event().paramB());
            eventTag.putInt("remainingDelay", event.remainingDelay());
            eventTag.putLong("order", event.order());
            eventList.add(eventTag);
        }
        tag.put("blockEvents", eventList);

        ListTag neighborList = new ListTag();
        for (QueuedNeighborUpdate update : state.neighborUpdates()) {
            CompoundTag updateTag = new CompoundTag();
            updateTag.putIntArray("pos", posArray(update.pos()));
            updateTag.put("state", NbtUtils.writeBlockState(update.state()));
            updateTag.putString("sourceBlock", BuiltInRegistries.BLOCK.getKey(update.sourceBlock()).toString());
            updateTag.putIntArray("sourcePos", posArray(update.sourcePos()));
            updateTag.putBoolean("movedByPiston", update.movedByPiston());
            neighborList.add(updateTag);
        }
        tag.put("neighborUpdates", neighborList);

        return tag;
    }

    public static QueueState loadQueueState(CompoundTag tag) {
        List<DelayedTickView<Block>> blockTicks = new ArrayList<>();
        ListTag blockTickList = tag.getList("blockTicks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockTickList.size(); i++) {
            CompoundTag tickTag = blockTickList.getCompound(i);
            BlockPos pos = readPos(tickTag, "pos");
            Block block = resolveBlock(tickTag.getString("block"));
            blockTicks.add(new DelayedTickView<>(
                    pos,
                    block,
                    tickTag.getInt("remainingDelay"),
                    TickPriority.valueOf(tickTag.getString("priority")),
                    tickTag.getLong("order")
            ));
        }

        List<DelayedTickView<Fluid>> fluidTicks = new ArrayList<>();
        ListTag fluidTickList = tag.getList("fluidTicks", Tag.TAG_COMPOUND);
        for (int i = 0; i < fluidTickList.size(); i++) {
            CompoundTag tickTag = fluidTickList.getCompound(i);
            BlockPos pos = readPos(tickTag, "pos");
            Fluid fluid = resolveFluid(tickTag.getString("fluid"));
            fluidTicks.add(new DelayedTickView<>(
                    pos,
                    fluid,
                    tickTag.getInt("remainingDelay"),
                    TickPriority.valueOf(tickTag.getString("priority")),
                    tickTag.getLong("order")
            ));
        }

        List<DelayedBlockEventView> blockEvents = new ArrayList<>();
        ListTag eventList = tag.getList("blockEvents", Tag.TAG_COMPOUND);
        for (int i = 0; i < eventList.size(); i++) {
            CompoundTag eventTag = eventList.getCompound(i);
            BlockPos pos = readPos(eventTag, "pos");
            Block block = resolveBlock(eventTag.getString("block"));
            BlockEventData event = new BlockEventData(pos, block, eventTag.getInt("paramA"), eventTag.getInt("paramB"));
            blockEvents.add(new DelayedBlockEventView(
                    event,
                    eventTag.getInt("remainingDelay"),
                    eventTag.getLong("order")
            ));
        }

        List<QueuedNeighborUpdate> neighborUpdates = new ArrayList<>();
        ListTag neighborList = tag.getList("neighborUpdates", Tag.TAG_COMPOUND);
        for (int i = 0; i < neighborList.size(); i++) {
            CompoundTag updateTag = neighborList.getCompound(i);
            neighborUpdates.add(new QueuedNeighborUpdate(
                    readPos(updateTag, "pos"),
                    NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), updateTag.getCompound("state")),
                    resolveBlock(updateTag.getString("sourceBlock")),
                    readPos(updateTag, "sourcePos"),
                    updateTag.getBoolean("movedByPiston")
            ));
        }

        return new QueueState(
                List.copyOf(blockTicks),
                List.copyOf(fluidTicks),
                List.copyOf(blockEvents),
                List.copyOf(neighborUpdates)
        );
    }

    private static int[] posArray(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        int[] pos = tag.getIntArray(key);
        if (pos.length < 3) {
            throw new IllegalStateException("Corrupted queue state: " + key + " is missing coordinates");
        }
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    private static Block resolveBlock(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            throw new IllegalStateException("Corrupted queue state: invalid block id '" + id + "'");
        }
        return BuiltInRegistries.BLOCK.getOptional(key)
                .orElseThrow(() -> new IllegalStateException("Corrupted queue state: unknown block '" + id + "'"));
    }

    private static Fluid resolveFluid(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            throw new IllegalStateException("Corrupted queue state: invalid fluid id '" + id + "'");
        }
        return BuiltInRegistries.FLUID.getOptional(key)
                .orElseThrow(() -> new IllegalStateException("Corrupted queue state: unknown fluid '" + id + "'"));
    }
}
