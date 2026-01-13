package com.example.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database schema initialization and management.
 * Creates tables, indexes, and other database objects required for the build task management system.
 */
public class DatabaseSchema {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSchema.class);
    
    private final DatabaseConfig databaseConfig;
    
    public DatabaseSchema(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }
    
    /**
     * Initialize the database schema by creating all required tables and indexes.
     * This method is idempotent - it can be called multiple times safely.
     */
    public void initializeSchema() throws SQLException {
        LOGGER.info("Initializing database schema...");
        
        try (Connection connection = databaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            
            try {
                createBuildsTable(connection);
                createBuildTasksTable(connection);
                createIndexes(connection);
                
                connection.commit();
                LOGGER.info("Database schema initialized successfully");
                
            } catch (SQLException e) {
                connection.rollback();
                LOGGER.error("Failed to initialize database schema, rolling back", e);
                throw e;
            }
        }
    }
    
    private void createBuildsTable(Connection connection) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS builds (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                name VARCHAR(255),
                description TEXT,
                status VARCHAR(50) NOT NULL DEFAULT 'created',
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                completed_at TIMESTAMP WITH TIME ZONE,
                world VARCHAR(255) NOT NULL DEFAULT 'minecraft:overworld'
            )
            """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            LOGGER.debug("Created builds table");
        }
    }
    
    private void createBuildTasksTable(Connection connection) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS build_tasks (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                build_id UUID NOT NULL,
                task_order INTEGER NOT NULL,
                task_type VARCHAR(50) NOT NULL,
                task_data JSONB NOT NULL,
                status VARCHAR(50) NOT NULL DEFAULT 'queued',
                executed_at TIMESTAMP WITH TIME ZONE,
                error_message TEXT,
                
                -- Coordinate tracking for location queries
                min_x INTEGER,
                min_y INTEGER,
                min_z INTEGER,
                max_x INTEGER,
                max_y INTEGER,
                max_z INTEGER,
                
                UNIQUE(build_id, task_order)
            )
            """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            LOGGER.debug("Created build_tasks table");
        }
        
        // Add foreign key constraint separately to handle existing data
        String fkSql = """
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints 
                    WHERE constraint_name = 'build_tasks_build_id_fkey'
                ) THEN
                    ALTER TABLE build_tasks 
                    ADD CONSTRAINT build_tasks_build_id_fkey 
                    FOREIGN KEY (build_id) REFERENCES builds(id) ON DELETE CASCADE;
                END IF;
            END $$
            """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(fkSql);
            LOGGER.debug("Added foreign key constraint for build_tasks");
        }
    }
    
    private void createIndexes(Connection connection) throws SQLException {
        String[] indexes = {
            "CREATE INDEX IF NOT EXISTS idx_builds_status ON builds(status)",
            "CREATE INDEX IF NOT EXISTS idx_builds_world ON builds(world)",
            "CREATE INDEX IF NOT EXISTS idx_builds_created_at ON builds(created_at)",
            "CREATE INDEX IF NOT EXISTS idx_tasks_build_id ON build_tasks(build_id)",
            "CREATE INDEX IF NOT EXISTS idx_tasks_status ON build_tasks(status)",
            "CREATE INDEX IF NOT EXISTS idx_tasks_coordinates ON build_tasks(min_x, min_y, min_z, max_x, max_y, max_z)",
            // Spatial index for efficient location queries using box type
            "CREATE INDEX IF NOT EXISTS idx_tasks_location_query ON build_tasks USING GIST (box(point(min_x, min_z), point(max_x, max_z)))"
        };
        
        try (Statement stmt = connection.createStatement()) {
            for (String indexSql : indexes) {
                stmt.execute(indexSql);
            }
            LOGGER.debug("Created database indexes");
        }
    }
    
    /**
     * Check if the schema is properly initialized by verifying table existence.
     */
    public boolean isSchemaInitialized() {
        try (Connection connection = databaseConfig.getConnection()) {
            String sql = """
                SELECT COUNT(*) FROM information_schema.tables 
                WHERE table_name IN ('builds', 'build_tasks') 
                AND table_schema = 'public'
                """;
            
            try (Statement stmt = connection.createStatement();
                 var rs = stmt.executeQuery(sql)) {
                
                if (rs.next()) {
                    return rs.getInt(1) == 2; // Both tables should exist
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to check schema initialization status", e);
        }
        return false;
    }
    
    /**
     * Drop all tables (for testing purposes).
     * WARNING: This will delete all data!
     */
    public void dropSchema() throws SQLException {
        LOGGER.warn("Dropping database schema - all data will be lost!");
        
        try (Connection connection = databaseConfig.getConnection()) {
            connection.setAutoCommit(false);
            
            try {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS build_tasks CASCADE");
                    stmt.execute("DROP TABLE IF EXISTS builds CASCADE");
                }
                
                connection.commit();
                LOGGER.info("Database schema dropped successfully");
                
            } catch (SQLException e) {
                connection.rollback();
                LOGGER.error("Failed to drop database schema, rolling back", e);
                throw e;
            }
        }
    }
}