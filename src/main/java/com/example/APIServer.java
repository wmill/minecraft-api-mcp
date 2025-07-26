package com.example;

import com.example.endpoints.APIEndpoint;
import com.example.endpoints.EntitiesEndpoint;
import com.example.endpoints.MessageEndpoint;
import com.example.endpoints.PlayersEndpoint;
import com.example.endpoints.PlayerTeleportEndpoint;
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

        APIEndpoint entitiesEndpoint = new EntitiesEndpoint(app, server, logger);
        APIEndpoint blocksEndpoint = new com.example.endpoints.BlocksEndpoint(app, server, logger);
        APIEndpoint playersEndpoint = new PlayersEndpoint(app, server, logger);
        APIEndpoint messageEndpoint = new MessageEndpoint(app, server, logger);
        APIEndpoint teleportEndpoint = new PlayerTeleportEndpoint(app, server, logger);
    }
}
