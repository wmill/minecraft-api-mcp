package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskStatus;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.database.DatabaseConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
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

    private static final String TASK_COLUMNS =
        "id, build_id, task_order, task_type, task_data, status, executed_at, error_message, " +
        "min_x, min_y, min_z, max_x, max_y, max_z, description";

    private final DatabaseConfig databaseConfig;

    public PostgreSQLTaskRepository(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    @Override
    public BuildTask create(BuildTask task) throws SQLException {
        try (Connection conn = databaseConfig.getConnection()) {
            createWithConnection(conn, task);
            return task;
        }
    }

    private void createWithConnection(Connection conn, BuildTask task) throws SQLException {
        String sql = """
            INSERT INTO build_tasks (id, build_id, task_order, task_type, task_data, status,
                                   executed_at, error_message, min_x, min_y, min_z, max_x, max_y, max_z, description)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, task.getId());
            stmt.setObject(2, task.getBuildId());
            stmt.setInt(3, task.getTaskOrder());
            stmt.setString(4, task.getTaskType().name());
            stmt.setString(5, task.getTaskData() != null ? task.getTaskData().toString() : null);
            stmt.setString(6, task.getStatus().name());
            stmt.setTimestamp(7, task.getExecutedAt() != null ?
                Timestamp.from(task.getExecutedAt()) : null);
            stmt.setString(8, task.getErrorMessage());
            bindCoordinates(stmt, 9, task.getCoordinates());
            stmt.setString(15, task.getDescription());

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Creating task failed, no rows affected");
            }

            LOGGER.debug("Created task with ID: {}", task.getId());
        }
    }

    /**
     * Binds the six bounding-box columns (min then max, xyz order) starting at firstIndex,
     * writing SQL NULLs when the task has no coordinates.
     */
    private void bindCoordinates(PreparedStatement stmt, int firstIndex, BoundingBox coords) throws SQLException {
        if (coords != null) {
            stmt.setInt(firstIndex, coords.getMinX());
            stmt.setInt(firstIndex + 1, coords.getMinY());
            stmt.setInt(firstIndex + 2, coords.getMinZ());
            stmt.setInt(firstIndex + 3, coords.getMaxX());
            stmt.setInt(firstIndex + 4, coords.getMaxY());
            stmt.setInt(firstIndex + 5, coords.getMaxZ());
        } else {
            for (int i = 0; i < 6; i++) {
                stmt.setNull(firstIndex + i, Types.INTEGER);
            }
        }
    }

    @Override
    public Optional<BuildTask> findById(UUID id) throws SQLException {
        String sql = "SELECT " + TASK_COLUMNS + " FROM build_tasks WHERE id = ?";
        
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
        try (Connection conn = databaseConfig.getConnection()) {
            updateWithConnection(conn, task);
            return task;
        }
    }

    @Override
    public void updateAll(List<BuildTask> tasks) throws SQLException {
        Connection conn = databaseConfig.getConnection();
        try {
            conn.setAutoCommit(false);

            for (BuildTask task : tasks) {
                updateWithConnection(conn, task);
            }

            conn.commit();
            LOGGER.debug("Updated {} tasks atomically", tasks.size());
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
            conn.close();
        }
    }

    private void updateWithConnection(Connection conn, BuildTask task) throws SQLException {
        String sql = """
            UPDATE build_tasks
            SET task_order = ?, task_type = ?, task_data = ?::jsonb, status = ?,
                executed_at = ?, error_message = ?, min_x = ?, min_y = ?, min_z = ?,
                max_x = ?, max_y = ?, max_z = ?, description = ?
            WHERE id = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, task.getTaskOrder());
            stmt.setString(2, task.getTaskType().name());
            stmt.setString(3, task.getTaskData() != null ? task.getTaskData().toString() : null);
            stmt.setString(4, task.getStatus().name());
            stmt.setTimestamp(5, task.getExecutedAt() != null ?
                Timestamp.from(task.getExecutedAt()) : null);
            stmt.setString(6, task.getErrorMessage());
            bindCoordinates(stmt, 7, task.getCoordinates());
            stmt.setString(13, task.getDescription());
            stmt.setObject(14, task.getId());

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("Updating task failed, task not found: " + task.getId());
            }

            LOGGER.debug("Updated task with ID: {}", task.getId());
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
        String sql = "SELECT " + TASK_COLUMNS + " FROM build_tasks WHERE build_id = ? ORDER BY task_order ASC";

        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, buildId);

            try (ResultSet rs = stmt.executeQuery()) {
                return readTasks(rs);
            }
        }
    }

    private List<BuildTask> readTasks(ResultSet rs) throws SQLException {
        List<BuildTask> tasks = new ArrayList<>();
        while (rs.next()) {
            tasks.add(mapResultSetToTask(rs));
        }
        return tasks;
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

            // Delete existing tasks for this build, then insert in new order,
            // all on this connection so the whole swap is one transaction
            deleteByBuildIdWithConnection(conn, buildId);

            for (int i = 0; i < tasks.size(); i++) {
                BuildTask task = tasks.get(i);
                task.setBuildId(buildId);
                task.setTaskOrder(i + 1);
                createWithConnection(conn, task);
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
                   bt.executed_at, bt.error_message, bt.min_x, bt.min_y, bt.min_z, bt.max_x, bt.max_y, bt.max_z, bt.description
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
            JdbcSupport.bindBoxIntersection(stmt, 2, boundingBox);

            try (ResultSet rs = stmt.executeQuery()) {
                return readTasks(rs);
            }
        }
    }
    
    @Override
    public int deleteByBuildId(UUID buildId) throws SQLException {
        try (Connection conn = databaseConfig.getConnection()) {
            return deleteByBuildIdWithConnection(conn, buildId);
        }
    }

    private int deleteByBuildIdWithConnection(Connection conn, UUID buildId) throws SQLException {
        String sql = "DELETE FROM build_tasks WHERE build_id = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, buildId);

            int rowsAffected = stmt.executeUpdate();
            LOGGER.debug("Deleted {} tasks for build: {}", rowsAffected, buildId);

            return rowsAffected;
        }
    }
    
    private BuildTask mapResultSetToTask(ResultSet rs) throws SQLException {
        BuildTask task = new BuildTask();
        task.setId((UUID) rs.getObject("id"));
        task.setBuildId((UUID) rs.getObject("build_id"));
        task.setTaskOrder(rs.getInt("task_order"));
        task.setTaskType(TaskType.valueOf(rs.getString("task_type")));
        task.setStatus(TaskStatus.valueOf(rs.getString("status")));
        task.setDescription(rs.getString("description"));

        // Parse JSON task data
        JsonNode taskData = JdbcSupport.parseJsonOrNull(rs.getString("task_data"), LOGGER,
            "task data for task " + task.getId());
        if (taskData != null) {
            task.setTaskData(taskData);
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