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

import java.util.Map;
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
                                String blockId = req.blocks[x][y][z];
                                
                                // Skip null blocks (no change)
                                if (blockId == null) {
                                    blocksSkipped++;
                                    continue;
                                }
                                
                                // Parse block identifier
                                Identifier blockIdentifier = Identifier.tryParse(blockId);
                                if (blockIdentifier == null) {
                                    LOGGER.warn("Invalid block identifier: {}", blockId);
                                    blocksSkipped++;
                                    continue;
                                }
                                
                                // Get block from registry
                                Block block = Registries.BLOCK.get(blockIdentifier);
                                if (block == null) {
                                    LOGGER.warn("Unknown block: {}", blockId);
                                    blocksSkipped++;
                                    continue;
                                }
                                
                                // Calculate world position
                                BlockPos pos = new BlockPos(req.startX + x, req.startY + y, req.startZ + z);
                                BlockState blockState = block.getDefaultState();
                                
                                // Set block in world
                                if (world.setBlockState(pos, blockState)) {
                                    blocksSet++;
                                } else {
                                    LOGGER.warn("Failed to set block {} at {}", blockId, pos);
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
        app.get("/api/world/blocks/chunk", ctx -> {
            // TODO - Implement logic to get a chunk of blocks

        });
    }
}

record BlockInfo(String id, String display_name) {
}

class BlockSetRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public String[][][] blocks; // 3D array of block IDs, null means no change
}
