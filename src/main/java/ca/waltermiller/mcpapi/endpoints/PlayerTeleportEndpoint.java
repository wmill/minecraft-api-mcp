package ca.waltermiller.mcpapi.endpoints;

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
import java.util.concurrent.TimeUnit;

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

            CompletableFuture<TeleportResult> future = new CompletableFuture<>();

            server.execute(() -> {
                try {
                    // Find the player by name
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(request.player_name());
                    if (player == null) {
                        future.complete(new TeleportResult(404, new ErrorResponse("Player not found: " + request.player_name())));
                        return;
                    }

                    // Get the target dimension
                    Identifier dimensionId = Identifier.tryParse(request.dimension());
                    if (dimensionId == null) {
                        future.complete(new TeleportResult(400, new ErrorResponse("Invalid dimension: " + request.dimension())));
                        return;
                    }

                    RegistryKey<World> dimensionKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
                    ServerWorld targetWorld = server.getWorld(dimensionKey);
                    if (targetWorld == null) {
                        future.complete(new TeleportResult(400, new ErrorResponse("Invalid dimension: " + request.dimension())));
                        return;
                    }

                    // Teleport the player
                    player.teleport(targetWorld, request.x(), request.y(), request.z(), Set.of(), request.yaw(), request.pitch(), false);

                    // Create response
                    TeleportResponse response = new TeleportResponse(
                        true,
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

                    future.complete(new TeleportResult(200, response));
                    LOGGER.info("Teleported player {} to ({}, {}, {}) in {}",
                        request.player_name(), request.x(), request.y(), request.z(), request.dimension());
                    
                } catch (Exception e) {
                    LOGGER.error("Error teleporting player: ", e);
                    future.complete(new TeleportResult(500, new ErrorResponse("Failed to teleport player: " + e.getMessage())));
                }
            });

            try {
                TeleportResult result = future.get(10, TimeUnit.SECONDS);
                ctx.status(result.statusCode()).json(result.body());
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(new ErrorResponse("Timeout waiting for teleport operation"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.status(500).json(new ErrorResponse("Teleport operation interrupted"));
            } catch (Exception e) {
                LOGGER.error("Teleport operation failed: ", e);
                ctx.status(500).json(new ErrorResponse("Teleport operation failed: " + e.getMessage()));
            }
            
        } catch (Exception e) {
            LOGGER.error("Error processing teleport request: ", e);
            ctx.status(400).json(new ErrorResponse("Invalid request format: " + e.getMessage()));
        }
    }
    
    public record TeleportResponse(
        boolean success,
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

    private record TeleportResult(int statusCode, Object body) {}

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
