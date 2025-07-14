package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

public class APIEndpoint {
    protected Javalin app;
    protected MinecraftServer server;
//    public abstract void init(Javalin app, MinecraftServer server);
    public APIEndpoint(Javalin app, MinecraftServer server) {
        this.app = app;
        this.server = server;
    }
}
