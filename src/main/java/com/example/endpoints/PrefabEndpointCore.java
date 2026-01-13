package com.example.endpoints;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
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

        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new DoorResult(false, "Unknown world: " + worldKey, null, 0, null, null, false));
            return future;
        }

        if (request.width <= 0) {
            future.complete(new DoorResult(false, "Width must be positive", null, 0, null, null, false));
            return future;
        }

        Identifier blockId = Identifier.tryParse(request.blockType);
        if (blockId == null) {
            future.complete(new DoorResult(false, "Invalid block identifier", null, 0, null, null, false));
            return future;
        }

        Block block = Registries.BLOCK.get(blockId);
        if (!(block instanceof DoorBlock)) {
            future.complete(new DoorResult(false, "Block is not a door: " + request.blockType, null, 0, null, null, false));
            return future;
        }

        Direction facing = switch(request.facing.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (facing == null || !facing.getAxis().isHorizontal()) {
            future.complete(new DoorResult(false, "Facing must be one of north, south, east, west", null, 0, null, null, false));
            return future;
        }

        DoorHinge hinge = "right".equalsIgnoreCase(request.hinge) ? DoorHinge.RIGHT : DoorHinge.LEFT;
        boolean open = Boolean.TRUE.equals(request.open);

        logger.info("Placing door prefab in world {} at ({}, {}, {}) facing {} width {}", 
            worldKey.getValue(), request.startX, request.startY, request.startZ, facing, request.width);

        // Execute on server thread
        server.execute(() -> {
            try {
                int doorsPlaced = 0;
                Direction lateral = facing.rotateYClockwise(); // extend row to the right of facing

                for (int i = 0; i < request.width; i++) {
                    BlockPos basePos = new BlockPos(
                        request.startX + lateral.getOffsetX() * i,
                        request.startY,
                        request.startZ + lateral.getOffsetZ() * i
                    );
                    BlockPos upperPos = basePos.up();
                    DoorHinge currentHinge = hinge;
                    if (request.doubleDoors && i % 2 == 1) {
                        if (hinge == DoorHinge.RIGHT) {
                            currentHinge = DoorHinge.LEFT;
                        } else {
                            currentHinge = DoorHinge.RIGHT;
                        }
                    }

                    // Force overwrite
                    world.setBlockState(basePos, Blocks.AIR.getDefaultState());
                    world.setBlockState(upperPos, Blocks.AIR.getDefaultState());

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

                    world.setBlockState(basePos, lowerState);
                    world.setBlockState(upperPos, upperState);
                    doorsPlaced++;
                }

                future.complete(new DoorResult(true, null, worldKey.getValue().toString(), doorsPlaced, facing.asString(), hinge == DoorHinge.RIGHT ? "right" : "left", open));
            } catch (Exception e) {
                logger.error("Error placing door prefab", e);
                future.complete(new DoorResult(false, "Exception during door placement: " + e.getMessage(), null, 0, null, null, false));
            }
        });

        return future;
    }

    /**
     * Place stairs prefab
     */
    public CompletableFuture<StairResult> placeStairs(StairRequest request) {
        CompletableFuture<StairResult> future = new CompletableFuture<>();

        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new StairResult(false, "Unknown world: " + worldKey, null, 0, null, false));
            return future;
        }

        Identifier blockId = Identifier.tryParse(request.blockType);
        if (blockId == null) {
            future.complete(new StairResult(false, "Invalid block identifier", null, 0, null, false));
            return future;
        }
        Identifier stairId = Identifier.tryParse(request.stairType);
        if (stairId == null) {
            future.complete(new StairResult(false, "Invalid stair block identifier", null, 0, null, false));
            return future;
        }

        Block baseBlock = Registries.BLOCK.get(blockId);
        Block stairBlock = Registries.BLOCK.get(stairId);
        if (!(stairBlock instanceof StairsBlock)) {
            future.complete(new StairResult(false, "Block is not a stair: " + request.stairType, null, 0, null, false));
            return future;
        }

        Direction staircaseDirection = switch(request.staircaseDirection.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
        if (staircaseDirection == null || !staircaseDirection.getAxis().isHorizontal()) {
            future.complete(new StairResult(false, "staircaseDirection must be one of north, south, east, west", null, 0, null, false));
            return future;
        }

        logger.info("Placing stair prefab in world {} from ({}, {}, {}) to ({}, {}, {}) staircaseDirection {}", 
            worldKey.getValue(), request.startX, request.startY, request.startZ, request.endX, request.endY, request.endZ, staircaseDirection);

        // Execute on server thread
        server.execute(() -> {
            try {
                int blocksPlaced = buildStaircase(world, request, baseBlock, stairBlock, staircaseDirection);
                
                future.complete(new StairResult(true, null, worldKey.getValue().toString(), blocksPlaced, staircaseDirection.asString(), request.fillSupport));
            } catch (Exception e) {
                logger.error("Error placing stair prefab", e);
                future.complete(new StairResult(false, "Exception during stair placement: " + e.getMessage(), null, 0, null, false));
            }
        });

        return future;
    }

    /**
     * Place window pane wall prefab
     */
    public CompletableFuture<WindowPaneResult> placeWindowPane(WindowPaneRequest request) {
        CompletableFuture<WindowPaneResult> future = new CompletableFuture<>();

        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new WindowPaneResult(false, "Unknown world: " + worldKey, null, 0, null, false));
            return future;
        }

        if (request.height <= 0) {
            future.complete(new WindowPaneResult(false, "Height must be positive", null, 0, null, false));
            return future;
        }

        Identifier blockId = Identifier.tryParse(request.blockType);
        if (blockId == null) {
            future.complete(new WindowPaneResult(false, "Invalid block identifier", null, 0, null, false));
            return future;
        }

        Block block = Registries.BLOCK.get(blockId);
        if (!(block instanceof PaneBlock)) {
            future.complete(new WindowPaneResult(false, "Block is not a pane block: " + request.blockType, null, 0, null, false));
            return future;
        }

        // Determine wall orientation
        boolean isEastWest = request.startZ == request.endZ;
        boolean isNorthSouth = request.startX == request.endX;
        
        if (!isEastWest && !isNorthSouth) {
            future.complete(new WindowPaneResult(false, "Window pane wall must be aligned north-south or east-west", null, 0, null, false));
            return future;
        }

        logger.info("Placing window pane wall in world {} from ({}, {}, {}) to ({}, {}, {}) height {}", 
            worldKey.getValue(), request.startX, request.startY, request.startZ, request.endX, request.startY + request.height - 1, request.endZ);

        // Execute on server thread
        server.execute(() -> {
            try {
                int panesPlaced = buildWindowPaneWall(world, request, block);
                
                future.complete(new WindowPaneResult(true, null, worldKey.getValue().toString(), panesPlaced, isEastWest ? "east-west" : "north-south", request.waterlogged));
            } catch (Exception e) {
                logger.error("Error placing window pane wall", e);
                future.complete(new WindowPaneResult(false, "Exception during window pane placement: " + e.getMessage(), null, 0, null, false));
            }
        });

        return future;
    }

    /**
     * Place torch prefab
     */
    public CompletableFuture<TorchResult> placeTorch(TorchRequest request) {
        CompletableFuture<TorchResult> future = new CompletableFuture<>();

        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new TorchResult(false, "Unknown world: " + worldKey, null, null, null, false, null));
            return future;
        }

        Identifier blockId = Identifier.tryParse(request.blockType);
        if (blockId == null) {
            future.complete(new TorchResult(false, "Invalid block identifier", null, null, null, false, null));
            return future;
        }

        Block block = Registries.BLOCK.get(blockId);
        String blockName = blockId.getPath();
        boolean isWallTorch = blockName.contains("wall_torch");

        logger.info("Placing torch in world {} at ({}, {}, {}) type {}",
            worldKey.getValue(), request.x, request.y, request.z, request.blockType);

        // Execute on server thread
        server.execute(() -> {
            try {
                BlockPos pos = new BlockPos(request.x, request.y, request.z);
                BlockState torchState;

                if (isWallTorch) {
                    // Wall torch - needs facing direction
                    Direction facing = null;

                    // If facing is provided, validate and use it
                    if (request.facing != null && !request.facing.isEmpty()) {
                        facing = switch(request.facing.toLowerCase()) {
                            case "north" -> Direction.NORTH;
                            case "south" -> Direction.SOUTH;
                            case "east" -> Direction.EAST;
                            case "west" -> Direction.WEST;
                            default -> null;
                        };

                        if (facing == null) {
                            future.complete(new TorchResult(false, "Invalid facing direction. Must be north, south, east, or west", null, null, null, false, null));
                            return;
                        }

                        // Validate there's a solid block to attach to
                        BlockPos attachPos = pos.offset(facing);
                        if (!world.getBlockState(attachPos).isSolidBlock(world, attachPos)) {
                            // screw the error, just place it anyways
                            // TODO fix this later, floating torches for now
                            logger.error("No solid block to attach wall torch to in " + facing.asString() + " direction, doing it anyways.");
                        }
                    } else {
                        // Auto-detect facing by finding a solid block
                        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
                        for (Direction dir : directions) {
                            BlockPos attachPos = pos.offset(dir);
                            if (world.getBlockState(attachPos).isSolidBlock(world, attachPos)) {
                                facing = dir;
                                break;
                            }
                        }

                        if (facing == null) {
                            future.complete(new TorchResult(false, "No adjacent solid block found to attach wall torch. Please specify facing or provide a wall.", null, null, null, false, null));
                            return;
                        }
                    }

                    // Place wall torch with facing
                    torchState = block.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, facing);

                    world.setBlockState(pos, torchState);

                    Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                    future.complete(new TorchResult(true, null, worldKey.getValue().toString(), request.blockType, position, true, facing.asString()));
                } else {
                    // Regular ground torch - no facing needed
                    torchState = block.getDefaultState();
                    world.setBlockState(pos, torchState);

                    Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                    future.complete(new TorchResult(true, null, worldKey.getValue().toString(), request.blockType, position, false, null));
                }
            } catch (Exception e) {
                logger.error("Error placing torch", e);
                future.complete(new TorchResult(false, "Exception during torch placement: " + e.getMessage(), null, null, null, false, null));
            }
        });

        return future;
    }

    /**
     * Place sign prefab
     */
    public CompletableFuture<SignResult> placeSign(SignRequest request) {
        CompletableFuture<SignResult> future = new CompletableFuture<>();

        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;

        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new SignResult(false, "Unknown world: " + worldKey, null, null, null, null, null, null, false));
            return future;
        }

        Identifier blockId = Identifier.tryParse(request.blockType);
        if (blockId == null) {
            future.complete(new SignResult(false, "Invalid block identifier", null, null, null, null, null, null, false));
            return future;
        }

        Block block = Registries.BLOCK.get(blockId);
        boolean isWallSign = block instanceof WallSignBlock;
        boolean isStandingSign = block instanceof SignBlock && !isWallSign;

        if (!isWallSign && !isStandingSign) {
            future.complete(new SignResult(false, "Block is not a sign: " + request.blockType, null, null, null, null, null, null, false));
            return future;
        }

        // Validate lines (max 4 for front, max 4 for back)
        if (request.frontLines != null && request.frontLines.length > 4) {
            future.complete(new SignResult(false, "Front text can have maximum 4 lines", null, null, null, null, null, null, false));
            return future;
        }
        if (request.backLines != null && request.backLines.length > 4) {
            future.complete(new SignResult(false, "Back text can have maximum 4 lines", null, null, null, null, null, null, false));
            return future;
        }

        logger.info("Placing sign in world {} at ({}, {}, {}) type {}",
            worldKey.getValue(), request.x, request.y, request.z, request.blockType);

        // Execute on server thread
        server.execute(() -> {
            try {
                BlockPos pos = new BlockPos(request.x, request.y, request.z);
                BlockState signState;

                if (isWallSign) {
                    // Wall sign - needs facing direction
                    Direction facing = null;

                    // If facing is provided, validate and use it
                    if (request.facing != null && !request.facing.isEmpty()) {
                        facing = switch(request.facing.toLowerCase()) {
                            case "north" -> Direction.NORTH;
                            case "south" -> Direction.SOUTH;
                            case "east" -> Direction.EAST;
                            case "west" -> Direction.WEST;
                            default -> null;
                        };

                        if (facing == null) {
                            future.complete(new SignResult(false, "Invalid facing direction. Must be north, south, east, or west", null, null, null, null, null, null, false));
                            return;
                        }
                    } else {
                        // Auto-detect facing by finding a solid block
                        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
                        for (Direction dir : directions) {
                            BlockPos attachPos = pos.offset(dir);
                            if (world.getBlockState(attachPos).isSolidBlock(world, attachPos)) {
                                facing = dir;
                                break;
                            }
                        }

                        if (facing == null) {
                            // Default to north if no wall found
                            facing = Direction.NORTH;
                            logger.warn("No adjacent solid block found for wall sign, defaulting to north");
                        }
                    }

                    signState = block.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, facing);

                    world.setBlockState(pos, signState);

                    // Get the sign block entity and set text
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity instanceof SignBlockEntity signBlockEntity) {
                        // Set front text
                        if (request.frontLines != null && request.frontLines.length > 0) {
                            SignText frontText = createSignText(request.frontLines, request.glowing);
                            signBlockEntity.setText(frontText, true);
                        }

                        // Set back text
                        if (request.backLines != null && request.backLines.length > 0) {
                            SignText backText = createSignText(request.backLines, request.glowing);
                            signBlockEntity.setText(backText, false);
                        }

                        signBlockEntity.markDirty();
                    }

                    Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                    future.complete(new SignResult(true, null, worldKey.getValue().toString(), position, request.blockType, "wall", facing.asString(), null, request.glowing != null ? request.glowing : false));
                } else {
                    // Standing sign - use rotation
                    int rotation = request.rotation != null ? request.rotation : 0;

                    // Clamp rotation to 0-15
                    rotation = Math.max(0, Math.min(15, rotation));

                    signState = block.getDefaultState()
                        .with(Properties.ROTATION, rotation);

                    world.setBlockState(pos, signState);

                    // Get the sign block entity and set text
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity instanceof SignBlockEntity signBlockEntity) {
                        // Set front text
                        if (request.frontLines != null && request.frontLines.length > 0) {
                            SignText frontText = createSignText(request.frontLines, request.glowing);
                            signBlockEntity.setText(frontText, true);
                        }

                        // Set back text
                        if (request.backLines != null && request.backLines.length > 0) {
                            SignText backText = createSignText(request.backLines, request.glowing);
                            signBlockEntity.setText(backText, false);
                        }

                        signBlockEntity.markDirty();
                    }

                    Map<String, Integer> position = Map.of("x", request.x, "y", request.y, "z", request.z);
                    future.complete(new SignResult(true, null, worldKey.getValue().toString(), position, request.blockType, "standing", null, rotation, request.glowing != null ? request.glowing : false));
                }
            } catch (Exception e) {
                logger.error("Error placing sign", e);
                future.complete(new SignResult(false, "Exception during sign placement: " + e.getMessage(), null, null, null, null, null, null, false));
            }
        });

        return future;
    }

    // Helper methods
    private int buildStaircase(ServerWorld world, StairRequest req, Block baseBlock, Block stairBlock, Direction staircaseDirection) {
        int blocksPlaced = 0;
        
        // Calculate 3D line from start to end
        int dx = Math.abs(req.endX - req.startX);
        int dy = Math.abs(req.endY - req.startY);
        int dz = Math.abs(req.endZ - req.startZ);
        
        
        // Auto-calculate stair block facing direction from travel vector
        Direction stairBlockFacing;
        boolean isAscending = req.endY > req.startY;
        
        
        if (isAscending) {
            stairBlockFacing = staircaseDirection;
        } else {
            stairBlockFacing = staircaseDirection.getOpposite();
        }

        // Calculate width from bounding box perpendicular to staircase direction
        int width;
        Direction lateralDirection;
        if (staircaseDirection == Direction.NORTH || staircaseDirection == Direction.SOUTH) {
            // Staircase oriented north/south, width is along X axis
            width = dx + 1;
            lateralDirection = Direction.EAST;
        } else {
            // Staircase oriented east/west, width is along Z axis
            width = dz + 1;
            lateralDirection = Direction.SOUTH;
        }
        
        // Use 3D Bresenham-like algorithm
        int x = req.startX, y = req.startY, z = req.startZ;
        int maxSteps = Math.max(Math.max(dx, dy), dz);
        
        int prevY = y;
        for (int i = 0; i <= maxSteps; i++) {
            BlockPos centerPos = new BlockPos(x, y, z);
            int nextX = x;
            int nextY = y;
            int nextZ = z;
            if (i < maxSteps) {
                double progress = (double)(i + 1) / maxSteps;
                nextX = req.startX + (int)Math.round((req.endX - req.startX) * progress);
                nextY = req.startY + (int)Math.round((req.endY - req.startY) * progress);
                nextZ = req.startZ + (int)Math.round((req.endZ - req.startZ) * progress);
            }
            
            // Place a line of blocks across the width
            for (int w = 0; w < width; w++) {
                // Calculate offset from start position along the lateral axis
                int offsetX = lateralDirection == Direction.EAST ? (req.startX + w - x) : 0;
                int offsetZ = lateralDirection == Direction.SOUTH ? (req.startZ + w - z) : 0;
                BlockPos currentPos = centerPos.add(offsetX, 0, offsetZ);
                
                // Clear space above for walking (4 blocks)
                world.setBlockState(currentPos.up(), Blocks.AIR.getDefaultState());
                world.setBlockState(currentPos.up(2), Blocks.AIR.getDefaultState());
                world.setBlockState(currentPos.up(3), Blocks.AIR.getDefaultState());
                world.setBlockState(currentPos.up(4), Blocks.AIR.getDefaultState());
                
                // Determine if we should place a stair or solid block
                // Use stairs when we're ascending/descending, solid blocks for flat sections
                boolean useStair = (i == 0) ? (nextY != y) : (y != prevY);
                
                if (useStair) {
                    // Place stair block with calculated facing direction
                    BlockState stairState = stairBlock.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, stairBlockFacing)
                        .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM)
                        .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
                    world.setBlockState(currentPos, stairState);
                } else {
                    // Place solid block
                    world.setBlockState(currentPos, baseBlock.getDefaultState());
                }
                blocksPlaced++;
                
                // Fill support if requested
                if (req.fillSupport) {
                    BlockPos fillPos = currentPos.down();
                    int minY = Math.min(req.startY, req.endY) - 1;
                    while (fillPos.getY() >= minY && world.getBlockState(fillPos).isAir()) {
                        world.setBlockState(fillPos, baseBlock.getDefaultState());
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

    private int buildWindowPaneWall(ServerWorld world, WindowPaneRequest req, Block paneBlock) {
        int panesPlaced = 0;
        
        // Calculate wall dimensions
        int wallWidth = Math.abs(req.endX - req.startX) + Math.abs(req.endZ - req.startZ) + 1;
        boolean isEastWest = req.startZ == req.endZ;
        
        // Place panes in a rectangular area
        for (int y = 0; y < req.height; y++) {
            for (int i = 0; i < wallWidth; i++) {
                BlockPos pos;
                if (isEastWest) {
                    // East-West wall: varies along X axis
                    int x = Math.min(req.startX, req.endX) + i;
                    pos = new BlockPos(x, req.startY + y, req.startZ);
                } else {
                    // North-South wall: varies along Z axis
                    int z = Math.min(req.startZ, req.endZ) + i;
                    pos = new BlockPos(req.startX, req.startY + y, z);
                }
                
                // Calculate connection states
                boolean north = shouldConnectNorth(world, pos, paneBlock);
                boolean south = shouldConnectSouth(world, pos, paneBlock);
                boolean east = shouldConnectEast(world, pos, paneBlock);
                boolean west = shouldConnectWest(world, pos, paneBlock);
                
                // Create pane state with connections
                BlockState paneState = paneBlock.getDefaultState()
                    .with(Properties.NORTH, north)
                    .with(Properties.SOUTH, south)
                    .with(Properties.EAST, east)
                    .with(Properties.WEST, west)
                    .with(Properties.WATERLOGGED, req.waterlogged);
                
                world.setBlockState(pos, paneState);
                panesPlaced++;
            }
        }
        
        return panesPlaced;
    }
    
    private boolean shouldConnectNorth(ServerWorld world, BlockPos pos, Block paneBlock) {
        BlockPos northPos = pos.north();
        BlockState northState = world.getBlockState(northPos);
        return northState.getBlock() == paneBlock || northState.isSolidBlock(world, northPos);
    }
    
    private boolean shouldConnectSouth(ServerWorld world, BlockPos pos, Block paneBlock) {
        BlockPos southPos = pos.south();
        BlockState southState = world.getBlockState(southPos);
        return southState.getBlock() == paneBlock || southState.isSolidBlock(world, southPos);
    }
    
    private boolean shouldConnectEast(ServerWorld world, BlockPos pos, Block paneBlock) {
        BlockPos eastPos = pos.east();
        BlockState eastState = world.getBlockState(eastPos);
        return eastState.getBlock() == paneBlock || eastState.isSolidBlock(world, eastPos);
    }
    
    private boolean shouldConnectWest(ServerWorld world, BlockPos pos, Block paneBlock) {
        BlockPos westPos = pos.west();
        BlockState westState = world.getBlockState(westPos);
        return westState.getBlock() == paneBlock || westState.isSolidBlock(world, westPos);
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
record DoorResult(boolean success, String error, String world, int doorsPlaced, String facing, String hinge, boolean open) {}
record StairResult(boolean success, String error, String world, int blocksPlaced, String staircaseDirection, boolean fillSupport) {}
record WindowPaneResult(boolean success, String error, String world, int panesPlaced, String orientation, boolean waterlogged) {}
record TorchResult(boolean success, String error, String world, String blockType, Map<String, Integer> position, boolean wallMounted, String facing) {}
record SignResult(boolean success, String error, String world, Map<String, Integer> position, String blockType, String signType, String facing, Integer rotation, boolean glowing) {}