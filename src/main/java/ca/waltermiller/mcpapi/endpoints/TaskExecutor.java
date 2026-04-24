package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.service.TaskDataValidator;
import ca.waltermiller.mcpapi.preview.BlockSink;
import ca.waltermiller.mcpapi.preview.WorldBlockSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.block.RailBlock;
import net.minecraft.block.enums.RailShape;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
                case RAIL_SURFACE_SEGMENT -> executeRailSegmentTask(task, "surface");
                case RAIL_BRIDGE_SEGMENT -> executeRailSegmentTask(task, "bridge");
                case RAIL_TUNNEL_SEGMENT -> executeRailSegmentTask(task, "tunnel");
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
                case BLOCK_SET -> dispatchBlockSetInto(task, sink);
                case BLOCK_FILL -> dispatchBlockFillInto(task, sink);
                case PREFAB_DOOR -> dispatchPrefabDoorInto(task, sink);
                case PREFAB_STAIRS -> dispatchPrefabStairsInto(task, sink);
                case PREFAB_WINDOW -> dispatchPrefabWindowInto(task, sink);
                case PREFAB_TORCH -> dispatchPrefabTorchInto(task, sink);
                case PREFAB_SIGN -> dispatchPrefabSignInto(task, sink);
                case PREFAB_LADDER -> dispatchPrefabLadderInto(task, sink);
                case RAIL_SURFACE_SEGMENT -> dispatchRailSegmentInto(task, sink, "surface");
                case RAIL_BRIDGE_SEGMENT -> dispatchRailSegmentInto(task, sink, "bridge");
                case RAIL_TUNNEL_SEGMENT -> dispatchRailSegmentInto(task, sink, "tunnel");
                default -> new TaskExecutionResult(false, "Unknown task type: " + task.getTaskType(), null);
            };
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Exception during task execution: " + e.getMessage(), null);
        }
    }

    private RegistryKey<World> resolveWorldKey(String worldString) {
        return worldString != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(worldString))
            : World.OVERWORLD;
    }

    private TaskExecutionResult dispatchBlockSetInto(BuildTask task, BlockSink sink) {
        try {
            BlockSetRequest request = objectMapper.treeToValue(task.getTaskData(), BlockSetRequest.class);
            BlockSetResult result = blocksCore.setBlocksInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null, "Set " + result.blocksSet() + " blocks, skipped " + result.blocksSkipped());
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse BLOCK_SET task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchBlockFillInto(BuildTask task, BlockSink sink) {
        try {
            FillBoxRequest request = objectMapper.treeToValue(task.getTaskData(), FillBoxRequest.class);
            int minX = Math.min(request.x1, request.x2);
            int maxX = Math.max(request.x1, request.x2);
            int minY = Math.min(request.y1, request.y2);
            int maxY = Math.max(request.y1, request.y2);
            int minZ = Math.min(request.z1, request.z2);
            int maxZ = Math.max(request.z1, request.z2);
            int totalBlocks = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            FillResult result = blocksCore.fillBoxInto(sink, request, resolveWorldKey(request.world),
                minX, minY, minZ, maxX, maxY, maxZ, totalBlocks);
            if (result.success()) {
                return new TaskExecutionResult(true, null, "Filled " + result.blocksSet() + " blocks, failed " + result.blocksFailed());
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse BLOCK_FILL task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchPrefabDoorInto(BuildTask task, BlockSink sink) {
        try {
            DoorRequest request = objectMapper.treeToValue(task.getTaskData(), DoorRequest.class);
            DoorResult result = prefabCore.placeDoorInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null, "Placed " + result.doors_placed() + " doors");
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse PREFAB_DOOR task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchPrefabStairsInto(BuildTask task, BlockSink sink) {
        try {
            StairRequest request = objectMapper.treeToValue(task.getTaskData(), StairRequest.class);
            StairResult result = prefabCore.placeStairsInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null, "Placed " + result.blocks_placed() + " stair blocks");
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse PREFAB_STAIRS task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchPrefabWindowInto(BuildTask task, BlockSink sink) {
        try {
            WindowPaneRequest request = objectMapper.treeToValue(task.getTaskData(), WindowPaneRequest.class);
            WindowPaneResult result = prefabCore.placeWindowPaneInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null, "Placed " + result.panes_placed() + " window panes");
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse PREFAB_WINDOW task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchPrefabTorchInto(BuildTask task, BlockSink sink) {
        try {
            TorchRequest request = objectMapper.treeToValue(task.getTaskData(), TorchRequest.class);
            TorchResult result = prefabCore.placeTorchInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null,
                    "Placed torch at (" + result.position().get("x") + ", " +
                    result.position().get("y") + ", " + result.position().get("z") + ")");
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse PREFAB_TORCH task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchPrefabSignInto(BuildTask task, BlockSink sink) {
        try {
            SignRequest request = objectMapper.treeToValue(task.getTaskData(), SignRequest.class);
            SignResult result = prefabCore.placeSignInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null,
                    "Placed sign at (" + result.position().get("x") + ", " +
                    result.position().get("y") + ", " + result.position().get("z") + ")");
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse PREFAB_SIGN task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchPrefabLadderInto(BuildTask task, BlockSink sink) {
        try {
            LadderRequest request = objectMapper.treeToValue(task.getTaskData(), LadderRequest.class);
            LadderResult result = prefabCore.placeLadderInto(sink, request, resolveWorldKey(request.world));
            if (result.success()) {
                return new TaskExecutionResult(true, null,
                    "Placed " + result.blocks_placed() + " ladder blocks facing " + result.facing());
            }
            return new TaskExecutionResult(false, result.error(), null);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to parse PREFAB_LADDER task data: " + e.getMessage(), null);
        }
    }

    private TaskExecutionResult dispatchRailSegmentInto(BuildTask task, BlockSink sink, String mode) {
        try {
            RailSegmentDefinition segment = parseRailSegment(task);
            if (segment.path().size() < 2) {
                return new TaskExecutionResult(false, "Rail segment path must contain at least 2 points", null);
            }
            return executeRailSegmentInto(sink, segment, mode);
        } catch (Exception e) {
            return new TaskExecutionResult(false, "Failed to execute rail segment: " + e.getMessage(), null);
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

    private CompletableFuture<TaskExecutionResult> executeRailSegmentTask(BuildTask task, String mode) {
        CompletableFuture<TaskExecutionResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                RailSegmentDefinition segment = parseRailSegment(task);
                if (segment.path().size() < 2) {
                    future.complete(new TaskExecutionResult(false, "Rail segment path must contain at least 2 points", null));
                    return;
                }

                RegistryKey<World> worldKey = segment.world() != null
                    ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(segment.world()))
                    : World.OVERWORLD;
                ServerWorld world = server.getWorld(worldKey);
                if (world == null) {
                    future.complete(new TaskExecutionResult(false, "Unknown world: " + worldKey, null));
                    return;
                }

                future.complete(executeRailSegmentInto(new WorldBlockSink(world), segment, mode));
            } catch (Exception e) {
                future.complete(new TaskExecutionResult(false, "Failed to execute rail segment: " + e.getMessage(), null));
            }
        });
        return future;
    }

    /**
     * Synchronous rail segment placement against a sink. Assumes caller has already
     * resolved the world and is on the server thread.
     */
    TaskExecutionResult executeRailSegmentInto(BlockSink sink, RailSegmentDefinition segment, String mode) {
        int railsPlaced = 0;
        int poweredRailsPlaced = 0;
        for (int i = 0; i < segment.path().size(); i++) {
            RailPoint point = segment.path().get(i);
            BlockPos pos = new BlockPos(point.x(), point.y(), point.z());

            if ("tunnel".equals(mode)) {
                clearTunnel(sink, pos, segment);
            } else {
                clearHeadroom(sink, pos);
            }
        }

        for (int i = 0; i < segment.path().size(); i++) {
            RailPoint point = segment.path().get(i);
            BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
            boolean powered = shouldPlacePoweredRail(segment.path(), i, segment.poweredRailInterval());

            // NOTE: disabling support for now
            // ensureBase(sink, pos, segment, "bridge".equals(mode), powered);
            if (placeRail(sink, pos, segment.path(), i, segment, powered)) {
                poweredRailsPlaced++;
            }

            if ("tunnel".equals(mode)) {
                lineTunnel(sink, pos, segment, segment.path(), i);
            }
            railsPlaced++;
        }

        for (RailPoint point : segment.path()) {
            BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
            reconcileRailAt(sink, pos);
            for (BlockPos neighbor : getConnectedRailNeighbors(sink, pos).values()) {
                reconcileRailAt(sink, neighbor);
            }
        }

        return new TaskExecutionResult(
            true,
            null,
            "Placed " + railsPlaced + " rail blocks (" + poweredRailsPlaced + " powered)"
        );
    }

    private RailSegmentDefinition parseRailSegment(BuildTask task) {
        Map<?, ?> raw = objectMapper.convertValue(task.getTaskData(), Map.class);
        List<?> pathRaw = (List<?>) raw.get("path");
        List<RailPoint> points = new ArrayList<>();
        for (Object pointObj : pathRaw) {
            Map<?, ?> pointMap = (Map<?, ?>) pointObj;
            points.add(new RailPoint(
                ((Number) pointMap.get("x")).intValue(),
                ((Number) pointMap.get("y")).intValue(),
                ((Number) pointMap.get("z")).intValue()
            ));
        }
        int interval = ((Number) raw.get("powered_rail_interval")).intValue();
        return new RailSegmentDefinition(
            points,
            (String) raw.get("world"),
            (String) raw.get("rail_bed_block"),
            (String) raw.get("support_block"),
            (String) raw.get("power_block"),
            (String) raw.get("tunnel_lining_block"),
            interval
        );
    }

    private void clearHeadroom(BlockSink sink, BlockPos pos) {
        BlockState air = Blocks.AIR.getDefaultState();
        clearUnlessRail(sink, pos, air);
        clearUnlessRail(sink, pos.up(), air);
        clearUnlessRail(sink, pos.up(2), air);
    }

    private void clearTunnel(BlockSink sink, BlockPos pos, RailSegmentDefinition segment) {
        BlockState air = Blocks.AIR.getDefaultState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    clearUnlessRail(sink, pos.add(dx, dy, dz), air);
                }
            }
        }
    }

    private void lineTunnel(BlockSink sink, BlockPos pos, RailSegmentDefinition segment, List<RailPoint> path, int index) {
        BlockState lining = getBlockState(segment.tunnelLiningBlock() != null ? segment.tunnelLiningBlock() : segment.supportBlock());
        if (lining == null) {
            return;
        }
        Direction previousNeighbor = getPreviousNeighborDirection(path, index);
        Direction nextNeighbor = getNextNeighborDirection(path, index);
        for (BlockPos offset : tunnelLiningOffsets(previousNeighbor, nextNeighbor)) {
            BlockPos target = pos.add(offset);
            if (isRailState(sink.getBlockState(target))) {
                continue;
            }
            if (overlapsRailClearance(path, index, target)) {
                continue;
            }
            sink.setBlockState(target, lining, Block.NOTIFY_LISTENERS);
        }
    }

    private boolean overlapsRailClearance(List<RailPoint> path, int index, BlockPos target) {
        int start = Math.max(0, index - 1);
        int end = Math.min(path.size() - 1, index + 1);
        for (int i = start; i <= end; i++) {
            RailPoint point = path.get(i);
            if (target.getX() == point.x() && target.getZ() == point.z()
                && target.getY() >= point.y() && target.getY() <= point.y() + 2) {
                return true;
            }
        }
        return false;
    }

    private void ensureBase(BlockSink sink, BlockPos pos, RailSegmentDefinition segment, boolean extendSupportColumn, boolean poweredRail) {
        BlockState bed = getBlockState(segment.railBedBlock());
        BlockState support = getBlockState(segment.supportBlock());
        BlockState power = getBlockState(segment.powerBlock());
        BlockState chosenSupport = support != null ? support : power;
        // BlockState topSupport = poweredRail && power != null ? power : bed;
        // FIXME: skipping powered rail redstone as it is unneded
        BlockState topSupport = bed;
        if (topSupport != null) {
            sink.setBlockState(pos.down(), topSupport, Block.NOTIFY_LISTENERS);
        }

        if (chosenSupport == null) {
            return;
        }

        int maxDepth = extendSupportColumn ? 24 : 8;
        for (int depth = 2; depth <= maxDepth; depth++) {
            BlockPos supportPos = pos.down(depth);
            BlockState existing = sink.getBlockState(supportPos);
            if (!existing.isAir() && existing.getFluidState().isEmpty()) {
                break;
            }
            sink.setBlockState(supportPos, chosenSupport, Block.NOTIFY_LISTENERS);
        }
    }

    private boolean placeRail(BlockSink sink, BlockPos pos, List<RailPoint> path, int index, RailSegmentDefinition segment, boolean powered) {
        Direction forward = getForwardDirection(path, index);
        BlockState railState = powered ? Blocks.POWERED_RAIL.getDefaultState() : Blocks.RAIL.getDefaultState();
        railState = applyRailShape(railState, path, index, forward);
        sink.setBlockState(pos, railState, Block.NOTIFY_ALL);
        return powered;
    }

    private void clearUnlessRail(BlockSink sink, BlockPos pos, BlockState replacement) {
        if (isRailState(sink.getBlockState(pos))) {
            return;
        }
        sink.setBlockState(pos, replacement, Block.NOTIFY_LISTENERS);
    }

    private BlockState applyRailShape(BlockState railState, List<RailPoint> path, int index, Direction forward) {
        RailShape shape = getRailShape(path, index, forward);
        if (railState.contains(PoweredRailBlock.SHAPE)) {
            if (isCurveShape(shape)) {
                railState = Blocks.RAIL.getDefaultState();
            } else {
                railState = railState.with(PoweredRailBlock.SHAPE, shape);
            }
        }
        if (railState.contains(PoweredRailBlock.SHAPE)) {
            if (railState.contains(PoweredRailBlock.POWERED)) {
                railState = railState.with(PoweredRailBlock.POWERED, true);
            } else if (railState.contains(Properties.POWERED)) {
                railState = railState.with(Properties.POWERED, true);
            }
            return railState;
        }
        if (railState.contains(RailBlock.SHAPE)) {
            return railState.with(RailBlock.SHAPE, shape);
        }
        return railState;
    }

    static List<BlockPos> tunnelLiningOffsets(Direction incoming, Direction outgoing) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                    if (!isTunnelShellBoundary(dx, dy, dz)) {
                        continue;
                    }
                    if (isOpenTunnelFace(dx, dz, incoming) || isOpenTunnelFace(dx, dz, outgoing)) {
                        continue;
                    }
                    offsets.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        return offsets;
    }

    private static boolean isTunnelShellBoundary(int dx, int dy, int dz) {
        return dy == 3 || Math.abs(dx) == 1 || Math.abs(dz) == 1;
    }

    private static boolean isOpenTunnelFace(int dx, int dz, Direction direction) {
        if (direction == null) {
            return false;
        }
        return switch (direction) {
            case NORTH -> dz == -1;
            case SOUTH -> dz == 1;
            case EAST -> dx == 1;
            case WEST -> dx == -1;
            default -> false;
        };
    }

    static boolean shouldPlacePoweredRail(List<RailPoint> path, int index, int poweredRailInterval) {
        boolean poweredEligible = isStraightSegment(path, index) && poweredRailInterval > 0;
        return poweredEligible && (countStraightSegmentsUpTo(path, index) % poweredRailInterval == 0);
    }

    static RailShape getRailShape(List<RailPoint> path, int index, Direction fallbackForward) {
        Direction previous = getIncomingDirection(path, index);
        Direction next = getOutgoingDirection(path, index);
        Direction primary = next != null ? next : previous;
        RailPoint current = path.get(index);
        RailPoint previousPoint = index > 0 ? path.get(index - 1) : null;
        RailPoint nextPoint = index + 1 < path.size() ? path.get(index + 1) : null;
        if (primary == null) {
            primary = fallbackForward;
        }

        if (previous != null && next != null && previous.getAxis() != next.getAxis()) {
            if (matchesCurve(previous, next, Direction.NORTH, Direction.EAST)) {
                return RailShape.NORTH_EAST;
            }
            if (matchesCurve(previous, next, Direction.NORTH, Direction.WEST)) {
                return RailShape.NORTH_WEST;
            }
            if (matchesCurve(previous, next, Direction.SOUTH, Direction.EAST)) {
                return RailShape.SOUTH_EAST;
            }
            if (matchesCurve(previous, next, Direction.SOUTH, Direction.WEST)) {
                return RailShape.SOUTH_WEST;
            }
        }

        if (previous != null && next != null && previous.getAxis() == next.getAxis()) {
            RailShape ascending = getAscendingShape(current, previousPoint, nextPoint, previous.getAxis());
            if (ascending != null) {
                return ascending;
            }
        } else if (primary != null) {
            RailShape ascending = getAscendingShape(current, previousPoint, nextPoint, primary.getAxis());
            if (ascending != null) {
                return ascending;
            }
        }

        return (primary == Direction.EAST || primary == Direction.WEST)
            ? RailShape.EAST_WEST
            : RailShape.NORTH_SOUTH;
    }

    private static RailShape getAscendingShape(RailPoint current, RailPoint previousPoint, RailPoint nextPoint, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            if (previousPoint != null && previousPoint.x() > current.x() && previousPoint.y() > current.y()
                || nextPoint != null && nextPoint.x() > current.x() && nextPoint.y() > current.y()) {
                return RailShape.ASCENDING_EAST;
            }
            if (previousPoint != null && previousPoint.x() < current.x() && previousPoint.y() > current.y()
                || nextPoint != null && nextPoint.x() < current.x() && nextPoint.y() > current.y()) {
                return RailShape.ASCENDING_WEST;
            }
        }
        if (axis == Direction.Axis.Z) {
            if (previousPoint != null && previousPoint.z() > current.z() && previousPoint.y() > current.y()
                || nextPoint != null && nextPoint.z() > current.z() && nextPoint.y() > current.y()) {
                return RailShape.ASCENDING_SOUTH;
            }
            if (previousPoint != null && previousPoint.z() < current.z() && previousPoint.y() > current.y()
                || nextPoint != null && nextPoint.z() < current.z() && nextPoint.y() > current.y()) {
                return RailShape.ASCENDING_NORTH;
            }
        }
        return null;
    }

    private static boolean matchesCurve(Direction previous, Direction next, Direction first, Direction second) {
        return (previous == first && next == second) || (previous == second && next == first);
    }

    static boolean isStraightSegment(List<RailPoint> path, int index) {
        Direction previous = getIncomingDirection(path, index);
        Direction next = getOutgoingDirection(path, index);
        if (previous == null || next == null) {
            return true;
        }
        return previous.getAxis() == next.getAxis();
    }

    private static int countStraightSegmentsUpTo(List<RailPoint> path, int index) {
        int count = 0;
        for (int i = 0; i <= index; i++) {
            if (isStraightSegment(path, i)) {
                count++;
            }
        }
        return count;
    }

    private Direction getForwardDirection(List<RailPoint> path, int index) {
        RailPoint current = path.get(index);
        RailPoint neighbor = index + 1 < path.size() ? path.get(index + 1) : path.get(index - 1);
        int dx = Integer.compare(neighbor.x(), current.x());
        int dz = Integer.compare(neighbor.z(), current.z());
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction getIncomingDirection(List<RailPoint> path, int index) {
        if (index == 0) {
            return null;
        }
        return directionBetween(path.get(index - 1), path.get(index));
    }

    static Direction getPreviousNeighborDirection(List<RailPoint> path, int index) {
        Direction incoming = getIncomingDirection(path, index);
        return incoming != null ? incoming.getOpposite() : null;
    }

    static Direction getNextNeighborDirection(List<RailPoint> path, int index) {
        return getOutgoingDirection(path, index);
    }

    private static Direction getOutgoingDirection(List<RailPoint> path, int index) {
        if (index >= path.size() - 1) {
            return null;
        }
        return directionBetween(path.get(index), path.get(index + 1));
    }

    private static Direction directionBetween(RailPoint from, RailPoint to) {
        int dx = Integer.compare(to.x(), from.x());
        int dz = Integer.compare(to.z(), from.z());
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private BlockState getBlockState(String blockId) {
        if (blockId == null) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null) {
            return null;
        }
        return Registries.BLOCK.get(identifier).getDefaultState();
    }

    private void reconcileRailAt(BlockSink sink, BlockPos pos) {
        BlockState state = sink.getBlockState(pos);
        if (!isRailState(state)) {
            return;
        }

        RailShape shape = determineRenderedRailShape(sink, pos);
        if (shape == null) {
            return;
        }

        BlockState updated = coerceRailStateForShape(state, shape);
        sink.setBlockState(pos, updated, Block.NOTIFY_ALL);
    }

    static RailShape determineRenderedRailShape(BlockSink sink, BlockPos pos) {
        Map<Direction, BlockPos> neighbors = getConnectedRailNeighbors(sink, pos);
        if (neighbors.isEmpty()) {
            return null;
        }

        boolean hasEast = neighbors.containsKey(Direction.EAST);
        boolean hasWest = neighbors.containsKey(Direction.WEST);
        boolean hasNorth = neighbors.containsKey(Direction.NORTH);
        boolean hasSouth = neighbors.containsKey(Direction.SOUTH);

        if ((hasEast || hasWest) && !(hasNorth || hasSouth)) {
            if (hasEast && neighbors.get(Direction.EAST).getY() > pos.getY()) {
                return RailShape.ASCENDING_EAST;
            }
            if (hasWest && neighbors.get(Direction.WEST).getY() > pos.getY()) {
                return RailShape.ASCENDING_WEST;
            }
            return RailShape.EAST_WEST;
        }

        if ((hasNorth || hasSouth) && !(hasEast || hasWest)) {
            if (hasSouth && neighbors.get(Direction.SOUTH).getY() > pos.getY()) {
                return RailShape.ASCENDING_SOUTH;
            }
            if (hasNorth && neighbors.get(Direction.NORTH).getY() > pos.getY()) {
                return RailShape.ASCENDING_NORTH;
            }
            return RailShape.NORTH_SOUTH;
        }

        if ((hasNorth && hasEast) || (hasEast && hasNorth)) {
            return RailShape.NORTH_EAST;
        }
        if ((hasNorth && hasWest) || (hasWest && hasNorth)) {
            return RailShape.NORTH_WEST;
        }
        if ((hasSouth && hasEast) || (hasEast && hasSouth)) {
            return RailShape.SOUTH_EAST;
        }
        if ((hasSouth && hasWest) || (hasWest && hasSouth)) {
            return RailShape.SOUTH_WEST;
        }

        return null;
    }

    static Map<Direction, BlockPos> getConnectedRailNeighbors(BlockSink sink, BlockPos pos) {
        Map<Direction, BlockPos> neighbors = new EnumMap<>(Direction.class);
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            BlockPos sameLevel = pos.offset(direction);
            if (isRailState(sink.getBlockState(sameLevel))) {
                neighbors.put(direction, sameLevel);
                continue;
            }

            BlockPos above = sameLevel.up();
            if (isRailState(sink.getBlockState(above))) {
                neighbors.put(direction, above);
                continue;
            }

            BlockPos below = sameLevel.down();
            if (isRailState(sink.getBlockState(below))) {
                neighbors.put(direction, below);
            }
        }
        return neighbors;
    }

    static boolean isRailState(BlockState state) {
        return state.isOf(Blocks.RAIL) || state.isOf(Blocks.POWERED_RAIL);
    }

    private static BlockState coerceRailStateForShape(BlockState state, RailShape shape) {
        BlockState targetState = state;
        if (state.isOf(Blocks.POWERED_RAIL) && isCurveShape(shape)) {
            targetState = Blocks.RAIL.getDefaultState();
        }

        if (targetState.contains(PoweredRailBlock.SHAPE)) {
            targetState = targetState.with(PoweredRailBlock.SHAPE, shape);
            if (targetState.contains(PoweredRailBlock.POWERED)) {
                targetState = targetState.with(PoweredRailBlock.POWERED, true);
            } else if (targetState.contains(Properties.POWERED)) {
                targetState = targetState.with(Properties.POWERED, true);
            }
            return targetState;
        }

        if (targetState.contains(RailBlock.SHAPE)) {
            return targetState.with(RailBlock.SHAPE, shape);
        }

        return targetState;
    }

    private static boolean isCurveShape(RailShape shape) {
        return shape == RailShape.NORTH_EAST
            || shape == RailShape.NORTH_WEST
            || shape == RailShape.SOUTH_EAST
            || shape == RailShape.SOUTH_WEST;
    }

    /**
     * Result of task execution.
     */
    public record TaskExecutionResult(boolean success, String errorMessage, String details) {}

    static record RailPoint(int x, int y, int z) {}

    static record RailSegmentDefinition(
        List<RailPoint> path,
        String world,
        String railBedBlock,
        String supportBlock,
        String powerBlock,
        String tunnelLiningBlock,
        int poweredRailInterval
    ) {}
}
