package com.example.endpoints;
import io.javalin.Javalin;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.BlockState;
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

public class PrefabEndpoint extends APIEndpoint{
    public PrefabEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }
    private void init() {
        registerDoor();
        registerStairs();
        registerWindowPane();
    }
    private void registerDoor() {
        app.post("/api/world/prefabs/door", ctx -> {
            DoorRequest req = ctx.bodyAsClass(DoorRequest.class);

            // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }

            if (req.width <= 0) {
                ctx.status(400).json(Map.of("error", "Width must be positive"));
                return;
            }

            Identifier blockId = Identifier.tryParse(req.blockType);
            if (blockId == null) {
                ctx.status(400).json(Map.of("error", "Invalid block identifier"));
                return;
            }

            Block block = Registries.BLOCK.get(blockId);
            if (!(block instanceof DoorBlock)) {
                ctx.status(400).json(Map.of("error", "Block is not a door: " + req.blockType));
                return;
            }

            Direction facing = switch(req.facing.toLowerCase()) {
                case "north" -> Direction.NORTH;
                case "south" -> Direction.SOUTH;
                case "east" -> Direction.EAST;
                case "west" -> Direction.WEST;
                default -> null;
            };
            if (facing == null || !facing.getAxis().isHorizontal()) {
                ctx.status(400).json(Map.of("error", "Facing must be one of north, south, east, west"));
                return;
            }

            DoorHinge hinge = "right".equalsIgnoreCase(req.hinge) ? DoorHinge.RIGHT : DoorHinge.LEFT;
            boolean open = Boolean.TRUE.equals(req.open);


            LOGGER.info("Placing door prefab in world {} at ({}, {}, {}) facing {} width {}", 
                worldKey.getValue(), req.startX, req.startY, req.startZ, facing, req.width);

            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

            // Execute on server thread
            server.execute(() -> {
                try {
                    int doorsPlaced = 0;
                    Direction lateral = facing.rotateYClockwise(); // extend row to the right of facing

                    for (int i = 0; i < req.width; i++) {
                        BlockPos basePos = new BlockPos(
                            req.startX + lateral.getOffsetX() * i,
                            req.startY,
                            req.startZ + lateral.getOffsetZ() * i
                        );
                        BlockPos upperPos = basePos.up();
                        DoorHinge currentHinge = hinge;
                        if (req.doubleDoors && i % 2 == 1) {
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

                    future.complete(Map.of(
                        "success", true,
                        "world", worldKey.getValue().toString(),
                        "doors_placed", doorsPlaced,
                        "facing", facing.asString(),
                        "hinge", hinge == DoorHinge.RIGHT ? "right" : "left",
                        "open", open
                    ));
                } catch (Exception e) {
                    LOGGER.error("Error placing door prefab", e);
                    future.complete(Map.of("error", "Exception during door placement: " + e.getMessage()));
                }
            });

            // Wait for result and respond
            try {
                Map<String, Object> result = future.get(10, TimeUnit.SECONDS);
                if (result.containsKey("error")) {
                    ctx.status(500).json(result);
                } else {
                    ctx.json(result);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for door placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private void registerStairs() {
        app.post("/api/world/prefabs/stairs", ctx -> {
            StairRequest req = ctx.bodyAsClass(StairRequest.class);

                        // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }

            Identifier blockId = Identifier.tryParse(req.blockType);
            if (blockId == null) {
                ctx.status(400).json(Map.of("error", "Invalid block identifier"));
                return;
            }
            Identifier stairId = Identifier.tryParse(req.stairType);
            if (stairId == null) {
                ctx.status(400).json(Map.of("error", "Invalid stair block identifier"));
                return;
            }

            Block baseBlock = Registries.BLOCK.get(blockId);
            Block stairBlock = Registries.BLOCK.get(stairId);
            if (!(stairBlock instanceof StairsBlock)) {
                ctx.status(400).json(Map.of("error", "Block is not a stair: " + req.stairType));
                return;
            }

            Direction staircaseDirection = switch(req.staircaseDirection.toLowerCase()) {
                case "north" -> Direction.NORTH;
                case "south" -> Direction.SOUTH;
                case "east" -> Direction.EAST;
                case "west" -> Direction.WEST;
                default -> null;
            };
            if (staircaseDirection == null || !staircaseDirection.getAxis().isHorizontal()) {
                ctx.status(400).json(Map.of("error", "staircaseDirection must be one of north, south, east, west"));
                return;
            }

            LOGGER.info("Placing stair prefab in world {} from ({}, {}, {}) to ({}, {}, {}) staircaseDirection {}", 
                worldKey.getValue(), req.startX, req.startY, req.startZ, req.endX, req.endY, req.endZ, staircaseDirection);

            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

            // Execute on server thread
            server.execute(() -> {
                try {
                    int blocksPlaced = buildStaircase(world, req, baseBlock, stairBlock, staircaseDirection);
                    
                    future.complete(Map.of(
                        "success", true,
                        "world", worldKey.getValue().toString(),
                        "blocks_placed", blocksPlaced,
                        "staircaseDirection", staircaseDirection.asString(),
                        "fill_support", req.fillSupport
                    ));
                } catch (Exception e) {
                    LOGGER.error("Error placing stair prefab", e);
                    future.complete(Map.of("error", "Exception during stair placement: " + e.getMessage()));
                }
            });

            // Wait for result and respond
            try {
                Map<String, Object> result = future.get(10, TimeUnit.SECONDS);
                if (result.containsKey("error")) {
                    ctx.status(500).json(result);
                } else {
                    ctx.json(result);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for stair placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private int buildStaircase(ServerWorld world, StairRequest req, Block baseBlock, Block stairBlock, Direction staircaseDirection) {
        int blocksPlaced = 0;
        
        // Calculate 3D line from start to end
        int dx = Math.abs(req.endX - req.startX);
        int dy = Math.abs(req.endY - req.startY);
        int dz = Math.abs(req.endZ - req.startZ);
        
        
        // Auto-calculate stair block facing direction from travel vector
        Direction stairBlockFacing;
        boolean isAscending = req.endY > req.startY;
        

        // wtf was claude doing
        // if (Math.abs(req.endX - req.startX) > Math.abs(req.endZ - req.startZ)) {
        //     // Primary movement along X axis
        //     Direction horizontalDirection = req.endX > req.startX ? Direction.EAST : Direction.WEST;
        //     stairBlockFacing = isAscending ? horizontalDirection : horizontalDirection.getOpposite();
        // } else {
        //     // Primary movement along Z axis
        //     Direction horizontalDirection = req.endZ > req.startZ ? Direction.SOUTH : Direction.NORTH;
        //     stairBlockFacing = isAscending ? horizontalDirection : horizontalDirection.getOpposite();
        // }
        
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
        
        for (int i = 0; i <= maxSteps; i++) {
            BlockPos centerPos = new BlockPos(x, y, z);
            
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
                boolean useStair = (i <= maxSteps) && (req.startY != req.endY);
                
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
            if (i < maxSteps) {
                double progress = (double)(i + 1) / maxSteps;
                x = req.startX + (int)Math.round((req.endX - req.startX) * progress);
                y = req.startY + (int)Math.round((req.endY - req.startY) * progress);
                z = req.startZ + (int)Math.round((req.endZ - req.startZ) * progress);
            }
        }
        
        return blocksPlaced;
    }

    private void registerWindowPane() {
        app.post("/api/world/prefabs/window-pane", ctx -> {
            WindowPaneRequest req = ctx.bodyAsClass(WindowPaneRequest.class);

            // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }

            if (req.height <= 0) {
                ctx.status(400).json(Map.of("error", "Height must be positive"));
                return;
            }

            Identifier blockId = Identifier.tryParse(req.blockType);
            if (blockId == null) {
                ctx.status(400).json(Map.of("error", "Invalid block identifier"));
                return;
            }

            Block block = Registries.BLOCK.get(blockId);
            if (!(block instanceof PaneBlock)) {
                ctx.status(400).json(Map.of("error", "Block is not a pane block: " + req.blockType));
                return;
            }

            // Determine wall orientation
            boolean isEastWest = req.startZ == req.endZ;
            boolean isNorthSouth = req.startX == req.endX;
            
            if (!isEastWest && !isNorthSouth) {
                ctx.status(400).json(Map.of("error", "Window pane wall must be aligned north-south or east-west"));
                return;
            }

            LOGGER.info("Placing window pane wall in world {} from ({}, {}, {}) to ({}, {}, {}) height {}", 
                worldKey.getValue(), req.startX, req.startY, req.startZ, req.endX, req.startY + req.height - 1, req.endZ);

            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

            // Execute on server thread
            server.execute(() -> {
                try {
                    int panesPlaced = buildWindowPaneWall(world, req, block);
                    
                    future.complete(Map.of(
                        "success", true,
                        "world", worldKey.getValue().toString(),
                        "panes_placed", panesPlaced,
                        "orientation", isEastWest ? "east-west" : "north-south",
                        "waterlogged", req.waterlogged
                    ));
                } catch (Exception e) {
                    LOGGER.error("Error placing window pane wall", e);
                    future.complete(Map.of("error", "Exception during window pane placement: " + e.getMessage()));
                }
            });

            // Wait for result and respond
            try {
                Map<String, Object> result = future.get(10, TimeUnit.SECONDS);
                if (result.containsKey("error")) {
                    ctx.status(500).json(result);
                } else {
                    ctx.json(result);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for window pane placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
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
}

class DoorRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public int width = 1; // number of doors to place in a row
    public String facing; // direction door faces (e.g. "north")
    public String blockType; // block identifier (e.g., "minecraft:oak_door")
    public String hinge = "left"; // "left" or "right"
    public Boolean open = false; // whether the door starts open
    public Boolean doubleDoors = false; // pair up the doors by reversing hinges
}

class StairRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public int endX;
    public int endY;
    public int endZ;
    public String blockType; // block identifier (e.g., "minecraft:oak_block")
    public String stairType; // block identifier (e.g., "minecraft:oak_stairs")
    public String staircaseDirection; // orientation of the staircase structure (e.g. "north")
    public boolean fillSupport = false; // fill underneath the staircase
}

class WindowPaneRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public int endX;   // defines the wall endpoint
    public int endZ;   // defines the wall endpoint
    public int height; // Y dimension (how tall the wall is)
    public String blockType; // e.g., "minecraft:glass_pane", "minecraft:iron_bars"
    public boolean waterlogged = false;
}