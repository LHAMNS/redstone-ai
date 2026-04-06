package com.redstoneai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.level.ServerPlayer;
import com.redstoneai.workspace.InitialSnapshot;
import com.redstoneai.config.RAIConfig;
import com.redstoneai.mcr.MCRBlock;
import com.redstoneai.mcr.MCRParser;
import com.redstoneai.mcr.MCRPlacer;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.recording.RecordingSummarizer;
import com.redstoneai.recording.RecordingTimeline;
import com.redstoneai.registry.RAIBlocks;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceControllerBlockEntity;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.UUID;

/**
 * Server commands for workspace management, tick control, IO markers, recording, and MCR building.
 */
public final class RAICommands {
    private static final int CONTROLLER_SEARCH_RADIUS = 8;
    private static final int MAX_COMMAND_WORKSPACE_ARG = 64;
    private static final int MAX_COMMAND_STEP_ARG = 10000;

    private RAICommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rai")
                .requires(src -> src.hasPermission(2))

                // --- Workspace management ---
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("sizeX", IntegerArgumentType.integer(4, MAX_COMMAND_WORKSPACE_ARG))
                                        .then(Commands.argument("sizeY", IntegerArgumentType.integer(4, MAX_COMMAND_WORKSPACE_ARG))
                                                .then(Commands.argument("sizeZ", IntegerArgumentType.integer(4, MAX_COMMAND_WORKSPACE_ARG))
                                                        .executes(RAICommands::createWorkspace))))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(RAICommands::deleteWorkspace)))
                .then(Commands.literal("list")
                        .executes(RAICommands::listWorkspaces))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(RAICommands::workspaceInfo)))

                // --- Tick control ---
                .then(Commands.literal("freeze")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(RAICommands::freezeWorkspace)))
                .then(Commands.literal("unfreeze")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(RAICommands::unfreezeWorkspace)))
                .then(Commands.literal("step")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> stepWorkspace(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COMMAND_STEP_ARG))
                                        .executes(ctx -> stepWorkspace(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"))))))

                // --- Rewind / Fast-forward ---
                .then(Commands.literal("rewind")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> rewindWorkspace(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COMMAND_STEP_ARG))
                                        .executes(ctx -> rewindWorkspace(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("ff")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> fastForwardWorkspace(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COMMAND_STEP_ARG))
                                        .executes(ctx -> fastForwardWorkspace(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"))))))

                // --- IO markers ---
                .then(Commands.literal("io")
                        .then(Commands.literal("mark")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("role", StringArgumentType.word())
                                                .then(Commands.argument("label", StringArgumentType.word())
                                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                                .executes(RAICommands::markIO))))))))
                        .then(Commands.literal("unmark")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(RAICommands::unmarkIO))))))
                        .then(Commands.literal("list")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(RAICommands::listIO))))

                // --- Recording summary ---
                .then(Commands.literal("summary")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> showSummary(ctx, 1))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
                                        .executes(ctx -> showSummary(ctx,
                                                IntegerArgumentType.getInteger(ctx, "level"))))))

                // --- MCR Build ---
                .then(Commands.literal("build")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("mcr", StringArgumentType.greedyString())
                                        .executes(RAICommands::buildMCR))))

                // --- Revert to initial state ---
                .then(Commands.literal("revert")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(RAICommands::revertWorkspace)))
        );
    }

    // ==================== Workspace CRUD ====================

    private static int createWorkspace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        int sizeX = IntegerArgumentType.getInteger(ctx, "sizeX");
        int sizeY = IntegerArgumentType.getInteger(ctx, "sizeY");
        int sizeZ = IntegerArgumentType.getInteger(ctx, "sizeZ");
        ServerLevel level = player.serverLevel();
        WorkspaceManager manager = WorkspaceManager.get(level);

        String nameError = WorkspaceRules.validateWorkspaceName(name);
        if (nameError != null) {
            ctx.getSource().sendFailure(Component.literal(nameError));
            return 0;
        }
        String sizeError = WorkspaceRules.validateDimensions(sizeX, sizeY, sizeZ);
        if (sizeError != null) {
            ctx.getSource().sendFailure(Component.literal(sizeError));
            return 0;
        }
        if (manager.hasWorkspace(name)) {
            ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' already exists"));
            return 0;
        }
        if (manager.getWorkspacesOwnedBy(player.getUUID()).size() >= RAIConfig.SERVER.maxWorkspacesPerPlayer.get()) {
            ctx.getSource().sendFailure(Component.literal("You already have the maximum number of workspaces"));
            return 0;
        }

        BlockPos controllerPos = findNearestController(level, player.blockPosition());
        if (controllerPos == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No Workspace Controller block within " + CONTROLLER_SEARCH_RADIUS + " blocks. Place one first."));
            return 0;
        }

        if (level.getBlockEntity(controllerPos) instanceof WorkspaceControllerBlockEntity be) {
            if (!be.getWorkspaceName().isEmpty()) {
                Workspace current = manager.getByName(be.getWorkspaceName());
                if (current == null || !current.isControllerPos(controllerPos)) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Controller binding is stale or missing; reselect the controller range before creating."));
                    return 0;
                }
                ctx.getSource().sendFailure(Component.literal(
                        "Controller already linked to workspace '" + be.getWorkspaceName() + "'"));
                return 0;
            }
        }

        BoundingBox bounds = new BoundingBox(
                controllerPos.getX(), controllerPos.getY(), controllerPos.getZ(),
                controllerPos.getX() + sizeX - 1,
                controllerPos.getY() + sizeY - 1,
                controllerPos.getZ() + sizeZ - 1
        );

        String overlap = manager.checkOverlap(bounds);
        if (overlap != null) {
            ctx.getSource().sendFailure(Component.literal("Workspace would overlap with '" + overlap + "'"));
            return 0;
        }

        Workspace ws = new Workspace(UUID.randomUUID(), player.getUUID(), name, controllerPos, bounds);
        manager.addWorkspace(ws);

        if (level.getBlockEntity(controllerPos) instanceof WorkspaceControllerBlockEntity be) {
            be.setWorkspaceName(name);
            be.setSize(sizeX, sizeY, sizeZ);
            // Capture initial state of the workspace area for one-click revert
            InitialSnapshot snapshot = InitialSnapshot.capture(level, bounds);
            be.setInitialSnapshot(snapshot);
            be.getOperationLog().logSystem("create",
                    "Workspace '" + name + "' (" + sizeX + "x" + sizeY + "x" + sizeZ + ")");
        }

        ctx.getSource().sendSuccess(() ->
                Component.literal("[RedstoneAI] Created workspace '" + name + "' (" +
                        sizeX + "x" + sizeY + "x" + sizeZ + ") at " + controllerPos.toShortString() +
                        " — initial state captured")
                        .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int deleteWorkspace(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        WorkspaceManager manager = WorkspaceManager.get(level);

        Workspace ws = manager.getByName(name);
        if (ws == null) {
            ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found"));
            return 0;
        }

        if (!checkManagePermission(ctx, ws)) return 0;

        // Discard frozen state without replaying queued ticks to the world
        if (ws.isFrozen()) {
            TickController.discardFrozenState(level, ws);
        }
        ws.setTimeline(null);
        ws.setVirtualTick(0);
        TickController.removeQueue(ws.getId());

        if (level.getBlockEntity(ws.getControllerPos()) instanceof WorkspaceControllerBlockEntity be) {
            be.clearWorkspaceBinding();
        }

        manager.removeWorkspace(name);
        ctx.getSource().sendSuccess(() ->
                Component.literal("[RedstoneAI] Deleted workspace '" + name + "'")
                        .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int listWorkspaces(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorkspaceManager manager = WorkspaceManager.get(level);
        var workspaces = manager.getAllWorkspaces();

        if (workspaces.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                    Component.literal("[RedstoneAI] No workspaces in this dimension")
                            .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() ->
                Component.literal("[RedstoneAI] Workspaces (" + workspaces.size() + "):")
                        .withStyle(ChatFormatting.GREEN), false);
        for (Workspace ws : workspaces) {
            BoundingBox b = ws.getBounds();
            String size = (b.maxX() - b.minX() + 1) + "x" +
                    (b.maxY() - b.minY() + 1) + "x" +
                    (b.maxZ() - b.minZ() + 1);
            String frozen = ws.isFrozen() ? " [FROZEN]" : "";
            ctx.getSource().sendSuccess(() ->
                    Component.literal("  " + ws.getName() + " (" + size + ")" + frozen)
                            .withStyle(ws.isFrozen() ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
        }
        return workspaces.size();
    }

    private static int workspaceInfo(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) {
            ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found"));
            return 0;
        }

        BoundingBox b = ws.getBounds();
        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] " + ws.getName()).withStyle(ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal("  Size: " + (b.maxX()-b.minX()+1) + "x" + (b.maxY()-b.minY()+1) + "x" + (b.maxZ()-b.minZ()+1)).withStyle(ChatFormatting.GRAY), false);
        ctx.getSource().sendSuccess(() -> Component.literal("  Origin: " + ws.getControllerPos().toShortString()).withStyle(ChatFormatting.GRAY), false);
        ctx.getSource().sendSuccess(() -> Component.literal("  Mode: " + ws.getProtectionMode().getSerializedName()).withStyle(ChatFormatting.GRAY), false);
        ctx.getSource().sendSuccess(() -> Component.literal("  Frozen: " + ws.isFrozen()).withStyle(ws.isFrozen() ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
        ctx.getSource().sendSuccess(() -> Component.literal("  Virtual tick: " + ws.getVirtualTick()).withStyle(ChatFormatting.GRAY), false);
        ctx.getSource().sendSuccess(() -> Component.literal("  IO markers: " + ws.getIOMarkers().size()).withStyle(ChatFormatting.GRAY), false);
        if (ws.getTimeline() != null) {
            ctx.getSource().sendSuccess(() -> Component.literal("  Recording: " + ws.getTimeline().getLength() + " tick(s), position: " + (ws.getTimeline().getCurrentIndex()+1)).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ==================== Tick Control ====================

    private static int freezeWorkspace(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }
        if (ws.isFrozen()) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' is already frozen")); return 0; }
        if (!checkManagePermission(ctx, ws)) return 0;

        TickController.freeze(level, ws);
        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Workspace '" + name + "' frozen").withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    private static int unfreezeWorkspace(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }
        if (!ws.isFrozen()) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' is not frozen")); return 0; }
        if (!checkManagePermission(ctx, ws)) return 0;

        TickController.unfreeze(level, ws);
        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Workspace '" + name + "' unfrozen").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int stepWorkspace(CommandContext<CommandSourceStack> ctx, int count) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }
        if (!ws.isFrozen()) { ctx.getSource().sendFailure(Component.literal("Must be frozen to step")); return 0; }
        if (!WorkspaceRules.isValidTickCount(count)) { ctx.getSource().sendFailure(Component.literal("Step count exceeds configured maximum")); return 0; }
        if (!checkManagePermission(ctx, ws)) return 0;

        int stepped = TickController.step(level, ws, count);
        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Stepped " + stepped + " tick(s) (vtick: " + ws.getVirtualTick() + ")").withStyle(ChatFormatting.AQUA), false);
        return stepped;
    }

    // ==================== Rewind / Fast-Forward ====================

    private static int rewindWorkspace(CommandContext<CommandSourceStack> ctx, int count) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }
        if (!ws.isFrozen()) { ctx.getSource().sendFailure(Component.literal("Must be frozen to rewind")); return 0; }
        if (!WorkspaceRules.isValidTickCount(count)) { ctx.getSource().sendFailure(Component.literal("Rewind count exceeds configured maximum")); return 0; }

        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null || timeline.getLength() == 0) {
            ctx.getSource().sendFailure(Component.literal("No recording to rewind"));
            return 0;
        }
        if (!timeline.canRewind(count)) {
            ctx.getSource().sendFailure(Component.literal("Cannot rewind " + count + " ticks (at position " + (timeline.getCurrentIndex()+1) + ")"));
            return 0;
        }

        int rewound = TickController.rewind(level, ws, count);

        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Rewound " + rewound + " tick(s) (vtick: " + ws.getVirtualTick() + ")").withStyle(ChatFormatting.GOLD), false);
        return rewound;
    }

    private static int fastForwardWorkspace(CommandContext<CommandSourceStack> ctx, int count) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }
        if (!ws.isFrozen()) { ctx.getSource().sendFailure(Component.literal("Must be frozen to fast-forward")); return 0; }
        if (!WorkspaceRules.isValidTickCount(count)) { ctx.getSource().sendFailure(Component.literal("Fast-forward count exceeds configured maximum")); return 0; }

        RecordingTimeline timeline = ws.getTimeline();
        if (timeline == null) {
            ctx.getSource().sendFailure(Component.literal("No recording"));
            return 0;
        }

        int advanced = TickController.replayThenStep(level, ws, count);
        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Advanced " + advanced + " tick(s) (vtick: " + ws.getVirtualTick() + ")").withStyle(ChatFormatting.GOLD), false);
        return advanced;
    }

    // ==================== IO Markers ====================

    private static int markIO(CommandContext<CommandSourceStack> ctx) {
        String wsName = StringArgumentType.getString(ctx, "name");
        String roleStr = StringArgumentType.getString(ctx, "role");
        String label = StringArgumentType.getString(ctx, "label");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        ServerLevel level = ctx.getSource().getLevel();

        Workspace ws = WorkspaceManager.get(level).getByName(wsName);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + wsName + "' not found")); return 0; }

        BlockPos pos = new BlockPos(x, y, z);
        if (!ws.contains(pos)) {
            ctx.getSource().sendFailure(Component.literal("Position is outside workspace bounds"));
            return 0;
        }

        IOMarker.IORole role = IOMarker.IORole.tryParse(roleStr.toLowerCase());
        if (role == null) {
            ctx.getSource().sendFailure(Component.literal("Invalid IO role"));
            return 0;
        }
        ws.addIOMarker(new IOMarker(pos, role, label));
        WorkspaceManager.get(level).setDirty();

        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Marked " + role.getSerializedName() + " '" + label + "' at " + pos.toShortString()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int unmarkIO(CommandContext<CommandSourceStack> ctx) {
        String wsName = StringArgumentType.getString(ctx, "name");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        ServerLevel level = ctx.getSource().getLevel();

        Workspace ws = WorkspaceManager.get(level).getByName(wsName);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + wsName + "' not found")); return 0; }

        BlockPos pos = new BlockPos(x, y, z);
        ws.removeIOMarker(pos);
        WorkspaceManager.get(level).setDirty();

        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Unmarked " + pos.toShortString()).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int listIO(CommandContext<CommandSourceStack> ctx) {
        String wsName = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();

        Workspace ws = WorkspaceManager.get(level).getByName(wsName);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + wsName + "' not found")); return 0; }

        List<IOMarker> markers = ws.getIOMarkers();
        if (markers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] No IO markers").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] IO markers (" + markers.size() + "):").withStyle(ChatFormatting.GREEN), false);
        for (IOMarker m : markers) {
            int power = level.getBestNeighborSignal(m.pos());
            ctx.getSource().sendSuccess(() -> Component.literal("  " + m.role().getSerializedName().toUpperCase() + " '" + m.label() + "' at " + m.pos().toShortString() + " power=" + power)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return markers.size();
    }

    // ==================== Recording Summary ====================

    private static int showSummary(CommandContext<CommandSourceStack> ctx, int detailLevel) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }

        String summary = switch (detailLevel) {
            case 2 -> RecordingSummarizer.level2(ws);
            case 3 -> RecordingSummarizer.level3(ws);
            default -> RecordingSummarizer.level1(ws);
        };

        for (String line : summary.split("\n")) {
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ==================== MCR Build ====================

    private static int buildMCR(CommandContext<CommandSourceStack> ctx) {
        String wsName = StringArgumentType.getString(ctx, "name");
        String mcrString = StringArgumentType.getString(ctx, "mcr");
        ServerLevel level = ctx.getSource().getLevel();

        Workspace ws = WorkspaceManager.get(level).getByName(wsName);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + wsName + "' not found")); return 0; }

        try {
            List<MCRBlock> blocks = MCRParser.parse(mcrString);
            if (blocks.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("No blocks parsed from MCR string"));
                return 0;
            }

            MCRPlacer.PlaceResult result = MCRPlacer.place(level, ws, blocks);
            ctx.getSource().sendSuccess(() -> Component.literal("[RedstoneAI] Placed " + result.placed() + " block(s)" +
                    (result.skipped() > 0 ? ", " + result.skipped() + " skipped (out of bounds)" : ""))
                    .withStyle(ChatFormatting.GREEN), false);
            return result.placed();
        } catch (MCRParser.MCRParseException e) {
            ctx.getSource().sendFailure(Component.literal("MCR parse error: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== Revert to Initial State ====================

    private static int revertWorkspace(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();
        Workspace ws = WorkspaceManager.get(level).getByName(name);
        if (ws == null) { ctx.getSource().sendFailure(Component.literal("Workspace '" + name + "' not found")); return 0; }

        BlockPos controllerPos = ws.getControllerPos();
        if (!(level.getBlockEntity(controllerPos) instanceof WorkspaceControllerBlockEntity be)) {
            ctx.getSource().sendFailure(Component.literal("Controller block entity not found"));
            return 0;
        }

        InitialSnapshot snapshot = be.getInitialSnapshot();
        if (snapshot == null) {
            ctx.getSource().sendFailure(Component.literal("No initial snapshot stored — cannot revert"));
            return 0;
        }

        boolean wasFrozen = ws.isFrozen();
        int changed = snapshot.restore(level);
        BlockPos restoredControllerPos = snapshot.getControllerPos() != null ? snapshot.getControllerPos() : controllerPos;
        WorkspaceControllerBlockEntity restoredController = level.getBlockEntity(restoredControllerPos) instanceof WorkspaceControllerBlockEntity restored
                ? restored
                : be;

        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.updateWorkspaceGeometry(ws, snapshot.getBounds(), restoredControllerPos);
        ws.setTimeline(null);
        ws.setVirtualTick(0);
        TickController.removeQueue(ws.getId());
        if (wasFrozen) {
            TickController.discardFrozenState(level, ws);
        }

        restoredController.setInitialSnapshot(null);
        restoredController.getOperationLog().logSystem("revert", "Reverted " + changed + " blocks to initial state");

        ctx.getSource().sendSuccess(() ->
                Component.literal("[RedstoneAI] Reverted workspace '" + name + "' — " +
                        changed + " block(s) restored to initial state")
                        .withStyle(ChatFormatting.GOLD), true);
        return changed;
    }

    // ==================== Utility ====================

    /**
     * Check if the command source has permission to manage the given workspace.
     * Console always has permission; players need to be owner or op level 2.
     */
    private static boolean checkManagePermission(CommandContext<CommandSourceStack> ctx, Workspace ws) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            if (!WorkspaceRules.canPlayerManage(player, ws)) {
                ctx.getSource().sendFailure(Component.literal("You don't have permission to manage workspace '" + ws.getName() + "'"));
                return false;
            }
        } catch (CommandSyntaxException e) {
            // Console — always allowed
        }
        return true;
    }

    private static BlockPos findNearestController(ServerLevel level, BlockPos center) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -CONTROLLER_SEARCH_RADIUS; x <= CONTROLLER_SEARCH_RADIUS; x++) {
            for (int y = -CONTROLLER_SEARCH_RADIUS; y <= CONTROLLER_SEARCH_RADIUS; y++) {
                for (int z = -CONTROLLER_SEARCH_RADIUS; z <= CONTROLLER_SEARCH_RADIUS; z++) {
                    BlockPos check = center.offset(x, y, z);
                    BlockState state = level.getBlockState(check);
                    if (state.is(RAIBlocks.WORKSPACE_CONTROLLER.get())) {
                        double dist = center.distSqr(check);
                        if (dist < nearestDist) {
                            nearest = check;
                            nearestDist = dist;
                        }
                    }
                }
            }
        }
        return nearest;
    }
}
