package ca.waltermiller.mcpapi;

import ca.waltermiller.mcpapi.buildtask.repository.BuildRepository;
import ca.waltermiller.mcpapi.buildtask.repository.PostgreSQLBuildRepository;
import ca.waltermiller.mcpapi.buildtask.repository.PostgreSQLRailPlanningJobRepository;
import ca.waltermiller.mcpapi.buildtask.repository.PostgreSQLTaskRepository;
import ca.waltermiller.mcpapi.buildtask.repository.RailPlanningJobRepository;
import ca.waltermiller.mcpapi.buildtask.repository.TaskRepository;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import ca.waltermiller.mcpapi.buildtask.service.LocationQueryService;
import ca.waltermiller.mcpapi.buildtask.service.RailPlanningService;
import ca.waltermiller.mcpapi.database.DatabaseManager;
import ca.waltermiller.mcpapi.endpoints.BlocksEndpoint;
import ca.waltermiller.mcpapi.endpoints.BuildTaskEndpoint;
import ca.waltermiller.mcpapi.endpoints.EntitiesEndpoint;
import ca.waltermiller.mcpapi.endpoints.MessageEndpoint;
import ca.waltermiller.mcpapi.endpoints.NBTStructureEndpoint;
import ca.waltermiller.mcpapi.endpoints.PlayerTeleportEndpoint;
import ca.waltermiller.mcpapi.endpoints.PlayersEndpoint;
import ca.waltermiller.mcpapi.endpoints.PrefabEndpoint;
import ca.waltermiller.mcpapi.endpoints.RainFireEndpoint;
import ca.waltermiller.mcpapi.endpoints.TaskExecutor;
import ca.waltermiller.mcpapi.endpoints.TerrainSmoothEndpoint;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.endpoints.AreaLockEndpoint;
import ca.waltermiller.mcpapi.snapshot.SnapshotStore;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

import java.sql.SQLException;


public class APIServer {
    public static final int DEFAULT_PORT = 7070;

    public static Javalin app;
    public static MinecraftServer minecraftServer;
    public static org.slf4j.Logger logger;
    public static ca.waltermiller.mcpapi.marker.AreaMarkerService markers;
    public static ca.waltermiller.mcpapi.operation.OperationRegistry operations;
    private static BuildService buildService;
    private static RailPlanningService railPlanningService;
    private static java.util.concurrent.CompletableFuture<Void> stopping = java.util.concurrent.CompletableFuture.completedFuture(null);

    public static synchronized void start(MinecraftServer server, org.slf4j.Logger logger) {
        if (app != null || !stopping.isDone()) throw new IllegalStateException("API server is already started or stopping");
        try { startOwned(server, logger); }
        catch (RuntimeException | Error failure) { stop(); throw failure; }
    }

    private static void startOwned(MinecraftServer server, org.slf4j.Logger logger) {
        minecraftServer = server;
        APIServer.logger = logger;
        int port = DEFAULT_PORT;
        String portOverride = System.getProperty("api.port");
        if (portOverride != null && !portOverride.isBlank()) {
            try {
                port = Integer.parseInt(portOverride);
            } catch (NumberFormatException e) {
                logger.warn("Invalid api.port value '{}', falling back to {}", portOverride, DEFAULT_PORT);
            }
        }
        AreaLockService locks = new AreaLockService();
        var surveyOccupancy = new ca.waltermiller.mcpapi.survey.SurveyOccupancy();
        app = Javalin.create();
        operations = new ca.waltermiller.mcpapi.operation.OperationRegistry();
        ca.waltermiller.mcpapi.operation.OperationHttp.install(app, operations);
        logger.info("Web server started on port {}", port);

        app.get("/api/test", ctx -> ctx.result("Server is running"));

        new AreaLockEndpoint(app, server, locks);
        markers = new ca.waltermiller.mcpapi.marker.AreaMarkerService();
        new ca.waltermiller.mcpapi.endpoints.AreaMarkerEndpoint(app, server, markers);

        // Initialize existing endpoints
        new EntitiesEndpoint(app, server, logger);
        new BlocksEndpoint(app, server, logger, locks);
        new ca.waltermiller.mcpapi.endpoints.SiteSurveyEndpoint(app, server, locks, surveyOccupancy);
        new PlayersEndpoint(app, server, logger);
        new MessageEndpoint(app, server, logger);
        new PlayerTeleportEndpoint(app, server, logger);
        SnapshotStore snapshots = null;
        try {
            snapshots = SnapshotStore.fromEnvironment(
                server.getRunDirectory() != null ? server.getRunDirectory() : java.nio.file.Path.of(""));
        } catch (IllegalArgumentException e) {
            logger.warn("Undo snapshots disabled: {}", e.getMessage());
        }
        NBTStructureEndpoint nbtEndpoint = new NBTStructureEndpoint(app, server, logger, locks, surveyOccupancy, snapshots);
        new PrefabEndpoint(app, server, logger, locks);
        new RainFireEndpoint(app, server, logger, locks);
        TerrainSmoothEndpoint terrainEndpoint = new TerrainSmoothEndpoint(app, server, logger, locks, surveyOccupancy, snapshots);

        // Initialize build task management system
        try {
            initializeBuildTaskSystem(app, server, logger, nbtEndpoint, terrainEndpoint, locks, surveyOccupancy, snapshots);
        } catch (SQLException | RuntimeException e) {
            logger.error("Failed to initialize build task system", e);
            // Continue without build task system if database is not available
            logger.warn("Build task management endpoints will not be available");
            if (buildService != null) { buildService.shutdown(); buildService = null; }
            if (railPlanningService != null) { railPlanningService.shutdown(); railPlanningService = null; }
            DatabaseManager.getInstance().shutdown();
        }
        app.start(port);
    }
    
