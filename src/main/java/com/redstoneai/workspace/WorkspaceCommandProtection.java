package com.redstoneai.workspace;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;

/**
 * Shared command-level protection guards for protected workspaces.
 */
public final class WorkspaceCommandProtection {
    private static final SimpleCommandExceptionType BLOCKED_COMMAND =
            new SimpleCommandExceptionType(Component.literal("[RedstoneAI] Vanilla commands are blocked for protected workspaces."));
    private static final SimpleCommandExceptionType BLOCKED_ENTITY_COMMAND =
            new SimpleCommandExceptionType(Component.literal("[RedstoneAI] Command denied for a protected frozen entity."));

    private WorkspaceCommandProtection() {}

    public static void checkBlockMutation(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        Workspace workspace = WorkspaceAccessControl.findWorkspaceForCommandMutation(level, pos);
        if (workspace != null) {
            throw BLOCKED_COMMAND.create();
        }
    }

    public static void checkBlockMutation(CommandSourceStack source, BoundingBox area) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        Workspace workspace = WorkspaceAccessControl.findWorkspaceForCommandMutation(level, area);
        if (workspace != null) {
            throw BLOCKED_COMMAND.create();
        }
    }

    public static void checkEntityMutation(Entity entity) throws CommandSyntaxException {
        if (WorkspaceAccessControl.isFrozenEntityTeleportBlocked(entity) || WorkspaceAccessControl.isFrozenEntityDamageBlocked(entity)) {
            throw BLOCKED_ENTITY_COMMAND.create();
        }
    }

    @Nullable
    public static Workspace getProtectedWorkspace(Entity entity) {
        return WorkspaceAccessControl.findFrozenWorkspaceForEntity(entity);
    }
}
