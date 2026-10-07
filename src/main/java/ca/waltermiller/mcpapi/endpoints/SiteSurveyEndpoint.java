package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.survey.SiteSurvey;
import ca.waltermiller.mcpapi.survey.SurveyOccupancy;
import ca.waltermiller.mcpapi.survey.WorldSurveyTerrain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class SiteSurveyEndpoint {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record WorldTerrain(String world, SiteSurvey.Terrain terrain) {}
    private record Sample(SiteSurvey.Result result, AreaBounds volume, SurveyOccupancy.Overlaps reservations) {}

    public SiteSurveyEndpoint(Javalin app, MinecraftServer server, AreaLockService locks, SurveyOccupancy occupancy) {
        this(app, server::execute, name -> {
            var key = WorldResolver.resolveWorldKey(name);
            var world = key == null ? null : server.getWorld(key);
            if (world == null) throw new IllegalArgumentException("Unknown world: " + name);
            return new WorldTerrain(key.getValue().toString(), new WorldSurveyTerrain(world));
        }, locks, occupancy);
    }

    SiteSurveyEndpoint(Javalin app, Executor executor, Function<String, WorldTerrain> resolve,
                       AreaLockService locks, SurveyOccupancy occupancy) {
        app.post("/api/world/blocks/survey", ctx -> {
            try {
                JsonNode body = JSON.readTree(ctx.body());
                if (body == null || !body.isObject()) throw new IllegalArgumentException("Expected a JSON object");
                for (String name : new String[] {"x1", "z1", "x2", "z2"}) {
                    if (!body.path(name).isIntegralNumber() || !body.path(name).canConvertToInt()) {
                        throw new IllegalArgumentException(name + " must be a 32-bit integer");
                    }
                }
                if (body.hasNonNull("world") && !body.get("world").isTextual()) {
                    throw new IllegalArgumentException("world must be a string");
                }
                var bounds = SiteSurvey.Footprint.of(body.get("x1").intValue(), body.get("z1").intValue(),
                    body.get("x2").intValue(), body.get("z2").intValue());
                String worldName = body.path("world").asText(null);
                CompletableFuture<Sample> future = new CompletableFuture<>();
                executor.execute(() -> {
                    if (future.isCancelled()) return;
                    try {
                        var target = resolve.apply(worldName);
                        var result = SiteSurvey.survey(target.world(), bounds, target.terrain());
                        var volume = new AreaBounds(bounds.min_x(), target.terrain().bottomY(), bounds.min_z(),
                            bounds.max_x(), target.terrain().topYInclusive(), bounds.max_z());
                        future.complete(new Sample(result, volume, SurveyOccupancy.reservations(locks, target.world(), volume)));
                    } catch (Exception e) { future.completeExceptionally(e); }
                });
                Sample sample;
                try { sample = future.get(30, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(false);
                    ctx.status(504).json(Map.of("success", false, "code", "survey_timeout", "error", "Timed out waiting for survey"));
                    return;
                }
                var result = sample.result();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("world", result.world());
                response.put("surveyed_at", result.surveyed_at());
                response.put("bounds", result.bounds());
                response.put("size", result.size());
                response.put("ground", result.ground());
                response.put("slope", result.slope());
                response.put("water", result.water());
                response.put("lava", result.lava());
                response.put("vegetation", result.vegetation());
                response.put("grading", result.grading());
                response.put("reservations", sample.reservations());
                response.put("builds", occupancy.builds(result.world(), sample.volume()));
                response.put("limitations", "Surface observations, not a reservation or proof of unused land. Logs/leaves may be construction; roofs may count as ground. Cut/fill excludes vegetation removal, drainage, cavities and material suitability. Coverage is limited to the sampled surface columns.");
                ctx.json(response);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SiteSurvey.SurveyException survey) {
                    ctx.status(400).json(survey.payload());
                } else if (e.getCause() instanceof IllegalArgumentException invalid) {
                    ctx.status(400).json(Map.of("success", false, "code", "invalid_request", "error", invalid.getMessage()));
                } else throw e;
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("success", false, "code", "invalid_request", "error", e.getMessage()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                ctx.status(400).json(Map.of("success", false, "code", "invalid_request", "error", "Invalid JSON body"));
            }
        });
    }
}
