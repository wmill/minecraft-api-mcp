package com.example.endpoints;

import com.example.buildtask.model.BuildTask;
import com.example.buildtask.service.TaskDataValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

                int railsPlaced = 0;
                for (int i = 0; i < segment.path().size(); i++) {
                    RailPoint point = segment.path().get(i);
                    Direction forward = getForwardDirection(segment.path(), i);
                    BlockPos pos = new BlockPos(point.x(), point.y(), point.z());

                    if ("tunnel".equals(mode)) {
                        clearTunnel(world, pos, segment);
                    } else {
                        clearHeadroom(world, pos);
                    }

                    ensureBase(world, pos, segment, "bridge".equals(mode));
                    placeRail(world, pos, forward, i, segment);
                    if ("tunnel".equals(mode)) {
                        lineTunnel(world, pos, segment);
                    }
                    railsPlaced++;
                }

                future.complete(new TaskExecutionResult(true, null, "Placed " + railsPlaced + " rail blocks"));
            } catch (Exception e) {
                future.complete(new TaskExecutionResult(false, "Failed to execute rail segment: " + e.getMessage(), null));
            }
        });
        return future;
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

    private void clearHeadroom(ServerWorld world, BlockPos pos) {
        BlockState air = Blocks.AIR.getDefaultState();
        world.setBlockState(pos, air, Block.NOTIFY_LISTENERS);
        world.setBlockState(pos.up(), air, Block.NOTIFY_LISTENERS);
        world.setBlockState(pos.up(2), air, Block.NOTIFY_LISTENERS);
    }

    private void clearTunnel(ServerWorld world, BlockPos pos, RailSegmentDefinition segment) {
        BlockState air = Blocks.AIR.getDefaultState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlockState(pos.add(dx, dy, dz), air, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    private void lineTunnel(ServerWorld world, BlockPos pos, RailSegmentDefinition segment) {
        BlockState lining = getBlockState(segment.tunnelLiningBlock() != null ? segment.tunnelLiningBlock() : segment.supportBlock());
        if (lining == null) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                    world.setBlockState(pos.add(dx, 0, dz), lining, Block.NOTIFY_LISTENERS);
                    world.setBlockState(pos.add(dx, 2, dz), lining, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    private void ensureBase(ServerWorld world, BlockPos pos, RailSegmentDefinition segment, boolean extendSupportColumn) {
        BlockState bed = getBlockState(segment.railBedBlock());
        BlockState support = getBlockState(segment.supportBlock());
        BlockState power = getBlockState(segment.powerBlock());
        BlockState chosenSupport = support != null ? support : power;
        if (bed != null) {
            world.setBlockState(pos.down(), bed, Block.NOTIFY_LISTENERS);
        }

        if (chosenSupport == null) {
            return;
        }

        int maxDepth = extendSupportColumn ? 24 : 8;
        for (int depth = 2; depth <= maxDepth; depth++) {
            BlockPos supportPos = pos.down(depth);
            BlockState existing = world.getBlockState(supportPos);
            if (!existing.isAir() && existing.getFluidState().isEmpty()) {
                break;
            }
            world.setBlockState(supportPos, chosenSupport, Block.NOTIFY_LISTENERS);
        }
    }

    private void placeRail(ServerWorld world, BlockPos pos, Direction forward, int index, RailSegmentDefinition segment) {
        boolean powered = index % segment.poweredRailInterval() == 0;
        BlockState railState = powered ? Blocks.POWERED_RAIL.getDefaultState() : Blocks.RAIL.getDefaultState();
        railState = applyRailShape(railState, forward);
        world.setBlockState(pos, railState, Block.NOTIFY_ALL);
        if (powered) {
            BlockState power = getBlockState(segment.powerBlock());
            if (power != null) {
                world.setBlockState(pos.down(2), power, Block.NOTIFY_LISTENERS);
            }
        }
    }

    private BlockState applyRailShape(BlockState railState, Direction forward) {
        if (railState.contains(PoweredRailBlock.SHAPE)) {
            RailShape shape = (forward == Direction.EAST || forward == Direction.WEST)
                ? RailShape.EAST_WEST
                : RailShape.NORTH_SOUTH;
            return railState.with(PoweredRailBlock.SHAPE, shape);
        }
        if (railState.contains(RailBlock.SHAPE)) {
            RailShape shape = (forward == Direction.EAST || forward == Direction.WEST)
                ? RailShape.EAST_WEST
                : RailShape.NORTH_SOUTH;
            return railState.with(RailBlock.SHAPE, shape);
        }
        return railState;
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

    /**
     * Result of task execution.
     */
    public record TaskExecutionResult(boolean success, String errorMessage, String details) {}

    private record RailPoint(int x, int y, int z) {}

    private record RailSegmentDefinition(
        List<RailPoint> path,
        String world,
        String railBedBlock,
        String supportBlock,
        String powerBlock,
        String tunnelLiningBlock,
        int poweredRailInterval
    ) {}
}
