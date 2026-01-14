package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MessageEndpoint extends APIEndpoint {
    public MessageEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        // Send message to all players
        app.post("/api/message/broadcast", ctx -> {
            BroadcastMessageRequest req = ctx.bodyAsClass(BroadcastMessageRequest.class);
            
            CompletableFuture<Void> future = new CompletableFuture<>();
            
            server.execute(() -> {
                try {
                    Text messageText = Text.literal(req.message());
                    int playerCount = server.getPlayerManager().getPlayerList().size();
                    
                    if (playerCount == 0) {
                        future.complete(null);
                        return;
                    }
                    
                    // Send to all players
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        player.sendMessage(messageText, req.action_bar());
                    }
                    
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            
            try {
                future.get();
                ctx.json(Map.of(
                    "success", true,
                    "message", "Message sent to all players",
                    "player_count", server.getPlayerManager().getPlayerList().size()
                ));
            } catch (Exception e) {
                LOGGER.error("Error broadcasting message", e);
                ctx.status(500).json(Map.of("error", "Failed to send message: " + e.getMessage()));
            }
        });
        
        // Send message to specific player
        app.post("/api/message/player", ctx -> {
            PlayerMessageRequest req = ctx.bodyAsClass(PlayerMessageRequest.class);
            
            CompletableFuture<Void> future = new CompletableFuture<>();
            
            server.execute(() -> {
                try {
                    ServerPlayerEntity player = null;
                    
                    // Find player by UUID or name
                    if (req.uuid() != null) {
                        UUID playerUuid = UUID.fromString(req.uuid());
                        player = server.getPlayerManager().getPlayer(playerUuid);
                    } else if (req.name() != null) {
                        player = server.getPlayerManager().getPlayer(req.name());
                    }
                    
                    if (player == null) {
                        future.completeExceptionally(new RuntimeException("Player not found"));
                        return;
                    }
                    
                    Text messageText = Text.literal(req.message());
                    player.sendMessage(messageText, req.action_bar());
                    
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            
            try {
                future.get();
                ctx.json(Map.of(
                    "success", true,
                    "message", "Message sent to player"
                ));
            } catch (Exception e) {
                LOGGER.error("Error sending message to player", e);
                ctx.status(500).json(Map.of("error", "Failed to send message: " + e.getMessage()));
            }
        });
    }
}

record BroadcastMessageRequest(String message, boolean action_bar) {}

record PlayerMessageRequest(String message, String uuid, String name, boolean action_bar) {}
