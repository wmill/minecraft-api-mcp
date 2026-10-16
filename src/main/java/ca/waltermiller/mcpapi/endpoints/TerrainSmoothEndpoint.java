package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockException;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import ca.waltermiller.mcpapi.preview.TerrainMapRenderer;
import ca.waltermiller.mcpapi.snapshot.SnapshotStore;
import ca.waltermiller.mcpapi.survey.SurveyOccupancy;
import ca.waltermiller.mcpapi.terrain.GroundColumn;
import ca.waltermiller.mcpapi.terrain.HeightFilter;
import ca.waltermiller.mcpapi.terrain.TerrainAccess;
import ca.waltermiller.mcpapi.terrain.TerrainRewriter;
import ca.waltermiller.mcpapi.terrain.TerrainSmoother;
import ca.waltermiller.mcpapi.terrain.WorldTerrainAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * {@code POST /api/world/terrain/smooth}: reshapes a region's ground with a filter or a
 * flat pad, clearing vegetation. {@code dry_run} returns before/after relief maps and a
 * cut/fill change map without writing. Writes are area-lock checked, snapshotted for
 * undo, and recorded as a TERRAIN_SMOOTH build. See docs/terrain-smoothing.md.
 */
public final class TerrainSmoothEndpoint {
    static final int TIMEOUT_SECONDS = 30;
    static final double MAX_SIGMA = 5.0;
    static final int MAX_MEDIAN_RADIUS = 8;
    static final int MAX_ITERATIONS = 5;
    static final int MAX_FEATHER = 32;
    static final int MAX_BLEND = 32;
    static final int MAX_CHANGE_LIMIT = 64;
    static final int DEFAULT_MAX_CHANGE = 16;
    static final int MIN_PAD_Y = -63;
    static final int MAX_PAD_Y = 320;
    private static final Set<String> FIELDS = Set.of("world", "x1", "z1", "x2", "z2", "method", "sigma", "radius",
        "sigma_range", "iterations", "pad", "strength", "feather", "max_change", "dry_run", "px_per_block",
        "contour_interval");
    private static final Set<String> PAD_FIELDS = Set.of("y", "x1", "z1", "x2", "z2", "blend");

    /** A resolved world: its canonical id and server-thread access. */
    public record Target(String world, TerrainAccess access) {}

    record Request(String world, int minX, int minZ, int maxX, int maxZ, String method,
                   TerrainSmoother.Options options, boolean dryRun, Integer pxPerBlock, Integer contourInterval,
                   ObjectNode parameters) {
        int sizeX() { return maxX - minX + 1; }
        int sizeZ() { return maxZ - minZ + 1; }
    }

    /** 400 with a machine-readable code and optional details. */
    static final class RequestException extends RuntimeException {
        final String code;
        final Map<String, Object> details;

        RequestException(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = code;
            this.details = details;
        }

        RequestException(String message) { this("invalid_request", message, Map.of()); }

