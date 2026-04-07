package com.redstoneai.test;

import com.redstoneai.RedstoneAI;
import com.redstoneai.mcr.MCRBlock;
import com.redstoneai.mcr.MCRParser;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.network.rpc.handlers.BuildHandler;
import com.redstoneai.network.rpc.handlers.BlockEntityHandler;
import com.redstoneai.network.rpc.handlers.EntityHandler;
import com.redstoneai.network.rpc.handlers.IOHandler;
import com.redstoneai.network.rpc.handlers.SimulationHandler;
import com.redstoneai.network.rpc.handlers.TestHandler;
import com.redstoneai.network.rpc.handlers.WorkspaceHandler;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.tick.FrozenTickQueue;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.TickPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * GameTests verifying core RedstoneAI functionality:
 * MCR parsing, workspace management, tick freeze/step, recording, boundary checks.
 * <p>
 * All tests use the mod-local {@code redstone_ai:empty3x3x3} template.
 */
@GameTestHolder(RedstoneAI.ID)
@PrefixGameTestTemplate(false)
public class RAIGameTests {

    private static final String T = "empty3x3x3";

    // ── MCR Parser ──────────────────────────────────────────────────

    @GameTest(template = T, batch = "mcr")
    public static void mcr_simple_parse(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("# D R");
            helper.assertTrue(blocks.size() == 3, "Expected 3 blocks");
            helper.assertTrue("stone".equals(blocks.get(0).blockType()), "First=stone");
            helper.assertTrue("redstone_wire".equals(blocks.get(1).blockType()), "Second=wire");
            helper.assertTrue("repeater".equals(blocks.get(2).blockType()), "Third=repeater");
        } catch (MCRParser.MCRParseException e) {
            helper.fail("Parse failed: " + e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_modifiers(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("Rn2 Cex");
            MCRBlock r = blocks.get(0);
            helper.assertTrue(r.delay() == 2, "delay=2");
            helper.assertTrue(r.facing().getName().equals("north"), "facing=north");
            MCRBlock c = blocks.get(1);
            helper.assertTrue("subtract".equals(c.mode()), "mode=subtract");
        } catch (MCRParser.MCRParseException e) {
            helper.fail(e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_directives(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("@origin 1,2,3 # @row # @layer #");
            helper.assertTrue(blocks.get(0).x() == 1 && blocks.get(0).y() == 2 && blocks.get(0).z() == 3, "origin");
            helper.assertTrue(blocks.get(1).x() == 1 && blocks.get(1).z() == 4, "@row z+1");
            helper.assertTrue(blocks.get(2).y() == 3 && blocks.get(2).z() == 3, "@layer y+1 z-reset");
        } catch (MCRParser.MCRParseException e) {
            helper.fail(e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_invalid_code_throws(GameTestHelper helper) {
        try {
            MCRParser.parse("Z");
            helper.fail("Should throw");
        } catch (MCRParser.MCRParseException e) {
            helper.assertTrue(e.getMessage().contains("Unknown block code"), "msg");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_fill_directive(GameTestHelper helper) {
        try {
            List<MCRBlock> blocks = MCRParser.parse("@fill #");
            helper.assertTrue(blocks.size() == 1, "1 block");
            helper.assertTrue("fill".equals(blocks.get(0).extraProperties().get("_directive")), "fill directive");
        } catch (MCRParser.MCRParseException e) {
            helper.fail(e.getMessage());
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "mcr")
    public static void mcr_all_codes_parse(GameTestHelper helper) {
        try {
            MCRParser.parse("D R C T W P K O H L B # G S N _ A X I J Q");
            helper.succeed();
        } catch (MCRParser.MCRParseException e) {
            helper.fail("All codes should parse: " + e.getMessage());
        }
    }

    // ── Workspace + Tick Control ────────────────────────────────────

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_freeze_unfreeze(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_freeze", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            helper.assertTrue(ws.isFrozen(), "frozen");
            helper.assertTrue(ws.getTimeline() != null, "timeline created");

            TickController.unfreeze(level, ws);
            helper.assertTrue(!ws.isFrozen(), "unfrozen");
        } finally {
            if (ws.isFrozen()) TickController.unfreeze(level, ws);
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_freeze");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_step_increments_vtick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_step", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            int stepped = TickController.step(level, ws, 10);
            helper.assertTrue(stepped == 10, "stepped 10");
            helper.assertTrue(ws.getVirtualTick() == 10, "vtick=10");
        } finally {
            if (ws.isFrozen()) TickController.unfreeze(level, ws);
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_step");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_overlap_detection(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox b1 = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 7, abs.getY() + 3, abs.getZ() + 7);
        Workspace ws1 = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_ovlp1", abs, b1);
        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.addWorkspace(ws1);

        try {
            // Overlapping
            BoundingBox b2 = new BoundingBox(abs.getX() + 4, abs.getY(), abs.getZ(),
                    abs.getX() + 11, abs.getY() + 3, abs.getZ() + 7);
            helper.assertTrue("gt_ovlp1".equals(manager.checkOverlap(b2)), "overlap detected");

            // Non-overlapping
            BoundingBox b3 = new BoundingBox(abs.getX() + 100, abs.getY(), abs.getZ(),
                    abs.getX() + 107, abs.getY() + 3, abs.getZ() + 7);
            helper.assertTrue(manager.checkOverlap(b3) == null, "no overlap");
        } finally {
            manager.removeWorkspace("gt_ovlp1");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_controller_anchored_bounds_leave_controller_outside(GameTestHelper helper) {
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 6, 1));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 6, 4, 5);

        helper.assertTrue(bounds.minX() == controllerPos.getX(), "x min anchored to controller");
        helper.assertTrue(bounds.minY() == controllerPos.getY() - 4, "workspace extends below controller");
        helper.assertTrue(bounds.maxY() == controllerPos.getY() - 1, "workspace top sits below controller");
        helper.assertTrue(!bounds.isInside(controllerPos), "controller must stay outside workspace bounds");
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_save_load_preserves_origin_separate_from_controller(GameTestHelper helper) {
        BlockPos controllerPos = helper.absolutePos(new BlockPos(2, 7, 2));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        BlockPos originPos = WorkspaceRules.originFromBounds(bounds);

        Workspace original = new Workspace(
                UUID.randomUUID(),
                new UUID(0, 0),
                "gt_origin_save",
                controllerPos,
                originPos,
                bounds
        );

        Workspace restored = Workspace.load(original.save());
        helper.assertTrue(restored.getControllerPos().equals(controllerPos), "controller position restored");
        helper.assertTrue(restored.getOriginPos().equals(originPos), "logical origin restored");
        helper.assertTrue(restored.getBounds().equals(bounds), "bounds restored");
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_save_load_preserves_security_settings(GameTestHelper helper) {
        BlockPos controllerPos = helper.absolutePos(new BlockPos(2, 7, 2));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace original = new Workspace(
                UUID.randomUUID(),
                new UUID(0, 0),
                "gt_security_save",
                controllerPos,
                WorkspaceRules.originFromBounds(bounds),
                bounds
        );
        original.replaceAuthorizedPlayers(List.of("alice", "bob"));
        original.setAllowVanillaCommands(false);
        original.setAllowFrozenEntityTeleport(false);
        original.setAllowFrozenEntityDamage(false);
        original.setAllowFrozenEntityCollision(false);

        Workspace restored = Workspace.load(original.save());
        helper.assertTrue(restored.getAuthorizedPlayers().equals(List.of("alice", "bob")), "authorized players restored");
        helper.assertTrue(!restored.isAllowVanillaCommands(), "command protection restored");
        helper.assertTrue(!restored.isAllowFrozenEntityTeleport(), "teleport protection restored");
        helper.assertTrue(!restored.isAllowFrozenEntityDamage(), "damage protection restored");
        helper.assertTrue(!restored.isAllowFrozenEntityCollision(), "collision protection restored");
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void frozen_entity_teleport_and_damage_are_blocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(4, 7, 4));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_entity_lock", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        ItemEntity entity = new ItemEntity(level, bounds.minX() + 1.5, bounds.minY() + 1.0, bounds.minZ() + 1.5, new ItemStack(Items.STONE));
        level.addFreshEntity(entity);

        try {
            TickController.freeze(level, ws);
            double beforeX = entity.getX();
            double beforeY = entity.getY();
            double beforeZ = entity.getZ();
            entity.teleportTo(beforeX + 2.0, beforeY, beforeZ + 2.0);
            helper.assertTrue(entity.getX() == beforeX && entity.getY() == beforeY && entity.getZ() == beforeZ,
                    "teleport blocked");

            entity.hurt(level.damageSources().generic(), 100.0f);
            helper.assertTrue(entity.isAlive(), "damage blocked");
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            entity.discard();
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_entity_lock");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void external_entity_cannot_cross_workspace_boundary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(5, 7, 5));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_entity_boundary", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        ItemEntity outsider = new ItemEntity(level, bounds.minX() - 0.5, bounds.minY() + 1.0, bounds.minZ() + 1.5, new ItemStack(Items.STONE));
        level.addFreshEntity(outsider);
        Vec3 before = outsider.position();

        try {
            outsider.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(2.0, 0.0, 0.0));
            helper.assertTrue(outsider.position().equals(before), "outside entity should not enter workspace");
        } finally {
            outsider.discard();
            WorkspaceManager.get(level).removeWorkspace("gt_entity_boundary");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void dangerous_vanilla_command_is_blocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(6, 7, 6));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_command_block", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        BlockPos target = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        net.minecraft.world.level.block.state.BlockState before = level.getBlockState(target);
        int result = level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack(),
                "setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " minecraft:stone"
        );

        try {
            helper.assertTrue(result == 0, "command should be rejected");
            helper.assertTrue(level.getBlockState(target).equals(before), "protected block unchanged");
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_command_block");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void ai_build_path_still_works_with_command_protection(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(7, 7, 7));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_ai_build", controllerPos, bounds);
        ws.setAllowVanillaCommands(false);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("block", "minecraft:stone");
            params.addProperty("x", 0);
            params.addProperty("y", 0);
            params.addProperty("z", 0);

            JsonObject result = new BuildHandler()
                    .buildBlock(new JsonRpcRequest("1", "build.block", params), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue("minecraft:stone".equals(result.get("placed").getAsString()), "AI block build succeeded");
            helper.assertTrue(level.getBlockState(ws.toWorldPos(0, 0, 0)).is(Blocks.STONE), "AI placement changed world");
        } catch (JsonRpcException e) {
            helper.fail("AI build should not be blocked: " + e.getMessage());
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_ai_build");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void build_block_supports_explicit_state_properties(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(7, 7, 2));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_build_props", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("block", "minecraft:repeater");
            params.addProperty("x", 1);
            params.addProperty("y", 0);
            params.addProperty("z", 1);
            JsonObject properties = new JsonObject();
            properties.addProperty("facing", "east");
            properties.addProperty("delay", "4");
            params.add("properties", properties);

            new BuildHandler().buildBlock(new JsonRpcRequest("1", "build.block", params), level.getServer());

            var state = level.getBlockState(ws.toWorldPos(1, 0, 1));
            helper.assertTrue(state.is(Blocks.REPEATER), "repeater placed");
            helper.assertTrue(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getName().equals("east"),
                    "facing property applied");
            helper.assertTrue(state.getValue(BlockStateProperties.DELAY) == 4, "delay property applied");
        } catch (JsonRpcException e) {
            helper.fail("Block properties should be accepted: " + e.getMessage());
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_build_props");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void block_entity_write_updates_container_inventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(7, 7, 1));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_block_entity", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        BlockPos chestPos = ws.toWorldPos(0, 0, 0);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("x", 0);
            params.addProperty("y", 0);
            params.addProperty("z", 0);
            params.addProperty("nbt", "{Items:[{Slot:0b,id:\"minecraft:redstone\",Count:32b}]}");
            params.addProperty("mode", "merge");

            JsonObject result = new BlockEntityHandler()
                    .write(new JsonRpcRequest("1", "block_entity.write", params), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(result.get("updated").getAsBoolean(), "block entity updated");
            helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity, "chest block entity present");
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
            helper.assertTrue(chest.getItem(0).is(Items.REDSTONE), "slot 0 item written");
            helper.assertTrue(chest.getItem(0).getCount() == 32, "slot 0 count written");
        } catch (JsonRpcException e) {
            helper.fail("Block entity write should succeed: " + e.getMessage());
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_block_entity");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void io_drive_rejects_invalid_binary_power_without_mutating_world(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(7, 7, 3));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_io_binary", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        BlockPos inputPos = ws.toWorldPos(0, 0, 0);
        level.setBlock(inputPos, Blocks.LEVER.defaultBlockState(), 3);
        ws.addIOMarker(new IOMarker(inputPos, IOMarker.IORole.INPUT, "A"));

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("label", "A");
            params.addProperty("power", 1);

            try {
                new IOHandler().drive(new JsonRpcRequest("1", "io.drive", params), level.getServer());
                helper.fail("Expected binary input with power=1 to be rejected");
            } catch (JsonRpcException e) {
                helper.assertTrue(e.getCode() == JsonRpcException.INVALID_PARAMS, "invalid params code");
            }

            helper.assertTrue(!level.getBlockState(inputPos).getValue(BlockStateProperties.POWERED),
                    "lever state unchanged");
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_io_binary");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void io_drive_is_atomic_when_any_input_is_invalid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(7, 7, 4));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_io_atomic", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        BlockPos leverPos = ws.toWorldPos(0, 0, 0);
        BlockPos stonePos = ws.toWorldPos(1, 0, 0);
        level.setBlock(leverPos, Blocks.LEVER.defaultBlockState(), 3);
        level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), 3);
        ws.addIOMarker(new IOMarker(leverPos, IOMarker.IORole.INPUT, "A"));
        ws.addIOMarker(new IOMarker(stonePos, IOMarker.IORole.INPUT, "B"));

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            JsonObject values = new JsonObject();
            values.addProperty("A", 15);
            values.addProperty("B", 15);
            params.add("values", values);

            try {
                new IOHandler().drive(new JsonRpcRequest("1", "io.drive", params), level.getServer());
                helper.fail("Expected unsupported INPUT fixture to be rejected");
            } catch (JsonRpcException e) {
                helper.assertTrue(e.getCode() == JsonRpcException.INVALID_PARAMS, "invalid params code");
            }

            helper.assertTrue(!level.getBlockState(leverPos).getValue(BlockStateProperties.POWERED),
                    "valid input not partially applied");
            helper.assertTrue(level.getBlockState(stonePos).is(Blocks.STONE), "unsupported input block unchanged");
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_io_atomic");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void internal_entity_move_within_workspace_is_allowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(8, 7, 8));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_internal_move", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        ItemEntity entity = new ItemEntity(level, bounds.minX() + 1.5, bounds.minY() + 1.0, bounds.minZ() + 1.5, new ItemStack(Items.STONE));
        level.addFreshEntity(entity);
        Vec3 before = entity.position();

        try {
            entity.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.25, 0.0, 0.25));
            helper.assertTrue(!entity.position().equals(before), "internal entity movement remains allowed");
        } finally {
            entity.discard();
            WorkspaceManager.get(level).removeWorkspace("gt_internal_move");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void settle_stops_after_quiet_ticks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(8, 7, 2));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_settle", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);

            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("count", 5);
            params.addProperty("quietTicks", 2);

            JsonObject result = new SimulationHandler()
                    .settle(new JsonRpcRequest("1", "sim.settle", params), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(result.get("stable").getAsBoolean(), "workspace settled");
            helper.assertTrue(result.get("stepped").getAsInt() == 2, "empty workspace settles after quiet window");
            helper.assertTrue(ws.getVirtualTick() == 2, "virtual tick advanced by settle");
        } catch (JsonRpcException e) {
            helper.fail("Settle should succeed: " + e.getMessage());
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_settle");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void entity_handler_spawn_update_remove_round_trip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(8, 7, 1));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_entity_rpc", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject spawnParams = new JsonObject();
            spawnParams.addProperty("workspace", ws.getName());
            spawnParams.addProperty("entityType", "minecraft:armor_stand");
            spawnParams.addProperty("x", 1.5);
            spawnParams.addProperty("y", 0.0);
            spawnParams.addProperty("z", 1.5);
            spawnParams.addProperty("nbt", "{Invisible:1b}");

            JsonObject spawned = new EntityHandler()
                    .spawn(new JsonRpcRequest("1", "entity.spawn", spawnParams), level.getServer())
                    .getAsJsonObject();
            UUID uuid = UUID.fromString(spawned.get("uuid").getAsString());
            helper.assertTrue(level.getEntity(uuid) instanceof ArmorStand, "armor stand spawned");

            JsonObject updateParams = new JsonObject();
            updateParams.addProperty("workspace", ws.getName());
            updateParams.addProperty("uuid", uuid.toString());
            updateParams.addProperty("x", 2.5);
            updateParams.addProperty("y", 0.0);
            updateParams.addProperty("z", 1.5);
            updateParams.addProperty("nbt", "{NoGravity:1b}");
            updateParams.addProperty("mode", "merge");

            JsonObject updated = new EntityHandler()
                    .update(new JsonRpcRequest("2", "entity.update", updateParams), level.getServer())
                    .getAsJsonObject();
            helper.assertTrue(updated.get("updated").getAsBoolean(), "entity updated");
            helper.assertTrue(level.getEntity(uuid) instanceof ArmorStand, "same uuid entity still present");
            ArmorStand armorStand = (ArmorStand) level.getEntity(uuid);
            helper.assertTrue(Math.abs(armorStand.getX() - (ws.getOriginPos().getX() + 2.5)) < 0.01, "entity moved");
            CompoundTag entityTag = new CompoundTag();
            armorStand.saveWithoutId(entityTag);
            helper.assertTrue(entityTag.getBoolean("Invisible"), "existing invisibility retained");
            helper.assertTrue(entityTag.getBoolean("NoGravity"), "new NBT applied");

            JsonObject removeParams = new JsonObject();
            removeParams.addProperty("workspace", ws.getName());
            removeParams.addProperty("uuid", uuid.toString());
            new EntityHandler().remove(new JsonRpcRequest("3", "entity.remove", removeParams), level.getServer());
            helper.assertTrue(level.getEntity(uuid) == null, "entity removed");
        } catch (JsonRpcException e) {
            helper.fail("Entity handler round trip should succeed: " + e.getMessage());
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_entity_rpc");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void settle_does_not_report_stable_while_future_ticks_are_queued(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(8, 7, 3));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_settle_queue", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            FrozenTickQueue queue = Objects.requireNonNull(TickController.getQueue(ws), "queue");
            queue.addBlockTick(ws.toWorldPos(1, 0, 1), Blocks.STONE, 5, TickPriority.NORMAL);

            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("count", 2);
            params.addProperty("quietTicks", 2);

            JsonObject result = new SimulationHandler()
                    .settle(new JsonRpcRequest("1", "sim.settle", params), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(!result.get("stable").getAsBoolean(), "queued future work prevents stability");
            helper.assertTrue(result.get("stepped").getAsInt() == 2, "settle consumes requested budget");
        } catch (JsonRpcException e) {
            helper.fail("Settle queue test should succeed: " + e.getMessage());
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_settle_queue");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void suite_restores_frozen_queue_and_virtual_tick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_suite_frozen", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            TickController.freeze(level, ws);
            FrozenTickQueue queue = Objects.requireNonNull(TickController.getQueue(ws), "queue");
            queue.addBlockTick(abs.offset(1, 0, 0), Blocks.STONE, 2, TickPriority.NORMAL);
            ws.setVirtualTick(7);

            FrozenTickQueue.QueueState beforeQueue = queue.snapshot();
            List<TestCase> cases = List.of(
                    new TestCase("case1", Map.of(), Map.of(), 1),
                    new TestCase("case2", Map.of(), Map.of(), 1)
            );

            List<TestResult> results = TestRunner.runSuite(level, ws, new TestSuite("suite_frozen", cases));
            helper.assertTrue(results.size() == 2, "two results");
            helper.assertTrue(results.stream().allMatch(TestResult::passed), "suite passed");
            helper.assertTrue(ws.isFrozen(), "workspace restored frozen");
            helper.assertTrue(ws.getVirtualTick() == 7, "virtual tick restored");
            helper.assertTrue(beforeQueue.equals(Objects.requireNonNull(TickController.getQueue(ws)).snapshot()),
                    "queue restored");
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_suite_frozen");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void suite_restores_unfrozen_workspace_state(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_suite_unfrozen", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            List<TestResult> results = TestRunner.runSuite(level, ws, new TestSuite("suite_unfrozen",
                    List.of(new TestCase(Map.of(), Map.of(), 1))));

            helper.assertTrue(results.size() == 1, "single result");
            helper.assertTrue(results.get(0).passed(), "suite passed");
            helper.assertTrue(!ws.isFrozen(), "workspace restored unfrozen");
            helper.assertTrue(TickController.getQueue(ws) == null, "queue removed");
            helper.assertTrue(ws.getTimeline() == null, "timeline removed");
            helper.assertTrue(ws.getVirtualTick() == 0, "virtual tick restored");
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_suite_unfrozen");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void suite_total_tick_budget_is_enforced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_suite_budget", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            List<TestCase> cases = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                cases.add(new TestCase("case_" + i, Map.of(), Map.of(), 200));
            }

            try {
                TestRunner.runSuite(level, ws, new TestSuite("suite_budget", cases));
                helper.fail("Expected suite total tick budget to be rejected");
            } catch (IllegalArgumentException e) {
                helper.assertTrue(e.getMessage().contains("Suite total tick budget"), "budget message");
            }
        } finally {
            if (ws.isFrozen()) {
                TickController.unfreeze(level, ws);
            }
            TickController.removeQueue(ws.getId());
            WorkspaceManager.get(level).removeWorkspace("gt_suite_budget");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void test_handler_rejects_controller_marker_label(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_test_handler", abs, bounds);
        ws.addIOMarker(new IOMarker(ws.getControllerPos(), IOMarker.IORole.INPUT, "reserved"));
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            JsonObject inputs = new JsonObject();
            inputs.addProperty("reserved", 15);
            params.add("inputs", inputs);
            params.add("expected", new JsonObject());
            params.addProperty("ticks", 1);

            try {
                new TestHandler().run(new JsonRpcRequest("1", "run", params), level.getServer());
                helper.fail("Expected controller-position marker label to be rejected");
            } catch (JsonRpcException e) {
                helper.assertTrue(e.getCode() == JsonRpcException.INVALID_PARAMS, "invalid params code");
            }
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_test_handler");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_scan_includes_nbt_for_stateful_builds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkspaceHandler handler = new WorkspaceHandler();
        String name = "gt_scan_nbt";
        ItemEntity itemEntity = null;
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 6, 1));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, name, controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            BlockPos chestPos = ws.toWorldPos(0, 0, 0);
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity, "chest block entity created");
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
            chest.setItem(0, new ItemStack(Items.REDSTONE, 3));
            chest.setChanged();

            itemEntity = new ItemEntity(
                    level,
                    ws.getBounds().minX() + 1.5,
                    ws.getBounds().minY() + 1.0,
                    ws.getBounds().minZ() + 1.5,
                    new ItemStack(Items.COMPARATOR)
            );
            level.addFreshEntity(itemEntity);

            JsonObject scanParams = new JsonObject();
            scanParams.addProperty("name", name);
            JsonObject result = handler.scan(new JsonRpcRequest("2", "workspace.scan", scanParams), level.getServer())
                    .getAsJsonObject();

            JsonObject scannedChest = null;
            for (var element : result.getAsJsonArray("blocks")) {
                JsonObject block = element.getAsJsonObject();
                if (block.get("x").getAsInt() == 0 && block.get("y").getAsInt() == 0 && block.get("z").getAsInt() == 0) {
                    scannedChest = block;
                    break;
                }
            }

            helper.assertTrue(scannedChest != null, "scanned chest present");
            helper.assertTrue("minecraft:chest".equals(scannedChest.get("blockEntityType").getAsString()),
                    "block entity type captured");
            helper.assertTrue(scannedChest.get("nbt").getAsString().contains("Items"), "block entity NBT captured");
            helper.assertTrue(result.getAsJsonArray("entities").size() == 1, "entity scanned");
            JsonObject scannedEntity = result.getAsJsonArray("entities").get(0).getAsJsonObject();
            helper.assertTrue(scannedEntity.has("uuid"), "entity uuid captured");
            helper.assertTrue(scannedEntity.has("exactX"), "entity exactX captured");
            helper.assertTrue(scannedEntity.get("nbt").getAsString()
                    .contains("minecraft:comparator"), "entity NBT captured");
        } catch (JsonRpcException e) {
            helper.fail("Scan RPC should succeed: " + e.getMessage());
        } finally {
            if (itemEntity != null) {
                itemEntity.discard();
            }
            WorkspaceManager.get(level).removeWorkspace(name);
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_scan_supports_local_crop_ranges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkspaceHandler handler = new WorkspaceHandler();
        String name = "gt_scan_crop";
        BlockPos controllerPos = helper.absolutePos(new BlockPos(1, 6, 2));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, name, controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            level.setBlock(ws.toWorldPos(0, 0, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(ws.toWorldPos(2, 1, 3), Blocks.GLASS.defaultBlockState(), 3);
            level.setBlock(ws.toWorldPos(4, 3, 4), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);

            JsonObject scanParams = new JsonObject();
            scanParams.addProperty("name", name);
            scanParams.addProperty("fromX", 2);
            scanParams.addProperty("toX", 4);
            scanParams.addProperty("fromY", 1);
            scanParams.addProperty("toY", 3);
            scanParams.addProperty("fromZ", 3);
            scanParams.addProperty("toZ", 4);

            JsonObject result = handler.scan(new JsonRpcRequest("1", "workspace.scan", scanParams), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(result.get("fromX").getAsInt() == 2, "fromX captured");
            helper.assertTrue(result.get("toX").getAsInt() == 4, "toX captured");
            helper.assertTrue(result.get("fromY").getAsInt() == 1, "fromY captured");
            helper.assertTrue(result.get("toY").getAsInt() == 3, "toY captured");
            helper.assertTrue(result.get("fromZ").getAsInt() == 3, "fromZ captured");
            helper.assertTrue(result.get("toZ").getAsInt() == 4, "toZ captured");
            helper.assertTrue(result.getAsJsonArray("blocks").size() == 2, "only cropped blocks returned");
            for (var element : result.getAsJsonArray("blocks")) {
                JsonObject block = element.getAsJsonObject();
                helper.assertTrue(block.get("x").getAsInt() >= 2 && block.get("x").getAsInt() <= 4, "x inside crop");
                helper.assertTrue(block.get("y").getAsInt() >= 1 && block.get("y").getAsInt() <= 3, "y inside crop");
                helper.assertTrue(block.get("z").getAsInt() >= 3 && block.get("z").getAsInt() <= 4, "z inside crop");
            }
        } catch (JsonRpcException e) {
            helper.fail("Crop scan RPC should succeed: " + e.getMessage());
        } finally {
            WorkspaceManager.get(level).removeWorkspace(name);
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_configure_and_revert_rpc_support_full_ai_loop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkspaceHandler handler = new WorkspaceHandler();
        String name = "gt_config_revert";
        Workspace ws = null;

        try {
            JsonObject createParams = new JsonObject();
            createParams.addProperty("name", name);
            createParams.addProperty("sizeX", 5);
            createParams.addProperty("sizeY", 4);
            createParams.addProperty("sizeZ", 5);
            handler.create(new JsonRpcRequest("1", "workspace.create", createParams), level.getServer());

            ws = WorkspaceManager.get(level).getByName(name);
            helper.assertTrue(ws != null, "workspace created");

            JsonObject configureParams = new JsonObject();
            configureParams.addProperty("name", name);
            configureParams.addProperty("mode", "collaborative");
            configureParams.addProperty("entityFilter", "mechanical_only");
            configureParams.addProperty("allowVanillaCommands", false);
            configureParams.addProperty("allowFrozenEntityTeleport", false);
            JsonArray grants = new JsonArray();
            JsonObject grant = new JsonObject();
            grant.addProperty("player", "alice");
            JsonArray permissions = new JsonArray();
            permissions.add("build");
            permissions.add("time");
            permissions.add("history");
            grant.add("permissions", permissions);
            grants.add(grant);
            configureParams.add("permissionGrants", grants);

            JsonObject configured = handler.configure(
                    new JsonRpcRequest("2", "workspace.configure", configureParams),
                    level.getServer()
            ).getAsJsonObject();

            helper.assertTrue("collaborative".equals(configured.get("mode").getAsString()), "mode updated");
            helper.assertTrue("mechanical_only".equals(configured.get("entityFilter").getAsString()),
                    "entity filter updated");
            helper.assertTrue(!configured.get("allowVanillaCommands").getAsBoolean(), "command protection updated");
            helper.assertTrue(configured.getAsJsonArray("permissionGrants").size() == 1, "permission grant stored");

            BlockPos changedPos = ws.toWorldPos(1, 0, 1);
            level.setBlock(changedPos, Blocks.STONE.defaultBlockState(), 3);
            helper.assertTrue(level.getBlockState(changedPos).is(Blocks.STONE), "workspace mutated before revert");

            JsonObject revertParams = new JsonObject();
            revertParams.addProperty("name", name);
            JsonObject reverted = handler.revert(new JsonRpcRequest("3", "workspace.revert", revertParams), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(reverted.get("restoredBlocks").getAsInt() >= 1, "revert restored changed blocks");
            helper.assertTrue(level.getBlockState(changedPos).isAir(), "workspace reverted to baseline");
            helper.assertTrue(reverted.get("hasSnapshot").getAsBoolean(), "revert keeps baseline snapshot");

            level.setBlock(changedPos, Blocks.GLASS.defaultBlockState(), 3);
            JsonObject revertedAgain = handler.revert(new JsonRpcRequest("4", "workspace.revert", revertParams), level.getServer())
                    .getAsJsonObject();
            helper.assertTrue(revertedAgain.get("restoredBlocks").getAsInt() >= 1, "second revert also restores");
            helper.assertTrue(level.getBlockState(changedPos).isAir(), "second revert remains reusable");
        } catch (JsonRpcException e) {
            helper.fail("Configure/Revert RPC should succeed: " + e.getMessage());
        } finally {
            if (ws != null) {
                level.setBlock(ws.getControllerPos(), Blocks.AIR.defaultBlockState(), 3);
                WorkspaceManager.get(level).removeWorkspace(name);
            }
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void workspace_baseline_diff_reports_changed_blocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorkspaceHandler handler = new WorkspaceHandler();
        String name = "gt_baseline_diff";
        Workspace ws = null;

        try {
            JsonObject createParams = new JsonObject();
            createParams.addProperty("name", name);
            createParams.addProperty("sizeX", 5);
            createParams.addProperty("sizeY", 4);
            createParams.addProperty("sizeZ", 5);
            handler.create(new JsonRpcRequest("1", "workspace.create", createParams), level.getServer());

            ws = WorkspaceManager.get(level).getByName(name);
            helper.assertTrue(ws != null, "workspace created");

            BlockPos changedPos = ws.toWorldPos(2, 1, 3);
            level.setBlock(changedPos, Blocks.STONE.defaultBlockState(), 3);

            JsonObject diffParams = new JsonObject();
            diffParams.addProperty("name", name);
            diffParams.addProperty("fromX", 2);
            diffParams.addProperty("toX", 2);
            diffParams.addProperty("fromY", 1);
            diffParams.addProperty("toY", 1);
            diffParams.addProperty("fromZ", 3);
            diffParams.addProperty("toZ", 3);

            JsonObject diff = handler.baselineDiff(new JsonRpcRequest("2", "workspace.baseline_diff", diffParams), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(diff.get("changedBlocks").getAsInt() == 1, "one changed block reported");
            JsonObject change = diff.getAsJsonArray("changes").get(0).getAsJsonObject();
            helper.assertTrue(change.get("x").getAsInt() == 2, "x preserved");
            helper.assertTrue(change.get("y").getAsInt() == 1, "y preserved");
            helper.assertTrue(change.get("z").getAsInt() == 3, "z preserved");
            helper.assertTrue("minecraft:air".equals(change.get("baselineBlock").getAsString()), "baseline block tracked");
            helper.assertTrue("minecraft:stone".equals(change.get("currentBlock").getAsString()), "current block tracked");
        } catch (JsonRpcException e) {
            helper.fail("Baseline diff should succeed: " + e.getMessage());
        } finally {
            if (ws != null) {
                level.setBlock(ws.getControllerPos(), Blocks.AIR.defaultBlockState(), 3);
                WorkspaceManager.get(level).removeWorkspace(name);
            }
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void rpc_single_test_is_isolated_and_restores_input_fixture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos controllerPos = helper.absolutePos(new BlockPos(2, 7, 7));
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, 5, 4, 5);
        Workspace ws = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, "gt_probe_isolated", controllerPos, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        BlockPos leverPos = ws.toWorldPos(0, 0, 0);
        level.setBlock(leverPos, Blocks.LEVER.defaultBlockState(), 3);
        ws.addIOMarker(new IOMarker(leverPos, IOMarker.IORole.INPUT, "A"));

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            JsonObject inputs = new JsonObject();
            inputs.addProperty("A", 15);
            params.add("inputs", inputs);
            params.add("expected", new JsonObject());
            params.addProperty("ticks", 1);

            JsonObject result = new TestHandler()
                    .run(new JsonRpcRequest("1", "test.run", params), level.getServer())
                    .getAsJsonObject();

            helper.assertTrue(result.get("pass").getAsBoolean(), "single rpc test passed");
            helper.assertTrue(!ws.isFrozen(), "single rpc test restored unfrozen state");
            helper.assertTrue(TickController.getQueue(ws) == null, "single rpc test removed queue");
            helper.assertTrue(ws.getTimeline() == null, "single rpc test removed recording");
            helper.assertTrue(ws.getVirtualTick() == 0, "single rpc test restored virtual tick");
            helper.assertTrue(!level.getBlockState(leverPos).getValue(BlockStateProperties.POWERED),
                    "input fixture restored after probe-style test");
        } catch (JsonRpcException e) {
            helper.fail("Single RPC test should be isolated: " + e.getMessage());
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_probe_isolated");
        }
        helper.succeed();
    }

    @GameTest(template = T, batch = "workspace", timeoutTicks = 100)
    public static void io_handler_rejects_controller_position_mark(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        BoundingBox bounds = new BoundingBox(abs.getX(), abs.getY(), abs.getZ(),
                abs.getX() + 2, abs.getY() + 2, abs.getZ() + 2);
        Workspace ws = new Workspace(UUID.randomUUID(), new UUID(0, 0), "gt_io_handler", abs, bounds);
        WorkspaceManager.get(level).addWorkspace(ws);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("workspace", ws.getName());
            params.addProperty("x", 0);
            params.addProperty("y", 0);
            params.addProperty("z", 0);
            params.addProperty("role", "input");
            params.addProperty("label", "reserved");

            try {
                new IOHandler().mark(new JsonRpcRequest("1", "mark", params), level.getServer());
                helper.fail("Expected controller block position to be rejected");
            } catch (JsonRpcException e) {
                helper.assertTrue(e.getCode() == JsonRpcException.INVALID_PARAMS, "invalid params code");
            }
        } finally {
            WorkspaceManager.get(level).removeWorkspace("gt_io_handler");
        }
        helper.succeed();
    }
}
