package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskStatus;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
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
        List<BlockPos> offsets = TaskExecutor.tunnelLiningOffsets(null, null);

        assertThatOffsetMissing(offsets, new BlockPos(0, 0, 0));
        assertThatOffsetMissing(offsets, new BlockPos(0, 1, 0));
        assertThatOffsetMissing(offsets, new BlockPos(0, 2, 0));
        assertTrue(offsets.contains(new BlockPos(1, 1, 0)));
        assertTrue(offsets.contains(new BlockPos(0, 3, 0)));
    }

    @Test
    void tunnelLiningOffsetsOpenStraightTunnelFaces() {
        List<BlockPos> offsets = TaskExecutor.tunnelLiningOffsets(Direction.WEST, Direction.EAST);

        assertThatOffsetMissing(offsets, new BlockPos(-1, 1, 0));
        assertThatOffsetMissing(offsets, new BlockPos(1, 1, 0));
        assertThatOffsetMissing(offsets, new BlockPos(1, 3, 1));
        assertTrue(offsets.contains(new BlockPos(0, 1, -1)));
        assertTrue(offsets.contains(new BlockPos(0, 1, 1)));
        assertTrue(offsets.contains(new BlockPos(0, 3, 0)));
    }

    @Test
    void tunnelLiningOffsetsOpenCornerFacesWithoutRemovingClosedWalls() {
        List<BlockPos> offsets = TaskExecutor.tunnelLiningOffsets(Direction.WEST, Direction.SOUTH);

        assertThatOffsetMissing(offsets, new BlockPos(-1, 2, 0));
        assertThatOffsetMissing(offsets, new BlockPos(0, 2, 1));
        assertTrue(offsets.contains(new BlockPos(1, 2, 0)));
        assertTrue(offsets.contains(new BlockPos(0, 2, -1)));
        assertTrue(offsets.contains(new BlockPos(1, 3, -1)));
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

    @Test
    void ascendingStraightSegmentsUseAscendingShapes() {
        List<TaskExecutor.RailPoint> path = List.of(
            new TaskExecutor.RailPoint(0, 64, 0),
            new TaskExecutor.RailPoint(1, 65, 0),
            new TaskExecutor.RailPoint(2, 66, 0)
        );

        assertEquals(RailShape.ASCENDING_EAST, TaskExecutor.getRailShape(path, 1, Direction.EAST));
    }

    @Test
    void descendingStraightSegmentsStillResolveToAscendingTowardHigherNeighbor() {
        List<TaskExecutor.RailPoint> path = List.of(
            new TaskExecutor.RailPoint(2, 66, 0),
            new TaskExecutor.RailPoint(1, 65, 0),
            new TaskExecutor.RailPoint(0, 64, 0)
        );

        assertEquals(RailShape.ASCENDING_EAST, TaskExecutor.getRailShape(path, 1, Direction.WEST));
    }

    @Test
    void straightEastWestTunnelBisectionMatchesExpectedCrossSection() {
        List<BlockPos> offsets = TaskExecutor.tunnelLiningOffsets(Direction.WEST, Direction.EAST);

        // Tunnel rendering does not place a foundation: clearTunnel only clears dy 0..3,
        // and ensureBase (which would place a rail-bed block at pos.down()) is disabled.
        // The row at dy=-1 ('~') is preserved terrain, not anything the renderer places.
        String[] expected = {
            "###",
            "#.#",
            "#.#",
            "#R#",
            "~~~"
        };

        String[] actual = renderCrossSection(offsets, Direction.Axis.X);

        assertArrayEquals(expected, actual,
            "tunnel cross-section (looking along east-west axis):\n"
                + "expected:\n" + String.join("\n", expected) + "\n"
                + "actual:\n" + String.join("\n", actual));

        for (BlockPos offset : offsets) {
            assertFalse(offset.getY() < 0,
                "tunnel rendering must not place blocks below the rail (no foundation), found: " + offset);
        }
    }

    @Test
    void straightNorthSouthTunnelBisectionMatchesExpectedCrossSection() {
        List<BlockPos> offsets = TaskExecutor.tunnelLiningOffsets(Direction.NORTH, Direction.SOUTH);

        String[] expected = {
            "###",
            "#.#",
            "#.#",
            "#R#",
            "~~~"
        };

        String[] actual = renderCrossSection(offsets, Direction.Axis.Z);

        assertArrayEquals(expected, actual,
            "tunnel cross-section (looking along north-south axis):\n"
                + "expected:\n" + String.join("\n", expected) + "\n"
                + "actual:\n" + String.join("\n", actual));

        for (BlockPos offset : offsets) {
            assertFalse(offset.getY() < 0,
                "tunnel rendering must not place blocks below the rail (no foundation), found: " + offset);
        }
    }

    /**
     * Projects tunnel lining offsets onto the plane perpendicular to {@code travelAxis}
     * (the rail's direction of travel). Rows are dy from top (3) down to -1; columns
     * are the in-plane horizontal coordinate from -1 to 1.
     *   '#' = lined by the tunnel renderer
     *   '.' = open interior (cleared to air)
     *   'R' = the rail position itself
     *   '~' = untouched terrain below the rail (no foundation is placed)
     */
    private String[] renderCrossSection(List<BlockPos> offsets, Direction.Axis travelAxis) {
        java.util.Set<BlockPos> lined = new java.util.HashSet<>(offsets);
        String[] rows = new String[5];
        int rowIndex = 0;
        for (int dy = 3; dy >= -1; dy--) {
            StringBuilder row = new StringBuilder(3);
            for (int column = -1; column <= 1; column++) {
                BlockPos cell = travelAxis == Direction.Axis.X
                    ? new BlockPos(0, dy, column)
                    : new BlockPos(column, dy, 0);
                if (dy == -1) {
                    row.append('~');
                } else if (lined.contains(cell)) {
                    row.append('#');
                } else if (dy == 0 && column == 0) {
                    row.append('R');
                } else {
                    row.append('.');
                }
            }
            rows[rowIndex++] = row.toString();
        }
        return rows;
    }

    private void assertThatOffsetMissing(List<BlockPos> offsets, BlockPos expectedMissing) {
        assertFalse(offsets.contains(expectedMissing), "Offset should not be lined: " + expectedMissing);
    }
}
