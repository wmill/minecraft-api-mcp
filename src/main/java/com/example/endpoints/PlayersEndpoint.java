package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

public class PlayersEndpoint extends APIEndpoint {
    public PlayersEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        app.get("/api/world/players", ctx -> {
            PlayerInfo[] playerInfos = server.getPlayerManager().getPlayerList().stream()
                    .map(player -> new PlayerInfo(player.getName().getString(), player.getUuidAsString(),
                            new Position(player.getX(), player.getY(), player.getZ())))
                    .toArray(PlayerInfo[]::new);
            ctx.json(playerInfos);
        });
    }
}

record Position(double x, double y, double z) {
}

record PlayerInfo(String name, String uuid, Position position) {
}