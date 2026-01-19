package com.example.endpoints;

import com.example.buildtask.model.BuildTask;
import com.example.buildtask.service.TaskDataValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes build tasks using refactored endpoint cores.
 * Handles task execution, coordinate calculation, and status tracking.
 * Requirements: 3.1, 3.2, 3.3, 3.5, 4.3
 */
public class TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(TaskExecutor.class);
    private static final int EXECUTION_TIMEOUT_SECONDS = 30;
    
    private final BlocksEndpointCore blocksCore;
    private final PrefabEndpointCore prefabCore;
    private final MinecraftServer server;
    private final ObjectMapper objectMapper;
    private final TaskDataValidator validator;

    public TaskExecutor(MinecraftServer server) {
        this.server = server;
        this.blocksCore = new BlocksEndpointCore(server, logger);
        this.prefabCore = new PrefabEndpointCore(server, logger);
        this.objectMapper = new ObjectMapper();
        this.validator = new TaskDataValidator();
    }

    /**
     * Executes a single build task and updates its status.
     * Requirements: 3.1, 3.2, 3.3
     */
    public TaskExecutionResult executeTask(BuildTask task) {
        if (task == null) {
            return new TaskExecutionResult(false, "Task cannot be null", null);
        }

        logger.info("Executing task {} of type {} for build {}", 
            task.getId(), task.getTaskType(), task.getBuildId());

        // Validate task data before execution
        TaskDataValidator.ValidationResult validationResult = validator.validateTaskData(task.getTaskType(), task.getTaskData());
        if (!validationResult.isValid()) {
            String errorMessage = "Task data validation failed: " + validationResult.getErrorMessage();
            task.markFailed(errorMessage);
            logger.error("Task {} validation failed: {}", task.getId(), validationResult.getErrorMessage());
            return new TaskExecutionResult(false, errorMessage, null);
        }

        // Mark task as executing
        task.markExecuting();

        try {
            // Execute based on task type
            CompletableFuture<TaskExecutionResult> future = switch (task.getTaskType()) {
                case BLOCK_SET -> executeBlockSetTask(task);
                case BLOCK_FILL -> executeBlockFillTask(task);
                case PREFAB_DOOR -> executePrefabDoorTask(task);
                case PREFAB_STAIRS -> executePrefabStairsTask(task);
                case PREFAB_WINDOW -> executePrefabWindowTask(task);
                case PREFAB_TORCH -> executePrefabTorchTask(task);
                case PREFAB_SIGN -> executePrefabSignTask(task);
                case PREFAB_LADDER -> executePrefabLadderTask(task);
                default -> CompletableFuture.completedFuture(
                    new TaskExecutionResult(false, "Unknown task type: " + task.getTaskType(), null));
            };

            // Wait for completion with timeout
            TaskExecutionResult result = future.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Update task status based on result
            if (result.success()) {
                task.markCompleted();
                logger.info("Task {} completed successfully", task.getId());
            } else {
                task.markFailed(result.errorMessage());
                logger.error("Task {} failed: {}", task.getId(), result.errorMessage());
            }

            return result;

        } catch (Exception e) {
            String errorMessage = "Exception during task execution: " + e.getMessage();
            task.markFailed(errorMessage);
            logger.error("Task {} failed with exception", task.getId(), e);
            return new TaskExecutionResult(false, errorMessage, null);
        }
    }

    /**
     * Executes a BLOCK_SET task using BlocksEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executeBlockSetTask(BuildTask task) {
        try {
            BlockSetRequest request = objectMapper.treeToValue(task.getTaskData(), BlockSetRequest.class);
            
            return blocksCore.setBlocks(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Set " + result.blocksSet() + " blocks, skipped " + result.blocksSkipped());
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse BLOCK_SET task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a BLOCK_FILL task using BlocksEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executeBlockFillTask(BuildTask task) {
        try {
            FillBoxRequest request = objectMapper.treeToValue(task.getTaskData(), FillBoxRequest.class);
            
            return blocksCore.fillBox(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Filled " + result.blocksSet() + " blocks, failed " + result.blocksFailed());
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse BLOCK_FILL task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a PREFAB_DOOR task using PrefabEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executePrefabDoorTask(BuildTask task) {
        try {
            DoorRequest request = objectMapper.treeToValue(task.getTaskData(), DoorRequest.class);
            
            return prefabCore.placeDoor(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Placed " + result.doors_placed() + " doors");
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse PREFAB_DOOR task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a PREFAB_STAIRS task using PrefabEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executePrefabStairsTask(BuildTask task) {
        try {
            StairRequest request = objectMapper.treeToValue(task.getTaskData(), StairRequest.class);
            
            return prefabCore.placeStairs(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Placed " + result.blocks_placed() + " stair blocks");
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse PREFAB_STAIRS task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a PREFAB_WINDOW task using PrefabEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executePrefabWindowTask(BuildTask task) {
        try {
            WindowPaneRequest request = objectMapper.treeToValue(task.getTaskData(), WindowPaneRequest.class);
            
            return prefabCore.placeWindowPane(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Placed " + result.panes_placed() + " window panes");
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse PREFAB_WINDOW task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a PREFAB_TORCH task using PrefabEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executePrefabTorchTask(BuildTask task) {
        try {
            TorchRequest request = objectMapper.treeToValue(task.getTaskData(), TorchRequest.class);
            
            return prefabCore.placeTorch(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Placed torch at (" + result.position().get("x") + ", " + 
                            result.position().get("y") + ", " + result.position().get("z") + ")");
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse PREFAB_TORCH task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a PREFAB_SIGN task using PrefabEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executePrefabSignTask(BuildTask task) {
        try {
            SignRequest request = objectMapper.treeToValue(task.getTaskData(), SignRequest.class);
            
            return prefabCore.placeSign(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Placed sign at (" + result.position().get("x") + ", " + 
                            result.position().get("y") + ", " + result.position().get("z") + ")");
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse PREFAB_SIGN task data: " + e.getMessage(), null));
        }
    }

    /**
     * Executes a PREFAB_LADDER task using PrefabEndpointCore.
     */
    private CompletableFuture<TaskExecutionResult> executePrefabLadderTask(BuildTask task) {
        try {
            LadderRequest request = objectMapper.treeToValue(task.getTaskData(), LadderRequest.class);
            
            return prefabCore.placeLadder(request)
                .thenApply(result -> {
                    if (result.success()) {
                        return new TaskExecutionResult(true, null, 
                            "Placed " + result.blocks_placed() + " ladder blocks facing " + result.facing());
                    } else {
                        return new TaskExecutionResult(false, result.error(), null);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new TaskExecutionResult(false, "Failed to parse PREFAB_LADDER task data: " + e.getMessage(), null));
        }
    }

    /**
     * Result of task execution.
     */
    public record TaskExecutionResult(boolean success, String errorMessage, String details) {}
}