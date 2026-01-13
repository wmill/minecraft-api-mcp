package com.example.buildtask.service;

import com.example.buildtask.model.Build;
import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.BoundingBox;
import com.example.buildtask.repository.BuildRepository;
import com.example.buildtask.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for location-based build queries.
 * Implements spatial intersection logic and chronological ordering.
 * Requirements: 4.1, 4.2, 4.4
 */
public class LocationQueryService {
    private static final Logger logger = LoggerFactory.getLogger(LocationQueryService.class);
    
    private final BuildRepository buildRepository;
    private final TaskRepository taskRepository;

    public LocationQueryService(BuildRepository buildRepository, TaskRepository taskRepository) {
        this.buildRepository = buildRepository;
        this.taskRepository = taskRepository;
    }

    /**
     * Queries builds by location intersection.
     * Requirements: 4.1, 4.2, 4.4
     */
    public LocationQueryResult queryBuildsByLocation(LocationQueryRequest request) throws SQLException {
        if (request == null) {
            throw new IllegalArgumentException("Location query request cannot be null");
        }

        logger.info("Querying builds in world {} for area ({},{},{}) to ({},{},{})", 
            request.world, request.minX, request.minY, request.minZ, 
            request.maxX, request.maxY, request.maxZ);

        // Create bounding box for the query
        BoundingBox queryBox = new BoundingBox(
            request.minX, request.minY, request.minZ,
            request.maxX, request.maxY, request.maxZ
        );

        // Find builds that intersect with the query area
        List<Build> intersectingBuilds = buildRepository.findByLocationIntersection(request.world, queryBox);

        // Filter by status if needed
        if (!request.includeInProgress) {
            intersectingBuilds = intersectingBuilds.stream()
                .filter(build -> build.getStatus() == com.example.buildtask.model.BuildStatus.COMPLETED)
                .collect(Collectors.toList());
        }

        // Sort by creation time (chronological order for overlapping builds)
        // Requirements: 4.4
        intersectingBuilds.sort(Comparator.comparing(Build::getCreatedAt));

        logger.info("Found {} builds intersecting with query area", intersectingBuilds.size());

        // Get detailed results with task information
        List<BuildLocationResult> results = intersectingBuilds.stream()
            .map(build -> {
                try {
                    // Get tasks for this build that intersect with the query area
                    List<BuildTask> intersectingTasks = taskRepository.findByLocationIntersection(request.world, queryBox)
                        .stream()
                        .filter(task -> task.getBuildId().equals(build.getId()))
                        .collect(Collectors.toList());

                    return new BuildLocationResult(build, intersectingTasks);
                } catch (SQLException e) {
                    logger.error("Error getting tasks for build {}", build.getId(), e);
                    return new BuildLocationResult(build, List.of());
                }
            })
            .collect(Collectors.toList());

        return new LocationQueryResult(results, queryBox);
    }

    /**
     * Request object for location-based queries.
     */
    public static class LocationQueryRequest {
        public String world = "minecraft:overworld";
        public int minX, minY, minZ;
        public int maxX, maxY, maxZ;
        public boolean includeInProgress = false;

        public LocationQueryRequest() {}

        public LocationQueryRequest(String world, int minX, int minY, int minZ, 
                                  int maxX, int maxY, int maxZ, boolean includeInProgress) {
            this.world = world;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.includeInProgress = includeInProgress;
        }

        public LocationQueryRequest(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this("minecraft:overworld", minX, minY, minZ, maxX, maxY, maxZ, false);
        }
    }

    /**
     * Result of a location query containing build and task details.
     */
    public static class BuildLocationResult {
        public final Build build;
        public final List<BuildTask> intersectingTasks;

        public BuildLocationResult(Build build, List<BuildTask> intersectingTasks) {
            this.build = build;
            this.intersectingTasks = intersectingTasks;
        }
    }

    /**
     * Complete result of a location query.
     */
    public static class LocationQueryResult {
        public final List<BuildLocationResult> builds;
        public final BoundingBox queryArea;

        public LocationQueryResult(List<BuildLocationResult> builds, BoundingBox queryArea) {
            this.builds = builds;
            this.queryArea = queryArea;
        }

        public int getBuildCount() {
            return builds.size();
        }

        public int getTotalTaskCount() {
            return builds.stream()
                .mapToInt(result -> result.intersectingTasks.size())
                .sum();
        }
    }
}