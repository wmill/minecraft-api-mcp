package com.example;

import com.example.buildtask.repository.BuildRepository;
import com.example.buildtask.repository.PostgreSQLBuildRepository;
import com.example.buildtask.repository.PostgreSQLTaskRepository;
import com.example.buildtask.repository.TaskRepository;
import com.example.buildtask.service.BuildService;
import com.example.buildtask.service.LocationQueryService;
import com.example.database.DatabaseManager;
import com.example.endpoints.*;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

import java.sql.SQLException;


public class APIServer {
    public static Javalin app;
    public static MinecraftServer minecraftServer;
    public static org.slf4j.Logger logger;

    public static void start(MinecraftServer server, org.slf4j.Logger logger) {
        minecraftServer = server;
        APIServer.logger = logger;
        int port = 7070;
        String portOverride = System.getProperty("api.port");
        if (portOverride != null && !portOverride.isBlank()) {
            try {
                port = Integer.parseInt(portOverride);
            } catch (NumberFormatException ignored) {
                port = 7070;
            }
        }
        app = Javalin.create().start(port);

        app.get("/api/test", ctx -> ctx.result("Server is running"));

        // Initialize existing endpoints
        new EntitiesEndpoint(app, server, logger);
        new BlocksEndpoint(app, server, logger);
        new PlayersEndpoint(app, server, logger);
        new MessageEndpoint(app, server, logger);
        new PlayerTeleportEndpoint(app, server, logger);
        new NBTStructureEndpoint(app, server, logger);
        new PrefabEndpoint(app, server, logger);
        
        // Initialize build task management system
        try {
            initializeBuildTaskSystem(app, server, logger);
        } catch (SQLException | RuntimeException e) {
            logger.error("Failed to initialize build task system", e);
            // Continue without build task system if database is not available
            logger.warn("Build task management endpoints will not be available");
        }
    }
    
    private static void initializeBuildTaskSystem(Javalin app, MinecraftServer server, org.slf4j.Logger logger) throws SQLException {
        logger.info("Initializing build task management system...");
        
        // Initialize database
        DatabaseManager databaseManager = DatabaseManager.getInstance();
        databaseManager.initialize();
        
        // Create repositories
        BuildRepository buildRepository = new PostgreSQLBuildRepository(databaseManager.getDatabaseConfig());
        TaskRepository taskRepository = new PostgreSQLTaskRepository(databaseManager.getDatabaseConfig());
        
        // Create task executor
        TaskExecutor taskExecutor = new TaskExecutor(server);
        
        // Create services
        BuildService buildService = new BuildService(buildRepository, taskRepository, taskExecutor);
        LocationQueryService locationQueryService = new LocationQueryService(buildRepository, taskRepository);
        
        // Create and register build task endpoint
        new BuildTaskEndpoint(app, server, logger, buildService, locationQueryService);
        
        logger.info("Build task management system initialized successfully");
    }
}
