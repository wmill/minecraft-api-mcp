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
    private final BlocksEndpointCore core;
    
    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        this.core = new BlocksEndpointCore(server, logger);
        init();
    }

    private void init() {
        // Define your endpoints here
        app.get("/api/world/blocks/list", ctx -> {
            // Delegate to core method
            var blockInfos = core.getBlockList();
            ctx.json(blockInfos);
        });

        // note, payload will be a JSON three-dimensional array indicating the new block values along with coordinates for where to place the block array
        // that is one set of coordinates for the block, and then a three-dimensional array of block values
        // null will be used to indicate blocks that will not be changed.
        app.post("/api/world/blocks/set", ctx -> {
            BlockSetRequest req = ctx.bodyAsClass(BlockSetRequest.class);
            
            // Delegate to core method
            CompletableFuture<BlockSetResult> future = core.setBlocks(req);
            
            // Wait for result and respond
            try {
                BlockSetResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "blocks_set", result.blocksSet(),
                        "blocks_skipped", result.blocksSkipped(),
                        "world", result.world()
                    ));
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
            
            // Delegate to core method
            CompletableFuture<ChunkResult> future = core.getChunk(req);
            
            // Wait for result and respond
            try {
                ChunkResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "world", result.world(),
                        "start_position", result.startPosition(),
                        "size", result.size(),
                        "blocks", result.blocks()
                    ));
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
            
            // Delegate to core method
            CompletableFuture<FillResult> future = core.fillBox(req);
            
            // Wait for result and respond
            try {
                FillResult result = future.get(30, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "blocks_set", result.blocksSet(),
                        "blocks_failed", result.blocksFailed(),
                        "total_blocks", result.totalBlocks(),
                        "world", result.world(),
                        "box_bounds", result.boxBounds()
                    ));
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
            
            // Delegate to core method
            CompletableFuture<HeightmapResult> future = core.getHeightmap(req);
            
            // Wait for result and respond
            try {
                HeightmapResult result = future.get(30, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "world", result.world(),
                        "area_bounds", result.areaBounds(),
                        "size", result.size(),
                        "heightmap_type", result.heightmapType(),
                        "height_range", result.heightRange(),
                        "heights", result.heights()
                    ));
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for heightmap operation"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    /**
     * Get access to the core block operations for programmatic use
     */
    public BlocksEndpointCore getCore() {
        return core;
    }
}

record BlockInfo(String id, String display_name) {
}

class BlockData {
    public String block_name;
    public Map<String, String> block_states; // optional, uses default states if not provided
    
    // Helper method to create BlockState from this data
    public BlockState toBlockState() {
        Identifier blockId = Identifier.tryParse(block_name);
        if (blockId == null) return null;

        Block block = Registries.BLOCK.get(blockId);
        if (block == null) return null;

        BlockState state = block.getDefaultState();

        // Apply block states if provided
        if (block_states != null) {
            for (Map.Entry<String, String> entry : block_states.entrySet()) {
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
        data.block_name = Registries.BLOCK.getId(blockState.getBlock()).toString();
        data.block_states = new HashMap<>();

        // Extract all block state properties
        for (Property<?> property : blockState.getProperties()) {
            data.block_states.put(property.getName(), blockState.get(property).toString());
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
    public int start_x;
    public int start_y;
    public int start_z;
    public BlockData[][][] blocks; // 3D array of block data objects, null means no change
}

class ChunkRequest {
    public String world; // optional, defaults to overworld
    public int start_x;
    public int start_y;
    public int start_z;
    public int size_x;
    public int size_y;
    public int size_z;
}

class FillBoxRequest {
    public String world; // optional, defaults to overworld
    public int x1, y1, z1; // first corner coordinate
    public int x2, y2, z2; // second corner coordinate
    public String block_type; // block identifier (e.g., "minecraft:stone")
}

class HeightmapRequest {
    public String world; // optional, defaults to overworld
    public int x1, z1; // first corner coordinate (only X and Z needed for heightmap)
    public int x2, z2; // second corner coordinate (only X and Z needed for heightmap)
    public String heightmap_type; // optional, defaults to WORLD_SURFACE
    // Valid types: WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR
}
