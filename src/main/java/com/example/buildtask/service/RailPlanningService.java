package com.example.buildtask.service;

import com.example.buildtask.model.Build;
import com.example.buildtask.model.BuildStatus;
import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.RailPlanningJob;
import com.example.buildtask.model.RailPlanningStatus;
import com.example.buildtask.model.TaskType;
import com.example.buildtask.repository.BuildRepository;
import com.example.buildtask.repository.RailPlanningJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.endpoints.BlocksEndpointCore;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RailPlanningService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RailPlanningService.class);
    private static final int TILE_SIZE = 64;
    private static final int DEFAULT_MARGIN = 24;
    private static final int DEFAULT_MAX_DROP_BELOW_ANCHOR = 4;
    private static final int DEFAULT_VOID_SURFACE_THRESHOLD = -48;
    private static final double DEFAULT_VOID_SURFACE_PENALTY = 1000.0;

    private final BuildRepository buildRepository;
    private final BuildService buildService;
    private final RailPlanningJobRepository jobRepository;
    private final BlocksEndpointCore blocksCore;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    public RailPlanningService(BuildRepository buildRepository,
                               BuildService buildService,
                               RailPlanningJobRepository jobRepository,
                               MinecraftServer server,
                               org.slf4j.Logger logger) {
        this.buildRepository = buildRepository;
        this.buildService = buildService;
        this.jobRepository = jobRepository;
        this.blocksCore = new BlocksEndpointCore(server, logger);
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public RailPlanningJob startPlanning(StartRailPlanningRequest request) throws SQLException {
        validateRequest(request);

        Build build = buildRepository.findById(request.build_id)
            .orElseThrow(() -> new IllegalArgumentException("Build not found: " + request.build_id));
        if (build.getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot add planning tasks to completed build: " + request.build_id);
        }

        RailPlanningJob job = new RailPlanningJob();
        job.setBuildId(request.build_id);
        job.setRequestData(objectMapper.valueToTree(request));
        job.setPhase("queued");
        jobRepository.create(job);

        CompletableFuture.runAsync(() -> plan(job.getId(), request, build), executorService)
            .exceptionally(throwable -> {
                markFailed(job.getId(), "Planning crashed: " + throwable.getMessage());
                return null;
            });
        return job;
    }

    public Optional<RailPlanningJob> getJob(UUID jobId) throws SQLException {
        return jobRepository.findById(jobId);
    }

    private void plan(UUID jobId, StartRailPlanningRequest request, Build build) {
        try {
            RailPlanningJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Planning job disappeared: " + jobId));
            job.setPhase("sampling_heightmaps");
            jobRepository.update(job);

            HeightField field = sampleHeightField(request, job);
            job.setPhase("routing");
            jobRepository.update(job);

            List<GridPoint> coarsePath = route(field, request);
            if (coarsePath.isEmpty()) {
                throw new IllegalStateException("No viable route found");
            }

            List<RoutePoint> railPath = buildTrackProfile(coarsePath, field, request);
            job.setRouteLength(railPath.size());
            job.setPhase("segmenting");
            jobRepository.update(job);

            List<SegmentPlan> segments = segmentPath(railPath);
            appendTasks(build.getId(), build.getWorld(), segments, request);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("build_id", build.getId().toString());
            result.put("route_length", railPath.size());
            result.put("segment_count", segments.size());
            ObjectNode segmentCounts = result.putObject("segment_counts");
            segmentCounts.put("surface", countSegments(segments, SegmentType.SURFACE));
            segmentCounts.put("bridge", countSegments(segments, SegmentType.BRIDGE));
            segmentCounts.put("tunnel", countSegments(segments, SegmentType.TUNNEL));

            job.setResultData(result);
            job.setStatus(RailPlanningStatus.PLANNED);
            job.setPhase("planned");
            jobRepository.update(job);
        } catch (Exception e) {
            markFailed(jobId, e.getMessage());
        }
    }

    private void appendTasks(UUID buildId, String world, List<SegmentPlan> segments, StartRailPlanningRequest request) throws SQLException {
        for (SegmentPlan segment : segments) {
            ObjectNode taskData = objectMapper.createObjectNode();
            ArrayNode pathNode = taskData.putArray("path");
            for (RoutePoint point : segment.points()) {
                ObjectNode pointNode = pathNode.addObject();
                pointNode.put("x", point.x());
                pointNode.put("y", point.trackY());
                pointNode.put("z", point.z());
            }
            taskData.put("world", world);
            taskData.put("powered_rail_interval", effectiveInt(request.weight_overrides, "powered_rail_interval", 8));
            taskData.put("rail_bed_block", request.rail_bed_block);
            taskData.put("support_block", request.support_block);
            taskData.put("power_block", request.power_block);
            if (request.tunnel_lining_block != null) {
                taskData.put("tunnel_lining_block", request.tunnel_lining_block);
            }

            TaskType taskType = switch (segment.type()) {
                case SURFACE -> TaskType.RAIL_SURFACE_SEGMENT;
                case BRIDGE -> TaskType.RAIL_BRIDGE_SEGMENT;
                case TUNNEL -> TaskType.RAIL_TUNNEL_SEGMENT;
            };
            String description = "Rail " + segment.type().name().toLowerCase() + " segment with " + segment.points().size() + " points";
            buildService.addTask(buildId, new BuildService.AddTaskRequest(taskType, taskData, description));
        }
    }

    private int countSegments(List<SegmentPlan> segments, SegmentType type) {
        return (int) segments.stream().filter(segment -> segment.type() == type).count();
    }

    private List<SegmentPlan> segmentPath(List<RoutePoint> path) {
        List<SegmentPlan> segments = new ArrayList<>();
        if (path.isEmpty()) {
            return segments;
        }

        SegmentType currentType = classify(path.get(0));
        List<RoutePoint> currentPoints = new ArrayList<>();
        currentPoints.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            RoutePoint point = path.get(i);
            SegmentType pointType = classify(point);
            if (pointType != currentType) {
                currentPoints.add(point);
                segments.add(new SegmentPlan(currentType, new ArrayList<>(currentPoints)));
                currentPoints.clear();
                currentPoints.add(path.get(i - 1));
                currentType = pointType;
            }
            currentPoints.add(point);
        }
        if (currentPoints.size() >= 2) {
            segments.add(new SegmentPlan(currentType, currentPoints));
        }
        return segments;
    }

    private SegmentType classify(RoutePoint point) {
        if (point.surfaceY() + 1 < point.trackY()) {
            return SegmentType.BRIDGE;
        }
        if (point.surfaceY() - 2 > point.trackY()) {
            return SegmentType.TUNNEL;
        }
        return SegmentType.SURFACE;
    }

    private List<RoutePoint> buildTrackProfile(List<GridPoint> coarsePath, HeightField field, StartRailPlanningRequest request) {
        List<RoutePoint> result = new ArrayList<>();
        double maxGrade = effectiveDouble(request.weight_overrides, "max_grade", 1.0);
        int startTrackY = request.start_y;
        int targetEndY = request.end_y;
        int maxStep = Math.max(1, (int) Math.ceil(maxGrade));
        int maxDropBelowAnchor = effectiveInt(request.weight_overrides, "max_drop_below_anchor", DEFAULT_MAX_DROP_BELOW_ANCHOR);

        List<Integer> surfaceHeights = coarsePath.stream()
            .map(point -> field.heightAt(point.x(), point.z()))
            .toList();
        List<Integer> trackHeights = computeTrackProfileHeights(
            surfaceHeights, startTrackY, targetEndY, maxStep, maxDropBelowAnchor);

        for (int i = 0; i < coarsePath.size(); i++) {
            GridPoint point = coarsePath.get(i);
            int surfaceY = surfaceHeights.get(i);
            int trackY = trackHeights.get(i);
            result.add(new RoutePoint(point.x(), point.z(), surfaceY, trackY));
        }
        return result;
    }

    static List<Integer> computeTrackProfileHeights(List<Integer> surfaceHeights,
                                                    int startTrackY,
                                                    int endTrackY,
                                                    int maxStep,
                                                    int maxDropBelowAnchor) {
        List<Integer> trackHeights = new ArrayList<>();
        if (surfaceHeights.isEmpty()) {
            return trackHeights;
        }

        int safeMaxStep = Math.max(1, maxStep);
        int anchorFloor = Math.min(startTrackY, endTrackY) - Math.max(0, maxDropBelowAnchor);

        for (int i = 0; i < surfaceHeights.size(); i++) {
            int surfaceY = surfaceHeights.get(i);
            double progress = surfaceHeights.size() == 1 ? 1.0 : (double) i / (surfaceHeights.size() - 1);
            int idealY = (int) Math.round(startTrackY + (endTrackY - startTrackY) * progress);
            int desired = Math.max(idealY, Math.max(anchorFloor, surfaceY - 3));
            int previousTrackY = trackHeights.isEmpty() ? startTrackY : trackHeights.get(trackHeights.size() - 1);
            int trackY = clampStatic(desired, Math.max(anchorFloor, previousTrackY - safeMaxStep), previousTrackY + safeMaxStep);

            if (i == 0) {
                trackY = startTrackY;
            }
            if (i == surfaceHeights.size() - 1) {
                int targetY = Math.max(anchorFloor, endTrackY);
                trackY = clampStatic(targetY, Math.max(anchorFloor, previousTrackY - safeMaxStep), previousTrackY + safeMaxStep);
            }

            trackHeights.add(trackY);
        }

        return trackHeights;
    }

    private List<GridPoint> route(HeightField field, StartRailPlanningRequest request) {
        GridPoint start = new GridPoint(request.start_x, request.start_z);
        GridPoint end = new GridPoint(request.end_x, request.end_z);
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::fScore));
        Map<GridPoint, Double> gScores = new HashMap<>();
        Map<GridPoint, GridPoint> cameFrom = new HashMap<>();

        open.add(new PathNode(start, 0.0, heuristic(start, end)));
        gScores.put(start, 0.0);

        while (!open.isEmpty()) {
            PathNode currentNode = open.poll();
            GridPoint current = currentNode.point();
            if (current.equals(end)) {
                return reconstructPath(cameFrom, current);
            }

            for (GridPoint neighbor : neighbors(current)) {
                if (!field.contains(neighbor.x(), neighbor.z())) {
                    continue;
                }
                double movementCost = transitionCost(current, neighbor, field, request, start, end);
                double tentative = gScores.get(current) + movementCost;
                if (tentative < gScores.getOrDefault(neighbor, Double.POSITIVE_INFINITY)) {
                    cameFrom.put(neighbor, current);
                    gScores.put(neighbor, tentative);
                    open.add(new PathNode(neighbor, tentative, tentative + heuristic(neighbor, end)));
                }
            }
        }
        return List.of();
    }

    private double transitionCost(GridPoint current, GridPoint neighbor, HeightField field, StartRailPlanningRequest request,
                                  GridPoint start, GridPoint end) {
        int currentY = field.heightAt(current.x(), current.z());
        int nextY = field.heightAt(neighbor.x(), neighbor.z());
        double grade = Math.abs(nextY - currentY);
        double gradeCost = effectiveDouble(request.weight_overrides, "grade_cost", 5.0) * grade;
        double detourCost = effectiveDouble(request.weight_overrides, "detour_cost", 0.15) * distanceFromLine(neighbor, start, end);
        double base = effectiveDouble(request.weight_overrides, "surface_cost", 1.0);
        double turnCost = effectiveDouble(request.weight_overrides, "turn_cost", 0.5);
        if (nextY <= effectiveInt(request.weight_overrides, "void_surface_threshold", DEFAULT_VOID_SURFACE_THRESHOLD)) {
            base += effectiveDouble(request.weight_overrides, "void_surface_penalty", DEFAULT_VOID_SURFACE_PENALTY);
        }
        return base + gradeCost + detourCost + turnCost;
    }

    private double distanceFromLine(GridPoint point, GridPoint start, GridPoint end) {
        double numerator = Math.abs((end.z() - start.z()) * point.x() - (end.x() - start.x()) * point.z() + end.x() * start.z() - end.z() * start.x());
        double denominator = Math.hypot(end.z() - start.z(), end.x() - start.x());
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private double heuristic(GridPoint a, GridPoint b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
    }

    private List<GridPoint> neighbors(GridPoint current) {
        return List.of(
            new GridPoint(current.x() + 1, current.z()),
            new GridPoint(current.x() - 1, current.z()),
            new GridPoint(current.x(), current.z() + 1),
            new GridPoint(current.x(), current.z() - 1)
        );
    }

    private List<GridPoint> reconstructPath(Map<GridPoint, GridPoint> cameFrom, GridPoint current) {
        List<GridPoint> path = new ArrayList<>();
        path.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(current);
        }
        List<GridPoint> ordered = new ArrayList<>();
        for (int i = path.size() - 1; i >= 0; i--) {
            ordered.add(path.get(i));
        }
        return densify(ordered);
    }

    private List<GridPoint> densify(List<GridPoint> coarsePath) {
        List<GridPoint> result = new ArrayList<>();
        for (int i = 0; i < coarsePath.size(); i++) {
            GridPoint point = coarsePath.get(i);
            if (result.isEmpty()) {
                result.add(point);
                continue;
            }
            GridPoint previous = result.get(result.size() - 1);
            int x = previous.x();
            int z = previous.z();
            while (x != point.x() || z != point.z()) {
                x += Integer.compare(point.x(), x);
                z += Integer.compare(point.z(), z);
                result.add(new GridPoint(x, z));
            }
        }
        return result;
    }

    private HeightField sampleHeightField(StartRailPlanningRequest request, RailPlanningJob job) throws Exception {
        int minX = Math.min(request.start_x, request.end_x) - DEFAULT_MARGIN;
        int maxX = Math.max(request.start_x, request.end_x) + DEFAULT_MARGIN;
        int minZ = Math.min(request.start_z, request.end_z) - DEFAULT_MARGIN;
        int maxZ = Math.max(request.start_z, request.end_z) + DEFAULT_MARGIN;

        Map<GridPoint, Integer> heights = new HashMap<>();
        int tiles = 0;
        for (int x = minX; x <= maxX; x += TILE_SIZE) {
            for (int z = minZ; z <= maxZ; z += TILE_SIZE) {
                int tileMaxX = Math.min(maxX, x + TILE_SIZE - 1);
                int tileMaxZ = Math.min(maxZ, z + TILE_SIZE - 1);
                int[][] tileHeights = blocksCore.getHeightmapHeights(
                    x, z, tileMaxX, tileMaxZ, request.world, "WORLD_SURFACE").get();
                for (int dx = 0; dx < tileHeights.length; dx++) {
                    for (int dz = 0; dz < tileHeights[dx].length; dz++) {
                        heights.put(new GridPoint(x + dx, z + dz), tileHeights[dx][dz]);
                    }
                }

                tiles++;
                job.setSampledAreaCount(tiles);
                job.setPhase("sampled_" + tiles + "_tiles");
                jobRepository.update(job);
            }
        }
        return new HeightField(minX, maxX, minZ, maxZ, heights);
    }

    private void validateRequest(StartRailPlanningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.build_id == null) {
            throw new IllegalArgumentException("build_id is required");
        }
        if (request.start_x == request.end_x && request.start_z == request.end_z) {
            throw new IllegalArgumentException("Start and end must differ");
        }
        if (request.world == null || request.world.isBlank()) {
            request.world = "minecraft:overworld";
        }
        if (request.rail_bed_block == null) {
            request.rail_bed_block = "minecraft:stone";
        }
        if (request.support_block == null) {
            request.support_block = "minecraft:stone_bricks";
        }
        if (request.power_block == null) {
            request.power_block = "minecraft:redstone_block";
        }
        if (request.tunnel_lining_block == null) {
            request.tunnel_lining_block = "minecraft:stone_bricks";
        }
        if (request.weight_overrides == null) {
            request.weight_overrides = new HashMap<>();
        }
    }

    private void markFailed(UUID jobId, String message) {
        try {
            RailPlanningJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            job.setStatus(RailPlanningStatus.PLAN_FAILED);
            job.setPhase("failed");
            job.setErrorMessage(message);
            jobRepository.update(job);
        } catch (SQLException e) {
            LOGGER.error("Failed to update planning job {}", jobId, e);
        }
    }

    private int effectiveInt(Map<String, Double> weights, String key, int fallback) {
        if (weights == null || !weights.containsKey(key)) {
            return fallback;
        }
        return (int) Math.round(weights.get(key));
    }

    private double effectiveDouble(Map<String, Double> weights, String key, double fallback) {
        if (weights == null || !weights.containsKey(key)) {
            return fallback;
        }
        return weights.get(key);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampStatic(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class StartRailPlanningRequest {
        public UUID build_id;
        public String world = "minecraft:overworld";
        public int start_x;
        public int start_y;
        public int start_z;
        public int end_x;
        public int end_y;
        public int end_z;
        public Map<String, Double> weight_overrides;
        public String rail_bed_block = "minecraft:stone";
        public String support_block = "minecraft:stone_bricks";
        public String power_block = "minecraft:redstone_block";
        public String tunnel_lining_block = "minecraft:stone_bricks";
    }

    private record GridPoint(int x, int z) {}

    private record PathNode(GridPoint point, double gScore, double fScore) {}

    private record RoutePoint(int x, int z, int surfaceY, int trackY) {}

    private enum SegmentType { SURFACE, BRIDGE, TUNNEL }

    private record SegmentPlan(SegmentType type, List<RoutePoint> points) {}

    private record HeightField(int minX, int maxX, int minZ, int maxZ, Map<GridPoint, Integer> heights) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ && heights.containsKey(new GridPoint(x, z));
        }

        int heightAt(int x, int z) {
            Integer height = heights.get(new GridPoint(x, z));
            if (height == null) {
                throw new IllegalStateException("Missing sampled height at " + x + "," + z);
            }
            return height;
        }
    }
}
