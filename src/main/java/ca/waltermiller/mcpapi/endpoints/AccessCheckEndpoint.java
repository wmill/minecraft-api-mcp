package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.access.AccessCheck;
import ca.waltermiller.mcpapi.access.AccessException;
import ca.waltermiller.mcpapi.access.AccessRequest;
import ca.waltermiller.mcpapi.access.AccessTerrain;
import ca.waltermiller.mcpapi.access.MobProbe;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Doors;
import ca.waltermiller.mcpapi.access.VanillaAccessProbe;
import ca.waltermiller.mcpapi.access.WorldAccessTerrain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Read-only access check: can a player (or a villager) get from a start position to targets inside a placed
 * structure, and which standing areas of a region are reachable. {@code POST /api/world/access/check}.
 */
public final class AccessCheckEndpoint {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A resolved world: its id, terrain reads, and a vanilla probe factory for the search box. */
    public record WorldAccess(String world, AccessTerrain terrain, ProbeFactory probes) {}

    public interface ProbeFactory { MobProbe create(Box box, Doors doors); }

    /** Where a recorded build is. {@code bounds} is null when its tasks have no coordinates. */
    public record BuildArea(String world, boolean removed, Box bounds) {}

    public interface BuildLookup { Optional<BuildArea> find(UUID id) throws java.sql.SQLException; }

    private volatile BuildLookup builds;

    public AccessCheckEndpoint(Javalin app, MinecraftServer server) {
        this(app, server::execute, name -> {
            var key = WorldResolver.resolveWorldKey(name);
            var world = key == null ? null : server.getWorld(key);
            if (world == null) throw new AccessException("invalid_request", "Unknown world: " + name);
            return new WorldAccess(key.getValue().toString(), new WorldAccessTerrain(world),
                (box, doors) -> new VanillaAccessProbe(world, box, doors));
        });
    }

    AccessCheckEndpoint(Javalin app, Executor executor, Function<String, WorldAccess> resolve) {
        app.post("/api/world/access/check", ctx -> {
            try {
                JsonNode body;
                try { body = JSON.readTree(ctx.body()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new AccessException("invalid_request", "Invalid JSON body"); }
                AccessRequest request = AccessRequest.parse(body);
                if (request.mapBuildId() != null) request = withBuildRegion(request);
                AccessRequest req = request;
                CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
                executor.execute(() -> {
                    if (future.isCancelled()) return;
                    try {
                        var target = resolve.apply(req.world());
                        var box = req.searchBox();
                        MobProbe probe = req.profile() == AccessRequest.Profile.VILLAGER
                            ? target.probes().create(box, req.doors()) : null;
                        future.complete(AccessCheck.run(target.world(), req, target.terrain(), probe));
                    } catch (Throwable e) { future.completeExceptionally(e); }
                });
                try {
                    ctx.json(future.get(30, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    future.cancel(false);
                    ctx.status(504).json(Map.of("success", false, "code", "access_timeout",
                        "error", "Timed out waiting for the access check; lower max_nodes or the map size"));
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof AccessException access) throw access;
                    if (e.getCause() instanceof IllegalArgumentException invalid) throw new AccessException("invalid_request", invalid.getMessage());
                    throw e;
                }
            } catch (AccessException e) {
                ctx.status(400).json(e.payload());
            }
        });
    }

    public void setBuildLookup(BuildLookup lookup) { builds = lookup; }

    private AccessRequest withBuildRegion(AccessRequest request) {
        var lookup = builds;
        if (lookup == null) {
            throw new AccessException("build_lookup_unavailable", "Build history is unavailable; give the map as x1,y1,z1,x2,y2,z2");
        }
        Optional<BuildArea> found;
        try { found = lookup.find(request.mapBuildId()); }
        catch (java.sql.SQLException e) {
            throw new AccessException("build_lookup_unavailable", "Could not read build history; give the map as coordinates");
        }
        BuildArea area = found.orElseThrow(() -> new AccessException("build_not_found", "No build " + request.mapBuildId()));
        if (area.removed()) throw new AccessException("build_removed", "Build " + request.mapBuildId() + " is no longer in the world (undone or superseded)");
        if (area.bounds() == null) throw new AccessException("build_has_no_bounds", "Build " + request.mapBuildId() + " has no recorded coordinates");
        if (request.world() != null && area.world() != null) {
            var a = WorldResolver.resolveWorldKey(request.world());
            if (a == null || !a.equals(WorldResolver.resolveWorldKey(area.world()))) throw new AccessException("invalid_request", "Build " + request.mapBuildId() + " is in " + area.world());
        }
        Box b = area.bounds();
        // Include standing positions on the top surface (feet are one above the highest block).
        Box region = new Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY() + 2, b.maxZ());
        AccessRequest out = request.withMapRegion(region);
        return request.world() == null && area.world() != null ? out.withWorld(area.world()) : out;
    }
}
