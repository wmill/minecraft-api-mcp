package com.example.endpoints;

import com.example.buildtask.model.BuildTask;
import com.example.buildtask.model.TaskStatus;
import com.example.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.block.enums.RailShape;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
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
    void tunnelLiningOffsetsLeaveThreeBlockInteriorOpen() {
        List<BlockPos> offsets = TaskExecutor.tunnelLiningOffsets();

        assertThatOffsetMissing(offsets, new BlockPos(0, 0, 0));
        assertThatOffsetMissing(offsets, new BlockPos(0, 1, 0));
        assertThatOffsetMissing(offsets, new BlockPos(0, 2, 0));
        assertTrue(offsets.contains(new BlockPos(1, 1, 0)));
        assertTrue(offsets.contains(new BlockPos(0, 3, 0)));
    }

    @Test
    void poweredRailsArePlacedOnStraightSegments() {
        List<TaskExecutor.RailPoint> path = List.of(
            new TaskExecutor.RailPoint(0, 64, 0),
            new TaskExecutor.RailPoint(1, 64, 0),
            new TaskExecutor.RailPoint(2, 64, 0)
        );

        assertTrue(TaskExecutor.shouldPlacePoweredRail(path, 1, 1));
        assertEquals(RailShape.EAST_WEST, TaskExecutor.getRailShape(path, 1, Direction.EAST));
    }

    @Test
    void poweredRailsAreSkippedAtCorners() {
        List<TaskExecutor.RailPoint> path = List.of(
            new TaskExecutor.RailPoint(0, 64, 0),
            new TaskExecutor.RailPoint(1, 64, 0),
            new TaskExecutor.RailPoint(1, 64, 1)
        );

        assertFalse(TaskExecutor.shouldPlacePoweredRail(path, 1, 1));
        assertEquals(RailShape.SOUTH_EAST, TaskExecutor.getRailShape(path, 1, Direction.EAST));
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

    private void assertThatOffsetMissing(List<BlockPos> offsets, BlockPos expectedMissing) {
        assertFalse(offsets.contains(expectedMissing), "Offset should not be lined: " + expectedMissing);
    }
}
