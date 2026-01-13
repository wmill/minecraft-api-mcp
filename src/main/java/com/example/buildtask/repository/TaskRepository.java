package com.example.buildtask.repository;

import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.TaskStatus;
import com.example.buildtask.model.BoundingBox;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for BuildTask entity CRUD and queue operations.
 * Requirements: 2.1, 2.3, 2.5, 4.3
 */
public interface TaskRepository {
    
    /**
     * Create a new task in the database.
     * Requirements: 2.1
     */
    BuildTask create(BuildTask task) throws SQLException;
    
    /**
     * Find a task by its ID.
     */
    Optional<BuildTask> findById(UUID id) throws SQLException;
    
    /**
     * Update an existing task.
     */
    BuildTask update(BuildTask task) throws SQLException;
    
    /**
     * Delete a task by ID.
     */
    boolean deleteById(UUID id) throws SQLException;
    
    /**
     * Find all tasks for a specific build, ordered by task_order.
     * Requirements: 2.3
     */
    List<BuildTask> findByBuildIdOrdered(UUID buildId) throws SQLException;
    
    /**
     * Find tasks by build ID and status.
     */
    List<BuildTask> findByBuildIdAndStatus(UUID buildId, TaskStatus status) throws SQLException;
    
    /**
     * Add a task to the end of the queue for a build.
     * Requirements: 2.1, 2.5
     */
    BuildTask addToQueue(UUID buildId, BuildTask task) throws SQLException;
    
    /**
     * Update the entire task queue for a build (reorder tasks).
     * Requirements: 2.5
     */
    void updateTaskQueue(UUID buildId, List<BuildTask> tasks) throws SQLException;
    
    /**
     * Get the next task order number for a build.
     */
    int getNextTaskOrder(UUID buildId) throws SQLException;
    
    /**
     * Find tasks that intersect with the given bounding box.
     * Requirements: 4.3
     */
    List<BuildTask> findByLocationIntersection(String world, BoundingBox boundingBox) throws SQLException;
    
    /**
     * Find all tasks with coordinate information in a specific world.
     * Requirements: 4.3
     */
    List<BuildTask> findByWorldWithCoordinates(String world) throws SQLException;
    
    /**
     * Update task status and execution details.
     */
    BuildTask updateTaskStatus(UUID taskId, TaskStatus status, String errorMessage) throws SQLException;
    
    /**
     * Delete all tasks for a specific build.
     */
    int deleteByBuildId(UUID buildId) throws SQLException;
    
    /**
     * Count tasks by build ID.
     */
    long countByBuildId(UUID buildId) throws SQLException;
    
    /**
     * Count tasks by build ID and status.
     */
    long countByBuildIdAndStatus(UUID buildId, TaskStatus status) throws SQLException;
}