package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

public class APIServer {
    public static Javalin app;
    public static MinecraftServer minecraftServer;

    public static void start(MinecraftServer server) {
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
                            new Vec3d(player.getX(), player.getY(), player.getZ())))
                    .toArray(PlayerInfo[]::new);
            ctx.json(playerInfos);

        });
    }
}

record PlayerInfo(String name, String uuid, Vec3d position) {
    // This record can be used to return player information in a structured way
}
