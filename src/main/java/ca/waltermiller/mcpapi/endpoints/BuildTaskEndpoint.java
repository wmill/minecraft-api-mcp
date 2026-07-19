package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.model.BuildStatus;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import ca.waltermiller.mcpapi.buildtask.service.LocationQueryService;
import ca.waltermiller.mcpapi.buildtask.service.RailPlanningService;
import ca.waltermiller.mcpapi.preview.BlockGrid;
import ca.waltermiller.mcpapi.preview.IsoRenderer;
import ca.waltermiller.mcpapi.preview.PreviewViewDirection;
import ca.waltermiller.mcpapi.preview.RecordingBlockSink;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * HTTP endpoint handler for build task management.
 * Provides REST API for creating builds, managing task queues, and executing builds.
 * Requirements: 6.1, 6.4, 6.5
 */
public class BuildTaskEndpoint extends APIEndpoint {
    private static final int MIN_ISO_SCALE = 1;
    private static final int MAX_ISO_SCALE = 32;
    private static final int MAX_TERRAIN_MARGIN = 8;

    private final BuildService buildService;
    private final LocationQueryService locationQueryService;
    private final RailPlanningService railPlanningService;
    private final TaskExecutor taskExecutor;
    private final RailRenderInspectionService railRenderInspectionService;

