package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.marker.AreaMarkerService;
import ca.waltermiller.mcpapi.marker.MarkerSpec;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class AreaMarkerEndpoint {
    public AreaMarkerEndpoint(Javalin app, MinecraftServer server, AreaMarkerService markers) {
        app.post("/api/world/markers", ctx -> {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            try {
                if (body == null || !body.isObject()) throw new IllegalArgumentException("Expected a JSON object");
                int duration = 60;
                if (body.has("duration_seconds")) {
                    if (!body.get("duration_seconds").isIntegralNumber() || !body.get("duration_seconds").canConvertToInt()) {
                        throw new IllegalArgumentException("duration_seconds must be an integer");
                    }
                    duration = body.get("duration_seconds").intValue();
                }
                MarkerSpec spec = new MarkerSpec(AreaLockEndpoint.bounds(body.get("bounds")),
                    string(body, "label", ""), string(body, "color", "yellow"), duration);
                String worldName = string(body, "world", "minecraft:overworld");
                String player = string(body, "player", null);
                respond(ctx, server, () -> {
                    var key = WorldResolver.resolveWorldKey(worldName);
                    var world = key == null ? null : server.getWorld(key);
                    if (world == null) throw new IllegalArgumentException("Unknown world: " + worldName);
                    if (player != null && world.getPlayers(p -> p.getName().getString().equalsIgnoreCase(player)).isEmpty()) {
                        throw new IllegalArgumentException("Target player must be online in the marker's world");
                    }
                    return markers.create(world, spec, player);
                });
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
            }
        });
        app.delete("/api/world/markers/{marker_id}", ctx -> {
            String id = ctx.pathParam("marker_id");
            respond(ctx, server, () -> markers.remove(id));
        });
    }

    private static String string(JsonNode body, String key, String fallback) {
        if (!body.has(key)) return fallback;
        if (!body.get(key).isTextual()) throw new IllegalArgumentException(key + " must be a string");
        return body.get(key).textValue();
    }

    private static void respond(Context ctx, MinecraftServer server, Supplier<Object> operation) throws Exception {
        CompletableFuture<Object> future = new CompletableFuture<>();
        server.execute(() -> {
            try { future.complete(operation.get()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        try { ctx.json(future.get(5, TimeUnit.SECONDS)); }
        catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) {
                ctx.status(400).json(Map.of("success", false, "error", invalid.getMessage()));
            } else throw e;
        }
    }
}
