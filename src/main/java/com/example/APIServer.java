package com.example;

import com.example.endpoints.APIEndpoint;
import com.example.endpoints.EntitiesEndpoint;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;


public class APIServer {
    public static Javalin app;
    public static MinecraftServer minecraftServer;
    public static org.slf4j.Logger logger;

    public static void start(MinecraftServer server, org.slf4j.Logger logger) {
        minecraftServer = server;
        Javalin app = Javalin.create().start(7070);

        app.get("/", ctx -> ctx.result("Hello World"));
        app.post("/spawn", ctx -> {
            String mob = ctx.body();
            // call into your Fabric code here
            ctx.result("Spawning " + mob);
        });
        app.get("/players", ctx -> {
//            StringBuilder players = new StringBuilder();
//            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
//                players.append(player.getName()).append("\n");
//            }
//            ctx.result(players.toString());
            //ObjectMapper mapper = new ObjectMapper();
            PlayerInfo[] playerInfos = server.getPlayerManager().getPlayerList().stream()
                    .map(player -> new PlayerInfo(player.getName().getString(), player.getUuidAsString(),
                            new Position(player.getX(), player.getY(), player.getZ())))
                    .toArray(PlayerInfo[]::new);
            ctx.json(playerInfos);

        });

        APIEndpoint entitiesEndpoint = new EntitiesEndpoint(app, server, logger);
    }
}

record Position(double x, double y, double z) {
    // Simple position record for JSON serialization
}

record PlayerInfo(String name, String uuid, Position position) {
    // This record can be used to return player information in a structured way
}
