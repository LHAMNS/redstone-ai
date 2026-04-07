package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.Workspace;
import com.redstoneai.workspace.WorkspaceBypassContext;
import com.redstoneai.workspace.WorkspaceManager;
import com.redstoneai.workspace.WorkspaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EntityHandler {

    public JsonElement spawn(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace);

        ServerLevel level = server.overworld();
        String entityTypeId = req.getStringParam("entityType");
        EntityType<?> entityType = resolveEntityType(entityTypeId);
        double worldX = workspace.getOriginPos().getX() + req.getDoubleParam("x");
        double worldY = workspace.getOriginPos().getY() + req.getDoubleParam("y");
        double worldZ = workspace.getOriginPos().getZ() + req.getDoubleParam("z");
        String nbtString = req.getStringParam("nbt", "{}");
        CompoundTag tag = parseCompoundTag(nbtString);
        tag.putString("id", entityTypeId);
        setPosition(tag, worldX, worldY, worldZ);
        applyOptionalRotation(req, tag);

        Entity entity = createEntity(level, tag, entityTypeId);
        ensureEditableEntity(entity, workspace);
        level.addFreshEntity(entity);
        TickController.invalidateRecording(level, workspace);
        return toEntityResult(workspace, entity, "spawned");
    }

    public JsonElement update(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace);

        ServerLevel level = server.overworld();
        Entity original = requireWorkspaceEntity(level, workspace, req.getStringParam("uuid"));
        CompoundTag originalTag = baseEntityTag(original).copy();

        String entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(original.getType()).toString();
        String mode = req.getStringParam("mode", "merge");
        boolean replace = switch (mode) {
            case "merge" -> false;
            case "replace" -> true;
            default -> throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "mode must be merge or replace");
        };
        CompoundTag patch = parseCompoundTag(req.getStringParam("nbt", "{}"));

        CompoundTag updatedTag = replace ? minimalEntityTag(original) : baseEntityTag(original);
        updatedTag.merge(patch.copy());
        updatedTag.putString("id", entityTypeId);
        updatedTag.putUUID("UUID", original.getUUID());

        if (hasAnyPositionOverride(req)) {
            double worldX = workspace.getOriginPos().getX() + req.getDoubleParam("x", original.getX() - workspace.getOriginPos().getX());
            double worldY = workspace.getOriginPos().getY() + req.getDoubleParam("y", original.getY() - workspace.getOriginPos().getY());
            double worldZ = workspace.getOriginPos().getZ() + req.getDoubleParam("z", original.getZ() - workspace.getOriginPos().getZ());
            setPosition(updatedTag, worldX, worldY, worldZ);
        }
        applyOptionalRotation(req, updatedTag);

        Entity replacement = createEntity(level, updatedTag, entityTypeId);
        ensureEditableEntity(replacement, workspace);
        WorkspaceBypassContext.runWithEntityRemovalBypass(original::discard);
        if (!level.addFreshEntity(replacement)) {
            restoreOriginalEntity(level, originalTag, entityTypeId);
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Failed to apply entity update for " + original.getUUID());
        }
        TickController.invalidateRecording(level, workspace);
        return toEntityResult(workspace, replacement, "updated");
    }

    public JsonElement remove(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace);

        ServerLevel level = server.overworld();
        Entity entity = requireWorkspaceEntity(level, workspace, req.getStringParam("uuid"));
        WorkspaceBypassContext.runWithEntityRemovalBypass(entity::discard);
        TickController.invalidateRecording(level, workspace);

        JsonObject result = new JsonObject();
        result.addProperty("removed", true);
        result.addProperty("uuid", entity.getUUID().toString());
        return result;
    }

    public JsonElement clear(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace);

        ServerLevel level = server.overworld();
        String typeFilter = req.getStringParam("entityType", "");
        ResourceLocation typeId = typeFilter.isBlank() ? null : ResourceLocation.tryParse(typeFilter);
        if (!typeFilter.isBlank() && typeId == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid entity type: " + typeFilter);
        }

        List<Entity> toRemove = new ArrayList<>(level.getEntitiesOfClass(Entity.class, workspace.getAABB(),
                entity -> !(entity instanceof Player)
                        && workspace.containsEntityFully(entity)
                        && (typeId == null || typeId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))));
        for (Entity entity : toRemove) {
            WorkspaceBypassContext.runWithEntityRemovalBypass(entity::discard);
        }
        if (!toRemove.isEmpty()) {
            TickController.invalidateRecording(level, workspace);
        }

        JsonObject result = new JsonObject();
        result.addProperty("cleared", toRemove.size());
        if (typeId != null) {
            result.addProperty("entityType", typeId.toString());
        }
        return result;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String workspaceName = req.getStringParam("workspace");
        ServerLevel level = server.overworld();
        Workspace workspace = WorkspaceManager.get(level).getByName(workspaceName);
        if (workspace == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace not found: " + workspaceName);
        }
        return workspace;
    }

    private void requireApiOwned(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.API_OWNER.equals(workspace.getOwnerUUID())) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Entity RPC operations are only allowed on API-owned workspaces");
        }
    }

    private void enforceAiMutationAllowed(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.canAiModify(workspace)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + workspace.getProtectionMode().getSerializedName() + "' does not allow AI mutation");
        }
    }

    private EntityType<?> resolveEntityType(String entityTypeId) throws JsonRpcException {
        ResourceLocation resourceLocation = ResourceLocation.tryParse(entityTypeId);
        if (resourceLocation == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid entity type: " + entityTypeId);
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(resourceLocation)
                .orElseThrow(() -> new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "Unknown entity type: " + entityTypeId));
    }

    private CompoundTag parseCompoundTag(String raw) throws JsonRpcException {
        try {
            return TagParser.parseTag(raw);
        } catch (CommandSyntaxException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid SNBT: " + e.getMessage());
        }
    }

    private Entity createEntity(ServerLevel level, CompoundTag tag, String entityTypeId) throws JsonRpcException {
        Entity entity = EntityType.loadEntityRecursive(tag, level, created -> created);
        if (entity == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Failed to create entity from NBT for type " + entityTypeId);
        }
        return entity;
    }

    private void ensureEditableEntity(Entity entity, Workspace workspace) throws JsonRpcException {
        if (entity instanceof Player) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Players cannot be spawned or edited via RPC");
        }
        if (!workspace.containsEntityFully(entity)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Entity must remain fully inside the workspace bounds");
        }
    }

    private Entity requireWorkspaceEntity(ServerLevel level, Workspace workspace, String uuidString) throws JsonRpcException {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid UUID: " + uuidString);
        }

        Entity entity = level.getEntity(uuid);
        if (entity == null || entity instanceof Player || !workspace.containsEntityFully(entity)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace entity not found: " + uuidString);
        }
        return entity;
    }

    private CompoundTag baseEntityTag(Entity entity) {
        CompoundTag tag = new CompoundTag();
        entity.saveWithoutId(tag);
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        tag.putUUID("UUID", entity.getUUID());
        return tag;
    }

    private CompoundTag minimalEntityTag(Entity entity) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        tag.putUUID("UUID", entity.getUUID());
        setPosition(tag, entity.getX(), entity.getY(), entity.getZ());
        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        tag.put("Motion", motion);
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(entity.getYRot()));
        rotation.add(FloatTag.valueOf(entity.getXRot()));
        tag.put("Rotation", rotation);
        return tag;
    }

    private void setPosition(CompoundTag tag, double x, double y, double z) {
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
    }

    private boolean hasAnyPositionOverride(JsonRpcRequest req) {
        JsonObject params = req.params();
        return params != null && (params.has("x") || params.has("y") || params.has("z"));
    }

    private void applyOptionalRotation(JsonRpcRequest req, CompoundTag tag) {
        JsonObject params = req.params();
        if (params == null || (!params.has("yaw") && !params.has("pitch"))) {
            return;
        }
        float yaw = params.has("yaw")
                ? (float) req.getDoubleParam("yaw", 0.0D)
                : getRotationComponent(tag, 0, 0.0F);
        float pitch = params.has("pitch")
                ? (float) req.getDoubleParam("pitch", 0.0D)
                : getRotationComponent(tag, 1, 0.0F);
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        tag.put("Rotation", rotation);
    }

    private float getRotationComponent(CompoundTag tag, int index, float fallback) {
        if (!tag.contains("Rotation", net.minecraft.nbt.Tag.TAG_LIST)) {
            return fallback;
        }
        ListTag rotation = tag.getList("Rotation", net.minecraft.nbt.Tag.TAG_FLOAT);
        if (rotation.size() <= index) {
            return fallback;
        }
        return rotation.getFloat(index);
    }

    private void restoreOriginalEntity(ServerLevel level, CompoundTag originalTag, String entityTypeId) throws JsonRpcException {
        Entity restoredOriginal = createEntity(level, originalTag, entityTypeId);
        if (!level.addFreshEntity(restoredOriginal)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Entity update failed and original entity could not be restored");
        }
    }

    private JsonObject toEntityResult(Workspace workspace, Entity entity, String verb) {
        JsonObject result = new JsonObject();
        result.addProperty(verb, true);
        result.addProperty("uuid", entity.getUUID().toString());
        result.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        result.addProperty("x", entity.getX() - workspace.getOriginPos().getX());
        result.addProperty("y", entity.getY() - workspace.getOriginPos().getY());
        result.addProperty("z", entity.getZ() - workspace.getOriginPos().getZ());
        CompoundTag nbt = new CompoundTag();
        entity.saveWithoutId(nbt);
        if (!nbt.isEmpty()) {
            result.addProperty("nbt", nbt.toString());
        }
        return result;
    }
}