        Map<String, Object> payload() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", code);
            body.put("error", getMessage());
            body.putAll(details);
            return body;
        }
    }

    /** 409: the ground changed between planning and writing. */
    static final class TerrainChangedException extends RuntimeException {
        TerrainChangedException(int columns) {
            super(columns + " column(s) changed while the smoothing was planned; nothing was written, retry");
        }
    }

    /** Sampled region plus margin; region columns start at index {@code margin}. */
    private record Sample(Target target, GroundColumn[][] columns, int margin) {
        GroundColumn[][] region(Request req) {
            GroundColumn[][] out = new GroundColumn[req.sizeX()][];
            for (int x = 0; x < out.length; x++) {
                out[x] = java.util.Arrays.copyOfRange(columns[x + margin], margin, margin + req.sizeZ());
            }
            return out;
        }
    }

    private record Applied(NbtCompound snapshot, TerrainRewriter.Written written) {}

    private final Executor serverThread;
    private final Function<String, Target> resolve;
    private final AreaLockService locks;
    private final SurveyOccupancy occupancy;
    private final SnapshotStore snapshots;
    private final Logger logger;
    private volatile BuildService buildService;

    public TerrainSmoothEndpoint(Javalin app, MinecraftServer server, Logger logger, AreaLockService locks,
                                 SurveyOccupancy occupancy, SnapshotStore snapshots) {
        this(app, server::execute, name -> {
            var key = WorldResolver.resolveWorldKey(name);
            var world = key == null ? null : server.getWorld(key);
            if (world == null) throw new RequestException("Unknown world: " + name);
            return new Target(key.getValue().toString(), new WorldTerrainAccess(world));
        }, locks, occupancy, snapshots, logger);
    }

    TerrainSmoothEndpoint(Javalin app, Executor serverThread, Function<String, Target> resolve, AreaLockService locks,
                          SurveyOccupancy occupancy, SnapshotStore snapshots, Logger logger) {
        this.serverThread = serverThread;
        this.resolve = resolve;
        this.locks = locks;
        this.occupancy = occupancy;
        this.snapshots = snapshots;
        this.logger = logger;
        app.post("/api/world/terrain/smooth", this::smooth);
    }

    public void setBuildService(BuildService buildService) {
        this.buildService = buildService;
    }

    private void smooth(Context ctx) {
        try {
            Request req = parse(Json.MAPPER.readTree(ctx.body()));
            String lockId = ctx.header(AreaLockService.HEADER);
            Sample sample = onServer(() -> sample(req));
            GroundColumn[][] region = sample.region(req);
            TerrainSmoother.Plan plan = plan(sample, req);
            AreaBounds bounds = TerrainRewriter.bounds(req.minX(), req.minZ(), region, plan.heights());
            String world = sample.target().world();
            if (req.dryRun()) {
                ctx.json(dryRunBody(req, world, region, plan, bounds, lockId));
                return;
            }
            Applied applied = onServer(() -> locks.execute(world, bounds, lockId,
                () -> write(sample.target().access(), req, region, plan, bounds), ignored -> true));
            ctx.json(appliedBody(req, world, region, plan, bounds, applied));
        } catch (RequestException e) {
            ctx.status(400).json(e.payload());
        } catch (TerrainChangedException e) {
            ctx.status(409).json(Map.of("success", false, "code", "terrain_changed", "error", e.getMessage()));
        } catch (AreaLockException e) {
            ctx.status(409).json(e.payload());
        } catch (TimeoutException e) {
            ctx.status(504).json(Map.of("success", false, "code", "timeout",
                "error", "Timed out waiting for the server thread; inspect the region before retrying"));
        } catch (JsonProcessingException e) {
            ctx.status(400).json(Map.of("success", false, "code", "invalid_request", "error", "Invalid JSON body"));
        } catch (Exception e) {
            logger.error("Terrain smoothing failed", e);
            ctx.status(500).json(Map.of("success", false, "error", "Terrain smoothing failed: " + e.getMessage()));
        }
    }

    private <T> T onServer(Callable<T> work) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        serverThread.execute(() -> {
            if (future.isCancelled()) return;
            try {
                future.complete(work.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private Sample sample(Request req) {
        Target target = resolve.apply(req.world());
        TerrainAccess access = target.access();
        List<Map<String, Integer>> missing = new ArrayList<>();
        for (int cx = req.minX() >> 4; cx <= req.maxX() >> 4; cx++) {
            for (int cz = req.minZ() >> 4; cz <= req.maxZ() >> 4; cz++) {
                if (!access.chunkLoaded(cx, cz)) missing.add(Map.of("x", cx, "z", cz));
            }
        }
        if (!missing.isEmpty()) {
            throw new RequestException("unloaded_chunks", "Chunks in the region are not loaded; move a player nearby",
                Map.of("missing_chunks", missing));
        }
        int m = TerrainSmoother.margin(req.options().method());
        GroundColumn[][] columns = new GroundColumn[req.sizeX() + 2 * m][req.sizeZ() + 2 * m];
        for (int x = 0; x < columns.length; x++) {
            for (int z = 0; z < columns[x].length; z++) {
                columns[x][z] = access.sample(req.minX() - m + x, req.minZ() - m + z);
            }
        }
        return new Sample(target, columns, m);
    }

    private static TerrainSmoother.Plan plan(Sample sample, Request req) {
        GroundColumn[][] columns = sample.columns();
        int[][] ground = new int[columns.length][columns[0].length];
        boolean[][] masked = new boolean[columns.length][columns[0].length];
        boolean[][] anchors = new boolean[columns.length][columns[0].length];
        for (int x = 0; x < columns.length; x++) {
            for (int z = 0; z < columns[x].length; z++) {
                ground[x][z] = columns[x][z].ground();
                masked[x][z] = columns[x][z].masked();
                anchors[x][z] = masked[x][z] && columns[x][z].kind() != GroundColumn.Kind.UNLOADED;
            }
        }
        return TerrainSmoother.plan(ground, masked, anchors, sample.margin(), req.options());
    }

    /** Runs inside the area lock on the server thread: verify, snapshot, write. */
    private Applied write(TerrainAccess access, Request req, GroundColumn[][] region, TerrainSmoother.Plan plan,
                          AreaBounds bounds) {
        int changed = 0;
        for (int x = 0; x < region.length; x++) {
            for (int z = 0; z < region[x].length; z++) {
                if (!access.sample(req.minX() + x, req.minZ() + z).equals(region[x][z])) changed++;
            }
        }
        if (changed > 0) throw new TerrainChangedException(changed);
        NbtCompound snapshot = snapshots != null && snapshots.fits(bounds) ? access.capture(bounds) : null;
        TerrainRewriter.Written written = TerrainRewriter.apply(access, req.minX(), req.minZ(), region, plan.heights());
        try {
            access.placed(bounds);
        } catch (Exception e) {
            logger.warn("Failed to emit terrain smoothing effect", e);
        }
        return new Applied(snapshot, written);
    }

    private Map<String, Object> dryRunBody(Request req, String world, GroundColumn[][] region,
                                           TerrainSmoother.Plan plan, AreaBounds bounds, String lockId)
            throws IOException {
        Map<String, Object> body = baseBody(req, world, region, plan, bounds);
        body.put("dry_run", true);
        Map<String, Object> lockCheck;
        try {
            // Same authorization as the write; a false success predicate never renews the lease.
            locks.execute(world, bounds, lockId, () -> null, ignored -> false);
            lockCheck = Map.of("ok", true);
        } catch (AreaLockException e) {
            lockCheck = new LinkedHashMap<>(e.payload());
            lockCheck.remove("success");
            lockCheck.put("ok", false);
        }
        body.put("lock_check", lockCheck);
        body.put("reservations", SurveyOccupancy.reservations(locks, world, bounds));
        if (occupancy != null) body.put("builds", occupancy.builds(world, bounds));
        body.put("undo_snapshot", snapshots != null && snapshots.fits(bounds));

        int sx = region.length, sz = region[0].length;
        int[][] before = new int[sx][sz];
        int[][] delta = new int[sx][sz];
        boolean[][] masked = new boolean[sx][sz];
        String[][] tops = new String[sx][sz];
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                before[x][z] = region[x][z].ground();
                delta[x][z] = plan.heights()[x][z] - before[x][z];
                masked[x][z] = region[x][z].masked();
                tops[x][z] = region[x][z].top();
                lo = Math.min(lo, Math.min(before[x][z], plan.heights()[x][z]));
                hi = Math.max(hi, Math.max(before[x][z], plan.heights()[x][z]));
            }
        }
        var beforeMap = TerrainMapRenderer.render(before, tops, req.minX(), req.minZ(),
            new TerrainMapRenderer.Options(req.pxPerBlock(), req.contourInterval(), true, false, lo, hi,
                "Before: ground relief (vegetation ignored)"));
        var afterMap = TerrainMapRenderer.render(plan.heights(), tops, req.minX(), req.minZ(),
            new TerrainMapRenderer.Options(req.pxPerBlock(), req.contourInterval(), true, false, lo, hi,
                "After: planned ground relief"));
        var change = TerrainMapRenderer.renderDelta(delta, masked, req.minX(), req.minZ(), req.pxPerBlock());
        var meta = beforeMap.meta();
        body.put("px_per_block", meta.pxPerBlock());
        body.put("contour_interval", meta.contourInterval());
        body.put("grid_step", meta.gridStep());
        body.put("labels", meta.labels() && afterMap.meta().labels() && change.labels());
        Base64.Encoder base64 = Base64.getEncoder();
        body.put("before_png", base64.encodeToString(beforeMap.reliefPng()));
        body.put("after_png", base64.encodeToString(afterMap.reliefPng()));
        body.put("change_png", base64.encodeToString(change.png()));
        return body;
    }

    private Map<String, Object> appliedBody(Request req, String world, GroundColumn[][] region,
                                            TerrainSmoother.Plan plan, AreaBounds bounds, Applied applied) {
        Map<String, Object> body = baseBody(req, world, region, plan, bounds);
        body.put("vegetation_blocks_cleared", applied.written().vegetationBlocks());
        String reason = null;
        BuildService builds = buildService;
        String buildId = null;
        if (builds != null) {
            try {
                Build recorded = builds.recordTerrainSmooth(world, bounds.min_x(), bounds.min_y(), bounds.min_z(),
                    bounds.max_x() - bounds.min_x() + 1, bounds.max_y() - bounds.min_y() + 1,
                    bounds.max_z() - bounds.min_z() + 1, req.method(), req.parameters());
                buildId = recorded.getId().toString();
                body.put("build_id", buildId);
            } catch (Exception e) {
                logger.warn("Failed to record terrain smoothing in build system: {}", e.getMessage());
            }
        }
        if (snapshots == null) {
            reason = "snapshots_disabled";
        } else if (applied.snapshot() == null) {
            reason = snapshots.fits(bounds) ? "snapshot_unavailable" : "snapshot_too_large";
        } else if (buildId == null) {
            reason = "build_not_recorded";
        } else {
            try {
                snapshots.save(UUID.fromString(buildId), applied.snapshot());
            } catch (Exception e) {
                logger.warn("Failed to write undo snapshot for build {}: {}", buildId, e.getMessage());
                reason = "snapshot_write_failed";
            }
        }
        body.put("undo_available", reason == null);
        if (reason != null) body.put("undo_unavailable_reason", reason);
        logger.info("Smoothed terrain ({}) over X {}..{} Z {}..{} in {}: {} columns changed",
            req.method(), req.minX(), req.maxX(), req.minZ(), req.maxZ(), world, plan.stats().changedColumns());
        return body;
    }

    private static Map<String, Object> baseBody(Request req, String world, GroundColumn[][] region,
                                                TerrainSmoother.Plan plan, AreaBounds bounds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("world", world);
        body.put("region", Map.of("min_x", req.minX(), "min_z", req.minZ(), "max_x", req.maxX(), "max_z", req.maxZ()));
        body.put("size", Map.of("x", req.sizeX(), "z", req.sizeZ()));
        body.put("method", req.method());
        body.put("parameters", req.parameters());
        body.put("write_bounds", bounds);

        int beforeMin = Integer.MAX_VALUE, beforeMax = Integer.MIN_VALUE;
        int afterMin = Integer.MAX_VALUE, afterMax = Integer.MIN_VALUE;
        int vegetation = 0;
        Map<String, Integer> masked = new LinkedHashMap<>();
        for (int x = 0; x < region.length; x++) {
            for (int z = 0; z < region[x].length; z++) {
                GroundColumn c = region[x][z];
                if (c.clearsVegetation() && c.surface() > c.ground()) vegetation++;
                if (c.masked()) {
                    masked.merge(c.kind().name().toLowerCase(), 1, Integer::sum);
                    continue;
                }
                beforeMin = Math.min(beforeMin, c.ground());
                beforeMax = Math.max(beforeMax, c.ground());
                afterMin = Math.min(afterMin, plan.heights()[x][z]);
                afterMax = Math.max(afterMax, plan.heights()[x][z]);
            }
        }
        TerrainSmoother.Stats s = plan.stats();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("columns", s.columns());
        summary.put("changed_columns", s.changedColumns());
        summary.put("masked_columns", s.maskedColumns());
        summary.put("masked_by_kind", masked);
        summary.put("protected_columns", s.protectedColumns());
        summary.put("clamped_columns", s.clampedColumns());
        summary.put("cut_blocks", s.cutBlocks());
        summary.put("fill_blocks", s.fillBlocks());
        summary.put("max_lowered", s.maxLowered());
        summary.put("max_raised", s.maxRaised());
        summary.put("vegetation_columns", vegetation);
        if (req.options().method() instanceof TerrainSmoother.Pad) {
            summary.put("pad_columns", s.padColumns());
            summary.put("pad_columns_at_target", s.padColumnsAtTarget());
        }
        body.put("summary", summary);
        if (beforeMin != Integer.MAX_VALUE) {
            body.put("ground_y", Map.of(
                "before", Map.of("min", beforeMin, "max", beforeMax),
                "after", Map.of("min", afterMin, "max", afterMax)));
        }
        return body;
    }

    // ---- request parsing ----

    static Request parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new RequestException("Expected a JSON object");
        rejectUnknown(body, FIELDS, "");
        int x1 = requiredInt(body, "x1"), z1 = requiredInt(body, "z1");
        int x2 = requiredInt(body, "x2"), z2 = requiredInt(body, "z2");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long columns = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (columns > BlocksEndpointCore.MAX_HEIGHTMAP_POINTS) {
            throw new RequestException("Area too large: " + columns + " columns; maximum "
                + BlocksEndpointCore.MAX_HEIGHTMAP_POINTS);
        }
        String world = null;
        if (body.hasNonNull("world")) {
            if (!body.get("world").isTextual()) throw new RequestException("world must be a string");
            world = body.get("world").asText();
        }
        if (!body.path("method").isTextual()) {
            throw new RequestException("method is required: gaussian, median, bilateral or pad");
        }
        String method = body.get("method").asText();
        int iterations = optionalInt(body, "iterations", 1, 1, MAX_ITERATIONS);
        TerrainSmoother.Method smoothing = switch (method) {
            case "gaussian" -> new TerrainSmoother.Filter(
                new HeightFilter.Gaussian(optionalDouble(body, "sigma", 2.0, 0.5, MAX_SIGMA)), iterations);
            case "median" -> new TerrainSmoother.Filter(
                new HeightFilter.Median(optionalInt(body, "radius", 2, 1, MAX_MEDIAN_RADIUS)), iterations);
            case "bilateral" -> new TerrainSmoother.Filter(new HeightFilter.Bilateral(
                optionalDouble(body, "sigma", 2.0, 0.5, MAX_SIGMA),
                optionalDouble(body, "sigma_range", 2.0, 0.25, 32.0)), iterations);
            case "pad" -> parsePad(body.get("pad"), minX, minZ, maxX, maxZ);
            default -> throw new RequestException("Unknown method '" + method
                + "'; expected gaussian, median, bilateral or pad");
        };
        if (!method.equals("pad") && body.has("pad")) throw new RequestException("pad is only used with method=pad");
        boolean pad = smoothing instanceof TerrainSmoother.Pad;
        double strength = optionalDouble(body, "strength", 1.0, 0.0, 1.0);
        if (strength <= 0) throw new RequestException("strength must be greater than 0");
        int feather = optionalInt(body, "feather", pad ? 0 : 3, 0, MAX_FEATHER);
        int maxChange = optionalInt(body, "max_change", DEFAULT_MAX_CHANGE, 1, MAX_CHANGE_LIMIT);
        boolean dryRun = false;
        if (body.has("dry_run")) {
            if (!body.get("dry_run").isBoolean()) throw new RequestException("dry_run must be a boolean");
            dryRun = body.get("dry_run").booleanValue();
        }
        Integer px = body.hasNonNull("px_per_block") ? optionalInt(body, "px_per_block", 0,
            TerrainMapRenderer.MIN_PX_PER_BLOCK, TerrainMapRenderer.MAX_PX_PER_BLOCK) : null;
        Integer contour = body.hasNonNull("contour_interval") ? optionalInt(body, "contour_interval", 0,
            TerrainMapRenderer.MIN_CONTOUR_INTERVAL, TerrainMapRenderer.MAX_CONTOUR_INTERVAL) : null;

        ObjectNode parameters = ((ObjectNode) body).deepCopy();
        for (String presentation : new String[] {"dry_run", "px_per_block", "contour_interval", "world"}) {
            parameters.remove(presentation);
        }
        return new Request(world, minX, minZ, maxX, maxZ, method,
            new TerrainSmoother.Options(smoothing, strength, feather, maxChange), dryRun, px, contour, parameters);
    }

    private static TerrainSmoother.Pad parsePad(JsonNode pad, int minX, int minZ, int maxX, int maxZ) {
        if (pad == null || !pad.isObject()) {
            throw new RequestException("method=pad needs a pad object: {y, x1, z1, x2, z2, blend?}");
        }
        rejectUnknown(pad, PAD_FIELDS, "pad.");
        int y = requiredInt(pad, "y");
        if (y < MIN_PAD_Y || y > MAX_PAD_Y) {
            throw new RequestException("pad.y must be between " + MIN_PAD_Y + " and " + MAX_PAD_Y);
        }
        int x1 = requiredInt(pad, "x1"), z1 = requiredInt(pad, "z1");
        int x2 = requiredInt(pad, "x2"), z2 = requiredInt(pad, "z2");
        int px0 = Math.min(x1, x2), px1 = Math.max(x1, x2), pz0 = Math.min(z1, z2), pz1 = Math.max(z1, z2);
        if (px0 < minX || px1 > maxX || pz0 < minZ || pz1 > maxZ) {
            throw new RequestException("pad footprint must lie inside the region; widen the region by the blend "
                + "distance on each side so the slope back to existing ground fits");
        }
        int blend = optionalInt(pad, "blend", 6, 0, MAX_BLEND);
        return new TerrainSmoother.Pad(y, px0 - minX, pz0 - minZ, px1 - minX, pz1 - minZ, blend);
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String prefix) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new RequestException("Unknown field " + prefix + name + "; allowed: "
                    + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
    }

    private static int requiredInt(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new RequestException(name + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static int optionalInt(JsonNode node, String name, int fallback, int min, int max) {
        if (!node.hasNonNull(name)) return fallback;
        int value = requiredInt(node, name);
        if (value < min || value > max) throw new RequestException(name + " must be between " + min + " and " + max);
        return value;
    }

    private static double optionalDouble(JsonNode node, String name, double fallback, double min, double max) {
        if (!node.hasNonNull(name)) return fallback;
        JsonNode value = node.get(name);
        if (!value.isNumber()) throw new RequestException(name + " must be a number");
        double d = value.doubleValue();
        if (!(d >= min && d <= max)) throw new RequestException(name + " must be between " + min + " and " + max);
        return d;
    }
}