    private static void initializeBuildTaskSystem(Javalin app, MinecraftServer server, org.slf4j.Logger logger,
                                                   NBTStructureEndpoint nbtEndpoint, TerrainSmoothEndpoint terrainEndpoint,
                                                   AreaLockService locks,
                                                   ca.waltermiller.mcpapi.survey.SurveyOccupancy surveyOccupancy,
                                                   SnapshotStore snapshots) throws SQLException {
        logger.info("Initializing build task management system...");
        
        // Initialize database
        DatabaseManager databaseManager = DatabaseManager.getInstance();
        databaseManager.initialize();
        
        // Create repositories
        BuildRepository buildRepository = new PostgreSQLBuildRepository(databaseManager.getDatabaseConfig());
        TaskRepository taskRepository = new PostgreSQLTaskRepository(databaseManager.getDatabaseConfig());
        RailPlanningJobRepository railPlanningJobRepository = new PostgreSQLRailPlanningJobRepository(databaseManager.getDatabaseConfig());
        
        // Create task executor
        TaskExecutor taskExecutor = new TaskExecutor(server, locks);
        
        // Create services
        buildService = new BuildService(buildRepository, taskRepository, taskExecutor);
        LocationQueryService locationQueryService = new LocationQueryService(buildRepository, taskRepository);
        railPlanningService = new RailPlanningService(
            buildRepository, buildService, railPlanningJobRepository, server);
        
        // Wire build service into NBT endpoint for spatial record-keeping
        nbtEndpoint.setBuildService(buildService);
        terrainEndpoint.setBuildService(buildService);
        surveyOccupancy.setBuildRepository(buildRepository);

        // Create and register build task endpoint
        new BuildTaskEndpoint(app, server, logger, buildService, locationQueryService, railPlanningService, taskExecutor,
            locks, snapshots);

        logger.info("Build task management system initialized successfully");
    }

    public static synchronized void stop() {
        if (!stopping.isDone()) return;
        if (app == null && operations == null) return;
        if (operations != null) operations.closeAdmission();
        Javalin http = app;
        BuildService builds = buildService;
        RailPlanningService rails = railPlanningService;
        var registry = operations;
        var log = logger;
        app = null;
        buildService = null;
        railPlanningService = null;
        if (builds != null) builds.shutdown();
        if (rails != null) rails.shutdown();
        if (markers != null) { markers.clear(); markers = null; }
        minecraftServer = null;
        logger = null;
        // Never block Fabric's server thread on callbacks which need that thread.
        stopping = new java.util.concurrent.CompletableFuture<>();
        Thread cleanup = new Thread(() -> {
            try {
                if (http != null) http.stop();
                if (registry != null) registry.close();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                boolean settled = true;
                if (builds != null) settled &= builds.awaitTermination(deadline);
                if (rails != null) settled &= rails.awaitTermination(deadline);
                if (registry != null) settled &= registry.workers.awaitTermination(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                if (!settled && log != null) log.warn("API shutdown left unresolved background work after ten seconds");
            } catch (Exception failure) {
                if (log != null) log.error("API shutdown failed", failure);
            } finally {
                if (registry != null) registry.close();
                DatabaseManager.getInstance().shutdown();
                synchronized (APIServer.class) { operations = null; stopping.complete(null); }
            }
        }, "mcp-api-shutdown");
        cleanup.start();
    }

    public static synchronized java.util.concurrent.CompletableFuture<Void> stopped() { return stopping; }
}
