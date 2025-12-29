package com.example.endpoints;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

public class PrefabEndpoint extends APIEndpoint{
    public PrefabEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }
    private void init() {
        registerDoor();
    }
    private void registerDoor(){
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

            // Create future for async response
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

            // TODO rest of setup

            // Execute on server thread
            server.execute(() -> {
                // TODO execute changes
            })
        });
    }
}

class DoorRequest {
    public String world; // optional, defaults to overworld
    public int startX;
    public int startY;
    public int startZ;
    public int width;
    public int height;
    public String facing; // direction door faces (e.g. "north")
    public String blockType; // block identifier (e.g., "minecraft:oak_door")
}
