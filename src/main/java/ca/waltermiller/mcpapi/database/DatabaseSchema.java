package ca.waltermiller.mcpapi.database;

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

    private static final String[] TABLE_NAMES = {"builds", "build_tasks", "build_sources"};

    private final DatabaseConfig databaseConfig;

    public DatabaseSchema(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    private void execute(Connection connection, String sql, String logMessage) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            LOGGER.debug(logMessage);
        }
    }

    /**
     * Adds a foreign key from childTable.build_id to builds(id) if the named constraint
     * does not exist yet. Done separately from CREATE TABLE to handle existing data.
     */
    private void addBuildForeignKeyIfMissing(Connection connection, String childTable) throws SQLException {
        String constraintName = childTable + "_build_id_fkey";
        String fkSql = """
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE constraint_name = '%s'
                ) THEN
                    ALTER TABLE %s
                    ADD CONSTRAINT %s
                    FOREIGN KEY (build_id) REFERENCES builds(id) ON DELETE CASCADE;
                END IF;
            END $$
            """.formatted(constraintName, childTable, constraintName);

        execute(connection, fkSql, "Added foreign key constraint for " + childTable);
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
                removeLegacyRailData(connection);
                createBuildSourcesTable(connection);
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

        execute(connection, sql, "Created builds table");
        // Revision lineage: the build this one replaced in place. Added separately for existing databases.
        execute(connection, "ALTER TABLE builds ADD COLUMN IF NOT EXISTS parent_build_id UUID "
            + "REFERENCES builds(id) ON DELETE SET NULL", "Added builds.parent_build_id");
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
                description TEXT,
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

        execute(connection, sql, "Created build_tasks table");
        addBuildForeignKeyIfMissing(connection, "build_tasks");
    }

    /**
     * Minecart rail planning was removed (see git tag {@code last-minecart-railways}). Deletes leftover
     * RAIL_* tasks, which no longer map to a {@code TaskType}, renumbers the affected queues to
     * {@code 0..N-1}, and drops the planner's job table. Idempotent: a no-op once the data is gone.
     */
    private void removeLegacyRailData(Connection connection) throws SQLException {
        java.util.List<Object> affected = new java.util.ArrayList<>();
        String delete = """
            DELETE FROM build_tasks
            WHERE task_type IN ('RAIL_SURFACE_SEGMENT', 'RAIL_BRIDGE_SEGMENT', 'RAIL_TUNNEL_SEGMENT')
            RETURNING build_id
            """;
        int removed = 0;
        try (Statement stmt = connection.createStatement(); var rs = stmt.executeQuery(delete)) {
            while (rs.next()) {
                removed++;
                Object buildId = rs.getObject(1);
                if (!affected.contains(buildId)) affected.add(buildId);
            }
        }
        if (removed > 0) {
            // Two passes through negative values so UNIQUE(build_id, task_order) never sees a collision.
            String renumber = """
                UPDATE build_tasks t SET task_order = -1 - o.rn
                FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY build_id ORDER BY task_order, id) - 1 AS rn
                      FROM build_tasks WHERE build_id = ANY(?)) o
                WHERE t.id = o.id
                """;
            try (var ps = connection.prepareStatement(renumber)) {
                ps.setArray(1, connection.createArrayOf("uuid", affected.toArray()));
                ps.executeUpdate();
            }
            try (var ps = connection.prepareStatement(
                    "UPDATE build_tasks SET task_order = -1 - task_order WHERE build_id = ANY(?) AND task_order < 0")) {
                ps.setArray(1, connection.createArrayOf("uuid", affected.toArray()));
                ps.executeUpdate();
            }
            LOGGER.warn("Removed {} legacy rail task(s) from {} build(s)", removed, affected.size());
        }
        execute(connection, "DROP TABLE IF EXISTS rail_planning_jobs", "Dropped legacy rail_planning_jobs table");
    }
    
    /**
     * Where a placed structure came from (Starlark source and props, schematic id), one row per build,
     * so a placement can be inspected and revised after the compiler's artifact cache evicts it.
     */
    private void createBuildSourcesTable(Connection connection) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS build_sources (
                build_id UUID PRIMARY KEY REFERENCES builds(id) ON DELETE CASCADE,
                kind VARCHAR(32) NOT NULL,
                artifact_id VARCHAR(64),
                schematic_id VARCHAR(255),
                source TEXT,
                entry VARCHAR(255),
                props JSONB,
                root_size JSONB,
                y_offset INTEGER,
                target JSONB,
                details JSONB,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            )
            """;

        execute(connection, sql, "Created build_sources table");
    }

    private void createIndexes(Connection connection) throws SQLException {
        String[] indexes = {
            "CREATE INDEX IF NOT EXISTS idx_builds_status ON builds(status)",
            "CREATE INDEX IF NOT EXISTS idx_builds_world ON builds(world)",
            "CREATE INDEX IF NOT EXISTS idx_builds_created_at ON builds(created_at)",
            "CREATE INDEX IF NOT EXISTS idx_tasks_build_id ON build_tasks(build_id)",
            "CREATE INDEX IF NOT EXISTS idx_tasks_status ON build_tasks(status)",
            "CREATE INDEX IF NOT EXISTS idx_tasks_coordinates ON build_tasks(min_x, min_y, min_z, max_x, max_y, max_z)",
            "CREATE INDEX IF NOT EXISTS idx_builds_parent ON builds(parent_build_id)",
            "CREATE INDEX IF NOT EXISTS idx_build_sources_artifact ON build_sources(artifact_id)",
            "CREATE INDEX IF NOT EXISTS idx_build_sources_schematic ON build_sources(schematic_id)",
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
            String tableList = "'" + String.join("', '", TABLE_NAMES) + "'";
            String sql = """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_name IN (%s)
                AND table_schema = 'public'
                """.formatted(tableList);

            try (Statement stmt = connection.createStatement();
                 var rs = stmt.executeQuery(sql)) {

                if (rs.next()) {
                    return rs.getInt(1) == TABLE_NAMES.length;
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to check schema initialization status", e);
        }
        return false;
    }
}
