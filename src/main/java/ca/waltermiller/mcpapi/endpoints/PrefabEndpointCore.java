package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.preview.BlockSink;
import ca.waltermiller.mcpapi.preview.WorldBlockSink;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.state.property.Properties;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Core prefab operations that can be used both programmatically and by HTTP endpoints.
 * This class contains the business logic for prefab operations without Javalin Context dependencies.
 */
public class PrefabEndpointCore {
    private final MinecraftServer server;
    private final org.slf4j.Logger logger;

    public PrefabEndpointCore(MinecraftServer server, org.slf4j.Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    /**
     * Place door prefab
     */
    public CompletableFuture<DoorResult> placeDoor(DoorRequest request) {
        CompletableFuture<DoorResult> future = new CompletableFuture<>();

        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new DoorResult(false, "Unknown world: " + worldKey, null, 0, null, null, false));
            return future;
        }

        server.execute(() -> future.complete(placeDoorInto(new WorldBlockSink(world), request, worldKey)));
        return future;
    }

    /**
     * Core door-placement logic. All validation + block writes happen here, against a sink.
     * Dry-run callers pass a recording sink so no world state is mutated.
     */
    public DoorResult placeDoorInto(BlockSink sink, DoorRequest request, RegistryKey<World> worldKey) {
        if (request.width <= 0) {
            return new DoorResult(false, "Width must be positive", null, 0, null, null, false);
        }

        Identifier blockId = Identifier.tryParse(request.block_type);
        if (blockId == null) {
            return new DoorResult(false, "Invalid block identifier", null, 0, null, null, false);
        }

        Block block = Registries.BLOCK.get(blockId);
        if (!(block instanceof DoorBlock)) {
            return new DoorResult(false, "Block is not a door: " + request.block_type, null, 0, null, null, false);
        }

        Direction facing = switch(request.facing.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (facing == null || !facing.getAxis().isHorizontal()) {
            return new DoorResult(false, "Facing must be one of north, south, east, west", null, 0, null, null, false);
        }

        DoorHinge hinge = "right".equalsIgnoreCase(request.hinge) ? DoorHinge.RIGHT : DoorHinge.LEFT;
        boolean open = Boolean.TRUE.equals(request.open);

        logger.info("Placing door prefab in world {} at ({}, {}, {}) facing {} width {}",
            worldKey.getValue(), request.start_x, request.start_y, request.start_z, facing, request.width);

        try {
            int doorsPlaced = 0;
            Direction lateral = facing.rotateYClockwise();

            for (int i = 0; i < request.width; i++) {
                BlockPos basePos = new BlockPos(
                    request.start_x + lateral.getOffsetX() * i,
                    request.start_y,
                    request.start_z + lateral.getOffsetZ() * i
                );
                BlockPos upperPos = basePos.up();
                DoorHinge currentHinge = hinge;
                if (request.double_doors && i % 2 == 1) {
                    currentHinge = hinge == DoorHinge.RIGHT ? DoorHinge.LEFT : DoorHinge.RIGHT;
                }

                sink.setBlockState(basePos, Blocks.AIR.getDefaultState());
                sink.setBlockState(upperPos, Blocks.AIR.getDefaultState());

                BlockState lowerState = block.getDefaultState()
                    .with(Properties.HORIZONTAL_FACING, facing)
                    .with(Properties.DOOR_HINGE, currentHinge)
                    .with(Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER)
                    .with(Properties.OPEN, open)
                    .with(Properties.POWERED, false);

                BlockState upperState = block.getDefaultState()
                    .with(Properties.HORIZONTAL_FACING, facing)
                    .with(Properties.DOOR_HINGE, currentHinge)
                    .with(Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER)
                    .with(Properties.OPEN, open)
                    .with(Properties.POWERED, false);

                sink.setBlockState(basePos, lowerState);
                sink.setBlockState(upperPos, upperState);
                doorsPlaced++;
            }

            return new DoorResult(true, null, worldKey.getValue().toString(), doorsPlaced, facing.asString(), hinge == DoorHinge.RIGHT ? "right" : "left", open);
        } catch (Exception e) {
            logger.error("Error placing door prefab", e);
            return new DoorResult(false, "Exception during door placement: " + e.getMessage(), null, 0, null, null, false);
        }
    }

    /**
     * Place stairs prefab
     */
    public CompletableFuture<StairResult> placeStairs(StairRequest request) {
        CompletableFuture<StairResult> future = new CompletableFuture<>();

        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new StairResult(false, "Unknown world: " + worldKey, null, 0, null, false));
            return future;
        }

        server.execute(() -> future.complete(placeStairsInto(new WorldBlockSink(world), request, worldKey)));
        return future;
    }

    public StairResult placeStairsInto(BlockSink sink, StairRequest request, RegistryKey<World> worldKey) {
        Identifier blockId = Identifier.tryParse(request.block_type);
        if (blockId == null) {
            return new StairResult(false, "Invalid block identifier", null, 0, null, false);
        }
        Identifier stairId = Identifier.tryParse(request.stair_type);
        if (stairId == null) {
            return new StairResult(false, "Invalid stair block identifier", null, 0, null, false);
        }

        Block baseBlock = Registries.BLOCK.get(blockId);
        Block stairBlock = Registries.BLOCK.get(stairId);
        if (!(stairBlock instanceof StairsBlock)) {
            return new StairResult(false, "Block is not a stair: " + request.stair_type, null, 0, null, false);
        }

        Direction staircaseDirection = switch(request.staircase_direction.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (staircaseDirection == null || !staircaseDirection.getAxis().isHorizontal()) {
            return new StairResult(false, "staircase_direction must be one of north, south, east, west", null, 0, null, false);
        }

        logger.info("Placing stair prefab in world {} from ({}, {}, {}) to ({}, {}, {}) staircase_direction {}",
            worldKey.getValue(), request.start_x, request.start_y, request.start_z, request.end_x, request.end_y, request.end_z, staircaseDirection);

        try {
            int blocksPlaced = buildStaircase(sink, request, baseBlock, stairBlock, staircaseDirection);
            return new StairResult(true, null, worldKey.getValue().toString(), blocksPlaced, staircaseDirection.asString(), request.fill_support);
        } catch (Exception e) {
            logger.error("Error placing stair prefab", e);
            return new StairResult(false, "Exception during stair placement: " + e.getMessage(), null, 0, null, false);
        }
    }

    /**
     * Place window pane wall prefab
     */
    public CompletableFuture<WindowPaneResult> placeWindowPane(WindowPaneRequest request) {
        CompletableFuture<WindowPaneResult> future = new CompletableFuture<>();

        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new WindowPaneResult(false, "Unknown world: " + worldKey, null, 0, null, false));
            return future;
        }

        server.execute(() -> future.complete(placeWindowPaneInto(new WorldBlockSink(world), request, worldKey)));
        return future;
    }

    public WindowPaneResult placeWindowPaneInto(BlockSink sink, WindowPaneRequest request, RegistryKey<World> worldKey) {
        if (request.height <= 0) {
            return new WindowPaneResult(false, "Height must be positive", null, 0, null, false);
        }

        Identifier blockId = Identifier.tryParse(request.block_type);
        if (blockId == null) {
            return new WindowPaneResult(false, "Invalid block identifier", null, 0, null, false);
        }

        Block block = Registries.BLOCK.get(blockId);
        if (!(block instanceof PaneBlock)) {
            return new WindowPaneResult(false, "Block is not a pane block: " + request.block_type, null, 0, null, false);
        }

        boolean isEastWest = request.start_z == request.end_z;
        boolean isNorthSouth = request.start_x == request.end_x;

        if (!isEastWest && !isNorthSouth) {
            return new WindowPaneResult(false, "Window pane wall must be aligned north-south or east-west", null, 0, null, false);
        }

        logger.info("Placing window pane wall in world {} from ({}, {}, {}) to ({}, {}, {}) height {}",
            worldKey.getValue(), request.start_x, request.start_y, request.start_z, request.end_x, request.start_y + request.height - 1, request.end_z, request.height);

        try {
            int panesPlaced = buildWindowPaneWall(sink, request, block);
            return new WindowPaneResult(true, null, worldKey.getValue().toString(), panesPlaced, isEastWest ? "east-west" : "north-south", request.waterlogged);
        } catch (Exception e) {
            logger.error("Error placing window pane wall", e);
            return new WindowPaneResult(false, "Exception during window pane placement: " + e.getMessage(), null, 0, null, false);
        }
    }

    /**
     * Place torch prefab
     */
    public CompletableFuture<TorchResult> placeTorch(TorchRequest request) {
        CompletableFuture<TorchResult> future = new CompletableFuture<>();

        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new TorchResult(false, "Unknown world: " + worldKey, null, null, null, false, null));
            return future;
        }

        server.execute(() -> future.complete(placeTorchInto(new WorldBlockSink(world), request, worldKey)));
        return future;
    }

    public TorchResult placeTorchInto(BlockSink sink, TorchRequest request, RegistryKey<World> worldKey) {
        Identifier blockId = Identifier.tryParse(request.block_type);
        if (blockId == null) {
            return new TorchResult(false, "Invalid block identifier", null, null, null, false, null);
        }

        Block block = Registries.BLOCK.get(blockId);
        String blockName = blockId.getPath();
        boolean isWallTorch = blockName.contains("wall_torch");

        logger.info("Placing torch in world {} at ({}, {}, {}) type {}",
            worldKey.getValue(), request.x, request.y, request.z, request.block_type);

        try {
            BlockPos pos = new BlockPos(request.x, request.y, request.z);
            BlockState torchState;

            if (isWallTorch) {
                Direction facing = null;

                if (request.facing != null && !request.facing.isEmpty()) {
                    facing = switch(request.facing.toLowerCase()) {
                        case "north" -> Direction.NORTH;
                        case "south" -> Direction.SOUTH;
                        case "east" -> Direction.EAST;
                        case "west" -> Direction.WEST;
                        default -> null;
                    };

                    if (facing == null) {
                        return new TorchResult(false, "Invalid facing direction. Must be north, south, east, or west", null, null, null, false, null);
                    }

                    BlockPos attachPos = pos.offset(facing);
                    if (!sink.getBlockState(attachPos).isSolidBlock(sink.world(), attachPos)) {
                        logger.error("No solid block to attach wall torch to in " + facing.asString() + " direction, doing it anyways.");
                    }
                } else {
                    Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
                    for (Direction dir : directions) {
                        BlockPos attachPos = pos.offset(dir);
                        if (sink.getBlockState(attachPos).isSolidBlock(sink.world(), attachPos)) {
                            facing = dir;
                            break;
                        }
                    }

                    if (facing == null) {
                        return new TorchResult(false, "No adjacent solid block found to attach wall torch. Please specify facing or provide a wall.", null, null, null, false, null);
                    }
                }

                torchState = block.getDefaultState()
                    .with(Properties.HORIZONTAL_FACING, facing);

                sink.setBlockState(pos, torchState);

                Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                return new TorchResult(true, null, worldKey.getValue().toString(), request.block_type, position, true, facing.asString());
            } else {
                torchState = block.getDefaultState();
                sink.setBlockState(pos, torchState);

                Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                return new TorchResult(true, null, worldKey.getValue().toString(), request.block_type, position, false, null);
            }
        } catch (Exception e) {
            logger.error("Error placing torch", e);
            return new TorchResult(false, "Exception during torch placement: " + e.getMessage(), null, null, null, false, null);
        }
    }

    /**
     * Place sign prefab
     */
    public CompletableFuture<SignResult> placeSign(SignRequest request) {
        CompletableFuture<SignResult> future = new CompletableFuture<>();

        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new SignResult(false, "Unknown world: " + worldKey, null, null, null, null, null, null, false));
            return future;
        }

        server.execute(() -> future.complete(placeSignInto(new WorldBlockSink(world), request, worldKey)));
        return future;
    }

    public SignResult placeSignInto(BlockSink sink, SignRequest request, RegistryKey<World> worldKey) {
        Identifier blockId = Identifier.tryParse(request.block_type);
        if (blockId == null) {
            return new SignResult(false, "Invalid block identifier", null, null, null, null, null, null, false);
        }

        Block block = Registries.BLOCK.get(blockId);
        boolean isWallSign = block instanceof WallSignBlock;
        boolean isStandingSign = block instanceof SignBlock && !isWallSign;

        if (!isWallSign && !isStandingSign) {
            return new SignResult(false, "Block is not a sign: " + request.block_type, null, null, null, null, null, null, false);
        }

        if (request.front_lines != null && request.front_lines.length > 4) {
            return new SignResult(false, "Front text can have maximum 4 lines", null, null, null, null, null, null, false);
        }
        if (request.back_lines != null && request.back_lines.length > 4) {
            return new SignResult(false, "Back text can have maximum 4 lines", null, null, null, null, null, null, false);
        }

        logger.info("Placing sign in world {} at ({}, {}, {}) type {}",
            worldKey.getValue(), request.x, request.y, request.z, request.block_type);

        try {
            BlockPos pos = new BlockPos(request.x, request.y, request.z);
            BlockState signState;

            if (isWallSign) {
                Direction facing = null;

                if (request.facing != null && !request.facing.isEmpty()) {
                    facing = switch(request.facing.toLowerCase()) {
                        case "north" -> Direction.NORTH;
                        case "south" -> Direction.SOUTH;
                        case "east" -> Direction.EAST;
                        case "west" -> Direction.WEST;
                        default -> null;
                    };

                    if (facing == null) {
                        return new SignResult(false, "Invalid facing direction. Must be north, south, east, or west", null, null, null, null, null, null, false);
                    }
                } else {
                    Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
                    for (Direction dir : directions) {
                        BlockPos attachPos = pos.offset(dir);
                        if (sink.getBlockState(attachPos).isSolidBlock(sink.world(), attachPos)) {
                            facing = dir;
                            break;
                        }
                    }

                    if (facing == null) {
                        facing = Direction.NORTH;
                        logger.warn("No adjacent solid block found for wall sign, defaulting to north");
                    }
                }

                signState = block.getDefaultState()
                    .with(Properties.HORIZONTAL_FACING, facing);

                sink.setBlockState(pos, signState);
                applySignText(sink, pos, request);

                Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                return new SignResult(true, null, worldKey.getValue().toString(), position, request.block_type, "wall", facing.asString(), null, request.glowing != null ? request.glowing : false);
            } else {
                int rotation = request.rotation != null ? request.rotation : 0;
                rotation = Math.max(0, Math.min(15, rotation));

                signState = block.getDefaultState()
                    .with(Properties.ROTATION, rotation);

                sink.setBlockState(pos, signState);
                applySignText(sink, pos, request);

                Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                return new SignResult(true, null, worldKey.getValue().toString(), position, request.block_type, "standing", null, rotation, request.glowing != null ? request.glowing : false);
            }
        } catch (Exception e) {
            logger.error("Error placing sign", e);
            return new SignResult(false, "Exception during sign placement: " + e.getMessage(), null, null, null, null, null, null, false);
        }
    }

    private void applySignText(BlockSink sink, BlockPos pos, SignRequest request) {
        // Block entity only exists when the block was written through to the real world.
        // Recording sinks skip this branch, which means sign text is omitted from previews —
        // acceptable since the isometric renderer can't show text anyway.
        BlockEntity blockEntity = sink.world().getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity signBlockEntity)) {
            return;
        }
        if (request.front_lines != null && request.front_lines.length > 0) {
            signBlockEntity.setText(createSignText(request.front_lines, request.glowing), true);
        }
        if (request.back_lines != null && request.back_lines.length > 0) {
            signBlockEntity.setText(createSignText(request.back_lines, request.glowing), false);
        }
        signBlockEntity.markDirty();
    }

    /**
     * Place ladder prefab
     */
    public CompletableFuture<LadderResult> placeLadder(LadderRequest request) {
        CompletableFuture<LadderResult> future = new CompletableFuture<>();

        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new LadderResult(false, "Unknown world: " + worldKey, null, 0, null, null, null));
            return future;
        }

        server.execute(() -> future.complete(placeLadderInto(new WorldBlockSink(world), request, worldKey)));
        return future;
    }

    public LadderResult placeLadderInto(BlockSink sink, LadderRequest request, RegistryKey<World> worldKey) {
        if (request.height <= 0) {
            return new LadderResult(false, "Height must be positive", null, 0, null, null, null);
        }

        Identifier blockId = Identifier.tryParse(request.block_type);
        if (blockId == null) {
            return new LadderResult(false, "Invalid block identifier", null, 0, null, null, null);
        }

        Block block = Registries.BLOCK.get(blockId);
        if (!(block instanceof LadderBlock)) {
            return new LadderResult(false, "Block is not a ladder: " + request.block_type, null, 0, null, null, null);
        }

        if (request.y < -64 || request.y > 320) {
            return new LadderResult(false, "Y coordinate out of world bounds (-64 to 320)", null, 0, null, null, null);
        }

        int maxHeight = 320 - request.y + 1;
        int actualHeight = Math.min(request.height, maxHeight);

        logger.info("Placing ladder prefab in world {} at ({}, {}, {}) height {} block_type {}",
            worldKey.getValue(), request.x, request.y, request.z, actualHeight, request.block_type);

        try {
            int blocksPlaced = 0;
            Direction facing = null;

            if (request.facing != null && !request.facing.isEmpty()) {
                facing = switch(request.facing.toLowerCase()) {
                    case "north" -> Direction.NORTH;
                    case "south" -> Direction.SOUTH;
                    case "east" -> Direction.EAST;
                    case "west" -> Direction.WEST;
                    default -> null;
                };

                if (facing == null) {
                    return new LadderResult(false, "Invalid facing direction. Must be north, south, east, or west", null, 0, null, null, null);
                }

                if (!validateLadderAttachment(sink, request.x, request.y, request.z, actualHeight, facing)) {
                    logger.warn("Specified facing direction {} has poor attachment, but proceeding as requested", facing.asString());
                }
            } else {
                facing = findOptimalLadderFacing(sink, request.x, request.y, request.z, actualHeight);

                if (facing == null) {
                    facing = applyLadderFallbackLogic(sink, request.x, request.y, request.z, actualHeight);
                    if (facing == null) {
                        return new LadderResult(false, "No suitable attachment found for ladder placement. Consider specifying a facing direction or providing adjacent solid blocks.", null, 0, null, null, null);
                    }
                    logger.info("Using fallback facing direction {} for ladder placement", facing.asString());
                }
            }

            for (int i = 0; i < actualHeight; i++) {
                BlockPos pos = new BlockPos(request.x, request.y + i, request.z);

                if (!checkSolidBlockInDirection(sink, pos, facing)) {
                    logger.warn("No solid block to attach ladder at height {} in {} direction, placing anyway", i, facing.asString());
                }

                BlockState ladderState = block.getDefaultState()
                    .with(Properties.HORIZONTAL_FACING, facing);

                sink.setBlockState(pos, ladderState);
                blocksPlaced++;
            }

            Map<String, Integer> startPosition = Map.of("x", request.x, "y", request.y, "z", request.z);
            Map<String, Integer> endPosition = Map.of("x", request.x, "y", request.y + actualHeight - 1, "z", request.z);

            return new LadderResult(true, null, worldKey.getValue().toString(), blocksPlaced, facing.asString(), startPosition, endPosition);
        } catch (Exception e) {
            logger.error("Error placing ladder prefab", e);
            return new LadderResult(false, "Exception during ladder placement: " + e.getMessage(), null, 0, null, null, null);
        }
    }

    // Ladder attachment validation helper methods

    private boolean checkSolidBlockInDirection(BlockSink sink, BlockPos pos, Direction facing) {
        BlockPos attachPos = pos.offset(facing);
        return sink.getBlockState(attachPos).isSolidBlock(sink.world(), attachPos);
    }

    private boolean validateLadderAttachment(BlockSink sink, int x, int y, int z, int height, Direction facing) {
        int attachmentCount = 0;
        for (int i = 0; i < height; i++) {
            BlockPos pos = new BlockPos(x, y + i, z);
            if (checkSolidBlockInDirection(sink, pos, facing)) {
                attachmentCount++;
            }
        }
        return (double) attachmentCount / height >= 0.5;
    }

    private Direction findOptimalLadderFacing(BlockSink sink, int x, int y, int z, int height) {
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction bestFacing = null;
        int bestAttachmentCount = 0;

        for (Direction dir : directions) {
            int attachmentCount = 0;
            for (int i = 0; i < height; i++) {
                BlockPos pos = new BlockPos(x, y + i, z);
                if (checkSolidBlockInDirection(sink, pos, dir)) {
                    attachmentCount++;
                }
            }

            if (attachmentCount > bestAttachmentCount) {
                bestAttachmentCount = attachmentCount;
                bestFacing = dir;
            }
        }

        return bestAttachmentCount > 0 ? bestFacing : null;
    }

    private Direction applyLadderFallbackLogic(BlockSink sink, int x, int y, int z, int height) {
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (Direction dir : directions) {
            for (int i = 0; i < height; i++) {
                BlockPos pos = new BlockPos(x, y + i, z);
                if (checkSolidBlockInDirection(sink, pos, dir)) {
                    logger.info("Fallback: Found minimal attachment in {} direction", dir.asString());
                    return dir;
                }
            }
        }

        for (Direction dir : directions) {
            BlockPos basePos = new BlockPos(x, y, z);
            if (checkSolidBlockInDirection(sink, basePos, dir)) {
                logger.info("Fallback: Found base-level attachment in {} direction", dir.asString());
                return dir;
            }
        }

        for (Direction dir : directions) {
            BlockPos basePos = new BlockPos(x, y, z);
            BlockPos extendedPos = basePos.offset(dir).offset(dir);
            if (sink.getBlockState(extendedPos).isSolidBlock(sink.world(), extendedPos)) {
                logger.info("Fallback: Found extended attachment in {} direction", dir.asString());
                return dir;
            }
        }

        return null;
    }

    // Helper methods
    private int buildStaircase(BlockSink sink, StairRequest req, Block baseBlock, Block stairBlock, Direction staircaseDirection) {
        int blocksPlaced = 0;
        
        // Calculate 3D line from start to end
        Direction.Axis axis = staircaseDirection.getAxis();
        int deltaAxis = axis == Direction.Axis.X
            ? req.end_x - req.start_x
            : req.end_z - req.start_z;
        int deltaY = req.end_y - req.start_y;

        int dx = axis == Direction.Axis.X ? Math.abs(deltaAxis) : 0;
        int dy = Math.abs(deltaY);
        int dz = axis == Direction.Axis.Z ? Math.abs(deltaAxis) : 0;

        Direction horizontalDirection;
        if (axis == Direction.Axis.X) {
            horizontalDirection = deltaAxis == 0
                ? staircaseDirection
                : (deltaAxis > 0 ? Direction.EAST : Direction.WEST);
        } else {
            horizontalDirection = deltaAxis == 0
                ? staircaseDirection
                : (deltaAxis > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        Direction stairBlockFacing = deltaY >= 0
            ? horizontalDirection
            : horizontalDirection.getOpposite();

        // Calculate width from bounding box perpendicular to staircase axis
        int width = axis == Direction.Axis.X
            ? Math.abs(req.end_z - req.start_z) + 1
            : Math.abs(req.end_x - req.start_x) + 1;
        int baseX = Math.min(req.start_x, req.end_x);
        int baseZ = Math.min(req.start_z, req.end_z);
        
        // Use 3D Bresenham-like algorithm
        int x = req.start_x;
        int y = req.start_y;
        int z = req.start_z;
        if (axis == Direction.Axis.X) {
            z = baseZ;
        } else {
            x = baseX;
        }
        int maxSteps = Math.max(Math.max(dx, dy), dz);
        
        int prevY = y;
        for (int i = 0; i <= maxSteps; i++) {
            int nextX = x;
            int nextY = y;
            int nextZ = z;
            if (i < maxSteps) {
                double progress = (double)(i + 1) / maxSteps;
                if (axis == Direction.Axis.X) {
                    nextX = req.start_x + (int)Math.round((req.end_x - req.start_x) * progress);
                    nextZ = baseZ;
                } else {
                    nextX = baseX;
                    nextZ = req.start_z + (int)Math.round((req.end_z - req.start_z) * progress);
                }
                nextY = req.start_y + (int)Math.round((req.end_y - req.start_y) * progress);
            }
            
            // Place a line of blocks across the width
            for (int w = 0; w < width; w++) {
                BlockPos currentPos = axis == Direction.Axis.X
                    ? new BlockPos(x, y, baseZ + w)
                    : new BlockPos(baseX + w, y, z);
                
                // Clear space above for walking (4 blocks)
                sink.setBlockState(currentPos.up(), Blocks.AIR.getDefaultState());
                sink.setBlockState(currentPos.up(2), Blocks.AIR.getDefaultState());
                sink.setBlockState(currentPos.up(3), Blocks.AIR.getDefaultState());
                sink.setBlockState(currentPos.up(4), Blocks.AIR.getDefaultState());

                boolean useStair = (i == 0) ? (nextY != y) : (y != prevY);

                if (useStair) {
                    BlockState stairState = stairBlock.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, stairBlockFacing)
                        .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM)
                        .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
                    sink.setBlockState(currentPos, stairState);
                } else {
                    sink.setBlockState(currentPos, baseBlock.getDefaultState());
                }
                blocksPlaced++;

                if (req.fill_support) {
                    BlockPos fillPos = currentPos.down();
                    int minY = Math.min(req.start_y, req.end_y) - 1;
                    while (fillPos.getY() >= minY && sink.getBlockState(fillPos).isAir()) {
                        sink.setBlockState(fillPos, baseBlock.getDefaultState());
                        blocksPlaced++;
                        fillPos = fillPos.down();
                    }
                }
            }
            
            // Advance to next position using 3D line interpolation
            prevY = y;
            x = nextX;
            y = nextY;
            z = nextZ;
        }
        
        return blocksPlaced;
    }

    private int buildWindowPaneWall(BlockSink sink, WindowPaneRequest req, Block paneBlock) {
        int panesPlaced = 0;

        int wallWidth = Math.abs(req.end_x - req.start_x) + Math.abs(req.end_z - req.start_z) + 1;
        boolean isEastWest = req.start_z == req.end_z;

        for (int y = 0; y < req.height; y++) {
            for (int i = 0; i < wallWidth; i++) {
                BlockPos pos;
                if (isEastWest) {
                    int x = Math.min(req.start_x, req.end_x) + i;
                    pos = new BlockPos(x, req.start_y + y, req.start_z);
                } else {
                    int z = Math.min(req.start_z, req.end_z) + i;
                    pos = new BlockPos(req.start_x, req.start_y + y, z);
                }

                boolean north = shouldConnectNorth(sink, pos, paneBlock);
                boolean south = shouldConnectSouth(sink, pos, paneBlock);
                boolean east = shouldConnectEast(sink, pos, paneBlock);
                boolean west = shouldConnectWest(sink, pos, paneBlock);

                BlockState paneState = paneBlock.getDefaultState()
                    .with(Properties.NORTH, north)
                    .with(Properties.SOUTH, south)
                    .with(Properties.EAST, east)
                    .with(Properties.WEST, west)
                    .with(Properties.WATERLOGGED, req.waterlogged);

                sink.setBlockState(pos, paneState);
                panesPlaced++;
            }
        }

        return panesPlaced;
    }

    private boolean shouldConnectNorth(BlockSink sink, BlockPos pos, Block paneBlock) {
        BlockPos northPos = pos.north();
        BlockState northState = sink.getBlockState(northPos);
        return northState.getBlock() == paneBlock || northState.isSolidBlock(sink.world(), northPos);
    }

    private boolean shouldConnectSouth(BlockSink sink, BlockPos pos, Block paneBlock) {
        BlockPos southPos = pos.south();
        BlockState southState = sink.getBlockState(southPos);
        return southState.getBlock() == paneBlock || southState.isSolidBlock(sink.world(), southPos);
    }

    private boolean shouldConnectEast(BlockSink sink, BlockPos pos, Block paneBlock) {
        BlockPos eastPos = pos.east();
        BlockState eastState = sink.getBlockState(eastPos);
        return eastState.getBlock() == paneBlock || eastState.isSolidBlock(sink.world(), eastPos);
    }

    private boolean shouldConnectWest(BlockSink sink, BlockPos pos, Block paneBlock) {
        BlockPos westPos = pos.west();
        BlockState westState = sink.getBlockState(westPos);
        return westState.getBlock() == paneBlock || westState.isSolidBlock(sink.world(), westPos);
    }

    private SignText createSignText(String[] lines, Boolean glowing) {
        // Ensure we have exactly 4 lines (pad with empty if needed)
        Text[] textLines = new Text[4];
        for (int i = 0; i < 4; i++) {
            if (i < lines.length && lines[i] != null) {
                textLines[i] = Text.literal(lines[i]);
            } else {
                textLines[i] = Text.literal("");
            }
        }

        // filteredMessages - same as regular messages for now
        Text[] filteredLines = new Text[4];
        System.arraycopy(textLines, 0, filteredLines, 0, 4);

        return new SignText(
            textLines,
            filteredLines,
            net.minecraft.util.DyeColor.BLACK, // default color
            glowing != null && glowing // hasGlowingText
        );
    }
}

// Result classes for core operations
record DoorResult(boolean success, String error, String world, int doors_placed, String facing, String hinge, boolean open) {}
record StairResult(boolean success, String error, String world, int blocks_placed, String staircase_direction, boolean fill_support) {}
record WindowPaneResult(boolean success, String error, String world, int panes_placed, String orientation, boolean waterlogged) {}
record TorchResult(boolean success, String error, String world, String block_type, Map<String, Integer> position, boolean wall_mounted, String facing) {}
record SignResult(boolean success, String error, String world, Map<String, Integer> position, String block_type, String sign_type, String facing, Integer rotation, boolean glowing) {}
record LadderResult(boolean success, String error, String world, int blocks_placed, String facing, Map<String, Integer> start_position, Map<String, Integer> end_position) {}
