package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import net.minecraft.state.property.Property;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BlocksEndpoint extends APIEndpoint {
    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        // Define your endpoints here
        app.get("/api/world/blocks/list", ctx -> {
            // Map over Registries.BLOCK and return a list of BlockInfo
            var blockInfos = Registries.BLOCK.stream()
                    .map(block -> new BlockInfo(
                            Registries.BLOCK.getId(block).toString(),
                            block.getTranslationKey()))
                    .toList();
            ctx.json(blockInfos);
        });

        // note, payload will be a JSON three-dimensional array indicating the new block values along with coordinates for where to place the block array
        // that is one set of coordinates for the block, and then a three-dimensional array of block values
        // null will be used to indicate blocks that will not be changed.
        app.post("/api/world/blocks/set", ctx -> {
            BlockSetRequest req = ctx.bodyAsClass(BlockSetRequest.class);
            
            // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }
            
            LOGGER.info("Setting blocks in world {} starting at ({}, {}, {})", 
                worldKey.getValue(), req.startX, req.startY, req.startZ);
            
            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            
            // Execute on server thread
            server.execute(() -> {
                try {
                    int blocksSet = 0;
                    int blocksSkipped = 0;
                    
                    // Iterate through 3D array
                    for (int x = 0; x < req.blocks.length; x++) {
                        for (int y = 0; y < req.blocks[x].length; y++) {
                            for (int z = 0; z < req.blocks[x][y].length; z++) {
                                BlockData blockData = req.blocks[x][y][z];
                                
                                // Skip null blocks (no change)
                                if (blockData == null) {
                                    blocksSkipped++;
                                    continue;
                                }
                                
                                // Convert BlockData to BlockState
                                BlockState blockState = blockData.toBlockState();
                                if (blockState == null) {
                                    LOGGER.warn("Invalid block data: {}", blockData.blockName);
                                    blocksSkipped++;
                                    continue;
                                }
                                
                                // Calculate world position
                                BlockPos pos = new BlockPos(req.startX + x, req.startY + y, req.startZ + z);
                                
                                // Set block in world
                                if (world.setBlockState(pos, blockState)) {
                                    blocksSet++;
                                } else {
                                    LOGGER.warn("Failed to set block {} at {}", blockData.blockName, pos);
                                    blocksSkipped++;
                                }
                            }
                        }
                    }
                    
                    LOGGER.info("Block operation completed: {} blocks set, {} blocks skipped", 
                        blocksSet, blocksSkipped);
                    
                    future.complete(Map.of(
                        "success", true,
                        "blocks_set", blocksSet,
                        "blocks_skipped", blocksSkipped,
                        "world", worldKey.getValue().toString()
                    ));
                    
                } catch (Exception e) {
                    LOGGER.error("Error setting blocks", e);
                    future.complete(Map.of("error", "Exception during block setting: " + e.getMessage()));
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
                ctx.status(500).json(Map.of("error", "Timeout waiting for block operation"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // get a chunk of blocks, payload will be a JSON object with the chunk coordinates and size to grab
        // response will be a three-dimensional array of block values
        app.post("/api/world/blocks/chunk", ctx -> {
            ChunkRequest req = ctx.bodyAsClass(ChunkRequest.class);
            
            // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }
            
            // Validate chunk size limits (prevent huge requests)
            int maxChunkSize = 64; // Maximum 64x64x64 chunk
            if (req.sizeX > maxChunkSize || req.sizeY > maxChunkSize || req.sizeZ > maxChunkSize) {
                ctx.status(400).json(Map.of("error", "Chunk size too large. Maximum size is " + maxChunkSize + " per dimension"));
                return;
            }
            
            LOGGER.info("Getting chunk from world {} at ({}, {}, {}) with size {}x{}x{}", 
                worldKey.getValue(), req.startX, req.startY, req.startZ, req.sizeX, req.sizeY, req.sizeZ);
            
            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            
            // Execute on server thread
            server.execute(() -> {
                try {
                    // Create 3D array to hold block data
                    BlockData[][][] blocks = new BlockData[req.sizeX][req.sizeY][req.sizeZ];
                    
                    // Iterate through the requested chunk area
                    for (int x = 0; x < req.sizeX; x++) {
                        for (int y = 0; y < req.sizeY; y++) {
                            for (int z = 0; z < req.sizeZ; z++) {
                                // Calculate world position
                                BlockPos pos = new BlockPos(req.startX + x, req.startY + y, req.startZ + z);
                                
                                // Get block state at position
                                BlockState blockState = world.getBlockState(pos);
                                
                                // Convert to BlockData format
                                blocks[x][y][z] = BlockData.fromBlockState(blockState);
                            }
                        }
                    }
                    
                    LOGGER.info("Successfully retrieved chunk data: {}x{}x{} blocks", 
                        req.sizeX, req.sizeY, req.sizeZ);
                    
                    future.complete(Map.of(
                        "success", true,
                        "world", worldKey.getValue().toString(),
                        "start_position", Map.of("x", req.startX, "y", req.startY, "z", req.startZ),
                        "size", Map.of("x", req.sizeX, "y", req.sizeY, "z", req.sizeZ),
                        "blocks", blocks
                    ));
                    
                } catch (Exception e) {
                    LOGGER.error("Error getting chunk data", e);
                    future.complete(Map.of("error", "Exception during chunk retrieval: " + e.getMessage()));
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
                ctx.status(500).json(Map.of("error", "Timeout waiting for chunk operation"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // Fill a box/cuboid with a specific block type between two coordinates
        app.post("/api/world/blocks/fill", ctx -> {
            FillBoxRequest req = ctx.bodyAsClass(FillBoxRequest.class);
            
            // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }
            
            // Calculate box bounds (ensure min/max are correct)
            int minX = Math.min(req.x1, req.x2);
            int maxX = Math.max(req.x1, req.x2);
            int minY = Math.min(req.y1, req.y2);
            int maxY = Math.max(req.y1, req.y2);
            int minZ = Math.min(req.z1, req.z2);
            int maxZ = Math.max(req.z1, req.z2);
            
            // Calculate box size for validation
            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            int totalBlocks = sizeX * sizeY * sizeZ;
            
            // Validate box size (prevent huge operations)
            int maxBoxSize = 100000; // Maximum 100k blocks
            if (totalBlocks > maxBoxSize) {
                ctx.status(400).json(Map.of("error", "Box too large. Maximum " + maxBoxSize + " blocks allowed"));
                return;
            }
            
            LOGGER.info("Filling box in world {} from ({}, {}, {}) to ({}, {}, {}) with {} blocks of type {}", 
                worldKey.getValue(), minX, minY, minZ, maxX, maxY, maxZ, totalBlocks, req.blockType);
            
            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            
            // Execute on server thread
            server.execute(() -> {
                try {
                    // Parse block identifier
                    Identifier blockIdentifier = Identifier.tryParse(req.blockType);
                    if (blockIdentifier == null) {
                        future.complete(Map.of("error", "Invalid block identifier: " + req.blockType));
                        return;
                    }
                    
                    // Get block from registry
                    Block block = Registries.BLOCK.get(blockIdentifier);
                    if (block == null) {
                        future.complete(Map.of("error", "Unknown block: " + req.blockType));
                        return;
                    }
                    
                    BlockState blockState = block.getDefaultState();
                    int blocksSet = 0;
                    int blocksFailed = 0;
                    
                    // Fill the box
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                
                                if (world.setBlockState(pos, blockState)) {
                                    blocksSet++;
                                } else {
                                    blocksFailed++;
                                }
                            }
                        }
                    }
                    
                    LOGGER.info("Box fill completed: {} blocks set, {} blocks failed", 
                        blocksSet, blocksFailed);
                    
                    future.complete(Map.of(
                        "success", true,
                        "blocks_set", blocksSet,
                        "blocks_failed", blocksFailed,
                        "total_blocks", totalBlocks,
                        "world", worldKey.getValue().toString(),
                        "box_bounds", Map.of(
                            "min", Map.of("x", minX, "y", minY, "z", minZ),
                            "max", Map.of("x", maxX, "y", maxY, "z", maxZ)
                        )
                    ));
                    
                } catch (Exception e) {
                    LOGGER.error("Error filling box", e);
                    future.complete(Map.of("error", "Exception during box fill: " + e.getMessage()));
                }
            });
            
            // Wait for result and respond
            try {
                Map<String, Object> result = future.get(30, TimeUnit.SECONDS);
                if (result.containsKey("error")) {
                    ctx.status(500).json(result);
                } else {
                    ctx.json(result);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for box fill operation"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });

        // Get heightmap/topography for a rectangular area
        app.post("/api/world/blocks/heightmap", ctx -> {
            HeightmapRequest req = ctx.bodyAsClass(HeightmapRequest.class);
            
            // Validate world
            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }
            
            // Calculate area bounds (ensure min/max are correct)
            int minX = Math.min(req.x1, req.x2);
            int maxX = Math.max(req.x1, req.x2);
            int minZ = Math.min(req.z1, req.z2);
            int maxZ = Math.max(req.z1, req.z2);
            
            // Calculate area size for validation
            int sizeX = maxX - minX + 1;
            int sizeZ = maxZ - minZ + 1;
            int totalPoints = sizeX * sizeZ;
            
            // Validate area size (prevent huge operations)
            int maxAreaSize = 10000; // Maximum 10k height points (100x100 area)
            if (totalPoints > maxAreaSize) {
                ctx.status(400).json(Map.of("error", "Area too large. Maximum " + maxAreaSize + " height points allowed"));
                return;
            }
            
            // Parse heightmap type
            Heightmap.Type heightmapType;
            try {
                heightmapType = req.heightmapType != null 
                    ? Heightmap.Type.valueOf(req.heightmapType.toUpperCase())
                    : Heightmap.Type.WORLD_SURFACE;
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid heightmap type: " + req.heightmapType + 
                    ". Valid types: WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR"));
                return;
            }
            
            LOGGER.info("Getting heightmap for world {} from ({}, {}) to ({}, {}) using {}", 
                worldKey.getValue(), minX, minZ, maxX, maxZ, heightmapType);
            
            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            
            // Execute on server thread
            server.execute(() -> {
                try {
                    // Create 2D array to hold height data
                    int[][] heights = new int[sizeX][sizeZ];
                    int minHeight = Integer.MAX_VALUE;
                    int maxHeight = Integer.MIN_VALUE;
                    
                    // Iterate through the area and get height at each point
                    for (int x = 0; x < sizeX; x++) {
                        for (int z = 0; z < sizeZ; z++) {
                            int worldX = minX + x;
                            int worldZ = minZ + z;
                            
                            // Get height using Minecraft's heightmap
                            int height = world.getTopY(heightmapType, worldX, worldZ);
                            heights[x][z] = height;
                            
                            // Track min/max for statistics
                            minHeight = Math.min(minHeight, height);
                            maxHeight = Math.max(maxHeight, height);
                        }
                    }
                    
                    LOGGER.info("Successfully generated heightmap: {}x{} points, height range {}-{}", 
                        sizeX, sizeZ, minHeight, maxHeight);
                    
                    future.complete(Map.of(
                        "success", true,
                        "world", worldKey.getValue().toString(),
                        "area_bounds", Map.of(
                            "min", Map.of("x", minX, "z", minZ),
                            "max", Map.of("x", maxX, "z", maxZ)
                        ),
                        "size", Map.of("x", sizeX, "z", sizeZ),
                        "heightmap_type", heightmapType.toString(),
                        "height_range", Map.of("min", minHeight, "max", maxHeight),
                        "heights", heights
                    ));
                    
                } catch (Exception e) {
                    LOGGER.error("Error generating heightmap", e);
                    future.complete(Map.of("error", "Exception during heightmap generation: " + e.getMessage()));
                }
            });
            
            // Wait for result and respond
            try {
                Map<String, Object> result = future.get(30, TimeUnit.SECONDS);
                if (result.containsKey("error")) {
                    ctx.status(500).json(result);
                } else {
                    ctx.json(result);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for heightmap operation"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }
}

record BlockInfo(String id, String display_name) {
}

class BlockData {
    public String blockName;
    public Map<String, String> blockStates; // optional, uses default states if not provided
    
    // Helper method to create BlockState from this data
    public BlockState toBlockState() {
        Identifier blockId = Identifier.tryParse(blockName);
        if (blockId == null) return null;
        
        Block block = Registries.BLOCK.get(blockId);
        if (block == null) return null;
        
        BlockState state = block.getDefaultState();
        
        // Apply block states if provided
        if (blockStates != null) {
            for (Map.Entry<String, String> entry : blockStates.entrySet()) {
                String propertyName = entry.getKey();
                String value = entry.getValue();
                
                // Find the property in the block's state definition
                for (Property<?> property : state.getProperties()) {
                    if (property.getName().equals(propertyName)) {
                        try {
                            var parsedValue = property.parse(value);
                            if (parsedValue.isPresent()) {
                                state = setBlockStateProperty(state, property, parsedValue.get());
                            }
                        } catch (Exception e) {
                            // Invalid property value, skip this property
                        }
                        break;
                    }
                }
            }
        }
        
        return state;
    }
    
    // Helper method to create BlockData from BlockState
    public static BlockData fromBlockState(BlockState blockState) {
        BlockData data = new BlockData();
        data.blockName = Registries.BLOCK.getId(blockState.getBlock()).toString();
        data.blockStates = new HashMap<>();
        
        // Extract all block state properties
        for (Property<?> property : blockState.getProperties()) {
            data.blockStates.put(property.getName(), blockState.get(property).toString());
        }
        
        return data;
    }
    
    // Helper method to handle generic type casting for block state properties
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setBlockStateProperty(BlockState state, Property<T> property, Comparable<?> value) {
        return state.with(property, (T) value);
    }
}

class BlockSetRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public BlockData[][][] blocks; // 3D array of block data objects, null means no change
}

class ChunkRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public int sizeX;
    public int sizeY;
    public int sizeZ;
}

class FillBoxRequest {
    public String world; // optional, defaults to overworld
    public int x1, y1, z1; // first corner coordinate
    public int x2, y2, z2; // second corner coordinate
    public String blockType; // block identifier (e.g., "minecraft:stone")
}

class HeightmapRequest {
    public String world; // optional, defaults to overworld
    public int x1, z1; // first corner coordinate (only X and Z needed for heightmap)
    public int x2, z2; // second corner coordinate (only X and Z needed for heightmap)
    public String heightmapType; // optional, defaults to WORLD_SURFACE
    // Valid types: WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR
}
