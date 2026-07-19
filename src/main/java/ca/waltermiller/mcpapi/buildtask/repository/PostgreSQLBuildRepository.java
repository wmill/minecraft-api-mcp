package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.model.BuildStatus;
import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.database.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of BuildRepository.
 * Requirements: 1.1, 1.2, 1.3, 4.1, 4.4
 */
public class PostgreSQLBuildRepository implements BuildRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLBuildRepository.class);

    private static final String BUILD_COLUMNS = "id, name, description, status, created_at, completed_at, world";

    private final DatabaseConfig databaseConfig;
    
    public PostgreSQLBuildRepository(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }
    
    @Override
    public Build create(Build build) throws SQLException {
        String sql = """
            INSERT INTO builds (id, name, description, status, created_at, completed_at, world)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, build.getId());
            stmt.setString(2, build.getName());
            stmt.setString(3, build.getDescription());
            stmt.setString(4, build.getStatus().name());
            stmt.setTimestamp(5, Timestamp.from(build.getCreatedAt()));
            stmt.setTimestamp(6, build.getCompletedAt() != null ? 
                Timestamp.from(build.getCompletedAt()) : null);
            stmt.setString(7, build.getWorld());
            
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Creating build failed, no rows affected");
            }
            
            LOGGER.debug("Created build with ID: {}", build.getId());
            return build;
        }
    }
    
    @Override
    public Optional<Build> findById(UUID id) throws SQLException {
        String sql = "SELECT " + BUILD_COLUMNS + " FROM builds WHERE id = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, id);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToBuild(rs));
                }
                return Optional.empty();
            }
        }
    }
    
    @Override
    public Build update(Build build) throws SQLException {
        String sql = """
            UPDATE builds
            SET name = ?, description = ?, status = ?, completed_at = ?, world = ?
            WHERE id = ?
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, build.getName());
            stmt.setString(2, build.getDescription());
            stmt.setString(3, build.getStatus().name());
            stmt.setTimestamp(4, build.getCompletedAt() != null ? 
                Timestamp.from(build.getCompletedAt()) : null);
            stmt.setString(5, build.getWorld());
            stmt.setObject(6, build.getId());
            
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Updating build failed, build not found: " + build.getId());
            }
            
            LOGGER.debug("Updated build with ID: {}", build.getId());
            return build;
        }
    }
    
    @Override
    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM builds WHERE id = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, id);
            
            int rowsAffected = stmt.executeUpdate();
            boolean deleted = rowsAffected > 0;
            
            if (deleted) {
                LOGGER.debug("Deleted build with ID: {}", id);
            }
            
            return deleted;
        }
    }
    
    @Override
    public List<Build> findByLocationIntersection(String world, BoundingBox boundingBox) throws SQLException {
        String sql = """
            SELECT DISTINCT b.id, b.name, b.description, b.status, b.created_at, b.completed_at, b.world
            FROM builds b
            INNER JOIN build_tasks bt ON b.id = bt.build_id
            WHERE b.world = ?
            AND bt.min_x <= ? AND bt.max_x >= ?
            AND bt.min_y <= ? AND bt.max_y >= ?
            AND bt.min_z <= ? AND bt.max_z >= ?
            ORDER BY b.created_at ASC
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, world);
            JdbcSupport.bindBoxIntersection(stmt, 2, boundingBox);
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<Build> builds = new ArrayList<>();
                while (rs.next()) {
                    builds.add(mapResultSetToBuild(rs));
                }
                return builds;
            }
        }
    }
    
    private Build mapResultSetToBuild(ResultSet rs) throws SQLException {
        Build build = new Build();
        build.setId((UUID) rs.getObject("id"));
        build.setName(rs.getString("name"));
        build.setDescription(rs.getString("description"));
        build.setStatus(BuildStatus.valueOf(rs.getString("status")));
        
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            build.setCreatedAt(createdAt.toInstant());
        }
        
        Timestamp completedAt = rs.getTimestamp("completed_at");
        if (completedAt != null) {
            build.setCompletedAt(completedAt.toInstant());
        }
        
        build.setWorld(rs.getString("world"));
        
        return build;
    }
}