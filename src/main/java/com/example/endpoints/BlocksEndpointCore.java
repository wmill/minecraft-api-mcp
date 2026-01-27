package com.example.endpoints;

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

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core block operations that can be used both programmatically and by HTTP endpoints.
 * This class contains the business logic for block operations without Javalin Context dependencies.
 */
public class BlocksEndpointCore {
    private final MinecraftServer server;
    private final org.slf4j.Logger logger;

    public BlocksEndpointCore(MinecraftServer server, org.slf4j.Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    /**
     * Get a list of all available blocks in the registry
     */
    public List<BlockInfo> getBlockList() {
        return Registries.BLOCK.stream()
                .map(block -> new BlockInfo(
                        Registries.BLOCK.getId(block).toString(),
                        block.getTranslationKey()))
                .toList();
    }

    /**
     * Set blocks in a 3D array pattern
     */
    public CompletableFuture<BlockSetResult> setBlocks(BlockSetRequest request) {
        CompletableFuture<BlockSetResult> future = new CompletableFuture<>();
        
        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new BlockSetResult(false, "Unknown world: " + worldKey, 0, 0, null));
            return future;
        }
        
        logger.info("Setting blocks in world {} starting at ({}, {}, {})", 
            worldKey.getValue(), request.start_x, request.start_y, request.start_z);
        
        // Execute on server thread
        server.execute(() -> {
            try {
                int blocksSet = 0;
                int blocksSkipped = 0;
                
                // Iterate through 3D array
                for (int x = 0; x < request.blocks.length; x++) {
                    for (int y = 0; y < request.blocks[x].length; y++) {
                        for (int z = 0; z < request.blocks[x][y].length; z++) {
                            BlockData blockData = request.blocks[x][y][z];
                            
                            // Skip null blocks (no change)
                            if (blockData == null) {
                                blocksSkipped++;
                                continue;
                            }
                            
                            // Convert BlockData to BlockState
                            BlockState blockState = blockData.toBlockState();
                            if (blockState == null) {
                                logger.warn("Invalid block data: {}", blockData.block_name);
                                blocksSkipped++;
                                continue;
                            }
                            
                            // Calculate world position
                            BlockPos pos = new BlockPos(request.start_x + x, request.start_y + y, request.start_z + z);
                            
                            // Set block in world
                            if (world.setBlockState(pos, blockState)) {
                                blocksSet++;
                            } else {
                                logger.warn("Failed to set block {} at {}", blockData.block_name, pos);
                                blocksSkipped++;
                            }
                        }
                    }
                }
                
                logger.info("Block operation completed: {} blocks set, {} blocks skipped", 
                    blocksSet, blocksSkipped);
                
                future.complete(new BlockSetResult(true, null, blocksSet, blocksSkipped, worldKey.getValue().toString()));
                
            } catch (Exception e) {
                logger.error("Error setting blocks", e);
                future.complete(new BlockSetResult(false, "Exception during block setting: " + e.getMessage(), 0, 0, null));
            }
        });
        
