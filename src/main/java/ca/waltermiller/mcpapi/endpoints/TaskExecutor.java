package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.arealock.AreaLockException;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.service.TaskDataValidator;
import ca.waltermiller.mcpapi.preview.BlockSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes build tasks using refactored endpoint cores.
 * Handles task execution, coordinate calculation, and status tracking.
 * Requirements: 3.1, 3.2, 3.3, 3.5, 4.3
 */
public class TaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskExecutor.class);
    
    private final BlocksEndpointCore blocksCore;
    private final PrefabEndpointCore prefabCore;
    private final AreaLockService locks;
    private final MinecraftServer server;
    private final ObjectMapper objectMapper;
    private final TaskDataValidator validator;

    public TaskExecutor(MinecraftServer server) {
        this(server, new AreaLockService());
    }

    public TaskExecutor(MinecraftServer server, AreaLockService locks) {
        this.locks = locks;
        this.server = server;
        this.blocksCore = new BlocksEndpointCore(server, LOGGER, locks);
        this.prefabCore = new PrefabEndpointCore(server, LOGGER, locks);
        this.objectMapper = new ObjectMapper();
        this.validator = new TaskDataValidator();
    }

    /**
     * Executes a single build task and updates its status.
     * Requirements: 3.1, 3.2, 3.3
     */
    public TaskExecutionResult executeTask(BuildTask task) {
        return executeTask(task, (String) null);
    }

    public TaskExecutionResult executeTask(BuildTask task, String lockId) {
        return executeTaskAsync(task, lockId).join();
    }

    public CompletableFuture<TaskExecutionResult> executeTaskAsync(BuildTask task, String lockId) {
        if (task == null) {
            return CompletableFuture.completedFuture(new TaskExecutionResult(false, "Task cannot be null", null));
        }

        LOGGER.info("Executing task {} of type {} for build {}", 
            task.getId(), task.getTaskType(), task.getBuildId());

        // Validate task data before execution
        TaskDataValidator.ValidationResult validationResult = validator.validateTaskData(task.getTaskType(), task.getTaskData());
        if (!validationResult.isValid()) {
            String errorMessage = "Task data validation failed: " + validationResult.getErrorMessage();
            task.markFailed(errorMessage);
            LOGGER.error("Task {} validation failed: {}", task.getId(), validationResult.getErrorMessage());
            return CompletableFuture.completedFuture(new TaskExecutionResult(false, errorMessage, null));
        }

        // Mark task as executing
        task.markExecuting();

        try {
            validateDimensions(task);
            // Execute based on task type
            CompletableFuture<TaskExecutionResult> future = switch (task.getTaskType()) {
                case BLOCK_SET -> executeAsync(task, BlockSetRequest.class, req -> blocksCore.setBlocks(req, lockId), TaskExecutor::blockSetMessage);
                case BLOCK_FILL -> executeAsync(task, FillBoxRequest.class, req -> blocksCore.fillBox(req, lockId), TaskExecutor::blockFillMessage);
                case PREFAB_DOOR -> executeAsync(task, DoorRequest.class, req -> prefabCore.placeDoor(req, lockId), TaskExecutor::doorMessage);
                case PREFAB_STAIRS -> executeAsync(task, StairRequest.class, req -> prefabCore.placeStairs(req, lockId), TaskExecutor::stairsMessage);
                case PREFAB_WINDOW -> executeAsync(task, WindowPaneRequest.class, req -> prefabCore.placeWindowPane(req, lockId), TaskExecutor::windowMessage);
                case PREFAB_TORCH -> executeAsync(task, TorchRequest.class, req -> prefabCore.placeTorch(req, lockId), TaskExecutor::torchMessage);
                case PREFAB_SIGN -> executeAsync(task, SignRequest.class, req -> prefabCore.placeSign(req, lockId), TaskExecutor::signMessage);
                case PREFAB_LADDER -> executeAsync(task, LadderRequest.class, req -> prefabCore.placeLadder(req, lockId), TaskExecutor::ladderMessage);
                case NBT_STRUCTURE -> CompletableFuture.completedFuture(
                    new TaskExecutionResult(false, "NBT_STRUCTURE tasks cannot be replayed — NBT data is not stored", null));
                case TERRAIN_SMOOTH -> CompletableFuture.completedFuture(
                    new TaskExecutionResult(false, "TERRAIN_SMOOTH tasks cannot be replayed — run smooth_terrain again", null));
                default -> CompletableFuture.completedFuture(
                    new TaskExecutionResult(false, "Unknown task type: " + task.getTaskType(), null));
            };

            return future.handle((result, failure) -> {
                if (failure != null) {
                    AreaLockException conflict = AreaLockException.find(failure);
                    if (conflict != null) {
                        String error = objectMapper.valueToTree(conflict.payload()).toString();
                        task.markFailed(error);
                        return new TaskExecutionResult(false, error, null, conflict.payload());
                    }
                    task.markFailed("Exception during task execution: " + failure.getMessage());
                    return new TaskExecutionResult(false, task.getErrorMessage(), null);
                }
                if (result.success()) task.markCompleted();
                else task.markFailed(result.errorMessage());
                return result;
            });
        } catch (Exception failure) {
            task.markFailed("Exception during task execution: " + failure.getMessage());
            return CompletableFuture.completedFuture(new TaskExecutionResult(false, task.getErrorMessage(), null));
        }
    }

    /** Pure validation shared by execution and preview admission. */
    public void validateDimensions(BuildTask task) {
        if (task.getTaskType() == ca.waltermiller.mcpapi.buildtask.model.TaskType.BLOCK_FILL) {
            FillBoxRequest request = objectMapper.convertValue(task.getTaskData(), FillBoxRequest.class);
            CheckedDimensions.fill(request.x1, request.y1, request.z1, request.x2, request.y2, request.z2);
        }
    }

    /**
     * Synchronous dry-run dispatch: runs a task against the given sink without mutating
     * task status. Caller is responsible for running this on the server thread.
     * Used by the preview endpoint; real execute path still goes through executeTask(task).
     */
    public TaskExecutionResult executeTask(BuildTask task, BlockSink sink) {
        if (task == null) {
            return new TaskExecutionResult(false, "Task cannot be null", null);
        }

        TaskDataValidator.ValidationResult validationResult = validator.validateTaskData(task.getTaskType(), task.getTaskData());
        if (!validationResult.isValid()) {
            return new TaskExecutionResult(false, "Task data validation failed: " + validationResult.getErrorMessage(), null);
        }

        try {
            return switch (task.getTaskType()) {
                case BLOCK_SET -> dispatch(task, BlockSetRequest.class,
                    req -> blocksCore.setBlocksInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::blockSetMessage);
                case BLOCK_FILL -> dispatch(task, FillBoxRequest.class,
                    req -> fillInto(sink, req), TaskExecutor::blockFillMessage);
                case PREFAB_DOOR -> dispatch(task, DoorRequest.class,
                    req -> prefabCore.placeDoorInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::doorMessage);
                case PREFAB_STAIRS -> dispatch(task, StairRequest.class,
                    req -> prefabCore.placeStairsInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::stairsMessage);
                case PREFAB_WINDOW -> dispatch(task, WindowPaneRequest.class,
                    req -> prefabCore.placeWindowPaneInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::windowMessage);
                case PREFAB_TORCH -> dispatch(task, TorchRequest.class,
                    req -> prefabCore.placeTorchInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::torchMessage);
                case PREFAB_SIGN -> dispatch(task, SignRequest.class,
                    req -> prefabCore.placeSignInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::signMessage);
                case PREFAB_LADDER -> dispatch(task, LadderRequest.class,
                    req -> prefabCore.placeLadderInto(sink, req, resolveWorldKey(req.world)), TaskExecutor::ladderMessage);
                case NBT_STRUCTURE ->
                    new TaskExecutionResult(false, "NBT_STRUCTURE tasks cannot be replayed — NBT data is not stored", null);
                case TERRAIN_SMOOTH ->
                    new TaskExecutionResult(false, "TERRAIN_SMOOTH tasks cannot be replayed — run smooth_terrain again", null);
                default -> new TaskExecutionResult(false, "Unknown task type: " + task.getTaskType(), null);
            };
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Exception during task execution: " + e.getMessage(), null);
        }
    }

    public void validateLockToken(String lockId) throws Exception {
        if (lockId == null) return;
        CompletableFuture<Void> future = new CompletableFuture<>();
        ca.waltermiller.mcpapi.operation.OperationHttp.dispatch(server::execute, () -> {
            try { locks.validateToken(lockId); future.complete(null); }
            catch (Exception e) { future.completeExceptionally(e); }
        }, future);
        try { future.get(); }
        catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AreaLockException conflict) throw conflict;
            throw e;
        }
    }

    private RegistryKey<World> resolveWorldKey(String worldString) {
        return WorldResolver.resolveWorldKey(worldString);
    }

    /**
     * Parses the task data as the given request type and runs the operation synchronously,
     * translating the operation result into a TaskExecutionResult.
     */
    private <Req, Res extends OperationResult> TaskExecutionResult dispatch(
            BuildTask task, Class<Req> requestClass,
            java.util.function.Function<Req, Res> operation,
            java.util.function.Function<Res, String> successMessage) {
        try {
            Req request = objectMapper.treeToValue(task.getTaskData(), requestClass);
            return toExecutionResult(operation.apply(request), successMessage);
        } catch (Exception e) {
            return parseFailure(task, e);
        }
    }

    /**
     * Async variant of dispatch: runs the core's own async wrapper (which resolves the
     * world, enforces size limits, and hops to the server thread).
     */
    private <Req, Res extends OperationResult> CompletableFuture<TaskExecutionResult> executeAsync(
            BuildTask task, Class<Req> requestClass,
            java.util.function.Function<Req, CompletableFuture<Res>> operation,
            java.util.function.Function<Res, String> successMessage) {
        try {
            Req request = objectMapper.treeToValue(task.getTaskData(), requestClass);
            return operation.apply(request).thenApply(result -> toExecutionResult(result, successMessage));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(parseFailure(task, e));
        }
    }

    private <Res extends OperationResult> TaskExecutionResult toExecutionResult(
            Res result, java.util.function.Function<Res, String> successMessage) {
        return result.success()
            ? new TaskExecutionResult(true, null, successMessage.apply(result))
            : new TaskExecutionResult(false, result.error(), null);
    }

    private TaskExecutionResult parseFailure(BuildTask task, Exception e) {
        return new TaskExecutionResult(false,
            "Failed to parse " + task.getTaskType() + " task data: " + e.getMessage(), null);
    }

    private FillResult fillInto(BlockSink sink, FillBoxRequest request) {
        int minX = Math.min(request.x1, request.x2);
        int maxX = Math.max(request.x1, request.x2);
        int minY = Math.min(request.y1, request.y2);
        int maxY = Math.max(request.y1, request.y2);
        int minZ = Math.min(request.z1, request.z2);
        int maxZ = Math.max(request.z1, request.z2);
        int totalBlocks = CheckedDimensions.fill(minX, minY, minZ, maxX, maxY, maxZ);
        return blocksCore.fillBoxInto(sink, request, resolveWorldKey(request.world),
            minX, minY, minZ, maxX, maxY, maxZ, totalBlocks);
    }

    private static String blockSetMessage(BlockSetResult r) {
        return "Set " + r.blocksSet() + " blocks, skipped " + r.blocksSkipped();
    }

    private static String blockFillMessage(FillResult r) {
        return "Filled " + r.blocksSet() + " blocks, failed " + r.blocksFailed();
    }

    private static String doorMessage(DoorResult r) {
        return "Placed " + r.doors_placed() + " doors";
    }

    private static String stairsMessage(StairResult r) {
        return "Placed " + r.blocks_placed() + " stair blocks";
    }

    private static String windowMessage(WindowPaneResult r) {
        return "Placed " + r.panes_placed() + " window panes";
    }

    private static String torchMessage(TorchResult r) {
        return "Placed torch at (" + r.position().get("x") + ", "
            + r.position().get("y") + ", " + r.position().get("z") + ")";
    }

    private static String signMessage(SignResult r) {
        return "Placed sign at (" + r.position().get("x") + ", "
            + r.position().get("y") + ", " + r.position().get("z") + ")";
    }

    private static String ladderMessage(LadderResult r) {
        return "Placed " + r.blocks_placed() + " ladder blocks facing " + r.facing();
    }

    /**
     * Result of task execution.
     */
    public record TaskExecutionResult(boolean success, String errorMessage, String details, Map<String, Object> lockError) {
        public TaskExecutionResult(boolean success, String errorMessage, String details) {
            this(success, errorMessage, details, null);
        }
    }
}
