package com.example.buildtask.service;

import com.example.buildtask.model.Build;
import com.example.buildtask.model.BuildStatus;
import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.TaskStatus;
import com.example.buildtask.repository.BuildRepository;
import com.example.buildtask.repository.TaskRepository;
import com.example.endpoints.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core business logic for build management.
 * Handles build creation, task queue management, and build execution orchestration.
 * Requirements: 1.1, 1.2, 2.1, 2.5, 3.1, 3.4
 */
public class BuildService {
    private static final Logger logger = LoggerFactory.getLogger(BuildService.class);
    
    private final BuildRepository buildRepository;
    private final TaskRepository taskRepository;
    private final TaskExecutor taskExecutor;
    private final ExecutorService executorService;

    public BuildService(BuildRepository buildRepository, TaskRepository taskRepository, TaskExecutor taskExecutor) {
        this.buildRepository = buildRepository;
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Creates a new build with unique ID generation.
     * Requirements: 1.1, 1.2
     */
    public Build createBuild(CreateBuildRequest request) throws SQLException {
        if (request == null) {
            throw new IllegalArgumentException("Build request cannot be null");
        }

        // Create build with unique ID (UUID.randomUUID() in constructor)
        Build build = new Build(request.name, request.description, request.world);
        
        logger.info("Creating new build: {} in world {}", build.getName(), build.getWorld());
        
        // Store in database
        Build savedBuild = buildRepository.create(build);
        
        logger.info("Created build with ID: {}", savedBuild.getId());
        return savedBuild;
    }

    /**
     * Retrieves build information by ID.
     * Requirements: 1.3
     */
    public Optional<Build> getBuild(UUID buildId) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        
        return buildRepository.findById(buildId);
    }

