package com.example.endpoints;

import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.TaskType;
import com.example.buildtask.model.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskExecutor.
 * Tests task execution logic and validation integration.
 */
class TaskExecutorTest {

    @Mock
    private MinecraftServer mockServer;

    private TaskExecutor taskExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Note: TaskExecutor requires a real MinecraftServer for core operations
        // These tests focus on validation and error handling
        objectMapper = new ObjectMapper();
    }

    @Test
    void testExecuteTaskWithNullTask() {
        // Create TaskExecutor with mock server (will fail for actual execution but works for validation)
        taskExecutor = new TaskExecutor(mockServer);
        
        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(null);
        
        assertFalse(result.success());
        assertEquals("Task cannot be null", result.errorMessage());
    }

    @Test
    void testTaskValidationFailure() throws Exception {
        taskExecutor = new TaskExecutor(mockServer);
        
        // Create a task with invalid data (missing required fields)
        BuildTask task = new BuildTask();
        task.setId(UUID.randomUUID());
        task.setBuildId(UUID.randomUUID());
        task.setTaskType(TaskType.BLOCK_SET);
        
        // Create invalid task data (missing required fields)
        JsonNode invalidData = objectMapper.readTree("{}");
        task.setTaskData(invalidData);
        
        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task);
        
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Task data validation failed"));
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertNotNull(task.getErrorMessage());
    }

    @Test
    void testTaskValidationSuccess() throws Exception {
        taskExecutor = new TaskExecutor(mockServer);
        
        // Create a task with valid data structure (will still fail execution due to mock server)
        BuildTask task = new BuildTask();
        task.setId(UUID.randomUUID());
        task.setBuildId(UUID.randomUUID());
        task.setTaskType(TaskType.BLOCK_SET);
        
        // Create valid task data structure
        String validTaskData = """
            {
                "startX": 0,
                "startY": 64,
                "startZ": 0,
                "blocks": [
                    [
                        [
                            {
                                "blockName": "minecraft:stone"
                            }
                        ]
                    ]
                ]
            }
            """;
        JsonNode validData = objectMapper.readTree(validTaskData);
        task.setTaskData(validData);
        
        // This will pass validation but fail execution due to mock server
        TaskExecutor.TaskExecutionResult result = taskExecutor.executeTask(task);
        
        // Should fail due to mock server, but not due to validation
        assertFalse(result.success());
        // Should not be a validation error
        assertFalse(result.errorMessage().contains("Task data validation failed"));
    }

    @Test
    void testUnknownTaskType() {
        taskExecutor = new TaskExecutor(mockServer);
        
        // Create a task with null task type to trigger unknown type handling
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