package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.operation.OperationHttp;
import ca.waltermiller.mcpapi.operation.OperationRegistry;

import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.model.BuildSource;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.arealock.AreaLockException;
import ca.waltermiller.mcpapi.snapshot.OverwriteTally;
import ca.waltermiller.mcpapi.snapshot.RegionSnapshot;
import ca.waltermiller.mcpapi.snapshot.SnapshotStore;
import ca.waltermiller.mcpapi.survey.SurveyOccupancy;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.structure.StructureTemplateManager;
import org.jetbrains.annotations.NotNull;

public class NBTStructureEndpoint extends APIEndpoint {
    private final AreaLockService locks;
    private final SurveyOccupancy occupancy;
    private final SnapshotStore snapshots;
    private static final int TIMEOUT_SECONDS = 30;

    private BuildService buildService;

    public NBTStructureEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        this(app, server, logger, new AreaLockService());
    }

    public NBTStructureEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger, AreaLockService locks) {
        this(app, server, logger, locks, null, null);
    }

    /** {@code snapshots} null disables undo snapshots; {@code occupancy} null omits build overlaps from dry runs. */
    public NBTStructureEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger, AreaLockService locks,
                                SurveyOccupancy occupancy, SnapshotStore snapshots) {
        super(app, server, logger);
        this.locks = locks;
        this.occupancy = occupancy;
        this.snapshots = snapshots;
        init();
    }

    public void setBuildService(BuildService buildService) {
        this.buildService = buildService;
    }

    private void init() {
        app.post("/api/world/structure/place", this::placeStructure);
    }

    public boolean isGzipped(byte @NotNull [] data) {
        // Check for GZIP magic number (0x1F 0x8B)
        boolean isGzipped = data.length >= 2 && (data[0] == (byte) 0x1F) && (data[1] == (byte) 0x8B);
        LOGGER.info("InputStream isGzipped: {}", isGzipped);
        return isGzipped;
    }

    private void placeStructure(Context ctx) {
        try {
            // Get uploaded NBT file
            UploadedFile nbtFile = ctx.uploadedFile("nbt_file");
            if (nbtFile == null) {
                ctx.status(400).json(Map.of("error", "No NBT file uploaded. Use 'nbt_file' field"));
                return;
            }
            String filename = nbtFile.filename();

            // Get placement parameters from form data
            String worldName = Objects.requireNonNullElse(ctx.formParam("world"), "minecraft:overworld");
            String xStr = ctx.formParam("x");
            String yStr = ctx.formParam("y");
            String zStr = ctx.formParam("z");
            String rotationStr = Objects.requireNonNullElse(ctx.formParam("rotation"), "NONE");
            String includeEntitiesStr = Objects.requireNonNullElse(ctx.formParam("include_entities"), "true");
            boolean dryRun = Boolean.parseBoolean(ctx.formParam("dry_run"));
            boolean force = Boolean.parseBoolean(ctx.formParam("force"));

            BuildSource provenance;
            UUID replacesId;
            try {
                String provenanceJson = ctx.formParam("provenance");
                provenance = provenanceJson == null || provenanceJson.isBlank()
                    ? null : BuildSource.fromJson(Json.MAPPER.readTree(provenanceJson));
                String replaces = ctx.formParam("replaces_build_id");
                replacesId = replaces == null || replaces.isBlank() ? null : UUID.fromString(replaces.trim());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                ctx.status(400).json(Map.of("error", "Invalid provenance JSON: " + e.getOriginalMessage()));
                return;
            } catch (IllegalArgumentException e) {
                String message = java.util.Objects.toString(e.getMessage(), "invalid value");
                ctx.status(400).json(Map.of("error", message.startsWith("provenance")
                    ? message : "Invalid replaces_build_id: " + message));
                return;
            }
            if (replacesId != null && (buildService == null || snapshots == null)) {
                ctx.status(503).json(Map.of("error", "Revisions need the build database and undo snapshots, which are not configured"));
                return;
            }

            // Parse coordinates
            int x, y, z;
            try {
                x = xStr != null ? Integer.parseInt(xStr) : 0;
                y = yStr != null ? Integer.parseInt(yStr) : 64;
                z = zStr != null ? Integer.parseInt(zStr) : 0;
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid coordinate values. x, y, z must be integers"));
                return;
            }

            // Parse rotation
            BlockRotation rotation;
            try {
                rotation = BlockRotation.valueOf(rotationStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid rotation: " + rotationStr +
                    ". Valid values: NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90"));
                return;
            }

            // Parse boolean parameters
            boolean includeEntities = Boolean.parseBoolean(includeEntitiesStr);

            // Validate world
            RegistryKey<World> worldKey = WorldResolver.resolveWorldKey(worldName);
            ServerWorld world = worldKey != null ? server.getWorld(worldKey) : null;
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldName));
                return;
            }

            LOGGER.info("Placing NBT structure '{}' at ({}, {}, {}) in world {} with rotation {} and entities={}",
                nbtFile.filename(), x, y, z, worldName, rotation, includeEntities);

            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

            byte[] nbtData;

            try {
                nbtData = nbtFile.content().readAllBytes();
            } catch (IOException e) {
                LOGGER.error("Error reading NBT file: ", e);
                ctx.status(500).json(Map.of("error", "Failed to read NBT file: " + e.getMessage()));
                return;
            }
            NbtCompound nbtCompound = readNbt(nbtData);

            // Create structure template from NBT data
            StructureTemplateManager structureManager = server.getStructureTemplateManager();
            StructureTemplate template = structureManager.createTemplate(nbtCompound);

            // Staged builds also send each install stage, in order. nbt_file stays the finished
            // structure (bounds, dry run); the stages are what actually gets placed.
            int stageDelay = StagedPlacement.parseDelay(ctx.formParam("stage_delay_ticks"));
            List<StructureTemplate> stageTemplates = new ArrayList<>();
            List<int[]> stageSizes = new ArrayList<>();
            for (UploadedFile stageFile : ctx.uploadedFiles("stage_nbt_file")) {
                StructureTemplate stage = structureManager.createTemplate(readNbt(stageFile.content().readAllBytes()));
                stageTemplates.add(stage);
                stageSizes.add(new int[]{stage.getSize().getX(), stage.getSize().getY(), stage.getSize().getZ()});
            }
            Vec3i mainSize = template.getSize();
            String stageError = StagedPlacement.validateStages(
                new int[]{mainSize.getX(), mainSize.getY(), mainSize.getZ()}, stageSizes);
            if (stageError != null) {
                ctx.status(400).json(Map.of("error", stageError));
                return;
            }
            boolean staged = !stageTemplates.isEmpty();
            int stageCount = stageTemplates.size();

            // Create placement data with settings
            StructurePlacementData placementData = new StructurePlacementData()
                .setRotation(rotation)
                .setIgnoreEntities(!includeEntities)
                .setRandom(Random.create());

            // Place the structure at the specified position
            BlockPos pos = new BlockPos(x, y, z);

            String lockId = ctx.header(AreaLockService.HEADER);
            String worldId = worldKey.getValue().toString();
            Vec3i dimensions = template.getSize();
            AreaBounds bounds;
            try {
                bounds = PlacementBounds.structure(x, y, z, dimensions.getX(), dimensions.getY(), dimensions.getZ(), rotation.name());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid structure: " + e.getMessage()));
                return;
            }

            if (dryRun) {
                dryRun(ctx, world, worldKey, worldName, nbtCompound, pos, placementData, bounds, dimensions, rotation, lockId,
                    replacesId, force);
                return;
            }

            // Captured inside the lock's critical section, immediately before the write it can undo.
            AtomicReference<NbtCompound> snapshot = new AtomicReference<>();
            // A revision's cuboid after the replaced build is cleared back to terrain.
            AtomicReference<NbtCompound> base = new AtomicReference<>();
            AtomicReference<AreaBounds> written = new AtomicReference<>(bounds);
            // Execute on server thread
            OperationHttp.dispatch(server::execute, () -> {
                try {
                    // Checked on the server thread, which also serializes undo/redo of the replaced build.
                    Revision revision = replacesId == null ? null : prepareRevision(replacesId, worldKey, bounds);
                    if (revision != null && !revision.conflicts().isEmpty() && !force) {
                        future.complete(revisionConflict(revision));
                        return;
                    }
                    AreaBounds writeBounds = revision == null ? bounds : revision.union();
                    written.set(writeBounds);
                    StructureTemplate first = staged ? stageTemplates.get(0) : template;
                    boolean success = locks.execute(worldId, writeBounds, lockId, () -> {
                        if (snapshots != null && snapshots.fits(writeBounds)) snapshot.set(RegionSnapshot.capture(world, writeBounds));
                        if (revision != null) {
                            StructureTemplateManager manager = server.getStructureTemplateManager();
                            if (!RegionSnapshot.restore(world, manager.createTemplate(revision.base()), revision.baseBounds())) {
                                throw new IllegalStateException("Clearing build " + replacesId + " back to terrain failed; "
                                    + "inspect the world before retrying");
                            }
                            base.set(RegionSnapshot.capture(world, writeBounds));
                            boolean placed = first.place(world, pos, pos, placementData, Random.create(), 2);
                            // Never leave the area cleared: put the replaced build back.
                            if (!placed) RegionSnapshot.restore(world, manager.createTemplate(snapshot.get()), writeBounds);
                            return placed;
                        }
                        return first.place(world, pos, pos, placementData, Random.create(), 2);
                    }, Boolean.TRUE::equals);

                    if (success) {
                        if (!staged) emitEffects(world, bounds);
                        // Get structure size for response
                        Vec3i size = template.getSize();

                        LOGGER.info("Successfully placed NBT structure '{}' ({}x{}x{}) at ({}, {}, {})",
                            filename, size.getX(), size.getY(), size.getZ(), x, y, z);

                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("message", "Structure placed successfully");
                        response.put("filename", filename);
                        response.put("position", Map.of("x", x, "y", y, "z", z));
                        response.put("world", worldName);
                        response.put("structure_size", Map.of("x", size.getX(), "y", size.getY(), "z", size.getZ()));
                        response.put("rotation", rotation.toString());
                        response.put("include_entities", includeEntities);
                        if (staged) {
                            response.put("stages", stageCount);
                            response.put("stage_delay_ticks", stageDelay);
                        }

                        Map<String, Object> revised = new LinkedHashMap<>();
                        if (revision != null) {
                            revised.put("replaces_build_id", replacesId.toString());
                            revised.put("cleared_bounds", revision.baseBounds());
                            revised.put("bounds", writeBounds);
                            revised.put("overwrote_later_builds", revision.conflicts().size());
                            response.put("revision", revised);
                        }

                        if (buildService != null) {
                            try {
                                Build recorded = buildService.recordNbtPlacement(new BuildService.PlacementRecord(
                                    filename, worldName, x, y, z,
                                    size.getX(), size.getY(), size.getZ(), rotation.toString(), provenance, replacesId,
                                    revision == null ? null : box(writeBounds)));
                                response.put("build_id", recorded.getId().toString());
                                if (revision != null) {
                                    revised.put("parent_status", "SUPERSEDED");
                                }
                            } catch (AreaLockException e) {
                                future.completeExceptionally(e);
                            } catch (Exception e) {
                                LOGGER.warn("Failed to record NBT placement in build system: {}", e.getMessage());
                                if (revision != null) {
                                    response.put("warning", "The revision was placed but not recorded (" + e.getMessage()
                                        + "); build " + replacesId + " still shows as COMPLETED and cannot be undone from here");
                                }
                            }
                        }

                        if (!staged) {
                            future.complete(response);
                        } else {
                            // NOTIFY_ALL sends normal updates from each write. Skip the template's
                            // additional shape update pass, which can pulse newly placed observers.
                            StructurePlacementData stagePlacementData = placementData.copy().setUpdateNeighbors(true);
                            List<StagedPlacement.Stage> rest = new ArrayList<>();
                            for (StructureTemplate stage : stageTemplates.subList(1, stageCount)) {
                                rest.add(() -> locks.execute(worldId, bounds, lockId,
                                    () -> stage.place(world, pos, pos, stagePlacementData, Random.create(), Block.NOTIFY_ALL),
                                    Boolean.TRUE::equals));
                            }
                            StagedPlacement.instance().start(rest, stageDelay, 1).whenComplete((placed, failure) -> {
                                if (failure == null) {
                                    emitEffects(world, bounds);
                                    response.put("stages_placed", placed);
                                    future.complete(response);
                                    return;
                                }
                                int done = failure instanceof StagedPlacement.StageFailure stop ? stop.placed : 0;
                                Object buildId = response.get("build_id");
                                LOGGER.warn("Staged placement of '{}' stopped after {} of {} stages: {}",
                                    filename, done, stageCount, failure.getMessage());
                                response.put("success", false);
                                response.put("stages_placed", done);
                                response.put("error", "Staged placement stopped after " + done + " of " + stageCount
                                    + " stages (" + failure.getMessage() + ")"
                                    + (buildId != null ? "; undo_build " + buildId + " restores the area" : ""));
                                future.complete(response);
                            });
                        }
                    } else {
                        LOGGER.warn("Failed to place NBT structure '{}' at ({}, {}, {})",
                            filename, x, y, z);

                        future.complete(Map.of(
                            "success", false,
                            "error", "Structure placement failed. Check coordinates and world state."
                        ));
                    }

                } catch (AreaLockException e) {
                    future.completeExceptionally(e);
                } catch (Exception e) {
                    LOGGER.error("Error placing structure: ", e);
                    future.complete(Map.of("error", "Exception during structure placement: " + e.getMessage()));
                }
            }, future);

            CompletableFuture<Map<String, Object>> persisted = future.thenApplyAsync(result -> {
                // A staged run that stopped part-way still wrote blocks, so its snapshot is kept for undo.
                if (Boolean.TRUE.equals(result.get("success")) || result.get("build_id") != null) {
                    persistSnapshot(result, snapshot.get(), written.get());
                    persistExtras(result, nbtCompound, base.get());
                }
                return result;
            }, OperationHttp.persistenceExecutor(ctx));

            if (replacesId != null) {
                respondWithStatus(ctx, persisted);
                return;
            }
            respond(ctx, persisted, TIMEOUT_SECONDS, "structure placement",
                result -> !result.containsKey("error"),
                result -> (String) result.get("error"),
                result -> result);

        } catch (Exception e) {
            LOGGER.error("Error processing structure placement request: ", e);
            ctx.status(400).json(Map.of("error", "Invalid request: " + e.getMessage()));
        }
    }

    private NbtCompound readNbt(byte[] data) throws IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        if (isGzipped(data)) {
            return NbtIo.readCompressed(inputStream, NbtSizeTracker.ofUnlimitedBytes());
        }
        return NbtIo.readCompound(new DataInputStream(inputStream), NbtSizeTracker.ofUnlimitedBytes());
    }

    private void emitEffects(ServerWorld world, AreaBounds bounds) {
        try {
            StructurePlacementEffect.emit(world, bounds);
            StructureReveal.start(world, bounds);
        } catch (Exception e) {
            LOGGER.warn("Failed to emit NBT placement effect", e);
        }
    }

    /** Adds undo availability; the snapshot file must exist before the response claims undo. */
    private void persistSnapshot(Map<String, Object> response, NbtCompound snapshot, AreaBounds bounds) {
        String reason = null;
        Object buildId = response.get("build_id");
        if (snapshots == null) {
            reason = "snapshots_disabled";
        } else if (snapshot == null) {
            reason = snapshots.fits(bounds) ? "snapshot_unavailable" : "snapshot_too_large";
        } else if (buildId == null) {
            reason = "build_not_recorded";
        } else {
            try {
                snapshots.save(UUID.fromString(buildId.toString()), snapshot);
            } catch (Exception e) {
                LOGGER.warn("Failed to write undo snapshot for build {}: {}", buildId, e.getMessage());
                reason = "snapshot_write_failed";
            }
        }
        response.put("undo_available", reason == null);
        if (reason != null) response.put("undo_unavailable_reason", reason);
    }

    /** Saves the placed template (for verify_build) and a revision's base terrain beside the undo snapshot. */
    private void persistExtras(Map<String, Object> response, NbtCompound template, NbtCompound revisionBase) {
        Object buildId = response.get("build_id");
        if (snapshots == null || buildId == null) return;
        UUID id = UUID.fromString(buildId.toString());
        try {
            snapshots.saveTemplate(id, template);
        } catch (Exception e) {
            LOGGER.warn("Failed to save placed template for build {}: {}", buildId, e.getMessage());
        }
        if (revisionBase == null) return;
        try {
            snapshots.saveBase(id, revisionBase);
        } catch (Exception e) {
            LOGGER.warn("Failed to save revision base for build {}: {}", buildId, e.getMessage());
            response.put("warning", "This revision cannot itself be revised in place: its base terrain was not saved ("
                + e.getMessage() + "). Undo still works.");
        }
    }

    /**
     * The build a revision replaces: its recorded cuboid, the terrain to restore there before placing
     * the new structure, and later builds that clearing it would erase.
     */
    private record Revision(UUID parentId, Build parent, AreaBounds baseBounds, AreaBounds union, NbtCompound base,
                            List<Build> conflicts) {}

    private Revision prepareRevision(UUID parentId, RegistryKey<World> worldKey, AreaBounds newBounds) throws Exception {
        BuildService.NbtPlacement placement = buildService.getRevisablePlacement(parentId)
            .orElseThrow(() -> new IllegalArgumentException("Build not found: " + parentId));
        Build parent = placement.build();
        if (!worldKey.equals(WorldResolver.resolveWorldKey(parent.getWorld()))) {
            throw new IllegalArgumentException("Build " + parentId + " is in " + parent.getWorld()
                + "; a revision must be placed in the same world");
        }
        if (snapshots.pending(parentId)) {
            throw new IllegalStateException("An earlier restore of build " + parentId + " is unresolved; inspect the world first");
        }
        AreaBounds baseBounds = PlacementBounds.recorded(placement);
        // A root placement's undo snapshot is the terrain; a revision's undo snapshot holds the build it
        // replaced, so its own base is used instead.
        NbtCompound base = (parent.getParentBuildId() == null ? snapshots.load(parentId) : snapshots.loadBase(parentId))
            .orElseThrow(() -> new IllegalArgumentException("Build " + parentId + " has no saved "
                + (parent.getParentBuildId() == null ? "undo snapshot" : "revision base")
                + ", so it cannot be revised in place; undo_build it (or clear the area) and place the new structure instead"));
        AreaBounds union = baseBounds.union(newBounds);
        if (!snapshots.fits(union)) {
            throw new IllegalArgumentException("Revision area " + SnapshotStore.volume(union) + " blocks exceeds the undo snapshot limit of "
                + snapshots.maxVolume() + "; a revision must be undoable");
        }
        List<Build> conflicts = buildService.findLaterOverlappingBuilds(parent, box(baseBounds));
        return new Revision(parentId, parent, baseBounds, union, base, conflicts);
    }

    private static Map<String, Object> revisionConflict(Revision revision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "revision_conflict");
        body.put("error", "Later builds overlap build " + revision.parentId() + " and would be erased when it is cleared; "
            + "pass force=true to revise anyway");
        body.put("total", revision.conflicts().size());
        body.put("builds", conflictList(revision.conflicts()));
        body.put("http_status", 409);
        return body;
    }

    private static List<Map<String, Object>> conflictList(List<Build> conflicts) {
        return conflicts.stream().limit(5).map(b -> Map.<String, Object>of(
            "build_id", b.getId().toString(), "name", b.getName(), "status", b.getStatus().name())).toList();
    }

    private static BoundingBox box(AreaBounds b) {
        return new BoundingBox(b.min_x(), b.min_y(), b.min_z(), b.max_x(), b.max_y(), b.max_z());
    }

    /** Like respond(), but honours an {@code http_status} in the result and maps exceptions like build routes. */
    private void respondWithStatus(Context ctx, CompletableFuture<Map<String, Object>> future) {
        java.util.function.Function<Map<String, Object>, OperationRegistry.Response> mapper = result -> {
            Map<String, Object> body = new LinkedHashMap<>(result);
            Object status = body.remove("http_status");
            int code = status instanceof Integer i ? i : body.containsKey("error") ? 500 : 200;
            return new OperationRegistry.Response(code, body);
        };
        try {
            if (OperationHttp.tracked(ctx)) {
                OperationHttp.respond(ctx, future, TIMEOUT_SECONDS, mapper);
                return;
            }
            OperationRegistry.Response response = mapper.apply(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ctx.status(response.status()).json(response.body());
        } catch (TimeoutException e) {
            ctx.status(504).json(Map.of("error", "Timed out waiting for the revision; inspect the build and the world before retrying"));
        } catch (Exception e) {
            OperationRegistry.Response response = OperationHttp.failure(e);
            ctx.status(response.status()).json(response.body());
        }
    }

    /** Reports what a placement would overwrite without writing blocks, renewing locks, or loading chunks. */
    private void dryRun(Context ctx, ServerWorld world, RegistryKey<World> worldKey, String worldName, NbtCompound nbt,
                        BlockPos pos, StructurePlacementData placementData, AreaBounds structureBounds, Vec3i size,
                        BlockRotation rotation, String lockId, UUID replacesId, boolean force) throws Exception {
        String worldId = worldKey.getValue().toString();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        AtomicReference<AreaBounds> checked = new AtomicReference<>(structureBounds);
        OperationHttp.dispatch(server::execute, () -> {
            if (future.isCancelled()) return;
            try {
                Revision revision = replacesId == null ? null : prepareRevision(replacesId, worldKey, structureBounds);
                AreaBounds bounds = revision == null ? structureBounds : revision.union();
                checked.set(bounds);
                var missing = RegionSnapshot.missingChunks(world, bounds);
                if (!missing.isEmpty()) {
                    future.complete(Map.of("success", false, "code", "unloaded_chunks",
                        "error", "Chunks in the placement bounds are not loaded", "missing_chunks", missing));
                    return;
                }
                Map<String, Object> lockCheck;
                try {
                    // Same authorization as a real placement; a false success predicate never renews the lease.
                    locks.execute(worldId, bounds, lockId, () -> null, ignored -> false);
                    lockCheck = Map.of("ok", true);
                } catch (AreaLockException e) {
                    lockCheck = new LinkedHashMap<>(e.payload());
                    lockCheck.remove("success");
                    lockCheck.put("ok", false);
                }
                // A revision is compared with the terrain its predecessor will be cleared back to.
                OverwriteTally.Report overwrite = revision == null
                    ? RegionSnapshot.scan(world, nbt, pos, placementData)
                    : RegionSnapshot.scan(world, nbt, pos, placementData, RegionSnapshot.states(revision.base(), revision.baseBounds()));
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("dry_run", true);
                response.put("world", worldName);
                response.put("position", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
                response.put("rotation", rotation.toString());
                response.put("structure_size", Map.of("x", size.getX(), "y", size.getY(), "z", size.getZ()));
                response.put("bounds", structureBounds);
                response.put("overwrite", overwrite);
                if (revision != null) {
                    Map<String, Object> revised = new LinkedHashMap<>();
                    revised.put("replaces_build_id", replacesId.toString());
                    revised.put("cleared_bounds", revision.baseBounds());
                    revised.put("bounds", bounds);
                    // How the new structure differs from what stands there now (mostly the old build).
                    revised.put("vs_current", RegionSnapshot.scan(world, nbt, pos, placementData));
                    revised.put("conflicts", Map.of("total", revision.conflicts().size(),
                        "builds", conflictList(revision.conflicts()), "blocking", !force && !revision.conflicts().isEmpty()));
                    response.put("revision", revised);
                }
                response.put("lock_check", lockCheck);
                response.put("reservations", SurveyOccupancy.reservations(locks, worldId, bounds));
                response.put("undo_snapshot", snapshots != null && snapshots.fits(bounds));
                future.complete(response);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, future);
        Map<String, Object> result;
        try {
            result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            ctx.status(504).json(Map.of("success", false, "code", "dry_run_timeout", "error", "Timed out waiting for dry run"));
            return;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof IllegalStateException) {
                OperationRegistry.Response response = OperationHttp.failure(e.getCause());
                ctx.status(response.status()).json(response.body());
                return;
            }
            LOGGER.error("Error during NBT dry run: ", e.getCause());
            ctx.status(500).json(Map.of("error", "Exception during dry run: " + e.getCause().getMessage()));
            return;
        }
        if (!Boolean.TRUE.equals(result.get("success"))) {
            ctx.status(400).json(result);
            return;
        }
        if (occupancy != null) result.put("builds", occupancy.builds(worldName, checked.get(), replacesId));
        ctx.json(result);
    }
}