    /**
     * Adds a task to the build queue.
     * Requirements: 2.1, 2.5
     */
    public BuildTask addTask(UUID buildId, AddTaskRequest request) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Task request cannot be null");
        }

        // Verify build exists
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }

        Build build = buildOpt.get();
        if (build.getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot add tasks to completed build: " + buildId);
        }

        // Get next task order
        int taskOrder = taskRepository.getNextTaskOrder(buildId);
        
        // Create task
        BuildTask task = new BuildTask(buildId, taskOrder, request.task_type, request.task_data, request.description);
        
        logger.info("Adding task {} of type {} to build {}", task.getId(), task.getTaskType(), buildId);
        
        // Add to queue
        BuildTask savedTask = taskRepository.addToQueue(buildId, task);
        
        logger.info("Added task {} to build {} at position {}", savedTask.getId(), buildId, taskOrder);
        return savedTask;
    }

    /**
     * Retrieves the task queue for a build.
     * Requirements: 2.3
     */
    public List<BuildTask> getTasks(UUID buildId) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        
        return taskRepository.findByBuildIdOrdered(buildId);
    }

    /**
     * Updates the task queue for a build (reorders tasks).
     * Requirements: 2.5
     */
    public void updateTaskQueue(UUID buildId, List<BuildTask> tasks) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (tasks == null) {
            throw new IllegalArgumentException("Tasks list cannot be null");
        }

        // Verify build exists
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }

        Build build = buildOpt.get();
        if (build.getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot modify tasks for completed build: " + buildId);
        }

        logger.info("Updating task queue for build {} with {} tasks", buildId, tasks.size());
        
        // Update task orders and save
        for (int i = 0; i < tasks.size(); i++) {
            BuildTask task = tasks.get(i);
            task.setBuildId(buildId);
            task.setTaskOrder(i);
        }
        
        taskRepository.updateTaskQueue(buildId, tasks);
        
        logger.info("Updated task queue for build {}", buildId);
    }

    /**
     * Executes all tasks in a build.
     * Requirements: 3.1, 3.4
     */
    public CompletableFuture<BuildExecutionResult> executeBuild(UUID buildId) {
        if (buildId == null) {
            return CompletableFuture.completedFuture(
                new BuildExecutionResult(buildId, false, 0, 0, List.of(), "Build ID cannot be null"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Starting execution of build {}", buildId);
                
                // Get build and verify it exists
                Optional<Build> buildOpt = buildRepository.findById(buildId);
                if (buildOpt.isEmpty()) {
                    return new BuildExecutionResult(buildId, false, 0, 0, List.of(), "Build not found: " + buildId);
                }

                Build build = buildOpt.get();
                if (build.getStatus() == BuildStatus.COMPLETED) {
                    return new BuildExecutionResult(buildId, false, 0, 0, List.of(), "Build already completed");
                }

                // Update build status to in progress
                build.setStatus(BuildStatus.IN_PROGRESS);
                buildRepository.update(build);

                // Get tasks in order
                List<BuildTask> tasks = taskRepository.findByBuildIdOrdered(buildId);
                if (tasks.isEmpty()) {
                    build.setStatus(BuildStatus.COMPLETED);
                    buildRepository.update(build);
                    return new BuildExecutionResult(buildId, true, 0, 0, List.of(), "No tasks to execute");
                }

                logger.info("Executing {} tasks for build {}", tasks.size(), buildId);

                // Execute tasks in order
                int tasksExecuted = 0;
                int tasksFailed = 0;
                
                for (BuildTask task : tasks) {
                    if (task.getStatus() == TaskStatus.COMPLETED) {
                        tasksExecuted++;
                        continue; // Skip already completed tasks
                    }
                    
                    try {
                        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task);
                        
                        // Update task in database
                        taskRepository.update(task);
                        
                        if (result.success()) {
                            tasksExecuted++;
                            logger.info("Task {} completed successfully", task.getId());
                        } else {
                            tasksFailed++;
                            logger.error("Task {} failed: {}", task.getId(), result.errorMessage());
                        }
                    } catch (Exception e) {
                        tasksFailed++;
                        task.markFailed("Exception during execution: " + e.getMessage());
                        taskRepository.update(task);
                        logger.error("Task {} failed with exception", task.getId(), e);
                    }
                }

                // Update build status based on results
                boolean allTasksCompleted = tasksFailed == 0;
                build.setStatus(allTasksCompleted ? BuildStatus.COMPLETED : BuildStatus.FAILED);
                buildRepository.update(build);

                logger.info("Build {} execution completed. Tasks executed: {}, failed: {}", 
                    buildId, tasksExecuted, tasksFailed);

                return new BuildExecutionResult(buildId, allTasksCompleted, tasksExecuted, tasksFailed, 
                    List.of(), allTasksCompleted ? "Build completed successfully" : "Some tasks failed");

            } catch (SQLException e) {
                logger.error("Database error during build execution", e);
                return new BuildExecutionResult(buildId, false, 0, 0, List.of(), 
                    "Database error: " + e.getMessage());
            } catch (Exception e) {
                logger.error("Unexpected error during build execution", e);
                return new BuildExecutionResult(buildId, false, 0, 0, List.of(), 
                    "Unexpected error: " + e.getMessage());
            }
        }, executorService);
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * Deletes a task from a build and reorders remaining tasks.
     */
    public void deleteTask(UUID buildId, UUID taskId) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        // Verify build exists and is not completed
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        if (buildOpt.get().getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot delete tasks from completed build: " + buildId);
        }

        // Verify task exists and belongs to this build
        Optional<BuildTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!taskOpt.get().getBuildId().equals(buildId)) {
            throw new IllegalArgumentException("Task does not belong to build: " + buildId);
        }

        logger.info("Deleting task {} from build {}", taskId, buildId);

        // Delete the task
        taskRepository.deleteById(taskId);

        // Reorder remaining tasks to close the gap
        List<BuildTask> remainingTasks = taskRepository.findByBuildIdOrdered(buildId);
        for (int i = 0; i < remainingTasks.size(); i++) {
            BuildTask task = remainingTasks.get(i);
            if (task.getTaskOrder() != i) {
                task.setTaskOrder(i);
            }
        }
        taskRepository.updateTaskQueue(buildId, remainingTasks);

        logger.info("Deleted task {} and reordered {} remaining tasks", taskId, remainingTasks.size());
    }

    /**
     * Inserts a task at a specific position in the queue.
     * Shifts existing tasks at and after that position.
     */
    public BuildTask insertTaskAt(UUID buildId, AddTaskRequest request, int position) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Task request cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Position cannot be negative");
        }

        // Verify build exists and is not completed
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        if (buildOpt.get().getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot add tasks to completed build: " + buildId);
        }

        // Get current tasks
        List<BuildTask> tasks = taskRepository.findByBuildIdOrdered(buildId);

        // Clamp position to valid range
        int insertPosition = Math.min(position, tasks.size());

        // Create the new task
        BuildTask newTask = new BuildTask(buildId, insertPosition, request.task_type, request.task_data, request.description);

        logger.info("Inserting task {} at position {} in build {}", newTask.getId(), insertPosition, buildId);

        // Save the new task
        BuildTask savedTask = taskRepository.create(newTask);

        // Shift existing tasks at and after insert position
        for (BuildTask task : tasks) {
            if (task.getTaskOrder() >= insertPosition) {
                task.setTaskOrder(task.getTaskOrder() + 1);
            }
        }
        taskRepository.updateTaskQueue(buildId, tasks);

        logger.info("Inserted task {} at position {}", savedTask.getId(), insertPosition);
        return savedTask;
    }

    /**
     * Updates a task's task_data (merge) and/or description.
     */
    public BuildTask updateTask(UUID buildId, UUID taskId, com.fasterxml.jackson.databind.JsonNode partialTaskData,
                                String description) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        // Verify build exists and is not completed
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        if (buildOpt.get().getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot update tasks in completed build: " + buildId);
        }

        // Verify task exists and belongs to this build
        Optional<BuildTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        BuildTask task = taskOpt.get();
        if (!task.getBuildId().equals(buildId)) {
            throw new IllegalArgumentException("Task does not belong to build: " + buildId);
        }

        logger.info("Updating task {} in build {}", taskId, buildId);

        // Merge partial task_data with existing
        if (partialTaskData != null && !partialTaskData.isNull()) {
            com.fasterxml.jackson.databind.JsonNode existingData = task.getTaskData();
            com.fasterxml.jackson.databind.node.ObjectNode merged;

            if (existingData != null && existingData.isObject()) {
                merged = existingData.deepCopy();
            } else {
                merged = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }

            // Merge fields from partialTaskData into existing
            partialTaskData.fields().forEachRemaining(entry ->
                merged.set(entry.getKey(), entry.getValue()));

            task.setTaskData(merged);
        }

        // Update description if provided
        if (description != null) {
            task.setDescription(description);
        }

        BuildTask updatedTask = taskRepository.update(task);
        logger.info("Updated task {}", taskId);
        return updatedTask;
    }

    /**
     * Retrieves a single task by ID, verifying it belongs to the specified build.
     */
    public Optional<BuildTask> getTask(UUID buildId, UUID taskId) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        Optional<BuildTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isPresent() && !taskOpt.get().getBuildId().equals(buildId)) {
            return Optional.empty(); // Task doesn't belong to this build
        }
        return taskOpt;
    }

    /**
     * Request object for creating a new build.
     */
    public static class CreateBuildRequest {
        public String name;
        public String description;
        public String world = "minecraft:overworld";

        public CreateBuildRequest() {}

        public CreateBuildRequest(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public CreateBuildRequest(String name, String description, String world) {
            this.name = name;
            this.description = description;
            this.world = world;
        }
    }

    /**
     * Request object for adding a task to a build.
     */
    public static class AddTaskRequest {
        public com.example.buildtask.model.TaskType task_type;
        public com.fasterxml.jackson.databind.JsonNode task_data;
        public String description = "";

        public AddTaskRequest() {}

        public AddTaskRequest(com.example.buildtask.model.TaskType task_type,
                             com.fasterxml.jackson.databind.JsonNode task_data, String description) {
            this.task_type = task_type;
            this.task_data = task_data;
            this.description = description;
        }
    }

    /**
     * Result of build execution.
     */
    public static class BuildExecutionResult {
        public final UUID buildId;
        public final boolean success;
        public final int tasksExecuted;
        public final int tasksFailed;
        public final List<TaskExecutor.TaskExecutionResult> taskResults;
        public final String errorMessage;

        public BuildExecutionResult(UUID buildId, boolean success, int tasksExecuted, int tasksFailed,
                                  List<TaskExecutor.TaskExecutionResult> taskResults, String errorMessage) {
            this.buildId = buildId;
            this.success = success;
            this.tasksExecuted = tasksExecuted;
            this.tasksFailed = tasksFailed;
            this.taskResults = taskResults;
            this.errorMessage = errorMessage;
        }
    }
}