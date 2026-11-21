package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskStatus;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorTest {

    @Mock
    private MinecraftServer mockServer;

    private TaskExecutor taskExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        taskExecutor = new TaskExecutor(mockServer);
    }

    @Test
    void executeTaskWithNullTaskReturnsValidationError() {
        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(null);

        assertFalse(result.success());
        assertEquals("Task cannot be null", result.errorMessage());
    }

    @Test
    void taskValidationFailureMarksTaskFailed() throws Exception {
        BuildTask task = new BuildTask();
        task.setId(UUID.randomUUID());
        task.setBuildId(UUID.randomUUID());
        task.setTaskType(TaskType.BLOCK_SET);
        task.setTaskData(objectMapper.readTree("{}"));

        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Task data validation failed"));
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertNotNull(task.getErrorMessage());
    }

    @Test
    void nullTaskTypeFailsValidation() {
        BuildTask task = new BuildTask();
        task.setId(UUID.randomUUID());
        task.setBuildId(UUID.randomUUID());
        task.setTaskType(null);
        task.setTaskData(objectMapper.createObjectNode());

        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Task type cannot be null"));
    }
}
