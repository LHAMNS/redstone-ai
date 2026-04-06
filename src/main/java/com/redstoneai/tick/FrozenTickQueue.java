package com.redstoneai.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;

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
}
