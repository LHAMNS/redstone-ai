package com.redstoneai.network.rpc.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.redstoneai.config.RAIConfig;
import com.redstoneai.network.rpc.JsonRpcException;
import com.redstoneai.network.rpc.JsonRpcRequest;
import com.redstoneai.recording.IOMarker;
import com.redstoneai.tick.TickController;
import com.redstoneai.workspace.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class WorkspaceHandler {

    public JsonElement create(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("name");
        int sizeX = req.getIntParam("sizeX", 16);
        int sizeY = req.getIntParam("sizeY", 8);
        int sizeZ = req.getIntParam("sizeZ", 16);

        String nameError = WorkspaceRules.validateWorkspaceName(name);
        if (nameError != null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, nameError);
        }

        String sizeError = WorkspaceRules.validateDimensions(sizeX, sizeY, sizeZ);
        if (sizeError != null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, sizeError);
        }

        ServerLevel level = server.overworld();
        WorkspaceManager manager = WorkspaceManager.get(level);

        if (manager.hasWorkspace(name)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace '" + name + "' already exists");
        }
        if (manager.getWorkspacesOwnedBy(WorkspaceRules.API_OWNER).size() >= RAIConfig.SERVER.maxWorkspacesPerPlayer.get()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Too many API-managed workspaces");
        }

        BlockPos controllerPos = findAvailableControllerPos(level, manager, sizeX, sizeY, sizeZ);
        BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, sizeX, sizeY, sizeZ);
        BlockPos originPos = WorkspaceRules.originFromBounds(bounds);

        level.setBlock(controllerPos, com.redstoneai.registry.RAIBlocks.WORKSPACE_CONTROLLER.get().defaultBlockState(), 3);

        Workspace workspace = new Workspace(UUID.randomUUID(), WorkspaceRules.API_OWNER, name, controllerPos, originPos, bounds);
        manager.addWorkspace(workspace);

        if (level.getBlockEntity(controllerPos) instanceof WorkspaceControllerBlockEntity controller) {
            controller.setWorkspaceName(name);
            controller.setSize(sizeX, sizeY, sizeZ);
            controller.setInitialSnapshot(InitialSnapshot.capture(level, bounds));
            controller.getOperationLog().logSystem("create", "RPC workspace '" + name + "' created");
            applyControllerSettings(workspace, controller);
        }

        JsonObject result = new JsonObject();
        result.addProperty("workspace", name);
        result.add("origin", toIntArray(originPos));
        result.add("controller", toIntArray(controllerPos));
        result.add("size", toSizeArray(sizeX, sizeY, sizeZ));
        return result;
    }

    public JsonElement delete(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        ServerLevel level = server.overworld();
        WorkspaceManager manager = WorkspaceManager.get(level);

        if (workspace.isFrozen()) {
            TickController.discardFrozenState(level, workspace);
        }
        TickController.removeQueue(workspace.getId());

        if (level.getBlockEntity(workspace.getControllerPos()) instanceof WorkspaceControllerBlockEntity controller) {
            controller.setWorkspaceName("");
            controller.setInitialSnapshot(null);
        }

        manager.removeWorkspace(workspace.getName());

        JsonObject result = new JsonObject();
        result.addProperty("deleted", workspace.getName());
        return result;
    }

    public JsonElement list(JsonRpcRequest req, MinecraftServer server) {
        ServerLevel level = server.overworld();
        WorkspaceManager manager = WorkspaceManager.get(level);

        JsonArray workspaces = new JsonArray();
        for (Workspace workspace : manager.getAllWorkspacesSnapshot()) {
            if (!WorkspaceRules.API_OWNER.equals(workspace.getOwnerUUID())) {
                continue;
            }
            workspaces.add(buildWorkspaceInfo(workspace, getController(level, workspace)));
        }

        JsonObject result = new JsonObject();
        result.add("workspaces", workspaces);
        return result;
    }

    public JsonElement info(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        return buildWorkspaceInfo(workspace, getController(server.overworld(), workspace));
    }

    public JsonElement configure(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);

        JsonObject params = req.params() != null ? req.params() : new JsonObject();
        ServerLevel level = server.overworld();
        WorkspaceControllerBlockEntity controller = getController(level, workspace);
        boolean changed = false;

        if (params.has("mode")) {
            ProtectionMode mode = parseProtectionMode(params.get("mode").getAsString());
            if (mode == null) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid protection mode");
            }
            workspace.setProtectionMode(mode);
            if (controller != null) {
                controller.setProtectionMode(mode);
            }
            changed = true;
        }

        if (params.has("entityFilter")) {
            EntityFilterMode filterMode = parseEntityFilterMode(params.get("entityFilter").getAsString());
            if (filterMode == null) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid entity filter mode");
            }
            workspace.setEntityFilterMode(filterMode);
            if (controller != null) {
                controller.setEntityFilterMode(filterMode);
            }
            TickController.invalidateRecording(level, workspace);
            changed = true;
        }

        if (params.has("authorizedPlayers")) {
            List<String> players = parseAuthorizedPlayers(params.get("authorizedPlayers"));
            workspace.replaceAuthorizedPlayers(players);
            if (controller != null) {
                controller.replaceAuthorizedPlayers(players);
            }
            changed = true;
        }

        if (params.has("permissionGrants")) {
            List<PlayerPermissionGrant> grants = parsePermissionGrants(params.get("permissionGrants"));
            workspace.replacePlayerPermissionGrants(grants);
            if (controller != null) {
                controller.replacePlayerPermissionGrants(grants);
            }
            changed = true;
        }

        if (params.has("allowVanillaCommands")) {
            boolean enabled = getBooleanParam(params, "allowVanillaCommands");
            workspace.setAllowVanillaCommands(enabled);
            if (controller != null) {
                controller.setAllowVanillaCommands(enabled);
            }
            changed = true;
        }

        if (params.has("allowFrozenEntityTeleport")) {
            boolean enabled = getBooleanParam(params, "allowFrozenEntityTeleport");
            workspace.setAllowFrozenEntityTeleport(enabled);
            if (controller != null) {
                controller.setAllowFrozenEntityTeleport(enabled);
            }
            changed = true;
        }

        if (params.has("allowFrozenEntityDamage")) {
            boolean enabled = getBooleanParam(params, "allowFrozenEntityDamage");
            workspace.setAllowFrozenEntityDamage(enabled);
            if (controller != null) {
                controller.setAllowFrozenEntityDamage(enabled);
            }
            changed = true;
        }

        if (params.has("allowFrozenEntityCollision")) {
            boolean enabled = getBooleanParam(params, "allowFrozenEntityCollision");
            workspace.setAllowFrozenEntityCollision(enabled);
            if (controller != null) {
                controller.setAllowFrozenEntityCollision(enabled);
            }
            changed = true;
        }

        if (!changed) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "No workspace settings were provided");
        }

        WorkspaceManager.get(level).setDirty();
        if (controller != null) {
            controller.getOperationLog().logSystem("config", "RPC settings updated");
        }
        return buildWorkspaceInfo(workspace, controller);
    }

    public JsonElement history(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);

        int limit = req.getIntParam("limit", 20);
        if (limit < 1 || limit > 200) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "History limit must be between 1 and 200");
        }

        ServerLevel level = server.overworld();
        WorkspaceControllerBlockEntity controller = requireController(level, workspace, "history");

        JsonObject result = buildWorkspaceInfo(workspace, controller);
        result.add("logEntries", buildOperationLogJson(controller.getOperationLog(), limit));
        result.add("chatMessages", buildChatHistoryJson(controller.getChatHistory(), limit));
        return result;
    }

    public JsonElement baselineDiff(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);

        ServerLevel level = server.overworld();
        WorkspaceControllerBlockEntity controller = requireController(level, workspace, "baseline_diff");
        InitialSnapshot snapshot = controller.getInitialSnapshot();
        if (snapshot == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace has no initial snapshot");
        }

        AxisWindow xWindow = normalizeAxisWindow(
                req.getIntParam("fromX", 0),
                req.getIntParam("toX", workspace.getSizeX() - 1),
                workspace.getSizeX()
        );
        AxisWindow yWindow = normalizeAxisWindow(
                req.getIntParam("fromY", 0),
                req.getIntParam("toY", workspace.getSizeY() - 1),
                workspace.getSizeY()
        );
        AxisWindow zWindow = normalizeAxisWindow(
                req.getIntParam("fromZ", 0),
                req.getIntParam("toZ", workspace.getSizeZ() - 1),
                workspace.getSizeZ()
        );

        JsonArray changes = new JsonArray();
        int changedCount = 0;
        for (int x = xWindow.from(); x <= xWindow.to(); x++) {
            for (int y = yWindow.from(); y <= yWindow.to(); y++) {
                for (int z = zWindow.from(); z <= zWindow.to(); z++) {
                    BlockPos worldPos = workspace.toWorldPos(x, y, z);
                    BlockState baselineState = snapshot.getBlockState(worldPos);
                    BlockState currentState = level.getBlockState(worldPos);
                    CompoundTag baselineTag = snapshot.getTileEntityData(worldPos);
                    BlockEntity currentBe = level.getBlockEntity(worldPos);
                    CompoundTag currentTag = currentBe != null ? currentBe.saveWithoutMetadata() : null;

                    if (baselineState.equals(currentState) && tagsEqual(baselineTag, currentTag)) {
                        continue;
                    }

                    JsonObject change = new JsonObject();
                    change.addProperty("x", x);
                    change.addProperty("y", y);
                    change.addProperty("z", z);
                    change.addProperty("baselineBlock", BuiltInRegistries.BLOCK.getKey(baselineState.getBlock()).toString());
                    change.addProperty("currentBlock", BuiltInRegistries.BLOCK.getKey(currentState.getBlock()).toString());
                    if (baselineTag != null && !baselineTag.isEmpty()) {
                        change.addProperty("baselineNbt", baselineTag.toString());
                    }
                    if (currentTag != null && !currentTag.isEmpty()) {
                        change.addProperty("currentNbt", currentTag.toString());
                    }
                    changes.add(change);
                    changedCount++;
                }
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("name", workspace.getName());
        result.add("size", toSizeArray(workspace.getSizeX(), workspace.getSizeY(), workspace.getSizeZ()));
        result.addProperty("fromX", xWindow.from());
        result.addProperty("toX", xWindow.to());
        result.addProperty("fromY", yWindow.from());
        result.addProperty("toY", yWindow.to());
        result.addProperty("fromZ", zWindow.from());
        result.addProperty("toZ", zWindow.to());
        result.addProperty("changedBlocks", changedCount);
        result.add("changes", changes);
        return result;
    }

    public JsonElement revert(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);

        ServerLevel level = server.overworld();
        WorkspaceControllerBlockEntity controller = requireController(level, workspace, "revert");
        InitialSnapshot snapshot = controller.getInitialSnapshot();
        if (snapshot == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace has no initial snapshot to restore");
        }

        boolean wasFrozen = workspace.isFrozen();
        int changed = snapshot.restore(level);
        BlockPos restoredControllerPos = snapshot.getControllerPos() != null ? snapshot.getControllerPos() : workspace.getControllerPos();
        WorkspaceControllerBlockEntity restoredController = level.getBlockEntity(restoredControllerPos) instanceof WorkspaceControllerBlockEntity be
                ? be
                : controller;

        WorkspaceManager manager = WorkspaceManager.get(level);
        manager.updateWorkspaceGeometry(
                workspace,
                snapshot.getBounds(),
                restoredControllerPos,
                WorkspaceRules.originFromBounds(snapshot.getBounds())
        );
        workspace.setTimeline(null);
        workspace.setVirtualTick(0);
        TickController.removeQueue(workspace.getId());
        if (wasFrozen) {
            TickController.discardFrozenState(level, workspace);
        }
        restoredController.setInitialSnapshot(snapshot);
        applyControllerSettings(workspace, restoredController);
        restoredController.getOperationLog().logSystem("revert", "RPC reverted " + changed + " blocks to initial state");

        JsonObject result = buildWorkspaceInfo(workspace, restoredController);
        result.addProperty("restoredBlocks", changed);
        return result;
    }

    public JsonElement scan(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        ServerLevel level = server.overworld();
        BoundingBox bounds = workspace.getBounds();
        AxisWindow xWindow = normalizeAxisWindow(
                req.getIntParam("fromX", 0),
                req.getIntParam("toX", workspace.getSizeX() - 1),
                workspace.getSizeX()
        );
        AxisWindow yWindow = normalizeAxisWindow(
                req.getIntParam("fromY", 0),
                req.getIntParam("toY", workspace.getSizeY() - 1),
                workspace.getSizeY()
        );
        AxisWindow zWindow = normalizeAxisWindow(
                req.getIntParam("fromZ", 0),
                req.getIntParam("toZ", workspace.getSizeZ() - 1),
                workspace.getSizeZ()
        );
        int minX = bounds.minX() + xWindow.from();
        int minY = bounds.minY() + yWindow.from();
        int minZ = bounds.minZ() + zWindow.from();
        int maxX = bounds.minX() + xWindow.to();
        int maxY = bounds.minY() + yWindow.to();
        int maxZ = bounds.minZ() + zWindow.to();
        net.minecraft.world.phys.AABB cropBox = new net.minecraft.world.phys.AABB(
                minX,
                minY,
                minZ,
                maxX + 1.0,
                maxY + 1.0,
                maxZ + 1.0
        );

        JsonArray blocks = new JsonArray();
        JsonObject blockCounts = new JsonObject();
        int nonAirBlocks = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    nonAirBlocks++;
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    JsonObject blockJson = new JsonObject();
                    BlockPos rel = workspace.toRelativePos(pos);
                    blockJson.addProperty("x", rel.getX());
                    blockJson.addProperty("y", rel.getY());
                    blockJson.addProperty("z", rel.getZ());
                    blockJson.addProperty("block", blockId);
                    JsonObject props = new JsonObject();
                    for (var property : state.getProperties()) {
                        props.addProperty(property.getName(), state.getValue(property).toString());
                    }
                    blockJson.add("properties", props);

                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        blockJson.addProperty("blockEntityType",
                                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
                        CompoundTag tag = blockEntity.saveWithoutMetadata();
                        if (!tag.isEmpty()) {
                            blockJson.addProperty("nbt", tag.toString());
                        }
                    }

                    blocks.add(blockJson);
                    blockCounts.addProperty(blockId, blockCounts.has(blockId) ? blockCounts.get(blockId).getAsInt() + 1 : 1);
                }
            }
        }

        JsonArray entities = new JsonArray();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, cropBox,
                candidate -> !(candidate instanceof Player)
                        && workspace.containsEntityFully(candidate)
                        && entityFitsInside(candidate.getBoundingBox(), cropBox))) {
            JsonObject entityJson = new JsonObject();
            BlockPos rel = workspace.toRelativePos(BlockPos.containing(entity.position()));
            entityJson.addProperty("uuid", entity.getUUID().toString());
            entityJson.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entityJson.addProperty("x", rel.getX());
            entityJson.addProperty("y", rel.getY());
            entityJson.addProperty("z", rel.getZ());
            entityJson.addProperty("exactX", entity.getX() - workspace.getOriginPos().getX());
            entityJson.addProperty("exactY", entity.getY() - workspace.getOriginPos().getY());
            entityJson.addProperty("exactZ", entity.getZ() - workspace.getOriginPos().getZ());
            CompoundTag entityTag = new CompoundTag();
            entity.saveWithoutId(entityTag);
            if (!entityTag.isEmpty()) {
                entityJson.addProperty("nbt", entityTag.toString());
            }
            entities.add(entityJson);
        }

        JsonArray ioMarkers = new JsonArray();
        for (IOMarker marker : workspace.getIOMarkers()) {
            if (!cropBox.contains(
                    marker.pos().getX() + 0.5,
                    marker.pos().getY() + 0.5,
                    marker.pos().getZ() + 0.5
            )) {
                continue;
            }
            JsonObject markerJson = new JsonObject();
            BlockPos rel = workspace.toRelativePos(marker.pos());
            markerJson.addProperty("label", marker.label());
            markerJson.addProperty("role", marker.role().getSerializedName());
            markerJson.addProperty("x", rel.getX());
            markerJson.addProperty("y", rel.getY());
            markerJson.addProperty("z", rel.getZ());
            ioMarkers.add(markerJson);
        }

        JsonObject result = new JsonObject();
        result.addProperty("name", workspace.getName());
        result.add("origin", toIntArray(workspace.getOriginPos()));
        result.add("controller", toIntArray(workspace.getControllerPos()));
        result.add("size", toSizeArray(workspace.getSizeX(), workspace.getSizeY(), workspace.getSizeZ()));
        result.addProperty("fromX", xWindow.from());
        result.addProperty("toX", xWindow.to());
        result.addProperty("nonAirBlocks", nonAirBlocks);
        result.addProperty("fromY", yWindow.from());
        result.addProperty("toY", yWindow.to());
        result.addProperty("fromZ", zWindow.from());
        result.addProperty("toZ", zWindow.to());
        result.add("blockCounts", blockCounts);
        result.add("blocks", blocks);
        result.add("entities", entities);
        result.add("ioMarkers", ioMarkers);
        result.add("permissionGrants", buildPermissionGrantsJson(workspace.getPlayerPermissionGrants()));
        return result;
    }

    public JsonElement clear(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        enforceAiMutationAllowed(workspace, "clear");

        ServerLevel level = server.overworld();
        BoundingBox bounds = workspace.getBounds();
        int cleared = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!WorkspaceRules.isEditableBlock(workspace, pos)) {
                        continue;
                    }
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        cleared++;
                    }
                }
            }
        }

        TickController.invalidateRecording(level, workspace);

        JsonObject result = new JsonObject();
        result.addProperty("cleared", cleared);
        return result;
    }

    public JsonElement setMode(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        Workspace workspace = getWorkspace(req, server);
        requireApiOwned(workspace);
        ProtectionMode mode = parseProtectionMode(req.getStringParam("mode"));
        if (mode == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Invalid protection mode");
        }

        workspace.setProtectionMode(mode);
        WorkspaceControllerBlockEntity controller = getController(server.overworld(), workspace);
        if (controller != null) {
            controller.setProtectionMode(mode);
        }
        WorkspaceManager.get(server.overworld()).setDirty();

        JsonObject result = new JsonObject();
        result.addProperty("mode", workspace.getProtectionMode().getSerializedName());
        return result;
    }

    private Workspace getWorkspace(JsonRpcRequest req, MinecraftServer server) throws JsonRpcException {
        String name = req.getStringParam("name");
        ServerLevel level = server.overworld();
        Workspace workspace = WorkspaceManager.get(level).getByName(name);
        if (workspace == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Workspace not found: " + name);
        }
        return workspace;
    }

    private void enforceAiMutationAllowed(Workspace workspace, String operation) throws JsonRpcException {
        if (!WorkspaceRules.canAiModify(workspace)) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "Workspace mode '" + workspace.getProtectionMode().getSerializedName()
                            + "' does not allow AI to " + operation);
        }
    }

    private void requireApiOwned(Workspace workspace) throws JsonRpcException {
        if (!WorkspaceRules.API_OWNER.equals(workspace.getOwnerUUID())) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "This RPC operation is only allowed on API-owned workspaces");
        }
    }

    private JsonObject buildWorkspaceInfo(Workspace workspace, @Nullable WorkspaceControllerBlockEntity controller) {
        JsonObject result = new JsonObject();
        result.addProperty("name", workspace.getName());
        result.addProperty("frozen", workspace.isFrozen());
        result.addProperty("mode", workspace.getProtectionMode().getSerializedName());
        result.addProperty("entityFilter", workspace.getEntityFilterMode().getSerializedName());
        result.addProperty("authorizedPlayers", String.join(",", workspace.getAuthorizedPlayers()));
        result.addProperty("allowVanillaCommands", workspace.isAllowVanillaCommands());
        result.addProperty("allowFrozenEntityTeleport", workspace.isAllowFrozenEntityTeleport());
        result.addProperty("allowFrozenEntityDamage", workspace.isAllowFrozenEntityDamage());
        result.addProperty("allowFrozenEntityCollision", workspace.isAllowFrozenEntityCollision());
        result.add("permissionGrants", buildPermissionGrantsJson(workspace.getPlayerPermissionGrants()));
        result.addProperty("virtualTick", workspace.getVirtualTick());
        result.addProperty("ioMarkers", workspace.getIOMarkers().size());
        result.addProperty("hasSnapshot", controller != null && controller.hasInitialSnapshot());
        result.addProperty("logEntryCount", controller != null ? controller.getOperationLog().size() : 0);
        result.addProperty("chatMessageCount", controller != null ? controller.getChatHistory().size() : 0);
        if (workspace.getTimeline() != null) {
            result.addProperty("recordingLength", workspace.getTimeline().getLength());
            result.addProperty("recordingPosition", workspace.getTimeline().getCurrentIndex() + 1);
        }
        result.add("origin", toIntArray(workspace.getOriginPos()));
        result.add("controller", toIntArray(workspace.getControllerPos()));
        result.add("size", toSizeArray(workspace.getSizeX(), workspace.getSizeY(), workspace.getSizeZ()));
        return result;
    }

    private static JsonArray buildPermissionGrantsJson(List<PlayerPermissionGrant> grants) {
        JsonArray grantsJson = new JsonArray();
        for (PlayerPermissionGrant grant : grants) {
            JsonObject grantJson = new JsonObject();
            grantJson.addProperty("player", grant.playerName());
            JsonArray permissions = new JsonArray();
            for (WorkspacePermission permission : grant.permissions()) {
                permissions.add(permission.getSerializedName());
            }
            grantJson.add("permissions", permissions);
            grantsJson.add(grantJson);
        }
        return grantsJson;
    }

    private static JsonArray buildOperationLogJson(OperationLog operationLog, int limit) {
        JsonArray entriesJson = new JsonArray();
        List<OperationLog.Entry> entries = operationLog.getEntries();
        int from = Math.max(0, entries.size() - limit);
        for (int i = from; i < entries.size(); i++) {
            OperationLog.Entry entry = entries.get(i);
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("timestamp", entry.timestamp());
            entryJson.addProperty("source", entry.source());
            entryJson.addProperty("action", entry.action());
            entryJson.addProperty("detail", entry.detail());
            entryJson.addProperty("display", entry.toDisplayString());
            entriesJson.add(entryJson);
        }
        return entriesJson;
    }

    private static JsonArray buildChatHistoryJson(List<WorkspaceControllerBlockEntity.ChatMessage> messages, int limit) {
        JsonArray messagesJson = new JsonArray();
        int from = Math.max(0, messages.size() - limit);
        for (int i = from; i < messages.size(); i++) {
            WorkspaceControllerBlockEntity.ChatMessage message = messages.get(i);
            JsonObject messageJson = new JsonObject();
            messageJson.addProperty("timestamp", message.timestamp());
            messageJson.addProperty("role", message.role());
            messageJson.addProperty("content", message.content());
            messagesJson.add(messageJson);
        }
        return messagesJson;
    }

    @Nullable
    private WorkspaceControllerBlockEntity getController(ServerLevel level, Workspace workspace) {
        return level.getBlockEntity(workspace.getControllerPos()) instanceof WorkspaceControllerBlockEntity controller
                ? controller
                : null;
    }

    private WorkspaceControllerBlockEntity requireController(ServerLevel level, Workspace workspace, String operation)
            throws JsonRpcException {
        WorkspaceControllerBlockEntity controller = getController(level, workspace);
        if (controller == null) {
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                    "Workspace controller is missing for RPC " + operation);
        }
        return controller;
    }

    private static void applyControllerSettings(Workspace workspace, WorkspaceControllerBlockEntity controller) {
        workspace.setProtectionMode(controller.getProtectionMode());
        workspace.setEntityFilterMode(controller.getEntityFilterMode());
        workspace.replaceAuthorizedPlayers(controller.getAuthorizedPlayers());
        workspace.replacePlayerPermissionGrants(controller.getPlayerPermissionGrants());
        workspace.setAllowVanillaCommands(controller.isAllowVanillaCommands());
        workspace.setAllowFrozenEntityTeleport(controller.isAllowFrozenEntityTeleport());
        workspace.setAllowFrozenEntityDamage(controller.isAllowFrozenEntityDamage());
        workspace.setAllowFrozenEntityCollision(controller.isAllowFrozenEntityCollision());
    }

    @Nullable
    private static ProtectionMode parseProtectionMode(String name) {
        return ProtectionMode.tryParse(name);
    }

    @Nullable
    private static EntityFilterMode parseEntityFilterMode(String name) {
        for (EntityFilterMode mode : EntityFilterMode.values()) {
            if (mode.getSerializedName().equals(name)) {
                return mode;
            }
        }
        return null;
    }

    private static List<String> parseAuthorizedPlayers(JsonElement element) throws JsonRpcException {
        if (element.isJsonPrimitive()) {
            return WorkspaceAccessControl.parseAuthorizedPlayers(element.getAsString());
        }
        if (!element.isJsonArray()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                    "authorizedPlayers must be a comma-separated string or array");
        }

        List<String> players = new ArrayList<>();
        for (JsonElement playerElement : element.getAsJsonArray()) {
            if (!playerElement.isJsonPrimitive()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "authorizedPlayers entries must be strings");
            }
            String normalized = WorkspaceAccessControl.normalizePlayerName(playerElement.getAsString());
            if (!normalized.isBlank() && !players.contains(normalized)) {
                players.add(normalized);
            }
        }
        return players;
    }

    private static List<PlayerPermissionGrant> parsePermissionGrants(JsonElement element) throws JsonRpcException {
        if (!element.isJsonArray()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "permissionGrants must be an array");
        }

        List<PlayerPermissionGrant> grants = new ArrayList<>();
        for (JsonElement grantElement : element.getAsJsonArray()) {
            if (!grantElement.isJsonObject()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "permissionGrants entries must be objects");
            }
            JsonObject grantJson = grantElement.getAsJsonObject();
            if (!grantJson.has("player") || !grantJson.get("player").isJsonPrimitive()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "permissionGrants entries must include a player");
            }
            String playerName = WorkspaceAccessControl.normalizePlayerName(grantJson.get("player").getAsString());
            if (playerName.isBlank()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "permissionGrants player name cannot be blank");
            }
            if (!grantJson.has("permissions")) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "permissionGrants entries must include permissions");
            }

            EnumSet<WorkspacePermission> permissions = EnumSet.noneOf(WorkspacePermission.class);
            JsonElement permissionsElement = grantJson.get("permissions");
            if (permissionsElement.isJsonArray()) {
                for (JsonElement permissionElement : permissionsElement.getAsJsonArray()) {
                    if (!permissionElement.isJsonPrimitive()) {
                        throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                                "permission values must be strings");
                    }
                    WorkspacePermission permission = WorkspacePermission.tryParse(permissionElement.getAsString());
                    if (permission == null) {
                        throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                                "Invalid workspace permission: " + permissionElement.getAsString());
                    }
                    permissions.add(permission);
                }
            } else if (permissionsElement.isJsonPrimitive()) {
                for (String token : permissionsElement.getAsString().split(",")) {
                    WorkspacePermission permission = WorkspacePermission.tryParse(token.trim());
                    if (permission == null) {
                        throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                                "Invalid workspace permission: " + token.trim());
                    }
                    permissions.add(permission);
                }
            } else {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "permissions must be a string or array of strings");
            }

            if (permissions.isEmpty()) {
                throw new JsonRpcException(JsonRpcException.INVALID_PARAMS,
                        "permissionGrants entries must include at least one permission");
            }
            grants.add(new PlayerPermissionGrant(playerName, permissions));
        }
        return grants;
    }

    private static boolean getBooleanParam(JsonObject params, String key) throws JsonRpcException {
        JsonElement element = params.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, key + " must be a boolean");
        }
        try {
            return element.getAsBoolean();
        } catch (UnsupportedOperationException e) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, key + " must be a boolean");
        }
    }

    private static BlockPos findAvailableControllerPos(ServerLevel level, WorkspaceManager manager,
                                                       int sizeX, int sizeY, int sizeZ) throws JsonRpcException {
        int spacing = Math.max(96, sizeX + 16);
        for (int attempt = 0; attempt < 128; attempt++) {
            BlockPos controllerPos = new BlockPos(1000 + attempt * spacing, 64 + sizeY, 1000);
            BoundingBox bounds = WorkspaceRules.createBoundsFromController(controllerPos, sizeX, sizeY, sizeZ);
            if (manager.checkOverlap(bounds) != null) continue;
            // Check the target area is mostly empty to avoid overwriting player builds
            if (hasSignificantBlocks(level, bounds)) continue;
            return controllerPos;
        }
        throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR, "Unable to allocate workspace location");
    }

    /** Returns true if more than 10% of the area has non-air blocks. */
    private static boolean hasSignificantBlocks(ServerLevel level, net.minecraft.world.level.levelgen.structure.BoundingBox bounds) {
        int total = 0;
        int nonAir = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                // Only sample the bottom layer for speed
                if (!level.getBlockState(new BlockPos(x, bounds.minY(), z)).isAir()) nonAir++;
                total++;
            }
        }
        return total > 0 && nonAir > total / 10;
    }

    private static JsonArray toIntArray(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static JsonArray toSizeArray(int sizeX, int sizeY, int sizeZ) {
        JsonArray size = new JsonArray();
        size.add(sizeX);
        size.add(sizeY);
        size.add(sizeZ);
        return size;
    }

    private static boolean tagsEqual(@Nullable CompoundTag left, @Nullable CompoundTag right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private static boolean entityFitsInside(net.minecraft.world.phys.AABB entityBox, net.minecraft.world.phys.AABB cropBox) {
        return entityBox.minX >= cropBox.minX
                && entityBox.minY >= cropBox.minY
                && entityBox.minZ >= cropBox.minZ
                && entityBox.maxX <= cropBox.maxX
                && entityBox.maxY <= cropBox.maxY
                && entityBox.maxZ <= cropBox.maxZ;
    }

    private static AxisWindow normalizeAxisWindow(int from, int to, int axisSize) {
        int normalizedFrom = from < 0 ? 0 : from;
        int normalizedTo = to < 0 ? axisSize - 1 : to;
        int clampedFrom = Math.max(0, Math.min(normalizedFrom, axisSize - 1));
        int clampedTo = Math.max(0, Math.min(normalizedTo, axisSize - 1));
        return new AxisWindow(Math.min(clampedFrom, clampedTo), Math.max(clampedFrom, clampedTo));
    }

    private record AxisWindow(int from, int to) {}
}
