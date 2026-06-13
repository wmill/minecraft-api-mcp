package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.structure.StructureTemplateManager;
import org.jetbrains.annotations.NotNull;

public class NBTStructureEndpoint extends APIEndpoint {

    private BuildService buildService;

    public NBTStructureEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        registerEndpoints();
    }

    public void setBuildService(BuildService buildService) {
        this.buildService = buildService;
    }

    protected void registerEndpoints() {
        app.post("/api/world/structure/place", this::placeStructure);
    }

    public boolean isGzipped(byte @NotNull [] data) {
        // Check for GZIP magic number (0x1F 0x8B)
        boolean isGzipped = data.length >= 2 && (data[0] == (byte) 0x1F) && (data[1] == (byte) 0x8B);
        LOGGER.info("InputStream isGzipped: {}", isGzipped);
        return isGzipped;
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
            String worldName = Objects.requireNonNullElse(ctx.formParam("world"), "minecraft:overworld");
            String xStr = ctx.formParam("x");
            String yStr = ctx.formParam("y");
            String zStr = ctx.formParam("z");
            String rotationStr = Objects.requireNonNullElse(ctx.formParam("rotation"), "NONE");
            String includeEntitiesStr = Objects.requireNonNullElse(ctx.formParam("include_entities"), "true");
            String replaceBlocksStr = Objects.requireNonNullElse(ctx.formParam("replace_blocks"), "true");

            // Parse coordinates
            int x, y, z;
            try {
                x = xStr != null ? Integer.parseInt(xStr) : 0;
                y = yStr != null ? Integer.parseInt(yStr) : 64;
                z = zStr != null ? Integer.parseInt(zStr) : 0;
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid coordinate values. x, y, z must be integers"));
                return;
            }

            // Parse rotation
            BlockRotation rotation;
            try {
                rotation = BlockRotation.valueOf(rotationStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid rotation: " + rotationStr +
                    ". Valid values: NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90"));
                return;
            }

            // Parse boolean parameters
            boolean includeEntities = Boolean.parseBoolean(includeEntitiesStr);
            boolean replaceBlocks = Boolean.parseBoolean(replaceBlocksStr);

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

            byte[] nbtData;

            try {
                nbtData = nbtFile.content().readAllBytes();
            } catch (IOException e) {
                LOGGER.error("Error reading NBT file: ", e);
                ctx.status(500).json(Map.of("error", "Failed to read NBT file: " + e.getMessage()));
                return;
            }
            ByteArrayInputStream inputStream = new ByteArrayInputStream(nbtData);

            NbtCompound nbtCompound;

            // Check if gzipped and follow appropriate reading method
            if (isGzipped(nbtData)) {
                // Read compressed NBT file (e.g., .nbt.gz)
                nbtCompound = NbtIo.readCompressed(inputStream, NbtSizeTracker.ofUnlimitedBytes());
            } else {
                // Read uncompressed NBT file (e.g., .nbt)
                nbtCompound = NbtIo.readCompound(new DataInputStream(inputStream), NbtSizeTracker.ofUnlimitedBytes());
            }

            // Create structure template from NBT data
            StructureTemplateManager structureManager = server.getStructureTemplateManager();
            StructureTemplate template = structureManager.createTemplate(nbtCompound);

            // Create placement data with settings
            StructurePlacementData placementData = new StructurePlacementData()
                .setRotation(rotation)
                .setIgnoreEntities(!includeEntities)
                .setRandom(Random.create());

            // Place the structure at the specified position
            BlockPos pos = new BlockPos(x, y, z);

            // Execute on server thread
            server.execute(() -> {
                try {
                    // Read NBT data from uploaded file

                    boolean success = template.place(world, pos, pos, placementData, Random.create(), 2);

                    if (success) {
                        // Get structure size for response
                        net.minecraft.util.math.Vec3i size = template.getSize();

                        LOGGER.info("Successfully placed NBT structure '{}' ({}x{}x{}) at ({}, {}, {})",
                            nbtFile.filename(), size.getX(), size.getY(), size.getZ(), x, y, z);

                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("message", "Structure placed successfully");
                        response.put("filename", nbtFile.filename());
                        response.put("position", Map.of("x", x, "y", y, "z", z));
                        response.put("world", worldName);
                        response.put("structure_size", Map.of("x", size.getX(), "y", size.getY(), "z", size.getZ()));
                        response.put("rotation", rotation.toString());
                        response.put("include_entities", includeEntities);
                        response.put("replace_blocks", replaceBlocks);

                        if (buildService != null) {
                            try {
                                Build recorded = buildService.recordNbtPlacement(
                                    nbtFile.filename(), worldName, x, y, z,
                                    size.getX(), size.getY(), size.getZ(), rotation.toString());
                                response.put("build_id", recorded.getId().toString());
                            } catch (Exception e) {
                                LOGGER.warn("Failed to record NBT placement in build system: {}", e.getMessage());
                            }
                        }

                        future.complete(response);
                    } else {
                        LOGGER.warn("Failed to place NBT structure '{}' at ({}, {}, {})",
                            nbtFile.filename(), x, y, z);

                        future.complete(Map.of(
                            "success", false,
                            "error", "Structure placement failed. Check coordinates and world state."
                        ));
                    }

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

        } catch (Exception e) {
            LOGGER.error("Error processing structure placement request: ", e);
            ctx.status(400).json(Map.of("error", "Invalid request: " + e.getMessage()));
        }
    }
}