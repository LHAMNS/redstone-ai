package com.redstoneai.network.rpc;

import com.google.gson.JsonElement;
import com.redstoneai.RedstoneAI;
import com.redstoneai.network.rpc.handlers.*;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes JSON-RPC method calls to registered handlers.
 * All handlers execute on the server thread via {@code server.execute()}.
 */
public class JsonRpcDispatcher {
    private final Map<String, RpcHandler> handlers = new HashMap<>();
    private final MinecraftServer server;

    public JsonRpcDispatcher(MinecraftServer server) {
        this.server = server;
        registerDefaults();
    }

    private void registerDefaults() {
        register("server.info", (req, srv) -> {
            com.google.gson.JsonObject result = new com.google.gson.JsonObject();
            result.addProperty("status", "ok");
            result.addProperty("version", "0.1.0");
            result.addProperty("maxWorkspaceSize", com.redstoneai.config.RAIConfig.SERVER.maxWorkspaceSize.get());
            result.addProperty("maxStepsPerCall", com.redstoneai.config.RAIConfig.SERVER.maxStepsPerCall.get());
            result.addProperty("maxRecordingTicks", com.redstoneai.config.RAIConfig.SERVER.maxRecordingTicks.get());
            result.addProperty("webSocketPort", com.redstoneai.config.RAIConfig.SERVER.webSocketPort.get());
            result.addProperty("webSocketBindAddress", com.redstoneai.config.RAIConfig.SERVER.webSocketBindAddress.get());
            return result;
        });

        WorkspaceHandler wsHandler = new WorkspaceHandler();
        register("workspace.create", wsHandler::create);
        register("workspace.delete", wsHandler::delete);
        register("workspace.list", wsHandler::list);
        register("workspace.info", wsHandler::info);
        register("workspace.configure", wsHandler::configure);
        register("workspace.history", wsHandler::history);
        register("workspace.baseline_diff", wsHandler::baselineDiff);
        register("workspace.revert", wsHandler::revert);
        register("workspace.scan", wsHandler::scan);
        register("workspace.clear", wsHandler::clear);
        register("workspace.set_mode", wsHandler::setMode);

        BuildHandler buildHandler = new BuildHandler();
        register("build.mcr", buildHandler::buildMcr);
        register("build.block", buildHandler::buildBlock);

        SimulationHandler simHandler = new SimulationHandler();
        register("sim.freeze", simHandler::freeze);
        register("sim.unfreeze", simHandler::unfreeze);
        register("sim.step", simHandler::step);
        register("sim.rewind", simHandler::rewind);
        register("sim.ff", simHandler::fastForward);
        register("sim.discard_future", simHandler::discardFuture);
        register("sim.settle", simHandler::settle);
        register("sim.summary", simHandler::summary);
        register("sim.timing", simHandler::timing);
        register("sim.detail", simHandler::detail);

        IOHandler ioHandler = new IOHandler();
        register("io.mark", ioHandler::mark);
        register("io.unmark", ioHandler::unmark);
        register("io.list", ioHandler::list);
        register("io.status", ioHandler::status);
        register("io.drive", ioHandler::drive);
        register("io.clear_inputs", ioHandler::clearInputs);

        BlockEntityHandler blockEntityHandler = new BlockEntityHandler();
        register("block_entity.write", blockEntityHandler::write);

        EntityHandler entityHandler = new EntityHandler();
        register("entity.spawn", entityHandler::spawn);
        register("entity.update", entityHandler::update);
        register("entity.remove", entityHandler::remove);
        register("entity.clear", entityHandler::clear);

        TestHandler testHandler = new TestHandler();
        register("test.run", testHandler::run);
        register("test.define", testHandler::define);

        register("status", (req, srv) -> {
            return handlers.get("server.info").handle(req, srv);
        });

        register("help.mcr", (req, srv) -> {
            com.google.gson.JsonObject result = new com.google.gson.JsonObject();
            result.addProperty("reference", com.redstoneai.mcr.MCRParser.getReferenceCard());
            return result;
        });
    }

    public void register(String method, RpcHandler handler) {
        handlers.put(method, handler);
    }

    /**
     * Dispatch a JSON-RPC request asynchronously.
     *
     * The handler still runs on the server thread, but callers do not need to
     * block their own thread while the server processes the request.
     */
    public CompletableFuture<String> dispatchAsync(JsonRpcRequest request) {
        RpcHandler handler = handlers.get(request.method());
        if (handler == null) {
            return CompletableFuture.completedFuture(
                    JsonRpcResponse.error(request.id(), JsonRpcException.METHOD_NOT_FOUND,
                            "Method not found: " + request.method()));
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    JsonElement response = handler.handle(request, server);
                    future.complete(JsonRpcResponse.success(request.id(), response));
                } catch (JsonRpcException e) {
                    future.complete(JsonRpcResponse.error(request.id(), e));
                } catch (Exception e) {
                    RedstoneAI.LOGGER.error("[RedstoneAI] RPC '{}' failed", request.method(), e);
                    future.complete(JsonRpcResponse.error(request.id(), JsonRpcException.INTERNAL_ERROR,
                            "Internal error: " + e.getMessage()));
                }
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Synchronous compatibility wrapper.
     */
    public String dispatch(JsonRpcRequest request) {
        try {
            return dispatchAsync(request).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonRpcResponse.error(request.id(), JsonRpcException.INTERNAL_ERROR, "Interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "unknown";
            return JsonRpcResponse.error(request.id(), JsonRpcException.INTERNAL_ERROR,
                    "Internal error: " + message);
        }
    }

    @FunctionalInterface
    public interface RpcHandler {
        JsonElement handle(JsonRpcRequest request, MinecraftServer server) throws JsonRpcException;
    }
}
