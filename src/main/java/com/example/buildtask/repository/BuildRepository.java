package com.example.buildtask.repository;

import com.example.buildtask.model.Build;
import com.example.buildtask.model.BuildStatus;
import com.example.buildtask.model.BoundingBox;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Build entity CRUD operations.
 * Requirements: 1.1, 1.2, 1.3, 4.1, 4.4
 */
public interface BuildRepository {
    
    /**
     * Create a new build in the database.
     * Requirements: 1.1, 1.2
     */
    Build create(Build build) throws SQLException;
    
    /**
     * Find a build by its ID.
     * Requirements: 1.3
     */
    Optional<Build> findById(UUID id) throws SQLException;
    
    /**
     * Update an existing build.
     * Requirements: 1.2, 1.3
     */
    Build update(Build build) throws SQLException;
    
    /**
     * Delete a build by ID.
     */
    boolean deleteById(UUID id) throws SQLException;
    
    /**
     * Find all builds with a specific status.
     */
    List<Build> findByStatus(BuildStatus status) throws SQLException;
    
    /**
     * Find all builds in a specific world.
     */
    List<Build> findByWorld(String world) throws SQLException;
    
    /**
     * Find builds that intersect with the given bounding box.
     * Requirements: 4.1, 4.4
     */
    List<Build> findByLocationIntersection(String world, BoundingBox boundingBox) throws SQLException;
    
    /**
     * Find all builds, optionally filtered by world.
     */
    List<Build> findAll(String world) throws SQLException;
    
    /**
     * Count total number of builds.
     */
    long count() throws SQLException;
    
    /**
     * Check if a build exists by ID.
     */
    boolean existsById(UUID id) throws SQLException;
}