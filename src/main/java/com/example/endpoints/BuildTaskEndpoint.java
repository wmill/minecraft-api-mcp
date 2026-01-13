package com.example.endpoints;

import com.example.buildtask.model.Build;
import com.example.buildtask.model.BuildTask;
import com.example.buildtask.service.BuildService;
import com.example.buildtask.service.LocationQueryService;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HTTP endpoint handler for build task management.
 * Provides REST API for creating builds, managing task queues, and executing builds.
 * Requirements: 6.1, 6.4, 6.5
 */
public class BuildTaskEndpoint extends APIEndpoint {
    private final BuildService buildService;
    private final LocationQueryService locationQueryService;

    public BuildTaskEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger,
                           BuildService buildService, LocationQueryService locationQueryService) {
        super(app, server, logger);
        this.buildService = buildService;
        this.locationQueryService = locationQueryService;
        init();
    }

    private void init() {
        // POST /api/builds - Create new build
        app.post("/api/builds", ctx -> {
            try {
                BuildService.CreateBuildRequest request = ctx.bodyAsClass(BuildService.CreateBuildRequest.class);
                
                // Validate request
                if (request.name == null || request.name.trim().isEmpty()) {
                    ctx.status(400).json(Map.of("error", "Build name is required"));
                    return;
                }
                
                Build build = buildService.createBuild(request);
                
                ctx.status(201).json(Map.of(
                    "success", true,
                    "build", Map.of(
                        "id", build.getId().toString(),
                        "name", build.getName(),
                        "description", build.getDescription() != null ? build.getDescription() : "",
                        "world", build.getWorld(),
                        "status", build.getStatus().toString(),
                        "createdAt", build.getCreatedAt().toString()
                    )
                ));
                
                LOGGER.info("Created build {} via API", build.getId());
                
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (SQLException e) {
                LOGGER.error("Database error creating build", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error creating build", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // GET /api/builds/{id} - Get build details
        app.get("/api/builds/{id}", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                UUID buildId;
                
                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
                    return;
                }
                
                Optional<Build> buildOpt = buildService.getBuild(buildId);

                if (buildOpt.isEmpty()) {
                    ctx.status(404).json(Map.of("error", "Build not found"));
                    return;
                }

                Build build = buildOpt.get();

                Map<String, Object> buildJson = new LinkedHashMap<>();
                buildJson.put("id", build.getId().toString());
                buildJson.put("name", build.getName());
                buildJson.put("description", Objects.toString(build.getDescription(), ""));
                buildJson.put("world", build.getWorld());
                buildJson.put("status", build.getStatus().toString());
                buildJson.put("createdAt", build.getCreatedAt().toString());
                if (build.getCompletedAt() != null) {
                    buildJson.put("completedAt", build.getCompletedAt().toString());
                }

                // Also fetch tasks for this build
                List<BuildTask> tasks = buildService.getTasks(buildId);
                List<Map<String, Object>> taskMaps = tasks.stream()
                    .map(task -> {
                        Map<String, Object> taskMap = new LinkedHashMap<>();
                        taskMap.put("id", task.getId().toString());
                        taskMap.put("buildId", task.getBuildId().toString());
                        taskMap.put("taskOrder", task.getTaskOrder());
                        taskMap.put("taskType", task.getTaskType().toString());
                        taskMap.put("status", task.getStatus().toString());
                        taskMap.put("taskData", task.getTaskData());
                        if (task.getExecutedAt() != null) {
                            taskMap.put("executedAt", task.getExecutedAt().toString());
                        }
                        if (task.getErrorMessage() != null) {
                            taskMap.put("errorMessage", task.getErrorMessage());
                        }
                        return taskMap;
                    })
                    .collect(Collectors.toList());

                ctx.json(Map.of(
                    "success", true,
                    "build", buildJson,
                    "tasks", taskMaps
                ));
                
            } catch (SQLException e) {
                LOGGER.error("Database error retrieving build", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error retrieving build", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // POST /api/builds/{id}/tasks - Add task to build
        app.post("/api/builds/{id}/tasks", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                UUID buildId;
                
                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
                    return;
                }
                
                BuildService.AddTaskRequest request = ctx.bodyAsClass(BuildService.AddTaskRequest.class);
                
                // Validate request
                if (request.taskType == null) {
                    ctx.status(400).json(Map.of("error", "Task type is required"));
                    return;
                }
                if (request.taskData == null) {
                    ctx.status(400).json(Map.of("error", "Task data is required"));
                    return;
                }
                
                BuildTask task = buildService.addTask(buildId, request);
                
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
                
                LOGGER.info("Added task {} to build {} via API", task.getId(), buildId);
                
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IllegalStateException e) {
                ctx.status(409).json(Map.of("error", e.getMessage()));
            } catch (SQLException e) {
                LOGGER.error("Database error adding task", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error adding task", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // GET /api/builds/{id}/tasks - Get build task queue
        app.get("/api/builds/{id}/tasks", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                UUID buildId;
                
                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
                    return;
                }
                
                List<BuildTask> tasks = buildService.getTasks(buildId);
                
                List<Map<String, Object>> taskMaps = tasks.stream()
                    .map(task -> {
                        Map<String, Object> taskMap = new LinkedHashMap<>();
                        taskMap.put("id", task.getId().toString());
                        taskMap.put("buildId", task.getBuildId().toString());
                        taskMap.put("taskOrder", task.getTaskOrder());
                        taskMap.put("taskType", task.getTaskType().toString());
                        taskMap.put("status", task.getStatus().toString());
                        taskMap.put("taskData", task.getTaskData());
                        if (task.getExecutedAt() != null) {
                            taskMap.put("executedAt", task.getExecutedAt().toString());
                        }
                        if (task.getErrorMessage() != null) {
                            taskMap.put("errorMessage", task.getErrorMessage());
                        }
                        return taskMap;
                    })
                    .collect(Collectors.toList());
                
                ctx.json(Map.of(
                    "success", true,
                    "buildId", buildId.toString(),
                    "taskCount", tasks.size(),
                    "tasks", taskMaps
                ));
                
            } catch (SQLException e) {
                LOGGER.error("Database error retrieving tasks", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error retrieving tasks", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // PUT /api/builds/{id}/tasks - Update task queue (reorder tasks)
        app.put("/api/builds/{id}/tasks", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                UUID buildId;
                
                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
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
                
                // Convert task maps to BuildTask objects
                List<BuildTask> tasks = taskMaps.stream()
                    .map(taskMap -> {
                        try {
                            UUID taskId = UUID.fromString((String) taskMap.get("id"));
                            // Get existing task to preserve all data
                            List<BuildTask> existingTasks = buildService.getTasks(buildId);
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
                    "buildId", buildId.toString(),
                    "taskCount", tasks.size(),
                    "message", "Task queue updated successfully"
                ));
                
                LOGGER.info("Updated task queue for build {} via API", buildId);
                
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IllegalStateException e) {
                ctx.status(409).json(Map.of("error", e.getMessage()));
            } catch (SQLException e) {
                LOGGER.error("Database error updating task queue", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error updating task queue", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // POST /api/builds/{id}/execute - Execute build
        app.post("/api/builds/{id}/execute", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                UUID buildId;
                
                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
                    return;
                }
                
                // Execute build asynchronously
                buildService.executeBuild(buildId)
                    .thenAccept(result -> {
                        // Note: In a real implementation, you might want to use WebSockets or 
                        // Server-Sent Events for real-time updates. For now, we return immediately.
                    })
                    .exceptionally(throwable -> {
                        LOGGER.error("Error during build execution", throwable);
                        return null;
                    });
                
                // Return immediate response that execution has started
                ctx.status(202).json(Map.of(
                    "success", true,
                    "buildId", buildId.toString(),
                    "message", "Build execution started",
                    "status", "accepted"
                ));
                
                LOGGER.info("Started execution of build {} via API", buildId);
                
            } catch (Exception e) {
                LOGGER.error("Unexpected error starting build execution", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // POST /api/builds/query-location - Query builds by location
        app.post("/api/builds/query-location", ctx -> {
            try {
                LocationQueryService.LocationQueryRequest request = 
                    ctx.bodyAsClass(LocationQueryService.LocationQueryRequest.class);
                
                // Validate request
                if (request.world == null || request.world.trim().isEmpty()) {
                    request.world = "minecraft:overworld";
                }
                
                // Validate coordinates
                if (request.minX > request.maxX || request.minY > request.maxY || request.minZ > request.maxZ) {
                    ctx.status(400).json(Map.of("error", "Invalid coordinate range: min values must be <= max values"));
                    return;
                }
                
                LocationQueryService.LocationQueryResult result = locationQueryService.queryBuildsByLocation(request);
                
                // Convert result to JSON-friendly format
                List<Map<String, Object>> buildResults = result.builds.stream()
                    .map(buildResult -> {
                        Map<String, Object> buildMap = new LinkedHashMap<>();
                        buildMap.put("id", buildResult.build.getId().toString());
                        buildMap.put("name", buildResult.build.getName());
                        buildMap.put("description", buildResult.build.getDescription() != null ? buildResult.build.getDescription() : "");
                        buildMap.put("world", buildResult.build.getWorld());
                        buildMap.put("status", buildResult.build.getStatus().toString());
                        buildMap.put("createdAt", buildResult.build.getCreatedAt().toString());
                        if (buildResult.build.getCompletedAt() != null) {
                            buildMap.put("completedAt", buildResult.build.getCompletedAt().toString());
                        }

                        List<Map<String, Object>> taskMaps = buildResult.intersectingTasks.stream()
                            .map(task -> {
                                Map<String, Object> taskMap = new LinkedHashMap<>();
                                taskMap.put("id", task.getId().toString());
                                taskMap.put("taskOrder", task.getTaskOrder());
                                taskMap.put("taskType", task.getTaskType().toString());
                                taskMap.put("status", task.getStatus().toString());
                                if (task.getCoordinates() != null) {
                                    taskMap.put("coordinates", Map.of(
                                        "minX", task.getCoordinates().getMinX(),
                                        "minY", task.getCoordinates().getMinY(),
                                        "minZ", task.getCoordinates().getMinZ(),
                                        "maxX", task.getCoordinates().getMaxX(),
                                        "maxY", task.getCoordinates().getMaxY(),
                                        "maxZ", task.getCoordinates().getMaxZ()
                                    ));
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
                
                ctx.json(Map.of(
                    "success", true,
                    "queryArea", Map.of(
                        "world", request.world,
                        "minX", result.queryArea.getMinX(),
                        "minY", result.queryArea.getMinY(),
                        "minZ", result.queryArea.getMinZ(),
                        "maxX", result.queryArea.getMaxX(),
                        "maxY", result.queryArea.getMaxY(),
                        "maxZ", result.queryArea.getMaxZ()
                    ),
                    "buildCount", result.getBuildCount(),
                    "totalTaskCount", result.getTotalTaskCount(),
                    "builds", buildResults
                ));
                
                LOGGER.info("Location query returned {} builds in world {} for area ({},{},{}) to ({},{},{})", 
                    result.getBuildCount(), request.world, request.minX, request.minY, request.minZ,
                    request.maxX, request.maxY, request.maxZ);
                
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (SQLException e) {
                LOGGER.error("Database error during location query", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error during location query", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }
}