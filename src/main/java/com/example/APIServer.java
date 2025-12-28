package com.example;

import com.example.endpoints.*;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;


public class APIServer {
    public static Javalin app;
    public static MinecraftServer minecraftServer;
    public static org.slf4j.Logger logger;

    public static void start(MinecraftServer server, org.slf4j.Logger logger) {
        minecraftServer = server;
        app = Javalin.create().start(7070);

        app.get("/", ctx -> ctx.result("Hello World"));

        new EntitiesEndpoint(app, server, logger);
        new BlocksEndpoint(app, server, logger);
        new PlayersEndpoint(app, server, logger);
        new MessageEndpoint(app, server, logger);
        new PlayerTeleportEndpoint(app, server, logger);
        new NBTStructureEndpoint(app, server, logger);
    }
}
