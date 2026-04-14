package ca.waltermiller.mcpapi.buildtask.service;

import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.model.BuildStatus;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskStatus;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import ca.waltermiller.mcpapi.buildtask.repository.BuildRepository;
import ca.waltermiller.mcpapi.buildtask.repository.TaskRepository;
import ca.waltermiller.mcpapi.endpoints.TaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildServiceTest {

    @Mock
    private BuildRepository buildRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskExecutor taskExecutor;

    private BuildService buildService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        buildService = new BuildService(buildRepository, taskRepository, taskExecutor);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        buildService.shutdown();
    }

    @Test
    void addTaskRejectsCompletedBuild() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("done", "desc");
        build.setId(buildId);
        build.setStatus(BuildStatus.COMPLETED);
        when(buildRepository.findById(buildId)).thenReturn(Optional.of(build));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            buildService.addTask(buildId, new BuildService.AddTaskRequest(TaskType.BLOCK_FILL, validFillData(), "desc")));

        assertThat(exception.getMessage()).contains("Cannot add tasks to completed build");
    }

    @Test
    void updateTaskMergesPartialTaskDataAndDescription() throws Exception {
        UUID buildId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);

        ObjectNode existing = validFillData();
        BuildTask task = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, existing, "old");
        task.setId(taskId);

        ObjectNode partial = objectMapper.createObjectNode();
        partial.put("block_type", "minecraft:dirt");
        partial.put("notify_neighbors", true);

        when(buildRepository.findById(buildId)).thenReturn(Optional.of(build));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.update(any(BuildTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BuildTask updated = buildService.updateTask(buildId, taskId, partial, "new description");

        assertThat(updated.getDescription()).isEqualTo("new description");
        assertThat(updated.getTaskData().get("block_type").asText()).isEqualTo("minecraft:dirt");
        assertThat(updated.getTaskData().get("x1").asInt()).isEqualTo(existing.get("x1").asInt());
        assertThat(updated.getTaskData().get("notify_neighbors").asBoolean()).isTrue();
    }

    @Test
    void deleteTaskReordersRemainingTasks() throws Exception {
        UUID buildId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);

        BuildTask toDelete = new BuildTask(buildId, 1, TaskType.BLOCK_FILL, validFillData(), "delete");
        toDelete.setId(deleteId);
        BuildTask remainingA = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, validFillData(), "a");
        BuildTask remainingB = new BuildTask(buildId, 2, TaskType.BLOCK_FILL, validFillData(), "b");

        when(buildRepository.findById(buildId)).thenReturn(Optional.of(build));
        when(taskRepository.findById(deleteId)).thenReturn(Optional.of(toDelete));
        when(taskRepository.findByBuildIdOrdered(buildId)).thenReturn(List.of(remainingA, remainingB));

        buildService.deleteTask(buildId, deleteId);

        ArgumentCaptor<List<BuildTask>> queueCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskRepository).updateTaskQueue(eq(buildId), queueCaptor.capture());
        List<BuildTask> reordered = queueCaptor.getValue();
        assertThat(reordered).hasSize(2);
        assertThat(reordered.get(0).getTaskOrder()).isEqualTo(0);
        assertThat(reordered.get(1).getTaskOrder()).isEqualTo(1);
    }

    @Test
    void executeBuildMarksCompletedWhenAllTasksSucceed() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);
        BuildTask task = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, validFillData(), "fill");

        when(buildRepository.findById(buildId)).thenReturn(Optional.of(build));
        when(buildRepository.update(any(Build.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.findByBuildIdOrdered(buildId)).thenReturn(List.of(task));
        doAnswer(invocation -> {
            task.markCompleted();
            return new TaskExecutor.TaskExecutionResult(true, null, "ok");
        }).when(taskExecutor).executeTask(task);

        BuildService.BuildExecutionResult result = buildService.executeBuild(buildId).get(5, TimeUnit.SECONDS);

        assertThat(result.success).isTrue();
        assertThat(result.tasksExecuted).isEqualTo(1);
        assertThat(result.tasksFailed).isZero();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(build.getStatus()).isEqualTo(BuildStatus.COMPLETED);
        verify(taskRepository).update(task);
    }

    @Test
    void executeBuildMarksFailedWhenTaskFails() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);
        BuildTask task = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, validFillData(), "fill");

        when(buildRepository.findById(buildId)).thenReturn(Optional.of(build));
        when(buildRepository.update(any(Build.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.findByBuildIdOrdered(buildId)).thenReturn(List.of(task));
        when(taskExecutor.executeTask(task)).thenReturn(new TaskExecutor.TaskExecutionResult(false, "boom", null));

        BuildService.BuildExecutionResult result = buildService.executeBuild(buildId).get(5, TimeUnit.SECONDS);

        assertThat(result.success).isFalse();
        assertThat(result.tasksExecuted).isZero();
        assertThat(result.tasksFailed).isEqualTo(1);
        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
    }

    @Test
    void replayBuildResetsTasksBeforeExecutingAgain() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);
        build.setStatus(BuildStatus.FAILED);
        BuildTask task = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, validFillData(), "fill");
        task.markFailed("old");

        when(buildRepository.findById(buildId)).thenReturn(Optional.of(build), Optional.of(build));
        when(buildRepository.update(any(Build.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.findByBuildIdOrdered(buildId)).thenReturn(List.of(task), List.of(task));
        doAnswer(invocation -> {
            task.markCompleted();
            return new TaskExecutor.TaskExecutionResult(true, null, "ok");
        }).when(taskExecutor).executeTask(task);

        BuildService.BuildExecutionResult result = buildService.replayBuild(buildId).get(5, TimeUnit.SECONDS);

        assertThat(result.success).isTrue();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(taskRepository, atLeastOnce()).update(task);
    }

    private ObjectNode validFillData() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("x1", 0);
        node.put("y1", 64);
        node.put("z1", 0);
        node.put("x2", 2);
        node.put("y2", 64);
        node.put("z2", 2);
        node.put("block_type", "minecraft:stone");
        return node;
    }
}
