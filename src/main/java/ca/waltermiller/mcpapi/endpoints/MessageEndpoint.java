package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.operation.OperationHttp;
import ca.waltermiller.mcpapi.operation.OperationRegistry;

import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MessageEndpoint extends APIEndpoint {
    private static final int TIMEOUT_SECONDS = 10;

    public MessageEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        // Send message to all players
        app.post("/api/message/broadcast", ctx -> {
            BroadcastMessageRequest req = ctx.bodyAsClass(BroadcastMessageRequest.class);
            
            CompletableFuture<Integer> future = new CompletableFuture<>();
            
            OperationHttp.dispatch(server::execute, () -> {
                try {
                    Text messageText = Text.literal(req.message());
                    int playerCount = server.getPlayerManager().getPlayerList().size();
                    
                    if (playerCount == 0) {
                        future.complete(server.getPlayerManager().getPlayerList().size());
                        return;
                    }
                    
                    // Send to all players
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        player.sendMessage(messageText, req.action_bar());
                    }
                    
                    future.complete(server.getPlayerManager().getPlayerList().size());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, future);
            
            if (OperationHttp.tracked(ctx)) {
                try {
                    OperationHttp.respond(ctx, future, TIMEOUT_SECONDS, count -> new OperationRegistry.Response(200,
                        Map.of("success", true, "message", "Message sent to all players", "player_count", count)));
                } catch (Exception failure) {
                    var response = OperationHttp.failure(failure);
                    ctx.status(response.status()).json(response.body());
                }
                return;
            }
            try { // legacy broadcast wait
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ctx.json(Map.of(
                    "success", true,
                    "message", "Message sent to all players",
                    "player_count", server.getPlayerManager().getPlayerList().size()
                ));
            } catch (TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for broadcast"));
            } catch (Exception e) {
                LOGGER.error("Error broadcasting message", e);
                ctx.status(500).json(Map.of("error", "Failed to send message: " + e.getMessage()));
            }
        });
        
        // Send message to specific player
        app.post("/api/message/player", ctx -> {
            PlayerMessageRequest req = ctx.bodyAsClass(PlayerMessageRequest.class);
            
            CompletableFuture<Integer> future = new CompletableFuture<>();
            
            OperationHttp.dispatch(server::execute, () -> {
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
                    
                    future.complete(server.getPlayerManager().getPlayerList().size());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, future);
            
            if (OperationHttp.tracked(ctx)) {
                try {
                    OperationHttp.respond(ctx, future, TIMEOUT_SECONDS, count -> new OperationRegistry.Response(200,
                        Map.of("success", true, "message", "Message sent to player")));
                } catch (Exception failure) {
                    var response = OperationHttp.failure(failure);
                    ctx.status(response.status()).json(response.body());
                }
                return;
            }
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ctx.json(Map.of(
                    "success", true,
                    "message", "Message sent to player"
                ));
            } catch (TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for player message"));
            } catch (Exception e) {
                LOGGER.error("Error sending message to player", e);
                ctx.status(500).json(Map.of("error", "Failed to send message: " + e.getMessage()));
            }
        });
    }
}

record BroadcastMessageRequest(String message, boolean action_bar) {}

record PlayerMessageRequest(String message, String uuid, String name, boolean action_bar) {}
