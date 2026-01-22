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
                        "created_at", build.getCreatedAt().toString()
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
                buildJson.put("created_at", build.getCreatedAt().toString());
                if (build.getCompletedAt() != null) {
                    buildJson.put("completed_at", build.getCompletedAt().toString());
                }

                // Also fetch tasks for this build
                List<BuildTask> tasks = buildService.getTasks(buildId);
                List<Map<String, Object>> taskMaps = tasks.stream()
                    .map(task -> {
                        Map<String, Object> taskMap = new LinkedHashMap<>();
                        taskMap.put("id", task.getId().toString());
                        taskMap.put("build_id", task.getBuildId().toString());
                        taskMap.put("task_order", task.getTaskOrder());
                        taskMap.put("task_type", task.getTaskType().toString());
                        taskMap.put("status", task.getStatus().toString());
                        taskMap.put("task_data", task.getTaskData());
                        taskMap.put("description", task.getDescription() != null ? task.getDescription() : "");
                        if (task.getExecutedAt() != null) {
                            taskMap.put("executed_at", task.getExecutedAt().toString());
                        }
                        if (task.getErrorMessage() != null) {
                            taskMap.put("error_message", task.getErrorMessage());
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

        // POST /api/builds/{id}/tasks - Add task to build (optional task_order for insertion)
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

                AddTaskWithOrderRequest request = ctx.bodyAsClass(AddTaskWithOrderRequest.class);

                // Validate request
                if (request.task_type == null) {
                    ctx.status(400).json(Map.of("error", "Task type is required"));
                    return;
                }
                if (request.task_data == null) {
                    ctx.status(400).json(Map.of("error", "Task data is required"));
                    return;
                }

                BuildTask task;
                BuildService.AddTaskRequest addRequest = new BuildService.AddTaskRequest(
                    request.task_type, request.task_data, request.description != null ? request.description : "");

                if (request.task_order != null) {
                    // Insert at specific position
                    task = buildService.insertTaskAt(buildId, addRequest, request.task_order);
                } else {
                    // Append to end (default behavior)
                    task = buildService.addTask(buildId, addRequest);
                }

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
                        taskMap.put("build_id", task.getBuildId().toString());
                        taskMap.put("task_order", task.getTaskOrder());
                        taskMap.put("task_type", task.getTaskType().toString());
                        taskMap.put("status", task.getStatus().toString());
                        taskMap.put("task_data", task.getTaskData());
                        if (task.getExecutedAt() != null) {
                            taskMap.put("executed_at", task.getExecutedAt().toString());
                        }
                        if (task.getErrorMessage() != null) {
                            taskMap.put("error_message", task.getErrorMessage());
                        }
                        return taskMap;
                    })
                    .collect(Collectors.toList());
                
                ctx.json(Map.of(
                    "success", true,
                    "build_id", buildId.toString(),
                    "task_count", tasks.size(),
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
                    "build_id", buildId.toString(),
                    "task_count", tasks.size(),
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

        // DELETE /api/builds/{id}/tasks/{taskId} - Delete a specific task
        app.delete("/api/builds/{id}/tasks/{taskId}", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                String taskIdParam = ctx.pathParam("taskId");
                UUID buildId;
                UUID taskId;

                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
                    return;
                }

                try {
                    taskId = UUID.fromString(taskIdParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid task ID format"));
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

            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IllegalStateException e) {
                ctx.status(409).json(Map.of("error", e.getMessage()));
            } catch (SQLException e) {
                LOGGER.error("Database error deleting task", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error deleting task", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // PATCH /api/builds/{id}/tasks/{taskId} - Update task_data and/or description
        app.patch("/api/builds/{id}/tasks/{taskId}", ctx -> {
            try {
                String idParam = ctx.pathParam("id");
                String taskIdParam = ctx.pathParam("taskId");
                UUID buildId;
                UUID taskId;

                try {
                    buildId = UUID.fromString(idParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid build ID format"));
                    return;
                }

                try {
                    taskId = UUID.fromString(taskIdParam);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid task ID format"));
                    return;
                }

                PatchTaskRequest request = ctx.bodyAsClass(PatchTaskRequest.class);

                // At least one field must be provided
                if (request.task_data == null && request.description == null) {
                    ctx.status(400).json(Map.of("error", "At least one of task_data or description must be provided"));
                    return;
                }

                BuildTask updatedTask = buildService.updateTask(buildId, taskId, request.task_data, request.description);

                Map<String, Object> taskMap = new LinkedHashMap<>();
                taskMap.put("id", updatedTask.getId().toString());
                taskMap.put("build_id", updatedTask.getBuildId().toString());
                taskMap.put("task_order", updatedTask.getTaskOrder());
                taskMap.put("task_type", updatedTask.getTaskType().toString());
                taskMap.put("status", updatedTask.getStatus().toString());
                taskMap.put("task_data", updatedTask.getTaskData());
                taskMap.put("description", updatedTask.getDescription() != null ? updatedTask.getDescription() : "");

                ctx.json(Map.of(
                    "success", true,
                    "task", taskMap
                ));

                LOGGER.info("Updated task {} in build {} via API", taskId, buildId);

            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IllegalStateException e) {
                ctx.status(409).json(Map.of("error", e.getMessage()));
            } catch (SQLException e) {
                LOGGER.error("Database error updating task", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error updating task", e);
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
                    "build_id", buildId.toString(),
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
                if (request.min_x > request.max_x || request.min_y > request.max_y || request.min_z > request.max_z) {
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

                        List<Map<String, Object>> taskMaps = buildResult.intersecting_tasks.stream()
                            .map(task -> {
                                Map<String, Object> taskMap = new LinkedHashMap<>();
                                taskMap.put("id", task.getId().toString());
                                taskMap.put("task_order", task.getTaskOrder());
                                taskMap.put("task_type", task.getTaskType().toString());
                                taskMap.put("status", task.getStatus().toString());
                                if (task.getCoordinates() != null) {
                                    taskMap.put("coordinates", Map.of(
                                        "min_x", task.getCoordinates().getMinX(),
                                        "min_y", task.getCoordinates().getMinY(),
                                        "min_z", task.getCoordinates().getMinZ(),
                                        "max_x", task.getCoordinates().getMaxX(),
                                        "max_y", task.getCoordinates().getMaxY(),
                                        "max_z", task.getCoordinates().getMaxZ()
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
                    "query_area", Map.of(
                        "world", request.world,
                        "min_x", result.query_area.getMinX(),
                        "min_y", result.query_area.getMinY(),
                        "min_z", result.query_area.getMinZ(),
                        "max_x", result.query_area.getMaxX(),
                        "max_y", result.query_area.getMaxY(),
                        "max_z", result.query_area.getMaxZ()
                    ),
                    "build_count", result.getBuildCount(),
                    "total_task_count", result.getTotalTaskCount(),
                    "builds", buildResults
                ));
                
                LOGGER.info("Location query returned {} builds in world {} for area ({},{},{}) to ({},{},{})", 
                    result.getBuildCount(), request.world, request.min_x, request.min_y, request.min_z,
                    request.max_x, request.max_y, request.max_z);
                
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

        // POST /api/builds/{id}/audit - Audit build task queue for obvious problems
        app.post("/api/builds/{id}/audit", ctx -> {
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

                List<BuildTask> tasks = buildService.getTasks(buildId);
                List<Map<String, Object>> issues = new ArrayList<>();

                // Sort tasks by order to ensure correct sequencing
                tasks.sort(Comparator.comparingInt(BuildTask::getTaskOrder));

                for (int i = 0; i < tasks.size(); i++) {
                    BuildTask task = tasks.get(i);
                    com.fasterxml.jackson.databind.JsonNode taskData = task.getTaskData();

                    // Check 1: Stair direction alignment
                    if (task.getTaskType() == com.example.buildtask.model.TaskType.PREFAB_STAIRS) {
                        checkStairDirection(task, taskData, issues);
                    }

                    // Check 2: Fill overwriting earlier structures
                    if (task.getTaskType() == com.example.buildtask.model.TaskType.BLOCK_FILL) {
                        checkFillOverwrite(task, taskData, tasks.subList(0, i), issues);
                    }
                }

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

            } catch (SQLException e) {
                LOGGER.error("Database error during audit", e);
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            } catch (Exception e) {
                LOGGER.error("Unexpected error during audit", e);
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    /**
     * Checks if stair direction aligns with the longer horizontal dimension.
     * Only warns if slope > 1 (steep stairs) combined with direction mismatch.
     */
    private void checkStairDirection(BuildTask task, com.fasterxml.jackson.databind.JsonNode taskData,
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
    private void checkFillOverwrite(BuildTask fillTask, com.fasterxml.jackson.databind.JsonNode fillData,
                                    List<BuildTask> earlierTasks, List<Map<String, Object>> issues) {
        com.example.buildtask.model.BoundingBox fillBox =
            com.example.buildtask.model.BoundingBox.fromFillBoxRequest(fillData);

        if (fillBox == null) {
            return;
        }

        for (BuildTask earlierTask : earlierTasks) {
            // Skip other fills - overwriting fills is usually intentional
            if (earlierTask.getTaskType() == com.example.buildtask.model.TaskType.BLOCK_FILL) {
                continue;
            }

            com.example.buildtask.model.BoundingBox earlierBox =
                com.example.buildtask.model.BoundingBox.fromTaskData(
                    earlierTask.getTaskType(), earlierTask.getTaskData());

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
     * Request object for adding a task with optional position.
     */
    public static class AddTaskWithOrderRequest {
        public com.example.buildtask.model.TaskType task_type;
        public com.fasterxml.jackson.databind.JsonNode task_data;
        public String description;
        public Integer task_order; // Optional: if provided, insert at this position

        public AddTaskWithOrderRequest() {}
    }

    /**
     * Request object for patching a task.
     */
    public static class PatchTaskRequest {
        public com.fasterxml.jackson.databind.JsonNode task_data; // Partial update, merged with existing
        public String description; // Full replacement if provided

        public PatchTaskRequest() {}
    }
}