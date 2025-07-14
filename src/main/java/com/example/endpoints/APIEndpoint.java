package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

public class APIEndpoint {
    protected Javalin app;
    protected MinecraftServer server;
    protected org.slf4j.Logger LOGGER;
//    public abstract void init(Javalin app, MinecraftServer server);
    public APIEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        this.app = app;
        this.server = server;
        this.LOGGER = logger;
    }
}