    public BuildTaskEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger,
                           BuildService buildService, LocationQueryService locationQueryService,
                           RailPlanningService railPlanningService, TaskExecutor taskExecutor) {
        super(app, server, logger);
        this.buildService = buildService;
        this.locationQueryService = locationQueryService;
        this.railPlanningService = railPlanningService;
        this.taskExecutor = taskExecutor;
        this.railRenderInspectionService = new RailRenderInspectionService(taskExecutor);
        init();
    }

    private void init() {
        registerCreateBuild();
        registerGetBuild();
        registerAddTask();
        registerGetTasks();
        registerUpdateTaskQueue();
        registerDeleteTask();
        registerPatchTask();
        registerExecuteBuild();
        registerReplayBuild();
        registerCloneBuild();
        registerQueryLocation();
        registerAuditBuild();
        registerPlanRail();
        registerTranslateBuild();
        registerPreviewBuild();
        registerGetRailPlan();
    }

    @FunctionalInterface
    private interface RouteLogic {
        void run() throws Exception;
    }

    /**
     * Runs a route handler with the standard error mapping: IllegalArgumentException to 400,
     * IllegalStateException to 409, SQLException to 500 (database error), anything else to 500.
     */
    private void handle(Context ctx, String operation, RouteLogic logic) {
        try {
            logic.run();
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", Objects.toString(e.getMessage(), "Invalid request")));
        } catch (IllegalStateException e) {
            ctx.status(409).json(Map.of("error", Objects.toString(e.getMessage(), "Conflict")));
        } catch (SQLException e) {
            LOGGER.error("Database error {}", operation, e);
            ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
        } catch (Exception e) {
            LOGGER.error("Unexpected error {}", operation, e);
            ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Parses a UUID path parameter, writing a 400 response and returning null when malformed.
     */
    private UUID pathUuid(Context ctx, String param, String label) {
        try {
            return UUID.fromString(ctx.pathParam(param));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "Invalid " + label + " format"));
            return null;
        }
    }

    private Map<String, Object> buildToMap(Build build) {
        Map<String, Object> buildJson = new LinkedHashMap<>();
        buildJson.put("id", build.getId().toString());
        buildJson.put("name", build.getName());
        buildJson.put("description", Objects.toString(build.getDescription(), ""));
        buildJson.put("world", build.getWorld());
        buildJson.put("status", build.getStatus().toString());
        buildJson.put("created_at", build.getCreatedAt().toString());
        if (build.getCompletedAt() != null) {
            buildJson.put("completed_at", build.getCompletedAt().toString());
        }
        return buildJson;
    }

    private Map<String, Object> taskToMap(BuildTask task) {
        Map<String, Object> taskMap = new LinkedHashMap<>();
        taskMap.put("id", task.getId().toString());
        taskMap.put("build_id", task.getBuildId().toString());
        taskMap.put("task_order", task.getTaskOrder());
        taskMap.put("task_type", task.getTaskType().toString());
        taskMap.put("status", task.getStatus().toString());
        taskMap.put("task_data", task.getTaskData());
        taskMap.put("description", Objects.toString(task.getDescription(), ""));
        if (task.getExecutedAt() != null) {
            taskMap.put("executed_at", task.getExecutedAt().toString());
        }
        if (task.getErrorMessage() != null) {
            taskMap.put("error_message", task.getErrorMessage());
        }
        return taskMap;
    }

    // POST /api/builds - Create new build
    private void registerCreateBuild() {
        app.post("/api/builds", ctx -> handle(ctx, "creating build", () -> {
            BuildService.CreateBuildRequest request = ctx.bodyAsClass(BuildService.CreateBuildRequest.class);

            if (request.name == null || request.name.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Build name is required"));
                return;
            }

            Build build = buildService.createBuild(request);

            ctx.status(201).json(Map.of(
                "success", true,
                "build", buildToMap(build)
            ));

            LOGGER.info("Created build {} via API", build.getId());
        }));
    }

    // GET /api/builds/{id} - Get build details
    private void registerGetBuild() {
        app.get("/api/builds/{id}", ctx -> handle(ctx, "retrieving build", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            Optional<Build> buildOpt = buildService.getBuild(buildId);
            if (buildOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Build not found"));
                return;
            }
            Build build = buildOpt.get();

            List<BuildTask> tasks = buildService.getTasks(buildId);
            List<Map<String, Object>> taskMaps = tasks.stream()
                .map(this::taskToMap)
                .collect(Collectors.toList());

            // Compute aggregate bounding box across all tasks
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            boolean hasBounds = false;
            for (BuildTask task : tasks) {
                BoundingBox bb = task.getCoordinates();
                if (bb != null) {
                    minX = Math.min(minX, bb.getMinX()); minY = Math.min(minY, bb.getMinY()); minZ = Math.min(minZ, bb.getMinZ());
                    maxX = Math.max(maxX, bb.getMaxX()); maxY = Math.max(maxY, bb.getMaxY()); maxZ = Math.max(maxZ, bb.getMaxZ());
                    hasBounds = true;
                }
            }
            Map<String, Object> boundingBox = hasBounds ? Map.of(
                "min_x", minX, "min_y", minY, "min_z", minZ,
                "max_x", maxX, "max_y", maxY, "max_z", maxZ,
                "size_x", maxX - minX + 1, "size_y", maxY - minY + 1, "size_z", maxZ - minZ + 1
            ) : null;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("build", buildToMap(build));
            response.put("tasks", taskMaps);
            if (boundingBox != null) {
                response.put("bounding_box", boundingBox);
            }
            ctx.json(response);
        }));
    }

    // POST /api/builds/{id}/tasks - Add task to build (optional task_order for insertion)
    private void registerAddTask() {
        app.post("/api/builds/{id}/tasks", ctx -> handle(ctx, "adding task", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            AddTaskWithOrderRequest request = ctx.bodyAsClass(AddTaskWithOrderRequest.class);

            if (request.task_type == null) {
                ctx.status(400).json(Map.of("error", "Task type is required"));
                return;
            }
            if (request.task_data == null) {
                ctx.status(400).json(Map.of("error", "Task data is required"));
                return;
            }

            BuildService.AddTaskRequest addRequest = new BuildService.AddTaskRequest(
                request.task_type, request.task_data, request.description != null ? request.description : "");

            BuildTask task = request.task_order != null
                ? buildService.insertTaskAt(buildId, addRequest, request.task_order)
                : buildService.addTask(buildId, addRequest);

            ctx.status(201).json(Map.of(
                "success", true,
                "task", Map.of(
                    "id", task.getId().toString(),
                    "buildId", task.getBuildId().toString(),
                    "taskOrder", task.getTaskOrder(),
                    "taskType", task.getTaskType().toString(),
                    "status", task.getStatus().toString(),
                    "taskData", task.getTaskData()
                )
            ));

            LOGGER.info("Added task {} to build {} at position {} via API", task.getId(), buildId, task.getTaskOrder());
        }));
    }

    // GET /api/builds/{id}/tasks - Get build task queue
    private void registerGetTasks() {
        app.get("/api/builds/{id}/tasks", ctx -> handle(ctx, "retrieving tasks", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            List<BuildTask> tasks = buildService.getTasks(buildId);
            List<Map<String, Object>> taskMaps = tasks.stream()
                .map(this::taskToMap)
                .collect(Collectors.toList());

            ctx.json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "task_count", tasks.size(),
                "tasks", taskMaps
            ));
        }));
    }

    // PUT /api/builds/{id}/tasks - Update task queue (reorder tasks)
    private void registerUpdateTaskQueue() {
        app.put("/api/builds/{id}/tasks", ctx -> handle(ctx, "updating task queue", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> requestBody = ctx.bodyAsClass(Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> taskMaps = (List<Map<String, Object>>) requestBody.get("tasks");

            if (taskMaps == null) {
                ctx.status(400).json(Map.of("error", "Tasks array is required"));
                return;
            }

            // Convert task maps to BuildTask objects, preserving stored data
            List<BuildTask> existingTasks = buildService.getTasks(buildId);
            List<BuildTask> tasks = taskMaps.stream()
                .map(taskMap -> {
                    try {
                        UUID taskId = UUID.fromString((String) taskMap.get("id"));
                        return existingTasks.stream()
                            .filter(t -> t.getId().equals(taskId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
                    } catch (Exception e) {
                        throw new RuntimeException("Invalid task in request: " + e.getMessage(), e);
                    }
                })
                .collect(Collectors.toList());

            buildService.updateTaskQueue(buildId, tasks);

            ctx.json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "task_count", tasks.size(),
                "message", "Task queue updated successfully"
            ));

            LOGGER.info("Updated task queue for build {} via API", buildId);
        }));
    }

    // DELETE /api/builds/{id}/tasks/{taskId} - Delete a specific task
    private void registerDeleteTask() {
        app.delete("/api/builds/{id}/tasks/{taskId}", ctx -> handle(ctx, "deleting task", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }
            UUID taskId = pathUuid(ctx, "taskId", "task ID");
            if (taskId == null) {
                return;
            }

            buildService.deleteTask(buildId, taskId);

            ctx.json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "deleted_task_id", taskId.toString(),
                "message", "Task deleted successfully"
            ));

            LOGGER.info("Deleted task {} from build {} via API", taskId, buildId);
        }));
    }

    // PATCH /api/builds/{id}/tasks/{taskId} - Update task_data and/or description
    private void registerPatchTask() {
        app.patch("/api/builds/{id}/tasks/{taskId}", ctx -> handle(ctx, "updating task", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }
            UUID taskId = pathUuid(ctx, "taskId", "task ID");
            if (taskId == null) {
                return;
            }

            PatchTaskRequest request = ctx.bodyAsClass(PatchTaskRequest.class);

            if (request.task_data == null && request.description == null) {
                ctx.status(400).json(Map.of("error", "At least one of task_data or description must be provided"));
                return;
            }

            BuildTask updatedTask = buildService.updateTask(buildId, taskId, request.task_data, request.description);

            ctx.json(Map.of(
                "success", true,
                "task", taskToMap(updatedTask)
            ));

            LOGGER.info("Updated task {} in build {} via API", taskId, buildId);
        }));
    }

    // POST /api/builds/{id}/execute - Execute build
    private void registerExecuteBuild() {
        app.post("/api/builds/{id}/execute", ctx -> handle(ctx, "starting build execution", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            // Execute build asynchronously; clients poll build status for progress
            buildService.executeBuild(buildId)
                .exceptionally(throwable -> {
                    LOGGER.error("Error during build execution", throwable);
                    return null;
                });

            ctx.status(202).json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "message", "Build execution started",
                "status", "accepted"
            ));

            LOGGER.info("Started execution of build {} via API", buildId);
        }));
    }

    // POST /api/builds/{id}/replay - Replay a completed or failed build
    private void registerReplayBuild() {
        app.post("/api/builds/{id}/replay", ctx -> handle(ctx, "starting build replay", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            // Replay build asynchronously (resets tasks and re-executes)
            buildService.replayBuild(buildId)
                .exceptionally(throwable -> {
                    LOGGER.error("Error during build replay", throwable);
                    return null;
                });

            ctx.status(202).json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "message", "Build replay started (tasks reset and re-executing)",
                "status", "accepted"
            ));

            LOGGER.info("Started replay of build {} via API", buildId);
        }));
    }

    // POST /api/builds/{id}/clone - Clone a build with a new UUID; source is preserved unchanged
    private void registerCloneBuild() {
        app.post("/api/builds/{id}/clone", ctx -> handle(ctx, "cloning build", () -> {
            UUID sourceId = pathUuid(ctx, "id", "build ID");
            if (sourceId == null) {
                return;
            }

            Build newBuild = buildService.cloneBuild(sourceId);
            List<BuildTask> clonedTasks = buildService.getTasks(newBuild.getId());
            ctx.status(200).json(Map.of(
                "success", true,
                "source_build_id", sourceId.toString(),
                "new_build_id", newBuild.getId().toString(),
                "tasks_cloned", clonedTasks.size()
            ));
            LOGGER.info("Cloned build {} → {} ({} tasks)", sourceId, newBuild.getId(), clonedTasks.size());
        }));
    }

    // POST /api/builds/query-location - Query builds by location
    private void registerQueryLocation() {
        app.post("/api/builds/query-location", ctx -> handle(ctx, "during location query", () -> {
            LocationQueryService.LocationQueryRequest request =
                ctx.bodyAsClass(LocationQueryService.LocationQueryRequest.class);

            if (request.world == null || request.world.trim().isEmpty()) {
                request.world = "minecraft:overworld";
            }

            if (request.min_x > request.max_x || request.min_y > request.max_y || request.min_z > request.max_z) {
                ctx.status(400).json(Map.of("error", "Invalid coordinate range: min values must be <= max values"));
                return;
            }

            LocationQueryService.LocationQueryResult result = locationQueryService.queryBuildsByLocation(request);

            // Convert result to JSON-friendly format (this route's wire format uses camelCase keys)
            List<Map<String, Object>> buildResults = result.builds.stream()
                .map(buildResult -> {
                    Map<String, Object> buildMap = new LinkedHashMap<>();
                    buildMap.put("id", buildResult.build.getId().toString());
                    buildMap.put("name", buildResult.build.getName());
                    buildMap.put("description", Objects.toString(buildResult.build.getDescription(), ""));
                    buildMap.put("world", buildResult.build.getWorld());
                    buildMap.put("status", buildResult.build.getStatus().toString());
                    buildMap.put("createdAt", buildResult.build.getCreatedAt().toString());
                    if (buildResult.build.getCompletedAt() != null) {
                        buildMap.put("completedAt", buildResult.build.getCompletedAt().toString());
                    }

                    List<Map<String, Object>> taskMaps = buildResult.intersecting_tasks.stream()
                        .map(task -> {
                            Map<String, Object> taskMap = new LinkedHashMap<>();
                            taskMap.put("id", task.getId().toString());
                            taskMap.put("task_order", task.getTaskOrder());
                            taskMap.put("task_type", task.getTaskType().toString());
                            taskMap.put("status", task.getStatus().toString());
                            if (task.getCoordinates() != null) {
                                taskMap.put("coordinates", boundingBoxToMap(task.getCoordinates()));
                            }
                            return taskMap;
                        })
                        .collect(Collectors.toList());

                    return Map.<String, Object>of(
                        "build", buildMap,
                        "intersectingTasks", taskMaps
                    );
                })
                .collect(Collectors.toList());

            Map<String, Object> queryArea = new LinkedHashMap<>();
            queryArea.put("world", request.world);
            queryArea.putAll(boundingBoxToMap(result.query_area));

            ctx.json(Map.of(
                "success", true,
                "query_area", queryArea,
                "build_count", result.getBuildCount(),
                "total_task_count", result.getTotalTaskCount(),
                "builds", buildResults
            ));

            LOGGER.info("Location query returned {} builds in world {} for area ({},{},{}) to ({},{},{})",
                result.getBuildCount(), request.world, request.min_x, request.min_y, request.min_z,
                request.max_x, request.max_y, request.max_z);
        }));
    }

    private Map<String, Object> boundingBoxToMap(BoundingBox box) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("min_x", box.getMinX());
        map.put("min_y", box.getMinY());
        map.put("min_z", box.getMinZ());
        map.put("max_x", box.getMaxX());
        map.put("max_y", box.getMaxY());
        map.put("max_z", box.getMaxZ());
        return map;
    }

    // POST /api/builds/{id}/audit - Audit build task queue for obvious problems
    private void registerAuditBuild() {
        app.post("/api/builds/{id}/audit", ctx -> handle(ctx, "during audit", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            Optional<Build> buildOpt = buildService.getBuild(buildId);
            if (buildOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Build not found"));
                return;
            }
            Build build = buildOpt.get();

            List<BuildTask> tasks = buildService.getTasks(buildId);
            List<Map<String, Object>> issues = new ArrayList<>();

            // Sort tasks by order to ensure correct sequencing
            tasks.sort(Comparator.comparingInt(BuildTask::getTaskOrder));

            for (int i = 0; i < tasks.size(); i++) {
                BuildTask task = tasks.get(i);
                JsonNode taskData = task.getTaskData();

                // Check 1: Stair direction alignment
                if (task.getTaskType() == TaskType.PREFAB_STAIRS) {
                    checkStairDirection(task, taskData, issues);
                }

                // Check 2: Fill overwriting earlier structures
                if (task.getTaskType() == TaskType.BLOCK_FILL) {
                    checkFillOverwrite(task, taskData, tasks.subList(0, i), issues);
                }

                // Check 3: Overlap with tasks belonging to other builds
                checkCrossBuildOverlap(build, task, buildId, issues);
            }

            RegistryKey<World> worldKey = WorldResolver.resolveWorldKey(build.getWorld());
            ServerWorld serverWorld = worldKey != null ? server.getWorld(worldKey) : null;
            RecordingBlockSink railInspectionSink = serverWorld != null ? new RecordingBlockSink(serverWorld) : null;
            issues.addAll(railRenderInspectionService.inspect(tasks, railInspectionSink));

            int warningCount = (int) issues.stream()
                .filter(issue -> "warning".equals(issue.get("severity")))
                .count();
            int errorCount = (int) issues.stream()
                .filter(issue -> "error".equals(issue.get("severity")))
                .count();

            ctx.json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "issues", issues,
                "summary", Map.of("warnings", warningCount, "errors", errorCount)
            ));

            LOGGER.info("Audited build {} - found {} warnings, {} errors", buildId, warningCount, errorCount);
        }));
    }

    // POST /api/builds/{id}/plan-rail - Start async rail corridor planning
    private void registerPlanRail() {
        app.post("/api/builds/{id}/plan-rail", ctx -> handle(ctx, "starting rail planner", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }
            RailPlanningService.StartRailPlanningRequest request = ctx.bodyAsClass(RailPlanningService.StartRailPlanningRequest.class);
            request.build_id = buildId;
            if (request.world == null || request.world.isBlank()) {
                Optional<Build> build = buildService.getBuild(buildId);
                request.world = build.map(Build::getWorld).orElse("minecraft:overworld");
            }

            var job = railPlanningService.startPlanning(request);
            ctx.status(202).json(Map.of(
                "success", true,
                "planning_job", Map.of(
                    "id", job.getId().toString(),
                    "build_id", job.getBuildId().toString(),
                    "status", job.getStatus().name(),
                    "phase", job.getPhase(),
                    "sampled_area_count", job.getSampledAreaCount(),
                    "route_length", job.getRouteLength(),
                    "created_at", job.getCreatedAt().toString(),
                    "updated_at", job.getUpdatedAt().toString()
                )
            ));
        }));
    }

    // POST /api/builds/{id}/translate - Shift every task in a build by (dx, dy, dz)
    private void registerTranslateBuild() {
        app.post("/api/builds/{id}/translate", ctx -> handle(ctx, "translating build", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            TranslateBuildRequest request = ctx.bodyAsClass(TranslateBuildRequest.class);

            List<BuildTask> translatedTasks = buildService.translateBuild(buildId, request.dx, request.dy, request.dz);

            ctx.json(Map.of(
                "success", true,
                "build_id", buildId.toString(),
                "dx", request.dx,
                "dy", request.dy,
                "dz", request.dz,
                "task_count", translatedTasks.size(),
                "message", "Build translated successfully"
            ));

            LOGGER.info("Translated build {} by ({},{},{}) via API", buildId, request.dx, request.dy, request.dz);
        }));
    }

    // GET /api/builds/{id}/preview - Isometric PNG dry-run preview
    private void registerPreviewBuild() {
        app.get("/api/builds/{id}/preview", ctx -> handle(ctx, "rendering preview", () -> {
            UUID buildId = pathUuid(ctx, "id", "build ID");
            if (buildId == null) {
                return;
            }

            Optional<Build> buildOpt = buildService.getBuild(buildId);
            if (buildOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Build not found"));
                return;
            }
            Build build = buildOpt.get();

            int scale = IsoRenderer.DEFAULT_SCALE;
            int terrainMargin = 0;
            PreviewViewDirection viewDirection = PreviewViewDirection.SOUTH;
            String scaleParam = ctx.queryParam("iso_scale");
            if (scaleParam != null && !scaleParam.isBlank()) {
                try {
                    scale = Integer.parseInt(scaleParam);
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of("error", "iso_scale must be an integer"));
                    return;
                }
                if (scale < MIN_ISO_SCALE || scale > MAX_ISO_SCALE) {
                    ctx.status(400).json(Map.of("error", "iso_scale must be between " + MIN_ISO_SCALE + " and " + MAX_ISO_SCALE));
                    return;
                }
            }

            String terrainMarginParam = ctx.queryParam("terrain_margin");
            if (terrainMarginParam != null && !terrainMarginParam.isBlank()) {
                try {
                    terrainMargin = Integer.parseInt(terrainMarginParam);
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of("error", "terrain_margin must be an integer"));
                    return;
                }
                if (terrainMargin < 0 || terrainMargin > MAX_TERRAIN_MARGIN) {
                    ctx.status(400).json(Map.of("error", "terrain_margin must be between 0 and " + MAX_TERRAIN_MARGIN));
                    return;
                }
            }

            String viewDirectionParam = ctx.queryParam("view_direction");
            if (viewDirectionParam != null && !viewDirectionParam.isBlank()) {
                try {
                    viewDirection = PreviewViewDirection.fromQueryParam(viewDirectionParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "view_direction must be one of: south, west, north, east"));
                    return;
                }
            }

            List<BuildTask> tasks = buildService.getTasks(buildId);
            tasks.sort(Comparator.comparingInt(BuildTask::getTaskOrder));

            RegistryKey<World> worldKey = WorldResolver.resolveWorldKey(build.getWorld());
            ServerWorld serverWorld = worldKey != null ? server.getWorld(worldKey) : null;
            if (serverWorld == null) {
                ctx.status(400).json(Map.of("error", "World not loaded: " + build.getWorld()));
                return;
            }

            RecordingBlockSink sink = new RecordingBlockSink(serverWorld);
            int finalScale = scale;
            int finalTerrainMargin = terrainMargin;
            PreviewViewDirection finalViewDirection = viewDirection;
            CompletableFuture<Boolean> allOk = CompletableFuture.supplyAsync(() -> {
                boolean partial = false;
                for (BuildTask task : tasks) {
                    TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task, sink);
                    if (!result.success()) {
                        partial = true;
                        LOGGER.warn("Preview dry-run task {} failed: {}", task.getId(), result.errorMessage());
                    }
                }
                return !partial;
            }, server::execute);

            boolean ok;
            try {
                ok = allOk.get();
            } catch (Exception e) {
                LOGGER.error("Preview dry-run interrupted", e);
                ctx.status(500).json(Map.of("error", "Preview execution failed: " + e.getMessage()));
                return;
            }

            BlockGrid grid = BlockGrid.from(sink.placedBlocks(), serverWorld, finalTerrainMargin);
            if (grid.isEmpty()) {
                ctx.status(204);
                return;
            }

            byte[] png = IsoRenderer.renderPng(grid, finalScale, finalViewDirection);
            ctx.contentType("image/png");
            if (!ok) {
                ctx.header("X-Preview-Partial", "true");
            }
            ctx.result(png);

            LOGGER.info("Rendered preview for build {} ({} recorded blocks, scale {}, terrain margin {}, view {})",
                    buildId, sink.placedBlocks().size(), finalScale, finalTerrainMargin, finalViewDirection);
        }));
    }

    // GET /api/rail-plans/{jobId} - Poll rail planning job status
    private void registerGetRailPlan() {
        app.get("/api/rail-plans/{jobId}", ctx -> handle(ctx, "fetching rail planner job", () -> {
            UUID jobId = pathUuid(ctx, "jobId", "planning job ID");
            if (jobId == null) {
                return;
            }
            var jobOpt = railPlanningService.getJob(jobId);
            if (jobOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Planning job not found"));
                return;
            }

            var job = jobOpt.get();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", job.getId().toString());
            response.put("build_id", job.getBuildId().toString());
            response.put("status", job.getStatus().name());
            response.put("phase", job.getPhase());
            response.put("sampled_area_count", job.getSampledAreaCount());
            response.put("route_length", job.getRouteLength());
            response.put("created_at", job.getCreatedAt().toString());
            response.put("updated_at", job.getUpdatedAt().toString());
            if (job.getErrorMessage() != null) {
                response.put("error_message", job.getErrorMessage());
            }
            if (job.getResultData() != null) {
                response.put("result", job.getResultData());
            }
            ctx.json(Map.of("success", true, "planning_job", response));
        }));
    }

    /**
     * Checks if stair direction aligns with the longer horizontal dimension.
     * Only warns if slope > 1 (steep stairs) combined with direction mismatch.
     */
    private void checkStairDirection(BuildTask task, JsonNode taskData,
                                     List<Map<String, Object>> issues) {
        if (!taskData.has("staircase_direction") ||
            !taskData.has("start_x") || !taskData.has("end_x") ||
            !taskData.has("start_y") || !taskData.has("end_y") ||
            !taskData.has("start_z") || !taskData.has("end_z")) {
            return;
        }

        String direction = taskData.get("staircase_direction").asText().toUpperCase();
        int xSpan = Math.abs(taskData.get("end_x").asInt() - taskData.get("start_x").asInt()) + 1;
        int ySpan = Math.abs(taskData.get("end_y").asInt() - taskData.get("start_y").asInt()) + 1;
        int zSpan = Math.abs(taskData.get("end_z").asInt() - taskData.get("start_z").asInt()) + 1;

        boolean directionAlongX = direction.equals("EAST") || direction.equals("WEST");
        boolean directionAlongZ = direction.equals("NORTH") || direction.equals("SOUTH");

        // Calculate slope along the direction of travel
        // Only warn if slope > 1 (steep) AND direction doesn't match longer dimension
        if (directionAlongX && xSpan < zSpan) {
            double slope = (double) ySpan / xSpan;
            if (slope > 1) {
                issues.add(Map.of(
                    "severity", "warning",
                    "task_id", task.getId().toString(),
                    "task_order", task.getTaskOrder(),
                    "check", "stair_direction_mismatch",
                    "message", String.format("Staircase direction %s travels along X-axis but X span (%d) < Z span (%d), slope %.1f",
                        direction, xSpan, zSpan, slope)
                ));
            }
        } else if (directionAlongZ && zSpan < xSpan) {
            double slope = (double) ySpan / zSpan;
            if (slope > 1) {
                issues.add(Map.of(
                    "severity", "warning",
                    "task_id", task.getId().toString(),
                    "task_order", task.getTaskOrder(),
                    "check", "stair_direction_mismatch",
                    "message", String.format("Staircase direction %s travels along Z-axis but Z span (%d) < X span (%d), slope %.1f",
                        direction, zSpan, xSpan, slope)
                ));
            }
        }
    }

    /**
     * Checks if a BLOCK_FILL task would overwrite earlier structure tasks.
     */
    private void checkFillOverwrite(BuildTask fillTask, JsonNode fillData,
                                    List<BuildTask> earlierTasks, List<Map<String, Object>> issues) {
        BoundingBox fillBox = BoundingBox.fromFillBoxRequest(fillData);

        if (fillBox == null) {
            return;
        }

        for (BuildTask earlierTask : earlierTasks) {
            // Skip other fills - overwriting fills is usually intentional
            if (earlierTask.getTaskType() == TaskType.BLOCK_FILL) {
                continue;
            }

            BoundingBox earlierBox =
                BoundingBox.fromTaskData(earlierTask.getTaskType(), earlierTask.getTaskData());

            if (earlierBox != null && fillBox.intersects(earlierBox)) {
                issues.add(Map.of(
                    "severity", "warning",
                    "task_id", fillTask.getId().toString(),
                    "task_order", fillTask.getTaskOrder(),
                    "overlaps_task_id", earlierTask.getId().toString(),
                    "overlaps_task_order", earlierTask.getTaskOrder(),
                    "check", "fill_overwrites_structure",
                    "message", String.format("BLOCK_FILL at order %d would overwrite %s at order %d",
                        fillTask.getTaskOrder(), earlierTask.getTaskType(), earlierTask.getTaskOrder())
                ));
            }
        }
    }

    /**
     * Checks whether a task's bounding box overlaps any task belonging to a different build.
     */
    private void checkCrossBuildOverlap(Build build, BuildTask task, UUID currentBuildId,
                                        List<Map<String, Object>> issues) throws SQLException {
        if (task.getCoordinates() == null) {
            return;
        }

        BoundingBox box = task.getCoordinates();
        LocationQueryService.LocationQueryRequest request = new LocationQueryService.LocationQueryRequest(
            build.getWorld(), box.getMinX(), box.getMinY(), box.getMinZ(),
            box.getMaxX(), box.getMaxY(), box.getMaxZ(), true);

        LocationQueryService.LocationQueryResult result = locationQueryService.queryBuildsByLocation(request);

        for (LocationQueryService.BuildLocationResult buildResult : result.builds) {
            if (buildResult.build.getId().equals(currentBuildId)) {
                continue; // Same-build overlaps are covered by checkFillOverwrite/rail inspection
            }

            String severity = buildResult.build.getStatus() == BuildStatus.COMPLETED ? "error" : "warning";

            for (BuildTask otherTask : buildResult.intersecting_tasks) {
                issues.add(Map.of(
                    "severity", severity,
                    "task_id", task.getId().toString(),
                    "task_order", task.getTaskOrder(),
                    "check", "cross_build_overlap",
                    "overlaps_build_id", buildResult.build.getId().toString(),
                    "overlaps_build_name", Objects.toString(buildResult.build.getName(), ""),
                    "overlaps_task_id", otherTask.getId().toString(),
                    "overlaps_task_order", otherTask.getTaskOrder(),
                    "message", String.format("Task %d (%s) overlaps with task %d in build '%s' (%s, status %s)",
                        task.getTaskOrder(), task.getTaskType(), otherTask.getTaskOrder(),
                        buildResult.build.getName(), buildResult.build.getId(), buildResult.build.getStatus())
                ));
            }
        }
    }

    /**
     * Request object for adding a task with optional position.
     */
    public static class AddTaskWithOrderRequest {
        public TaskType task_type;
        public JsonNode task_data;
        public String description;
        public Integer task_order; // Optional: if provided, insert at this position

        public AddTaskWithOrderRequest() {}
    }

    /**
     * Request object for translating a build by (dx, dy, dz).
     */
    public static class TranslateBuildRequest {
        public int dx;
        public int dy;
        public int dz;

        public TranslateBuildRequest() {}
    }

    /**
     * Request object for patching a task.
     */
    public static class PatchTaskRequest {
        public JsonNode task_data; // Partial update, merged with existing
        public String description; // Full replacement if provided

        public PatchTaskRequest() {}
    }
}
