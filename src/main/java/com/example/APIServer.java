package com.example;

import com.example.endpoints.APIEndpoint;
import com.example.endpoints.EntitiesEndpoint;
import com.example.endpoints.PlayersEndpoint;
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

        APIEndpoint entitiesEndpoint = new EntitiesEndpoint(app, server, logger);
        APIEndpoint blocksEndpoint = new com.example.endpoints.BlocksEndpoint(app, server, logger);
        APIEndpoint playersEndpoint = new PlayersEndpoint(app, server, logger);
    }
}
