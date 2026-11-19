package ca.waltermiller.mcpapi.buildtask.service;

import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.*;
import ca.waltermiller.mcpapi.buildtask.repository.BuildRepository;
import ca.waltermiller.mcpapi.buildtask.repository.BuildSourceRepository;
import ca.waltermiller.mcpapi.buildtask.repository.TaskRepository;
import ca.waltermiller.mcpapi.endpoints.TaskExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import ca.waltermiller.mcpapi.buildtask.repository.BuildTransaction;
import ca.waltermiller.mcpapi.buildtask.repository.PostgreSQLBuildRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core business logic for build management.
 * Handles build creation, task queue management, and build execution orchestration.
 * Requirements: 1.1, 1.2, 2.1, 2.5, 3.1, 3.4
 */
public class BuildService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildService.class);
    
    private final BuildRepository buildRepository;
    private final TaskRepository taskRepository;
    private final BuildSourceRepository sourceRepository;
    private final TaskExecutor taskExecutor;
    private final ExecutorService executorService;
    private volatile boolean stopping;
    private final java.util.Set<CompletableFuture<?>> executions = new HashSet<>();

    public BuildService(BuildRepository buildRepository, TaskRepository taskRepository, TaskExecutor taskExecutor) {
        this(buildRepository, taskRepository, null, taskExecutor);
    }

    /** {@code sourceRepository} null disables placement provenance (placements are still recorded). */
    public BuildService(BuildRepository buildRepository, TaskRepository taskRepository,
                        BuildSourceRepository sourceRepository, TaskExecutor taskExecutor) {
        this(buildRepository, taskRepository, sourceRepository, taskExecutor, Executors.newCachedThreadPool(), false);
    }
    private final boolean inTransaction;
    private BuildService(BuildRepository buildRepository, TaskRepository taskRepository,
                         BuildSourceRepository sourceRepository, TaskExecutor taskExecutor,
                         ExecutorService executor, boolean inTransaction) {
        this.inTransaction = inTransaction;
        this.buildRepository = buildRepository;
        this.taskRepository = taskRepository;
        this.sourceRepository = sourceRepository;
        this.taskExecutor = taskExecutor;
        this.executorService = executor;
    }

    @FunctionalInterface
    private interface Change<T> { T run(BuildService service) throws SQLException; }
    private <T> T transaction(UUID buildId, Change<T> change) throws SQLException {
        if (stopping) throw new ca.waltermiller.mcpapi.operation.OperationRegistry.Failure(503, "Build service is stopping");
        if (buildRepository instanceof PostgreSQLBuildRepository postgres) {
            return BuildTransaction.run(postgres.databaseConfig(), buildId,
                (builds, tasks) -> change.run(new BuildService(builds, tasks, null, taskExecutor, executorService, true)));
        }
        synchronized (this) { return change.run(this); }
    }

    /**
     * Creates a new build with unique ID generation.
     * Requirements: 1.1, 1.2
     */
    public Build createBuild(CreateBuildRequest request) throws SQLException {
        if (request == null) {
            throw new IllegalArgumentException("Build request cannot be null");
        }

        // Create build with unique ID (UUID.randomUUID() in constructor)
        Build build = new Build(request.name, request.description, request.world);
        
        LOGGER.info("Creating new build: {} in world {}", build.getName(), build.getWorld());
        
        // Store in database
        Build savedBuild = buildRepository.create(build);
        
        LOGGER.info("Created build with ID: {}", savedBuild.getId());
        return savedBuild;
    }

    /**
     * A completed NBT placement to record. {@code provenance} (nullable) says where it came from;
     * {@code parentId} (nullable) is the build it replaced in place, which becomes SUPERSEDED in the
     * same transaction; {@code restoreBounds} (nullable) is the cuboid its undo snapshot covers when
     * that is larger than the structure (a revision snapshots the union of old and new bounds).
     */
    public record PlacementRecord(String filename, String world, int x, int y, int z, int sizeX, int sizeY, int sizeZ,
                                  String rotation, BuildSource provenance, UUID parentId, BoundingBox restoreBounds) {
        public PlacementRecord(String filename, String world, int x, int y, int z, int sizeX, int sizeY, int sizeZ,
                               String rotation) {
            this(filename, world, x, y, z, sizeX, sizeY, sizeZ, rotation, null, null, null);
        }
    }

    /**
     * Records a completed NBT structure placement as a build entry for spatial awareness.
     * The build and its single task are immediately marked COMPLETED — no execution needed.
     */
    public Build recordNbtPlacement(String filename, String world, int x, int y, int z,
                                    int sizeX, int sizeY, int sizeZ, String rotation) throws SQLException {
        return recordNbtPlacement(new PlacementRecord(filename, world, x, y, z, sizeX, sizeY, sizeZ, rotation));
    }

    public Build recordNbtPlacement(PlacementRecord record) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository postgres) {
            return BuildTransaction.runWithSources(postgres.databaseConfig(), record.parentId(),
                (builds, tasks, sources) -> new BuildService(builds, tasks, sources, taskExecutor, executorService, true)
                    .recordPlacement(record));
        }
        synchronized (this) { return recordPlacement(record); }
    }

    private Build recordPlacement(PlacementRecord record) throws SQLException {
        Build parent = null;
        if (record.parentId() != null) {
            parent = buildRepository.findById(record.parentId())
                .orElseThrow(() -> new IllegalArgumentException("Build not found: " + record.parentId()));
            if (parent.getStatus() != BuildStatus.COMPLETED) {
                throw new IllegalStateException("Build " + parent.getId() + " is " + parent.getStatus() + ", not COMPLETED");
            }
        }
        String filename = record.filename();
        Build build = new Build("NBT: " + filename,
            parent == null ? "Auto-recorded NBT structure placement" : "Revision of build " + parent.getId(), record.world());
        build.setParentBuildId(record.parentId());
        Build savedBuild = buildRepository.create(build);

        ObjectNode taskData = Json.MAPPER.createObjectNode()
            .put("x", record.x()).put("y", record.y()).put("z", record.z())
            .put("size_x", record.sizeX()).put("size_y", record.sizeY()).put("size_z", record.sizeZ())
            .put("filename", filename).put("rotation", record.rotation());
        if (record.restoreBounds() != null) {
            BoundingBox box = record.restoreBounds();
            taskData.set("restore_bounds", Json.MAPPER.createObjectNode()
                .put("min_x", box.getMinX()).put("min_y", box.getMinY()).put("min_z", box.getMinZ())
                .put("max_x", box.getMaxX()).put("max_y", box.getMaxY()).put("max_z", box.getMaxZ()));
        }

        BuildTask task = new BuildTask(savedBuild.getId(), 0, TaskType.NBT_STRUCTURE, taskData,
            parent == null ? "NBT structure placement" : "NBT structure revision");
        task.markCompleted();
        taskRepository.create(task);

        if (record.provenance() != null) {
            if (sourceRepository != null) sourceRepository.create(record.provenance().withBuildId(savedBuild.getId()));
            else LOGGER.warn("Placement provenance for build {} dropped: no source repository", savedBuild.getId());
        }

        savedBuild.setStatus(BuildStatus.COMPLETED);
        buildRepository.update(savedBuild);
        if (parent != null) {
            parent.setStatus(BuildStatus.SUPERSEDED);
            buildRepository.update(parent);
        }

        LOGGER.info("Recorded NBT placement '{}' at ({},{},{}) as build {}{}", filename, record.x(), record.y(), record.z(),
            savedBuild.getId(), parent == null ? "" : " (revision of " + parent.getId() + ")");
        return savedBuild;
    }

    /**
     * Records a completed terrain smoothing as a one-task build over the cuboid it may have
     * written, in the same task-data shape as an NBT placement so undo/redo reuse its bounds.
     */
    public Build recordTerrainSmooth(String world, int x, int y, int z, int sizeX, int sizeY, int sizeZ,
                                     String method, JsonNode parameters) throws SQLException {
        Build build = new Build("Terrain: " + method, "Auto-recorded terrain smoothing", world);
        Build savedBuild = buildRepository.create(build);

        ObjectNode taskData = Json.MAPPER.createObjectNode()
            .put("x", x).put("y", y).put("z", z)
            .put("size_x", sizeX).put("size_y", sizeY).put("size_z", sizeZ)
            .put("rotation", "NONE").put("method", method);
        taskData.set("parameters", parameters);

        BuildTask task = new BuildTask(savedBuild.getId(), 0, TaskType.TERRAIN_SMOOTH, taskData,
            "Terrain smoothing (" + method + ")");
        task.markCompleted();
        taskRepository.create(task);

        savedBuild.setStatus(BuildStatus.COMPLETED);
        buildRepository.update(savedBuild);

        LOGGER.info("Recorded terrain smoothing '{}' at ({},{},{}) size {}x{}x{} as build {}",
            method, x, y, z, sizeX, sizeY, sizeZ, savedBuild.getId());
        return savedBuild;
    }

    /** Task types recorded after a direct write; restorable from snapshots but never executed. */
    static boolean isSnapshotOnly(TaskType type) {
        return type == TaskType.NBT_STRUCTURE || type == TaskType.TERRAIN_SMOOTH;
    }

    /** Where a recorded NBT placement was written, as needed to rebuild its bounds. */
    public record NbtPlacement(Build build, int x, int y, int z, int sizeX, int sizeY, int sizeZ, String rotation,
                               BoundingBox restoreBounds, TaskType type, String filename) {
        public NbtPlacement(Build build, int x, int y, int z, int sizeX, int sizeY, int sizeZ, String rotation) {
            this(build, x, y, z, sizeX, sizeY, sizeZ, rotation, null, TaskType.NBT_STRUCTURE, null);
        }
    }

    /**
     * Loads the single NBT_STRUCTURE or TERRAIN_SMOOTH task of an auto-recorded placement. Empty when the build does not exist;
     * IllegalArgumentException when it is not an NBT placement, IllegalStateException when already reverted.
     */
    public Optional<NbtPlacement> getNbtPlacement(UUID buildId) throws SQLException {
        return getNbtPlacement(buildId, false);
    }

    public Optional<NbtPlacement> getNbtPlacement(UUID buildId, boolean redo) throws SQLException {
        Optional<Build> found = getBuild(buildId);
        if (found.isEmpty()) return Optional.empty();
        Build build = found.get();
        if (build.getStatus() == BuildStatus.SUPERSEDED) {
            throw new IllegalStateException("Build " + buildId + " was replaced by revision " + activeChildText(buildId)
                + "; undo_build that revision to bring this build back");
        }
        if (!redo && build.getStatus() == BuildStatus.REVERTED) {
            throw new IllegalStateException("Build " + buildId + " has already been reverted");
        }
        if (redo && build.getStatus() != BuildStatus.REVERTED) {
            throw new IllegalStateException("Only reverted builds can be redone");
        }
        return Optional.of(readPlacement(build));
    }

    /** The recorded placement whatever its status; empty when the build does not exist. */
    public Optional<NbtPlacement> getPlacementRecord(UUID buildId) throws SQLException {
        Optional<Build> found = getBuild(buildId);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(readPlacement(found.get()));
    }

    private NbtPlacement readPlacement(Build build) throws SQLException {
        List<BuildTask> tasks = taskRepository.findByBuildIdOrdered(build.getId());
        if (tasks.size() != 1 || !isSnapshotOnly(tasks.get(0).getTaskType())) {
            throw new IllegalArgumentException("Only NBT structure placements and terrain smoothing have placement records "
                + "(undo, redo, source, verify, revise); build " + build.getId() + " is a task-queue build");
        }
        JsonNode data = tasks.get(0).getTaskData();
        for (String field : new String[] {"x", "y", "z", "size_x", "size_y", "size_z"}) {
            if (!data.path(field).isInt()) throw new IllegalArgumentException("NBT placement record is missing " + field);
        }
        JsonNode restore = data.path("restore_bounds");
        BoundingBox restoreBounds = restore.isObject() ? new BoundingBox(restore.path("min_x").intValue(),
            restore.path("min_y").intValue(), restore.path("min_z").intValue(), restore.path("max_x").intValue(),
            restore.path("max_y").intValue(), restore.path("max_z").intValue()) : null;
        return new NbtPlacement(build, data.get("x").intValue(), data.get("y").intValue(), data.get("z").intValue(),
            data.get("size_x").intValue(), data.get("size_y").intValue(), data.get("size_z").intValue(),
            data.path("rotation").asText("NONE"), restoreBounds, tasks.get(0).getTaskType(),
            data.path("filename").asText(null));
    }

    /**
     * The placement a revision would replace: a COMPLETED NBT structure placement (not terrain smoothing).
     * Empty when the build does not exist.
     */
    public Optional<NbtPlacement> getRevisablePlacement(UUID buildId) throws SQLException {
        Optional<NbtPlacement> found = getNbtPlacement(buildId);
        if (found.isEmpty()) return found;
        NbtPlacement placement = found.get();
        if (placement.type() != TaskType.NBT_STRUCTURE) {
            throw new IllegalArgumentException("Only NBT structure placements can be revised");
        }
        if (placement.build().getStatus() != BuildStatus.COMPLETED) {
            throw new IllegalStateException("Build " + buildId + " is " + placement.build().getStatus() + "; only COMPLETED placements can be revised");
        }
        return found;
    }

    private String activeChildText(UUID buildId) throws SQLException {
        return buildRepository.findChildren(buildId).stream()
            .filter(child -> !child.getStatus().removedFromWorld())
            .map(child -> child.getId().toString()).reduce((a, b) -> b).orElse("(unknown)");
    }

    public List<Build> getChildren(UUID buildId) throws SQLException {
        return buildRepository.findChildren(buildId);
    }

    public Optional<BuildSource> getSource(UUID buildId) throws SQLException {
        return sourceRepository == null ? Optional.empty() : sourceRepository.findByBuildId(buildId);
    }

    /** Provenance summaries (no source text) for list views; empty when provenance is not configured. */
    public java.util.Map<UUID, BuildSource> getSourceSummaries(java.util.Collection<UUID> buildIds) throws SQLException {
        return sourceRepository == null ? java.util.Map.of() : sourceRepository.findSummaries(buildIds);
    }

    private void requireNotReverted(Build build) {
        if (build.getStatus() == BuildStatus.IN_PROGRESS) throw new IllegalStateException("Cannot edit a build while it is executing");
        if (build.getStatus() == BuildStatus.REVERTED) {
            throw new IllegalStateException("Cannot edit a reverted build's placement history; use redo_build or create a new build");
        }
        if (build.getStatus() == BuildStatus.SUPERSEDED) {
            throw new IllegalStateException("Cannot edit a build that was replaced by a revision");
        }
    }

    /** Unreverted builds created after {@code build} whose recorded tasks intersect {@code box}. */
    public List<Build> findLaterOverlappingBuilds(Build build, BoundingBox box) throws SQLException {
        return buildRepository.findByLocationIntersection(build.getWorld(), box).stream()
            .filter(other -> !other.getId().equals(build.getId()))
            .filter(other -> !other.getStatus().removedFromWorld())
            .filter(other -> other.getCreatedAt() != null && build.getCreatedAt() != null
                && other.getCreatedAt().isAfter(build.getCreatedAt()))
            .toList();
    }

    /**
     * Undo committed. A revision's parent is back in the world (the revision's undo snapshot is the
     * parent's placed state), so it returns to COMPLETED unless another revision of it is still active.
     */
    public Build markReverted(Build build) throws SQLException {
        return lineageChange(build, BuildStatus.REVERTED);
    }

    /** Redo committed. A redone revision supersedes its parent again. */
    public Build markRestored(Build build) throws SQLException {
        return lineageChange(build, BuildStatus.COMPLETED);
    }

    private Build lineageChange(Build build, BuildStatus status) throws SQLException {
        if (build.getParentBuildId() != null && !inTransaction && buildRepository instanceof PostgreSQLBuildRepository) {
            return transaction(build.getId(), service -> service.lineageChange(build, status));
        }
        build.setStatus(status);
        buildRepository.update(build);
        UUID parentId = build.getParentBuildId();
        if (parentId == null) return build;
        Optional<Build> found = buildRepository.findById(parentId);
        if (found.isEmpty()) return build;
        Build parent = found.get();
        if (status == BuildStatus.REVERTED && parent.getStatus() == BuildStatus.SUPERSEDED) {
            boolean otherActive = buildRepository.findChildren(parentId).stream()
                .anyMatch(child -> !child.getId().equals(build.getId()) && !child.getStatus().removedFromWorld());
            if (!otherActive) {
                parent.setStatus(BuildStatus.COMPLETED);
                buildRepository.update(parent);
            }
        } else if (status == BuildStatus.COMPLETED && parent.getStatus() == BuildStatus.COMPLETED) {
            parent.setStatus(BuildStatus.SUPERSEDED);
            buildRepository.update(parent);
        }
        return build;
    }

    /** Reject unsupported operations before an endpoint reports asynchronous acceptance. */
    public void validateTaskExecution(UUID buildId, boolean replay) throws SQLException {
        Build build = buildRepository.findById(buildId)
            .orElseThrow(() -> new IllegalArgumentException("Build not found: " + buildId));
        validateTaskExecution(build, taskRepository.findByBuildIdOrdered(buildId), replay);
    }

    private void validateTaskExecution(Build build, List<BuildTask> tasks, boolean replay) {
        if (build.getStatus().removedFromWorld()
            || tasks.stream().anyMatch(task -> isSnapshotOnly(task.getTaskType()))) {
            throw new IllegalStateException("NBT placements and terrain smoothing cannot be executed or replayed; use redo_build after undo_build");
        }
        if (replay && build.getStatus() == BuildStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot replay a build while it is executing");
        }
        for (BuildTask task : tasks) taskExecutor.validateDimensions(task);
    }

    /**
     * Retrieves build information by ID.
     * Requirements: 1.3
     */
    public Optional<Build> getBuild(UUID buildId) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        
        return buildRepository.findById(buildId);
    }

    /**
     * Adds a task to the build queue.
     * Requirements: 2.1, 2.5
     */
    public BuildTask addTask(UUID buildId, AddTaskRequest request) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository) { return transaction(buildId, s -> s.addTask(buildId, request)); }
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Task request cannot be null");
        }

        // Verify build exists
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }

        Build build = buildOpt.get();
        requireNotReverted(build);
        if (build.getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot add tasks to completed build: " + buildId);
        }

        List<BuildTask> tasks = new ArrayList<>(taskRepository.findByBuildIdOrdered(buildId));
        BuildTask task = new BuildTask(buildId, tasks.size(), request.task_type, request.task_data, request.description);
        tasks.add(task);
        taskRepository.updateTaskQueue(buildId, tasks);
        return task;
    }

    public void addTasks(UUID buildId, List<AddTaskRequest> requests) throws SQLException {
        transaction(buildId, service -> {
            Build build = service.buildRepository.findById(buildId).orElseThrow(() -> new IllegalArgumentException("Build not found"));
            service.requireNotReverted(build);
            if (build.getStatus() == BuildStatus.COMPLETED) throw new IllegalStateException("Cannot add tasks to completed build");
            List<BuildTask> queue = new ArrayList<>(service.taskRepository.findByBuildIdOrdered(buildId));
            for (AddTaskRequest request : requests) queue.add(new BuildTask(buildId, queue.size(), request.task_type, request.task_data, request.description));
            service.taskRepository.updateTaskQueue(buildId, queue);
            return null;
        });
    }

    /**
     * Retrieves the task queue for a build.
     * Requirements: 2.3
     */
    public List<BuildTask> getTasks(UUID buildId) throws SQLException {
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        
        return taskRepository.findByBuildIdOrdered(buildId);
    }

    /**
     * Updates the task queue for a build (reorders tasks).
     * Requirements: 2.5
     */
    public void updateTaskQueue(UUID buildId, List<BuildTask> tasks) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository) { transaction(buildId, s -> { s.updateTaskQueue(buildId, tasks); return null; }); return; }
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (tasks == null) {
            throw new IllegalArgumentException("Tasks list cannot be null");
        }

        // Verify build exists
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }

        Build build = buildOpt.get();
        requireNotReverted(build);
        if (build.getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot modify tasks for completed build: " + buildId);
        }

        List<BuildTask> existing = taskRepository.findByBuildIdOrdered(buildId);
        var byId = existing.stream().collect(java.util.stream.Collectors.toMap(BuildTask::getId, t -> t));
        var seen = new HashSet<UUID>();
        List<BuildTask> ordered = new ArrayList<>();
        for (BuildTask task : tasks) {
            if (task == null || !byId.containsKey(task.getId()) || !seen.add(task.getId())) {
                throw new IllegalArgumentException("Reorder must contain every existing task ID exactly once");
            }
            ordered.add(byId.get(task.getId()));
        }
        if (seen.size() != existing.size()) throw new IllegalArgumentException("Reorder must contain every existing task ID exactly once");
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setTaskOrder(i);
        taskRepository.updateTaskQueue(buildId, ordered);

        LOGGER.info("Updated task queue for build {}", buildId);
    }

    /**
     * Executes all tasks in a build.
     * Requirements: 3.1, 3.4
     */
    public CompletableFuture<BuildExecutionResult> executeBuild(UUID buildId) {
        return executeBuild(buildId, null);
    }

    public CompletableFuture<BuildExecutionResult> executeBuild(UUID buildId, String lockId) {
        return startExecution(buildId, lockId, false);
    }

    private record Claim(Build build, List<BuildTask> tasks) {}

    private synchronized CompletableFuture<BuildExecutionResult> startExecution(UUID buildId, String lockId, boolean replay) {
        if (stopping) throw new ca.waltermiller.mcpapi.operation.OperationRegistry.Failure(503, "Build execution is stopping");
        final Claim claim;
        try {
            claim = transaction(buildId, service -> {
                Build build = service.buildRepository.findById(buildId)
                    .orElseThrow(() -> new IllegalArgumentException("Build not found: " + buildId));
                List<BuildTask> tasks = service.taskRepository.findByBuildIdOrdered(buildId);
                validateTaskExecution(build, tasks, replay);
                if (build.getStatus() == BuildStatus.IN_PROGRESS || (!replay && build.getStatus() == BuildStatus.COMPLETED)) {
                    throw new IllegalStateException("Build is already executing or completed");
                }
                if (replay) {
                    for (BuildTask task : tasks) {
                        task.resetForReplay();
                        service.taskRepository.update(task);
                    }
                }
                build.setStatus(BuildStatus.IN_PROGRESS);
                service.buildRepository.update(build);
                return new Claim(build, tasks);
            });
        } catch (SQLException failure) {
            throw new ca.waltermiller.mcpapi.operation.OperationRegistry.Failure(500, "Could not claim build execution");
        }
        CompletableFuture<ExecutionProgress> chain = CompletableFuture.completedFuture(new ExecutionProgress());
        for (BuildTask task : claim.tasks()) {
            chain = chain.thenComposeAsync(progress -> {
                if (progress.stop) return CompletableFuture.completedFuture(progress);
                if (task.getStatus() == TaskStatus.COMPLETED) {
                    progress.executed++;
                    return CompletableFuture.completedFuture(progress);
                }
                try {
                    task.markExecuting();
                    taskRepository.update(task);
                } catch (SQLException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
                return taskExecutor.executeTaskAsync(task, lockId).thenApplyAsync(result -> {
                    try { taskRepository.update(task); }
                    catch (SQLException failure) { throw new java.util.concurrent.CompletionException(failure); }
                    if (result.success()) progress.executed++;
                    else { progress.failed++; progress.stop = result.lockError() != null; }
                    return progress;
                }, executorService);
            }, executorService);
        }
        CompletableFuture<BuildExecutionResult> execution = chain.handleAsync((progress, failure) -> {
            Build build = claim.build();
            boolean success = failure == null && progress.failed == 0;
            build.setStatus(success ? BuildStatus.COMPLETED : BuildStatus.FAILED);
            try { buildRepository.update(build); }
            catch (SQLException persistence) { throw new java.util.concurrent.CompletionException(persistence); }
            if (failure != null) LOGGER.error("Build execution failed for {}", buildId, failure);
            return new BuildExecutionResult(buildId, success, progress == null ? 0 : progress.executed,
                progress == null ? 1 : progress.failed, List.of(), failure == null
                    ? success ? "Build completed successfully" : "Some tasks failed" : "Build execution failed");
        }, executorService);
        executions.add(execution);
        execution.whenComplete((result, failure) -> {
            synchronized (this) {
                executions.remove(execution);
                if (stopping && executions.isEmpty()) executorService.shutdown();
            }
        });
        return execution;
    }

    private static final class ExecutionProgress {
        int executed;
        int failed;
        boolean stop;
    }

    /**
     * Replays a build by resetting all task statuses and re-executing.
     * Requirements: Allows re-running completed or failed builds.
     */
    public CompletableFuture<BuildExecutionResult> replayBuild(UUID buildId) {
        return replayBuild(buildId, null);
    }

    public CompletableFuture<BuildExecutionResult> replayBuild(UUID buildId, String lockId) {
        return startExecution(buildId, lockId, true);
    }

    /**
     * Creates a copy of a build with a new UUID. All non-NBT tasks are cloned as QUEUED.
     * The source build is left unchanged, preserving its placement history.
     *
     * @return the newly created Build
     */
    public Build cloneBuild(UUID sourceId) throws SQLException {
        if (sourceId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }

        Optional<Build> sourceOpt = buildRepository.findById(sourceId);
        if (sourceOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + sourceId);
        }

        Build source = sourceOpt.get();
        if (source.getStatus() == BuildStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot clone build that is currently executing: " + sourceId);
        }

        Build newBuild = new Build(source.getName(), source.getDescription(), source.getWorld());
        Build savedBuild = buildRepository.create(newBuild);

        List<BuildTask> sourceTasks = taskRepository.findByBuildIdOrdered(sourceId);
        int clonedCount = 0;
        for (BuildTask task : sourceTasks) {
            if (isSnapshotOnly(task.getTaskType())) {
                continue;
            }
            BuildTask cloned = new BuildTask(savedBuild.getId(), task.getTaskOrder(),
                task.getTaskType(), task.getTaskData().deepCopy(), task.getDescription());
            taskRepository.addToQueue(savedBuild.getId(), cloned);
            clonedCount++;
        }

        LOGGER.info("Cloned build {} → {} ({} tasks)", sourceId, savedBuild.getId(), clonedCount);
        return savedBuild;
    }

    /**
     * Shuts down the executor service.
     */
    public synchronized void shutdown() {
        stopping = true;
        if (executions.isEmpty()) executorService.shutdown();
    }

    public boolean awaitTermination(long deadline) throws InterruptedException {
        return executorService.awaitTermination(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Deletes a task from a build and reorders remaining tasks.
     */
    public void deleteTask(UUID buildId, UUID taskId) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository) { transaction(buildId, s -> { s.deleteTask(buildId, taskId); return null; }); return; }
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        // Verify build exists and is not completed
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        requireNotReverted(buildOpt.get());
        if (buildOpt.get().getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot delete tasks from completed build: " + buildId);
        }

        // Verify task exists and belongs to this build
        Optional<BuildTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (!taskOpt.get().getBuildId().equals(buildId)) {
            throw new IllegalArgumentException("Task does not belong to build: " + buildId);
        }

        LOGGER.info("Deleting task {} from build {}", taskId, buildId);

        // Delete the task
        taskRepository.deleteById(taskId);

        // Reorder remaining tasks to close the gap
        List<BuildTask> remainingTasks = taskRepository.findByBuildIdOrdered(buildId);
        for (int i = 0; i < remainingTasks.size(); i++) {
            BuildTask task = remainingTasks.get(i);
            if (task.getTaskOrder() != i) {
                task.setTaskOrder(i);
            }
        }
        taskRepository.updateTaskQueue(buildId, remainingTasks);

        LOGGER.info("Deleted task {} and reordered {} remaining tasks", taskId, remainingTasks.size());
    }

    /**
     * Inserts a task at a specific position in the queue.
     * Shifts existing tasks at and after that position.
     */
    public BuildTask insertTaskAt(UUID buildId, AddTaskRequest request, int position) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository) { return transaction(buildId, s -> s.insertTaskAt(buildId, request, position)); }
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Task request cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Position cannot be negative");
        }

        // Verify build exists and is not completed
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        requireNotReverted(buildOpt.get());
        if (buildOpt.get().getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot add tasks to completed build: " + buildId);
        }

        // Get current tasks
        List<BuildTask> tasks = taskRepository.findByBuildIdOrdered(buildId);

        // Clamp position to valid range
        int insertPosition = Math.min(position, tasks.size());

        // Create the new task
        BuildTask newTask = new BuildTask(buildId, insertPosition, request.task_type, request.task_data, request.description);

        LOGGER.info("Inserting task {} at position {} in build {}", newTask.getId(), insertPosition, buildId);

        tasks = new ArrayList<>(tasks);
        tasks.add(insertPosition, newTask);
        for (int i = 0; i < tasks.size(); i++) tasks.get(i).setTaskOrder(i);
        taskRepository.updateTaskQueue(buildId, tasks);
        return newTask;
    }

    /**
     * Updates a task's task_data (merge) and/or description.
     */
    public BuildTask updateTask(UUID buildId, UUID taskId, com.fasterxml.jackson.databind.JsonNode partialTaskData,
                                String description) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository) { return transaction(buildId, s -> s.updateTask(buildId, taskId, partialTaskData, description)); }
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        // Verify build exists and is not completed
        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        requireNotReverted(buildOpt.get());
        if (buildOpt.get().getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot update tasks in completed build: " + buildId);
        }

        // Verify task exists and belongs to this build
        Optional<BuildTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        BuildTask task = taskOpt.get();
        if (!task.getBuildId().equals(buildId)) {
            throw new IllegalArgumentException("Task does not belong to build: " + buildId);
        }

        LOGGER.info("Updating task {} in build {}", taskId, buildId);

        // Merge partial task_data with existing
        if (partialTaskData != null && !partialTaskData.isNull()) {
            JsonNode existingData = task.getTaskData();
            ObjectNode merged;

            if (existingData != null && existingData.isObject()) {
                merged = existingData.deepCopy();
            } else {
                merged = Json.MAPPER.createObjectNode();
            }

            // Merge fields from partialTaskData into existing
            partialTaskData.fields().forEachRemaining(entry ->
                merged.set(entry.getKey(), entry.getValue()));

            task.setTaskData(merged);
        }

        // Update description if provided
        if (description != null) {
            task.setDescription(description);
        }

        BuildTask updatedTask = taskRepository.update(task);
        if (inTransaction) taskRepository.updateTaskQueue(buildId, taskRepository.findByBuildIdOrdered(buildId));
        LOGGER.info("Updated task {}", taskId);
        return updatedTask;
    }

    /**
     * Shifts every task in a build by (dx, dy, dz) before execution.
     * Rejected if the build or any of its tasks has already executed, since that would
     * misrepresent already-placed blocks as having moved.
     */
    public List<BuildTask> translateBuild(UUID buildId, int dx, int dy, int dz) throws SQLException {
        if (!inTransaction && buildRepository instanceof PostgreSQLBuildRepository) { return transaction(buildId, s -> s.translateBuild(buildId, dx, dy, dz)); }
        if (buildId == null) {
            throw new IllegalArgumentException("Build ID cannot be null");
        }

        Optional<Build> buildOpt = buildRepository.findById(buildId);
        if (buildOpt.isEmpty()) {
            throw new IllegalArgumentException("Build not found: " + buildId);
        }
        Build build = buildOpt.get();
        requireNotReverted(build);
        if (build.getStatus() == BuildStatus.COMPLETED) {
            throw new IllegalStateException("Cannot translate completed build: " + buildId);
        }

        List<BuildTask> tasks = taskRepository.findByBuildIdOrdered(buildId);

        for (BuildTask task : tasks) {
            if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.EXECUTING) {
                throw new IllegalStateException(
                    "Cannot translate build " + buildId + ": task " + task.getId() +
                    " (order " + task.getTaskOrder() + ") has already " +
                    (task.getStatus() == TaskStatus.COMPLETED ? "executed" : "started executing"));
            }
        }

        for (BuildTask task : tasks) {
            com.fasterxml.jackson.databind.JsonNode translated =
                TaskDataTranslator.translate(task.getTaskType(), task.getTaskData(), dx, dy, dz);
            task.setTaskData(translated);
        }

        taskRepository.updateTaskQueue(buildId, tasks);

        LOGGER.info("Translated build {} by ({},{},{})", buildId, dx, dy, dz);
        return tasks;
    }

    /**
     * Request object for creating a new build.
     */
    public static class CreateBuildRequest {
        public String name;
        public String description;
        public String world = "minecraft:overworld";

        public CreateBuildRequest() {}

        public CreateBuildRequest(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public CreateBuildRequest(String name, String description, String world) {
            this.name = name;
            this.description = description;
            this.world = world;
        }
    }

    /**
     * Request object for adding a task to a build.
     */
    public static class AddTaskRequest {
        public TaskType task_type;
        public com.fasterxml.jackson.databind.JsonNode task_data;
        public String description = "";

        public AddTaskRequest() {}

        public AddTaskRequest(TaskType task_type,
                              com.fasterxml.jackson.databind.JsonNode task_data, String description) {
            this.task_type = task_type;
            this.task_data = task_data;
            this.description = description;
        }
    }

    /**
     * Result of build execution.
     */
    public static class BuildExecutionResult {
        public final UUID buildId;
        public final boolean success;
        public final int tasksExecuted;
        public final int tasksFailed;
        public final List<TaskExecutor.TaskExecutionResult> taskResults;
        public final String errorMessage;

        public BuildExecutionResult(UUID buildId, boolean success, int tasksExecuted, int tasksFailed,
                                  List<TaskExecutor.TaskExecutionResult> taskResults, String errorMessage) {
            this.buildId = buildId;
            this.success = success;
            this.tasksExecuted = tasksExecuted;
            this.tasksFailed = tasksFailed;
            this.taskResults = taskResults;
            this.errorMessage = errorMessage;
        }
    }
}
