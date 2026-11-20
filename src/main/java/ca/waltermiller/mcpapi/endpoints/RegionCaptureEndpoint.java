package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.endpoints.AccessCheckEndpoint.BuildArea;
import ca.waltermiller.mcpapi.endpoints.AccessCheckEndpoint.BuildLookup;
import ca.waltermiller.mcpapi.endpoints.RegionCaptureRequest.RegionException;
import ca.waltermiller.mcpapi.snapshot.RegionSnapshot;
import io.javalin.Javalin;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Read-only capture of a world cuboid as gzipped structure NBT, for rendering placed builds in context.
 * {@code GET /api/world/region/nbt}. Only already-loaded chunks are read; nothing is tracked, locked or recorded.
 */
public final class RegionCaptureEndpoint {
    public static final String PATH = "/api/world/region/nbt";

    /** Reads one world's blocks. Both methods run on the server thread. */
    public interface RegionReader {
        List<Map<String, Integer>> missingChunks(AreaBounds bounds);
        byte[] capture(AreaBounds bounds) throws IOException;
    }

    /** A resolved world: its id, build height range and block reader. */
    public record WorldRegion(String world, int bottomY, int topYInclusive, RegionReader reader) {}

    private record Capture(String world, AreaBounds bounds, byte[] nbt) {}

    private volatile BuildLookup builds;

    public RegionCaptureEndpoint(Javalin app, MinecraftServer server) {
        this(app, server::execute, name -> {
            var key = WorldResolver.resolveWorldKey(name);
            ServerWorld world = key == null ? null : server.getWorld(key);
            if (world == null) throw new RegionException(400, "invalid_request", "Unknown world: " + name);
            return new WorldRegion(key.getValue().toString(), world.getBottomY(), world.getTopYInclusive(),
                new RegionReader() {
                    @Override public List<Map<String, Integer>> missingChunks(AreaBounds bounds) {
                        return RegionSnapshot.missingChunks(world, bounds);
                    }

                    @Override public byte[] capture(AreaBounds bounds) throws IOException {
                        var out = new ByteArrayOutputStream();
                        NbtIo.writeCompressed(RegionSnapshot.capture(world, bounds), out);
                        return out.toByteArray();
                    }
                });
        }, RegionCaptureRequest.maxVolumeFromEnv());
    }

    RegionCaptureEndpoint(Javalin app, Executor executor, Function<String, WorldRegion> resolve, long maxVolume) {
        app.get(PATH, ctx -> {
            try {
                Map<String, String> query = new HashMap<>();
                ctx.queryParamMap().forEach((name, values) -> {
                    if (!values.isEmpty()) query.put(name, values.get(0));
                });
                var request = RegionCaptureRequest.parse(query);
                String worldName = request.world();
                AreaBounds requested = request.cuboid();
                Boolean removed = null;
                if (request.buildId() != null) {
                    BuildArea area = findBuild(request);
                    if (worldName != null && area.world() != null) {
                        var given = WorldResolver.resolveWorldKey(worldName);
                        if (given == null || !given.equals(WorldResolver.resolveWorldKey(area.world()))) {
                            throw new RegionException(400, "invalid_request", "Build " + request.buildId() + " is in " + area.world());
                        }
                    }
                    if (worldName == null) worldName = area.world();
                    removed = area.removed();
                    requested = request.aroundBuild(area.bounds());
                }
                String targetWorld = worldName;
                AreaBounds target = requested;
                CompletableFuture<Capture> future = new CompletableFuture<>();
                executor.execute(() -> {
                    if (future.isCancelled()) return;
                    try {
                        var region = resolve.apply(targetWorld);
                        var bounds = RegionCaptureRequest.clampY(target, region.bottomY(), region.topYInclusive());
                        RegionCaptureRequest.checkVolume(bounds, maxVolume);
                        var missing = region.reader().missingChunks(bounds);
                        if (!missing.isEmpty()) {
                            future.completeExceptionally(new MissingChunks(missing));
                            return;
                        }
                        future.complete(new Capture(region.world(), bounds, region.reader().capture(bounds)));
                    } catch (Throwable e) { future.completeExceptionally(e); }
                });
                Capture capture;
                try {
                    capture = future.get(30, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(false);
                    ctx.status(504).json(Map.of("success", false, "code", "capture_timeout",
                        "error", "Timed out waiting for the region capture; try a smaller region"));
                    return;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof MissingChunks missing) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("success", false);
                        body.put("code", "chunks_not_loaded");
                        body.put("error", missing.chunks.size() + " chunk(s) in the region are not loaded; "
                            + "move a player nearby or narrow the region");
                        body.put("missing_chunks", missing.chunks.size() > 64 ? missing.chunks.subList(0, 64) : missing.chunks);
                        ctx.status(409).json(body);
                        return;
                    }
                    if (e.getCause() instanceof RegionException region) throw region;
                    if (e.getCause() instanceof IllegalArgumentException invalid) {
                        throw new RegionException(400, "invalid_request", invalid.getMessage());
                    }
                    throw e;
                }
                var b = capture.bounds();
                ctx.header("X-Region-World", capture.world());
                ctx.header("X-Region-Origin", b.min_x() + "," + b.min_y() + "," + b.min_z());
                ctx.header("X-Region-Size", (b.max_x() - b.min_x() + 1) + "," + (b.max_y() - b.min_y() + 1)
                    + "," + (b.max_z() - b.min_z() + 1));
                if (removed != null) ctx.header("X-Build-Removed", removed.toString());
                ctx.contentType("application/octet-stream").result(capture.nbt());
            } catch (RegionException e) {
                ctx.status(e.status()).json(e.payload());
            }
        });
    }

    public void setBuildLookup(BuildLookup lookup) { builds = lookup; }

    private BuildArea findBuild(RegionCaptureRequest request) {
        var lookup = builds;
        if (lookup == null) {
            throw new RegionException(503, "builds_unavailable", "Build history is unavailable; give the region as x1,y1,z1,x2,y2,z2");
        }
        Optional<BuildArea> found;
        try { found = lookup.find(request.buildId()); }
        catch (java.sql.SQLException e) {
            throw new RegionException(503, "builds_unavailable", "Could not read build history; give the region as coordinates");
        }
        BuildArea area = found.orElseThrow(() -> new RegionException(404, "build_not_found", "No build " + request.buildId()));
        Box bounds = area.bounds();
        if (bounds == null) {
            throw new RegionException(400, "build_has_no_bounds", "Build " + request.buildId() + " has no recorded coordinates");
        }
        return area;
    }

    private static final class MissingChunks extends RuntimeException {
        final List<Map<String, Integer>> chunks;

        MissingChunks(List<Map<String, Integer>> chunks) {
            super(null, null, false, false);
            this.chunks = chunks;
        }
    }
}
