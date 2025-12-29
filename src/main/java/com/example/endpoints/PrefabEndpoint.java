package com.example.endpoints;
import io.javalin.Javalin;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
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

                        // Force overwrite
                        world.setBlockState(basePos, Blocks.AIR.getDefaultState());
                        world.setBlockState(upperPos, Blocks.AIR.getDefaultState());

                        BlockState lowerState = block.getDefaultState()
                            .with(Properties.HORIZONTAL_FACING, facing)
                            .with(Properties.DOOR_HINGE, hinge)
                            .with(Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER)
                            .with(Properties.OPEN, open)
                            .with(Properties.POWERED, false);

                        BlockState upperState = block.getDefaultState()
                            .with(Properties.HORIZONTAL_FACING, facing)
                            .with(Properties.DOOR_HINGE, hinge)
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
}
