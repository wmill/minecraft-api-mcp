package com.example.buildtask.repository;

import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.TaskStatus;
import com.example.buildtask.model.TaskType;
import com.example.buildtask.model.BoundingBox;
import com.example.database.DatabaseConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of TaskRepository.
 * Requirements: 2.1, 2.3, 2.5, 4.3
 */
public class PostgreSQLTaskRepository implements TaskRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLTaskRepository.class);
    
    private final DatabaseConfig databaseConfig;
    private final ObjectMapper objectMapper;
    
    public PostgreSQLTaskRepository(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public BuildTask create(BuildTask task) throws SQLException {
        String sql = """
            INSERT INTO build_tasks (id, build_id, task_order, task_type, task_data, status, 
                                   executed_at, error_message, min_x, min_y, min_z, max_x, max_y, max_z, description)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, task.getId());
            stmt.setObject(2, task.getBuildId());
            stmt.setInt(3, task.getTaskOrder());
            stmt.setString(4, task.getTaskType().name());
            stmt.setString(5, task.getTaskData() != null ? task.getTaskData().toString() : null);
            stmt.setString(6, task.getStatus().name());
            stmt.setTimestamp(7, task.getExecutedAt() != null ? 
                Timestamp.from(task.getExecutedAt()) : null);
            stmt.setString(8, task.getErrorMessage());
            
            // Set coordinate information
            BoundingBox coords = task.getCoordinates();
            if (coords != null) {
                stmt.setInt(9, coords.getMinX());
                stmt.setInt(10, coords.getMinY());
                stmt.setInt(11, coords.getMinZ());
                stmt.setInt(12, coords.getMaxX());
                stmt.setInt(13, coords.getMaxY());
                stmt.setInt(14, coords.getMaxZ());
            } else {
                stmt.setNull(9, Types.INTEGER);
                stmt.setNull(10, Types.INTEGER);
                stmt.setNull(11, Types.INTEGER);
                stmt.setNull(12, Types.INTEGER);
                stmt.setNull(13, Types.INTEGER);
                stmt.setNull(14, Types.INTEGER);
            }
            stmt.setString(15, task.getDescription());
            
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Creating task failed, no rows affected");
            }
            
            LOGGER.debug("Created task with ID: {}", task.getId());
            return task;
        }
    }
    
    @Override
    public Optional<BuildTask> findById(UUID id) throws SQLException {
        String sql = """
            SELECT id, build_id, task_order, task_type, task_data, status, executed_at, error_message,
                   min_x, min_y, min_z, max_x, max_y, max_z
            FROM build_tasks
            WHERE id = ?
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, id);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToTask(rs));
                }
                return Optional.empty();
            }
        }
    }
    
    @Override
    public BuildTask update(BuildTask task) throws SQLException {
        String sql = """
            UPDATE build_tasks
            SET task_order = ?, task_type = ?, task_data = ?::jsonb, status = ?, 
                executed_at = ?, error_message = ?, min_x = ?, min_y = ?, min_z = ?, 
                max_x = ?, max_y = ?, max_z = ?
            WHERE id = ?
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, task.getTaskOrder());
            stmt.setString(2, task.getTaskType().name());
            stmt.setString(3, task.getTaskData() != null ? task.getTaskData().toString() : null);
            stmt.setString(4, task.getStatus().name());
            stmt.setTimestamp(5, task.getExecutedAt() != null ? 
                Timestamp.from(task.getExecutedAt()) : null);
            stmt.setString(6, task.getErrorMessage());
            
            // Set coordinate information
            BoundingBox coords = task.getCoordinates();
            if (coords != null) {
                stmt.setInt(7, coords.getMinX());
                stmt.setInt(8, coords.getMinY());
                stmt.setInt(9, coords.getMinZ());
                stmt.setInt(10, coords.getMaxX());
                stmt.setInt(11, coords.getMaxY());
                stmt.setInt(12, coords.getMaxZ());
            } else {
                stmt.setNull(7, Types.INTEGER);
                stmt.setNull(8, Types.INTEGER);
                stmt.setNull(9, Types.INTEGER);
                stmt.setNull(10, Types.INTEGER);
                stmt.setNull(11, Types.INTEGER);
                stmt.setNull(12, Types.INTEGER);
            }
            
            stmt.setObject(13, task.getId());
            
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Updating task failed, task not found: " + task.getId());
            }
            
            LOGGER.debug("Updated task with ID: {}", task.getId());
            return task;
        }
    }
    
    @Override
    public boolean deleteById(UUID id) throws SQLException {
        String sql = "DELETE FROM build_tasks WHERE id = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, id);
            
            int rowsAffected = stmt.executeUpdate();
            boolean deleted = rowsAffected > 0;
            
            if (deleted) {
                LOGGER.debug("Deleted task with ID: {}", id);
            }
            
            return deleted;
        }
    }
    
    @Override
    public List<BuildTask> findByBuildIdOrdered(UUID buildId) throws SQLException {
        String sql = """
            SELECT id, build_id, task_order, task_type, task_data, status, executed_at, error_message,
                   min_x, min_y, min_z, max_x, max_y, max_z
            FROM build_tasks
            WHERE build_id = ?
            ORDER BY task_order ASC
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, buildId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<BuildTask> tasks = new ArrayList<>();
                while (rs.next()) {
                    tasks.add(mapResultSetToTask(rs));
                }
                return tasks;
            }
        }
    }
    
    @Override
    public List<BuildTask> findByBuildIdAndStatus(UUID buildId, TaskStatus status) throws SQLException {
        String sql = """
            SELECT id, build_id, task_order, task_type, task_data, status, executed_at, error_message,
                   min_x, min_y, min_z, max_x, max_y, max_z
            FROM build_tasks
            WHERE build_id = ? AND status = ?
            ORDER BY task_order ASC
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, buildId);
            stmt.setString(2, status.name());
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<BuildTask> tasks = new ArrayList<>();
                while (rs.next()) {
                    tasks.add(mapResultSetToTask(rs));
                }
                return tasks;
            }
        }
    }
    
    @Override
    public BuildTask addToQueue(UUID buildId, BuildTask task) throws SQLException {
        // Get the next task order
        int nextOrder = getNextTaskOrder(buildId);
        task.setBuildId(buildId);
        task.setTaskOrder(nextOrder);
        
        return create(task);
    }
    
    @Override
    public void updateTaskQueue(UUID buildId, List<BuildTask> tasks) throws SQLException {
        Connection conn = databaseConfig.getConnection();
        try {
            conn.setAutoCommit(false);
            
            // Delete existing tasks for this build
            deleteByBuildId(buildId);
            
            // Insert tasks in new order
            for (int i = 0; i < tasks.size(); i++) {
                BuildTask task = tasks.get(i);
                task.setBuildId(buildId);
                task.setTaskOrder(i + 1);
                create(task);
            }
            
            conn.commit();
            LOGGER.debug("Updated task queue for build: {}", buildId);
            
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
            conn.close();
        }
    }
    
    @Override
    public int getNextTaskOrder(UUID buildId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(task_order), 0) + 1 FROM build_tasks WHERE build_id = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, buildId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 1;
            }
        }
    }
    
    @Override
    public List<BuildTask> findByLocationIntersection(String world, BoundingBox boundingBox) throws SQLException {
        String sql = """
            SELECT bt.id, bt.build_id, bt.task_order, bt.task_type, bt.task_data, bt.status, 
                   bt.executed_at, bt.error_message, bt.min_x, bt.min_y, bt.min_z, bt.max_x, bt.max_y, bt.max_z
            FROM build_tasks bt
            INNER JOIN builds b ON bt.build_id = b.id
            WHERE b.world = ?
            AND bt.min_x <= ? AND bt.max_x >= ?
            AND bt.min_y <= ? AND bt.max_y >= ?
            AND bt.min_z <= ? AND bt.max_z >= ?
            ORDER BY bt.task_order ASC
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, world);
            stmt.setInt(2, boundingBox.getMaxX());
            stmt.setInt(3, boundingBox.getMinX());
            stmt.setInt(4, boundingBox.getMaxY());
            stmt.setInt(5, boundingBox.getMinY());
            stmt.setInt(6, boundingBox.getMaxZ());
            stmt.setInt(7, boundingBox.getMinZ());
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<BuildTask> tasks = new ArrayList<>();
                while (rs.next()) {
                    tasks.add(mapResultSetToTask(rs));
                }
                return tasks;
            }
        }
    }
    
    @Override
    public List<BuildTask> findByWorldWithCoordinates(String world) throws SQLException {
        String sql = """
            SELECT bt.id, bt.build_id, bt.task_order, bt.task_type, bt.task_data, bt.status, 
                   bt.executed_at, bt.error_message, bt.min_x, bt.min_y, bt.min_z, bt.max_x, bt.max_y, bt.max_z
            FROM build_tasks bt
            INNER JOIN builds b ON bt.build_id = b.id
            WHERE b.world = ? AND bt.min_x IS NOT NULL
            ORDER BY bt.task_order ASC
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, world);
            
            try (ResultSet rs = stmt.executeQuery()) {
                List<BuildTask> tasks = new ArrayList<>();
                while (rs.next()) {
                    tasks.add(mapResultSetToTask(rs));
                }
                return tasks;
            }
        }
    }
    
    @Override
    public BuildTask updateTaskStatus(UUID taskId, TaskStatus status, String errorMessage) throws SQLException {
        String sql = """
            UPDATE build_tasks
            SET status = ?, executed_at = ?, error_message = ?
            WHERE id = ?
            """;
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, status.name());
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setString(3, errorMessage);
            stmt.setObject(4, taskId);
            
            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Updating task status failed, task not found: " + taskId);
            }
            
            LOGGER.debug("Updated task status for ID: {} to {}", taskId, status);
            
            // Return the updated task
            return findById(taskId).orElseThrow(() -> 
                new SQLException("Task not found after status update: " + taskId));
        }
    }
    
    @Override
    public int deleteByBuildId(UUID buildId) throws SQLException {
        String sql = "DELETE FROM build_tasks WHERE build_id = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, buildId);
            
            int rowsAffected = stmt.executeUpdate();
            LOGGER.debug("Deleted {} tasks for build: {}", rowsAffected, buildId);
            
            return rowsAffected;
        }
    }
    
    @Override
    public long countByBuildId(UUID buildId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM build_tasks WHERE build_id = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, buildId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0;
            }
        }
    }
    
    @Override
    public long countByBuildIdAndStatus(UUID buildId, TaskStatus status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM build_tasks WHERE build_id = ? AND status = ?";
        
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setObject(1, buildId);
            stmt.setString(2, status.name());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0;
            }
        }
    }
    
    private BuildTask mapResultSetToTask(ResultSet rs) throws SQLException {
        BuildTask task = new BuildTask();
        task.setId((UUID) rs.getObject("id"));
        task.setBuildId((UUID) rs.getObject("build_id"));
        task.setTaskOrder(rs.getInt("task_order"));
        task.setTaskType(TaskType.valueOf(rs.getString("task_type")));
        task.setStatus(TaskStatus.valueOf(rs.getString("status")));
        
        // Parse JSON task data
        String taskDataJson = rs.getString("task_data");
        if (taskDataJson != null) {
            try {
                JsonNode taskData = objectMapper.readTree(taskDataJson);
                task.setTaskData(taskData);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse task data JSON for task {}: {}", task.getId(), e.getMessage());
            }
        }
        
        Timestamp executedAt = rs.getTimestamp("executed_at");
        if (executedAt != null) {
            task.setExecutedAt(executedAt.toInstant());
        }
        
        task.setErrorMessage(rs.getString("error_message"));
        
        // Parse coordinate information
        Integer minX = (Integer) rs.getObject("min_x");
        if (minX != null) {
            BoundingBox coords = new BoundingBox(
                minX,
                rs.getInt("min_y"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_y"),
                rs.getInt("max_z")
            );
            task.setCoordinates(coords);
        }
        
        return task;
    }
}