package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockException;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

public final class AreaLockEndpoint {
    private static final String[] COORDINATES = {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z"};

    public AreaLockEndpoint(Javalin app, MinecraftServer server, AreaLockService locks) {
        this(app, server::execute, value -> world(server, value), locks);
    }

    AreaLockEndpoint(Javalin app, Executor executor, Function<String, String> resolveWorld, AreaLockService locks) {
        app.post("/api/area-locks", ctx -> respond(ctx, executor, () -> {
            JsonNode body = body(ctx);
            AreaBounds bounds = bounds(body.get("bounds"));
            if (bounds == null) throw new IllegalArgumentException("bounds is required");
            return locks.acquire(resolveWorld.apply(body.path("world").asText(null)), bounds, body.path("label").asText(""));
        }));
        app.patch("/api/area-locks/{lock_id}", ctx -> respond(ctx, executor, () -> {
            JsonNode body = body(ctx);
            if (body.has("world")) throw new IllegalArgumentException("A reservation's world cannot be changed");
            return locks.update(ctx.pathParam("lock_id"), bounds(body.get("bounds")));
        }));
        app.delete("/api/area-locks/{lock_id}", ctx -> respond(ctx, executor, () -> {
            locks.release(ctx.pathParam("lock_id"));
            return Map.of("success", true);
        }));
        app.get("/api/area-locks", ctx -> respond(ctx, executor, () -> {
            String filter = ctx.queryParam("world");
            int[] coordinates = new int[6];
            int count = 0;
            for (int i = 0; i < COORDINATES.length; i++) {
                String value = ctx.queryParam(COORDINATES[i]);
                if (value != null) { coordinates[i] = Integer.parseInt(value); count++; }
            }
            if (count != 0 && count != 6) throw new IllegalArgumentException("All six bounds coordinates are required");
            AreaBounds bounds = count == 0 ? null : new AreaBounds(coordinates[0], coordinates[1], coordinates[2],
                coordinates[3], coordinates[4], coordinates[5]);
            return Map.of("locks", locks.list(filter == null ? null : resolveWorld.apply(filter), bounds));
        }));
    }

    private static JsonNode body(Context ctx) {
        JsonNode body = ctx.bodyAsClass(JsonNode.class);
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Expected a JSON object");
        return body;
    }

    static AreaBounds bounds(JsonNode node) {
        if (node == null || node.isNull()) return null;
        for (String key : COORDINATES) {
            if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt()) {
                throw new IllegalArgumentException("bounds requires six integer coordinates");
            }
        }
        return new AreaBounds(node.get("min_x").intValue(), node.get("min_y").intValue(), node.get("min_z").intValue(),
            node.get("max_x").intValue(), node.get("max_y").intValue(), node.get("max_z").intValue());
    }

    private static String world(MinecraftServer server, String value) {
        var key = WorldResolver.resolveWorldKey(value);
        if (key == null || server.getWorld(key) == null) throw new IllegalArgumentException("Unknown world: " + value);
        return key.getValue().toString();
    }

    private static void respond(Context ctx, Executor executor, Supplier<Object> operation) throws Exception {
        CompletableFuture<Object> future = new CompletableFuture<>();
        executor.execute(() -> {
            try { future.complete(operation.get()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        try {
            ctx.json(future.get(30, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AreaLockException conflict) ctx.status(409).json(conflict.payload());
            else if (e.getCause() instanceof IllegalArgumentException invalid) ctx.status(400).json(Map.of("error", invalid.getMessage()));
            else if (e.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException) ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            else if (e.getCause() instanceof io.javalin.http.HttpResponseException response) throw response;
            else throw e;
        }
    }
}