        return future;
    }

    /**
     * Get a chunk of blocks from the world
     */
    public CompletableFuture<ChunkResult> getChunk(ChunkRequest request) {
        CompletableFuture<ChunkResult> future = new CompletableFuture<>();
        
        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new ChunkResult(false, "Unknown world: " + worldKey, null, null, null, null));
            return future;
        }
        
        // Validate chunk size limits (prevent huge requests)
        int maxChunkSize = 64; // Maximum 64x64x64 chunk
        if (request.size_x > maxChunkSize || request.size_y > maxChunkSize || request.size_z > maxChunkSize) {
            future.complete(new ChunkResult(false, "Chunk size too large. Maximum size is " + maxChunkSize + " per dimension", null, null, null, null));
            return future;
        }
        
        logger.info("Getting chunk from world {} at ({}, {}, {}) with size {}x{}x{}", 
            worldKey.getValue(), request.start_x, request.start_y, request.start_z, request.size_x, request.size_y, request.size_z);
        
        // Execute on server thread
        server.execute(() -> {
            try {
                // Create 3D array to hold block data
                BlockData[][][] blocks = new BlockData[request.size_x][request.size_y][request.size_z];
                
                // Iterate through the requested chunk area
                for (int x = 0; x < request.size_x; x++) {
                    for (int y = 0; y < request.size_y; y++) {
                        for (int z = 0; z < request.size_z; z++) {
                            // Calculate world position
                            BlockPos pos = new BlockPos(request.start_x + x, request.start_y + y, request.start_z + z);
                            
                            // Get block state at position
                            BlockState blockState = world.getBlockState(pos);
                            
                            // Convert to BlockData format
                            blocks[x][y][z] = BlockData.fromBlockState(blockState);
                        }
                    }
                }
                
                logger.info("Successfully retrieved chunk data: {}x{}x{} blocks", 
                    request.size_x, request.size_y, request.size_z);
                
                Map<String, Integer> startPosition = Map.of("x", request.start_x, "y", request.start_y, "z", request.start_z);
                Map<String, Integer> size = Map.of("x", request.size_x, "y", request.size_y, "z", request.size_z);
                
                future.complete(new ChunkResult(true, null, worldKey.getValue().toString(), startPosition, size, blocks));
                
            } catch (Exception e) {
                logger.error("Error getting chunk data", e);
                future.complete(new ChunkResult(false, "Exception during chunk retrieval: " + e.getMessage(), null, null, null, null));
            }
        });
        
        return future;
    }

    /**
     * Fill a box/cuboid with a specific block type
     */
    public CompletableFuture<FillResult> fillBox(FillBoxRequest request) {
        CompletableFuture<FillResult> future = new CompletableFuture<>();
        
        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new FillResult(false, "Unknown world: " + worldKey, 0, 0, 0, null, null));
            return future;
        }
        
        // Calculate box bounds (ensure min/max are correct)
        int minX = Math.min(request.x1, request.x2);
        int maxX = Math.max(request.x1, request.x2);
        int minY = Math.min(request.y1, request.y2);
        int maxY = Math.max(request.y1, request.y2);
        int minZ = Math.min(request.z1, request.z2);
        int maxZ = Math.max(request.z1, request.z2);
        
        // Calculate box size for validation
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int totalBlocks = sizeX * sizeY * sizeZ;
        
        // Validate box size (prevent huge operations)
        int maxBoxSize = 100000; // Maximum 100k blocks
        if (totalBlocks > maxBoxSize) {
            future.complete(new FillResult(false, "Box too large. Maximum " + maxBoxSize + " blocks allowed", 0, 0, 0, null, null));
            return future;
        }
        
        logger.info("Filling box in world {} from ({}, {}, {}) to ({}, {}, {}) with {} blocks of type {}", 
            worldKey.getValue(), minX, minY, minZ, maxX, maxY, maxZ, totalBlocks, request.block_type);
        
        // Execute on server thread
        server.execute(() -> {
            try {
                // Parse block identifier
                Identifier blockIdentifier = Identifier.tryParse(request.block_type);
                if (blockIdentifier == null) {
                    future.complete(new FillResult(false, "Invalid block identifier: " + request.block_type, 0, 0, 0, null, null));
                    return;
                }
                
                // Get block from registry
                Block block = Registries.BLOCK.get(blockIdentifier);
                if (block == null) {
                    future.complete(new FillResult(false, "Unknown block: " + request.block_type, 0, 0, 0, null, null));
                    return;
                }
                
                BlockState blockState = block.getDefaultState();
                int blocksSet = 0;
                int blocksFailed = 0;

                // Determine flags for setBlockState
                // Block.NOTIFY_ALL (3) is the default, which includes NOTIFY_NEIGHBORS (1) + NOTIFY_LISTENERS (2)
                // When notify_neighbors is false, we skip neighbor updates for better performance
                int flags = request.notify_neighbors ? Block.NOTIFY_ALL : Block.NOTIFY_LISTENERS;

                // Fill the box
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockPos pos = new BlockPos(x, y, z);

                            if (world.setBlockState(pos, blockState, flags)) {
                                blocksSet++;
                            } else {
                                blocksFailed++;
                            }
                        }
                    }
                }
                
                logger.info("Box fill completed: {} blocks set, {} blocks failed", 
                    blocksSet, blocksFailed);
                
                Map<String, Map<String, Integer>> boxBounds = Map.of(
                    "min", Map.of("x", minX, "y", minY, "z", minZ),
                    "max", Map.of("x", maxX, "y", maxY, "z", maxZ)
                );
                
                future.complete(new FillResult(true, null, blocksSet, blocksFailed, totalBlocks, worldKey.getValue().toString(), boxBounds));
                
            } catch (Exception e) {
                logger.error("Error filling box", e);
                future.complete(new FillResult(false, "Exception during box fill: " + e.getMessage(), 0, 0, 0, null, null));
            }
        });
        
        return future;
    }

    /**
     * Get heightmap/topography for a rectangular area
     */
    public CompletableFuture<HeightmapResult> getHeightmap(HeightmapRequest request) {
        CompletableFuture<HeightmapResult> future = new CompletableFuture<>();
        
        // Validate world
        RegistryKey<World> worldKey = request.world != null
            ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.world))
            : World.OVERWORLD;
        
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            future.complete(new HeightmapResult(false, "Unknown world: " + worldKey, null, null, null, null, null, null));
            return future;
        }
        
        // Calculate area bounds (ensure min/max are correct)
        int minX = Math.min(request.x1, request.x2);
        int maxX = Math.max(request.x1, request.x2);
        int minZ = Math.min(request.z1, request.z2);
        int maxZ = Math.max(request.z1, request.z2);
        
        // Calculate area size for validation
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int totalPoints = sizeX * sizeZ;
        
        // Validate area size (prevent huge operations)
        int maxAreaSize = 10000; // Maximum 10k height points (100x100 area)
        if (totalPoints > maxAreaSize) {
            future.complete(new HeightmapResult(false, "Area too large. Maximum " + maxAreaSize + " height points allowed", null, null, null, null, null, null));
            return future;
        }
        
        // Parse heightmap type
        Heightmap.Type heightmapType;
        try {
            heightmapType = request.heightmap_type != null 
                ? Heightmap.Type.valueOf(request.heightmap_type.toUpperCase())
                : Heightmap.Type.WORLD_SURFACE;
        } catch (IllegalArgumentException e) {
            future.complete(new HeightmapResult(false, "Invalid heightmap type: " + request.heightmap_type + 
                ". Valid types: WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR", null, null, null, null, null, null));
            return future;
        }
        
        logger.info("Getting heightmap for world {} from ({}, {}) to ({}, {}) using {}", 
            worldKey.getValue(), minX, minZ, maxX, maxZ, heightmapType);
        
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
                
                logger.info("Successfully generated heightmap: {}x{} points, height range {}-{}", 
                    sizeX, sizeZ, minHeight, maxHeight);
                
                Map<String, Map<String, Integer>> areaBounds = Map.of(
                    "min", Map.of("x", minX, "z", minZ),
                    "max", Map.of("x", maxX, "z", maxZ)
                );
                Map<String, Integer> size = Map.of("x", sizeX, "z", sizeZ);
                Map<String, Integer> heightRange = Map.of("min", minHeight, "max", maxHeight);
                
                future.complete(new HeightmapResult(true, null, worldKey.getValue().toString(), areaBounds, size, heightmapType.toString(), heightRange, heights));
                
            } catch (Exception e) {
                logger.error("Error generating heightmap", e);
                future.complete(new HeightmapResult(false, "Exception during heightmap generation: " + e.getMessage(), null, null, null, null, null, null));
            }
        });
        
        return future;
    }
}

// Result classes for core operations
record BlockSetResult(boolean success, String error, int blocksSet, int blocksSkipped, String world) {}
record ChunkResult(boolean success, String error, String world, Map<String, Integer> startPosition, Map<String, Integer> size, BlockData[][][] blocks) {}
record FillResult(boolean success, String error, int blocksSet, int blocksFailed, int totalBlocks, String world, Map<String, Map<String, Integer>> boxBounds) {}
record HeightmapResult(boolean success, String error, String world, Map<String, Map<String, Integer>> areaBounds, Map<String, Integer> size, String heightmapType, Map<String, Integer> heightRange, int[][] heights) {}