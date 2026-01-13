package com.example.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Main database manager that coordinates database configuration and schema initialization.
 * This class should be initialized once during application startup.
 */
public class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);
    
    private static DatabaseManager instance;
    private final DatabaseConfig databaseConfig;
    private final DatabaseSchema databaseSchema;
    private boolean initialized = false;
    
    private DatabaseManager() {
        this.databaseConfig = DatabaseConfig.getInstance();
        this.databaseSchema = new DatabaseSchema(databaseConfig);
    }
    
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }
    
    /**
     * Initialize the database system: test connection and set up schema.
     * This method should be called during application startup.
     */
    public void initialize() throws SQLException {
        if (initialized) {
            LOGGER.info("Database manager already initialized");
            return;
        }
        
        LOGGER.info("Initializing database manager...");
        
        try {
            // Test database connection
            databaseConfig.testConnection();
            LOGGER.info("Database connection established successfully");
            
            // Initialize schema if needed
            if (!databaseSchema.isSchemaInitialized()) {
                LOGGER.info("Database schema not found, initializing...");
                databaseSchema.initializeSchema();
            } else {
                LOGGER.info("Database schema already exists");
            }
            
            initialized = true;
            LOGGER.info("Database manager initialized successfully");
            
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database manager", e);
            throw e;
        }
    }
    
    /**
     * Get the database configuration instance.
     */
    public DatabaseConfig getDatabaseConfig() {
        return databaseConfig;
    }
    
    /**
     * Get the database schema instance.
     */
    public DatabaseSchema getDatabaseSchema() {
        return databaseSchema;
    }
    
    /**
     * Check if the database system is healthy and ready for use.
     */
    public boolean isHealthy() {
        return initialized && databaseConfig.isHealthy() && databaseSchema.isSchemaInitialized();
    }
    
    /**
     * Shutdown the database system gracefully.
     */
    public void shutdown() {
        if (initialized) {
            LOGGER.info("Shutting down database manager...");
            databaseConfig.shutdown();
            initialized = false;
            LOGGER.info("Database manager shut down successfully");
        }
    }
    
    /**
     * Reset the singleton instance (for testing purposes only).
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
    
    /**
     * Force schema reinitialization (for testing purposes).
     * WARNING: This will delete all existing data!
     */
    public void reinitializeSchema() throws SQLException {
        LOGGER.warn("Reinitializing database schema - all data will be lost!");
        databaseSchema.dropSchema();
        databaseSchema.initializeSchema();
        LOGGER.info("Database schema reinitialized");
    }
}