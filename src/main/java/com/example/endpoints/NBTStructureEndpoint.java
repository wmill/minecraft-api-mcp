package com.example.endpoints;

import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRotation;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class NBTStructureEndpoint extends APIEndpoint {
    
    public NBTStructureEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        registerEndpoints();
    }
    
    protected void registerEndpoints() {
        app.post("/api/world/structure/place", this::placeStructure);
    }
    
    private void placeStructure(io.javalin.http.Context ctx) {
        try {
            // Get uploaded NBT file
            UploadedFile nbtFile = ctx.uploadedFile("nbt_file");
            if (nbtFile == null) {
                ctx.status(400).json(Map.of("error", "No NBT file uploaded. Use 'nbt_file' field"));
                return;
            }
            
            // Get placement parameters from form data
            String worldName = ctx.formParam("world", "minecraft:overworld");
            int x = Integer.parseInt(ctx.formParam("x", "0"));
            int y = Integer.parseInt(ctx.formParam("y", "64"));
            int z = Integer.parseInt(ctx.formParam("z", "0"));
            String rotationStr = ctx.formParam("rotation", "NONE");
            boolean includeEntities = Boolean.parseBoolean(ctx.formParam("include_entities", "true"));
            boolean replaceBlocks = Boolean.parseBoolean(ctx.formParam("replace_blocks", "true"));
            
            // Parse rotation
            BlockRotation rotation;
            try {
                rotation = BlockRotation.valueOf(rotationStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid rotation: " + rotationStr + 
                    ". Valid values: NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90"));
                return;
            }
            
            // Validate world
            RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(worldName));
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldName));
                return;
            }
            
            LOGGER.info("Placing NBT structure '{}' at ({}, {}, {}) in world {} with rotation {} and entities={}", 
                nbtFile.filename(), x, y, z, worldName, rotation, includeEntities);
            
            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            
            // Execute on server thread
            server.execute(() -> {
                try {
                    // Read NBT data from uploaded file
                    byte[] nbtData = nbtFile.content();
                    ByteArrayInputStream inputStream = new ByteArrayInputStream(nbtData);
                    NbtCompound nbtCompound = NbtIo.readCompressed(inputStream);
                    
                    // Create structure template from NBT data
                    StructureTemplate template = new StructureTemplate();
                    template.readNbt(world.getRegistryManager(), nbtCompound);
                    
                    // Create placement data with settings
                    StructurePlacementData placementData = new StructurePlacementData()
                        .setRotation(rotation)
                        .setIncludeEntities(includeEntities)
                        .setReplaceBlocks(replaceBlocks)
                        .setRandom(Random.create());
                    
                    // Place the structure at the specified position
                    BlockPos pos = new BlockPos(x, y, z);
                    boolean success = template.place(world, pos, pos, placementData, Random.create(), 2);
                    
                    if (success) {
                        // Get structure size for response
                        net.minecraft.util.math.Vec3i size = template.getSize();
                        
                        LOGGER.info("Successfully placed NBT structure '{}' ({}x{}x{}) at ({}, {}, {})", 
                            nbtFile.filename(), size.getX(), size.getY(), size.getZ(), x, y, z);
                        
                        future.complete(Map.of(
                            "success", true,
                            "message", "Structure placed successfully",
                            "filename", nbtFile.filename(),
                            "position", Map.of("x", x, "y", y, "z", z),
                            "world", worldName,
                            "structure_size", Map.of("x", size.getX(), "y", size.getY(), "z", size.getZ()),
                            "rotation", rotation.toString(),
                            "include_entities", includeEntities,
                            "replace_blocks", replaceBlocks
                        ));
                    } else {
                        LOGGER.warn("Failed to place NBT structure '{}' at ({}, {}, {})", 
                            nbtFile.filename(), x, y, z);
                        
                        future.complete(Map.of(
                            "success", false,
                            "error", "Structure placement failed. Check coordinates and world state."
                        ));
                    }
                    
                } catch (IOException e) {
                    LOGGER.error("Error reading NBT file: ", e);
                    future.complete(Map.of("error", "Invalid NBT file format: " + e.getMessage()));
                } catch (Exception e) {
                    LOGGER.error("Error placing structure: ", e);
                    future.complete(Map.of("error", "Exception during structure placement: " + e.getMessage()));
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
                ctx.status(500).json(Map.of("error", "Timeout waiting for structure placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
            
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid coordinate values. x, y, z must be integers"));
        } catch (Exception e) {
            LOGGER.error("Error processing structure placement request: ", e);
            ctx.status(400).json(Map.of("error", "Invalid request: " + e.getMessage()));
        }
    }
}