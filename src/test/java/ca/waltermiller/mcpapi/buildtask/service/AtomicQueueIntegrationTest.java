package ca.waltermiller.mcpapi.buildtask.service;

import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.*;
import ca.waltermiller.mcpapi.buildtask.repository.*;
import ca.waltermiller.mcpapi.database.DatabaseManager;
import ca.waltermiller.mcpapi.endpoints.TaskExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class AtomicQueueIntegrationTest {
    private BuildService service;
    private PostgreSQLBuildRepository builds;
    private PostgreSQLTaskRepository tasks;
    private TaskExecutor executor;
    private UUID id;
    @BeforeEach void start() throws Exception {
        assertThat(System.getenv("DB_NAME")).as("Use an isolated test database").contains("test");
        var manager = DatabaseManager.getInstance();
        manager.initialize();
        builds = new PostgreSQLBuildRepository(manager.getDatabaseConfig());
        tasks = new PostgreSQLTaskRepository(manager.getDatabaseConfig());
        executor = mock(TaskExecutor.class);
        service = new BuildService(builds, tasks, executor);
        id = service.createBuild(new BuildService.CreateBuildRequest("atomic test", "isolated test")).getId();
    }
    @AfterEach void stop() throws Exception {
        if (service != null) { service.shutdown(); assertThat(service.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue(); }
        if (id != null) builds.deleteById(id);
    }
    private BuildService.AddTaskRequest request(String description) {
        return new BuildService.AddTaskRequest(TaskType.BLOCK_FILL, Json.MAPPER.createObjectNode()
            .put("x1", 1).put("y1", 64).put("z1", 2).put("x2", 2).put("y2", 64).put("z2", 3)
            .put("block_type", "minecraft:stone"), description);
    }
    @Test void insertsEmptyStartMiddleEndAndPreservesMetadata() throws Exception {
        var original = service.insertTaskAt(id, request("original"), 0);
        original.setTaskOrder(17); // Legacy ordering is preserved until the next queue edit.
        original.markFailed("stored error");
        original.setExecutedAt(Instant.parse("2026-01-01T01:02:03Z"));
        tasks.update(original);
        var start = service.insertTaskAt(id, request("start"), 0);
        var end = service.insertTaskAt(id, request("end"), 100);
        var middle = service.insertTaskAt(id, request("middle"), 2);
        var queue = service.getTasks(id);
        assertThat(queue).extracting(BuildTask::getId).containsExactly(start.getId(), original.getId(), middle.getId(), end.getId());
        assertThat(queue).extracting(BuildTask::getTaskOrder).containsExactly(0, 1, 2, 3);
        var retained = queue.get(1);
        assertThat(retained.getTaskData()).isEqualTo(original.getTaskData());
        assertThat(retained.getDescription()).isEqualTo("original");
        assertThat(retained.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(retained.getExecutedAt()).isEqualTo(original.getExecutedAt());
        assertThat(retained.getErrorMessage()).isEqualTo("stored error");
        assertThat(retained.getCoordinates()).isEqualTo(original.getCoordinates());
        Collections.reverse(queue);
        service.updateTaskQueue(id, queue);
        service.deleteTask(id, middle.getId());
        var appended = service.addTask(id, request("append"));
        assertThat(service.getTasks(id)).extracting(BuildTask::getId).containsExactly(end.getId(), original.getId(), start.getId(), appended.getId());
        assertThat(service.getTasks(id)).extracting(BuildTask::getTaskOrder).containsExactly(0, 1, 2, 3);
    }
    @Test void invalidReorderAndFailedReplacementRollback() throws Exception {
        var first = service.addTask(id, request("first"));
        var second = service.addTask(id, request("second"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.updateTaskQueue(id, List.of(first)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.updateTaskQueue(id, List.of(first, first)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.updateTaskQueue(id, List.of(first,
            new BuildTask(UUID.randomUUID(), 0, TaskType.BLOCK_FILL, first.getTaskData(), "foreign"))));
        var invalid = request("invalid"); invalid.task_type = null;
        assertThatThrownBy(() -> service.insertTaskAt(id, invalid, 0)).isInstanceOf(RuntimeException.class);
        assertThat(service.getTasks(id)).extracting(BuildTask::getId).containsExactly(first.getId(), second.getId());
        var config = DatabaseManager.getInstance().getDatabaseConfig();
        assertThatThrownBy(() -> BuildTransaction.run(config, id, (buildRepo, taskRepo) -> {
            taskRepo.updateTaskQueue(id, List.of());
            throw new java.sql.SQLException("forced rollback");
        })).isInstanceOf(java.sql.SQLException.class);
        assertThat(service.getTasks(id)).extracting(BuildTask::getId).containsExactly(first.getId(), second.getId());
    }
    @Test void concurrentAppendsDoNotLoseTasks() throws Exception {
        try (var threads = Executors.newFixedThreadPool(8)) {
            List<Future<?>> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) pending.add(threads.submit(() -> { service.addTask(id, request("append")); return null; }));
            for (var future : pending) future.get(10, TimeUnit.SECONDS);
        }
        var queue = service.getTasks(id);
        assertThat(queue).hasSize(24);
        assertThat(queue.stream().map(BuildTask::getId).distinct().count()).isEqualTo(24);
        for (int i = 0; i < queue.size(); i++) assertThat(queue.get(i).getTaskOrder()).isEqualTo(i);
    }
    @Test void executionClaimsExcludeReplayAndQueueEdits() throws Exception {
        service.addTask(id, request("execute"));
        var completed = new CompletableFuture<TaskExecutor.TaskExecutionResult>();
        when(executor.executeTaskAsync(any(), nullable(String.class))).thenReturn(completed);
        var execution = service.executeBuild(id);
        assertThat(builds.findById(id).orElseThrow().getStatus()).isEqualTo(BuildStatus.IN_PROGRESS);
        assertThatIllegalStateException().isThrownBy(() -> service.executeBuild(id));
        assertThatIllegalStateException().isThrownBy(() -> service.replayBuild(id));
        assertThatIllegalStateException().isThrownBy(() -> service.addTask(id, request("late")));
        completed.complete(new TaskExecutor.TaskExecutionResult(true, null, "done"));
        assertThat(execution.get(5, TimeUnit.SECONDS).success).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> service.executeBuild(id));
        var replayDone = new CompletableFuture<TaskExecutor.TaskExecutionResult>();
        when(executor.executeTaskAsync(any(), nullable(String.class))).thenReturn(replayDone);
        var replay = service.replayBuild(id);
        assertThatIllegalStateException().isThrownBy(() -> service.replayBuild(id));
        replayDone.complete(new TaskExecutor.TaskExecutionResult(true, null, "done"));
        assertThat(replay.get(5, TimeUnit.SECONDS).success).isTrue();
    }

    @Test void independentServicesRaceForOneExecutionClaim() throws Exception {
        service.addTask(id, request("execute"));
        var completion = new CompletableFuture<TaskExecutor.TaskExecutionResult>();
        when(executor.executeTaskAsync(any(), nullable(String.class))).thenReturn(completion);
        var other = new BuildService(builds, tasks, executor);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Object> first = () -> { barrier.await(); try { return service.executeBuild(id); } catch (IllegalStateException conflict) { return conflict; } };
            Callable<Object> second = () -> { barrier.await(); try { return other.executeBuild(id); } catch (IllegalStateException conflict) { return conflict; } };
            var a = threads.submit(first); var b = threads.submit(second);
            Object left = a.get(5, TimeUnit.SECONDS), right = b.get(5, TimeUnit.SECONDS);
            assertThat(List.of(left, right).stream().filter(IllegalStateException.class::isInstance).count()).isEqualTo(1);
            completion.complete(new TaskExecutor.TaskExecutionResult(true, null, "done"));
            if (left instanceof CompletableFuture<?> future) future.get(5, TimeUnit.SECONDS);
            if (right instanceof CompletableFuture<?> future) future.get(5, TimeUnit.SECONDS);
        } finally {
            completion.complete(new TaskExecutor.TaskExecutionResult(true, null, "done"));
            other.shutdown();
            assertThat(other.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
        }
    }
    @Test void startupSweepUnsticksInterruptedExecution() throws Exception {
        var done = service.insertTaskAt(id, request("done"), 0);
        var interrupted = service.insertTaskAt(id, request("interrupted"), 1);
        done.markCompleted();
        tasks.update(done);
        interrupted.markExecuting();
        tasks.update(interrupted);
        var build = builds.findById(id).orElseThrow();
        build.setStatus(BuildStatus.IN_PROGRESS);
        builds.update(build);
        assertThatThrownBy(() -> service.addTask(id, request("blocked"))).isInstanceOf(IllegalStateException.class);

        var recovered = InterruptedExecutions.fail(DatabaseManager.getInstance().getDatabaseConfig());

        assertThat(recovered.builds()).isGreaterThanOrEqualTo(1);
        assertThat(recovered.tasks()).isGreaterThanOrEqualTo(1);
        assertThat(builds.findById(id).orElseThrow().getStatus()).isEqualTo(BuildStatus.FAILED);
        var queue = service.getTasks(id);
        assertThat(queue.get(0).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(queue.get(1).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(queue.get(1).getErrorMessage()).isEqualTo(InterruptedExecutions.TASK_ERROR);
        assertThat(service.addTask(id, request("after recovery"))).isNotNull();
    }
}
