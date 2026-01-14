package com.example.endpoints;

import io.javalin.Javalin;
import io.javalin.http.Context;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class PlayerTeleportEndpoint extends APIEndpoint {
    
    public PlayerTeleportEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        registerEndpoints();
    }
    
    protected void registerEndpoints() {
        app.post("/api/players/teleport", this::teleportPlayer);
    }
    
    private void teleportPlayer(Context ctx) {
        try {
            TeleportRequest request = ctx.bodyAsClass(TeleportRequest.class);
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // Find the player by name
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(request.player_name());
                    if (player == null) {
                        ctx.status(404).json(new ErrorResponse("Player not found: " + request.player_name()));
                        return;
                    }

                    // Get the target dimension
                    RegistryKey<World> dimensionKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(request.dimension()));
                    ServerWorld targetWorld = server.getWorld(dimensionKey);
                    if (targetWorld == null) {
                        ctx.status(400).json(new ErrorResponse("Invalid dimension: " + request.dimension()));
                        return;
                    }

                    // Teleport the player
                    player.teleport(targetWorld, request.x(), request.y(), request.z(), Set.of(), request.yaw(), request.pitch(), false);

                    // Create response
                    TeleportResponse response = new TeleportResponse(
                        "success",
                        "Player " + request.player_name() + " teleported successfully",
                        request.player_name(),
                        request.x(),
                        request.y(),
                        request.z(),
                        request.dimension(),
                        request.yaw(),
                        request.pitch()
                    );

                    ctx.json(response);
                    LOGGER.info("Teleported player {} to ({}, {}, {}) in {}",
                        request.player_name(), request.x(), request.y(), request.z(), request.dimension());
                    
                } catch (Exception e) {
                    LOGGER.error("Error teleporting player: ", e);
                    ctx.status(500).json(new ErrorResponse("Failed to teleport player: " + e.getMessage()));
                }
            }, server::execute);
            
            // Wait for completion with timeout handling
            future.exceptionally(throwable -> {
                LOGGER.error("Teleport operation failed: ", throwable);
                ctx.status(500).json(new ErrorResponse("Teleport operation failed: " + throwable.getMessage()));
                return null;
            });
            
        } catch (Exception e) {
            LOGGER.error("Error processing teleport request: ", e);
            ctx.status(400).json(new ErrorResponse("Invalid request format: " + e.getMessage()));
        }
    }
    
    public record TeleportResponse(
        String status,
        String message,
        String player_name,
        double x,
        double y,
        double z,
        String dimension,
        float yaw,
        float pitch
    ) {}

    public record ErrorResponse(String error) {}

    public record TeleportRequest(
        String player_name,
        double x,
        double y,
        double z,
        String dimension,
        float yaw,
        float pitch
    ) {
        public TeleportRequest {
            if (player_name == null || player_name.trim().isEmpty()) {
                throw new IllegalArgumentException("Player name cannot be null or empty");
            }
            if (dimension == null || dimension.trim().isEmpty()) {
                dimension = "minecraft:overworld";
            }
        }

        public TeleportRequest(String player_name, double x, double y, double z) {
            this(player_name, x, y, z, "minecraft:overworld", 0.0f, 0.0f);
        }

        public TeleportRequest(String player_name, double x, double y, double z, String dimension) {
            this(player_name, x, y, z, dimension, 0.0f, 0.0f);
        }
}
}

