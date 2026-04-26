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
        List<BlockPos> lined = withFoundation(TaskExecutor.tunnelLiningOffsets(Direction.WEST, Direction.EAST));

        // Every rail tile gets a rail-bed foundation block placed at pos.down() by
        // ensureBase. The row at dy=-1 shows '#' under the rail to reflect that.
        String[] expected = {
            "###",
            "#.#",
            "#.#",
            "#R#",
            "~#~"
        };

        String[] actual = renderCrossSection(lined, tunnelClearedOffsets(), Direction.Axis.X);

        assertArrayEquals(expected, actual,
            "tunnel cross-section (looking along east-west axis):\n"
                + "expected:\n" + String.join("\n", expected) + "\n"
                + "actual:\n" + String.join("\n", actual));

        assertTrue(lined.contains(TaskExecutor.foundationOffset()),
            "tunnel rendering must place a foundation block under every rail tile");
    }

    @Test
    void straightNorthSouthTunnelBisectionMatchesExpectedCrossSection() {
        List<BlockPos> lined = withFoundation(TaskExecutor.tunnelLiningOffsets(Direction.NORTH, Direction.SOUTH));

        String[] expected = {
            "###",
            "#.#",
            "#.#",
            "#R#",
            "~#~"
        };

        String[] actual = renderCrossSection(lined, tunnelClearedOffsets(), Direction.Axis.Z);

        assertArrayEquals(expected, actual,
            "tunnel cross-section (looking along north-south axis):\n"
                + "expected:\n" + String.join("\n", expected) + "\n"
                + "actual:\n" + String.join("\n", actual));

        assertTrue(lined.contains(TaskExecutor.foundationOffset()),
            "tunnel rendering must place a foundation block under every rail tile");
    }

    @Test
    void straightSurfaceSegmentPlacesPowerBlockUnderPoweredRailTilesOnly() {
        assertBaseBlocksMatchPoweredRailPattern("surface");
    }

    @Test
    void straightBridgeSegmentPlacesPowerBlockUnderPoweredRailTilesOnly() {
        assertBaseBlocksMatchPoweredRailPattern("bridge");
    }

    @Test
    void straightTunnelSegmentPlacesPowerBlockUnderPoweredRailTilesOnly() {
        assertBaseBlocksMatchPoweredRailPattern("tunnel");
    }

    /**
     * Walks a 9-tile straight east-west path and verifies that for every index i,
     * {@code chooseTopSupportBlockId} matches the rule: power_block when the tile
     * is powered (per {@code shouldPlacePoweredRail}), rail_bed_block otherwise.
     *
     * The mode parameter is documentation: surface/bridge/tunnel all call ensureBase
     * with the same powered flag, so the base-block choice is mode-independent. These
     * three tests exist to lock that invariant per mode.
     */
    private void assertBaseBlocksMatchPoweredRailPattern(String mode) {
        List<TaskExecutor.RailPoint> path = List.of(
            new TaskExecutor.RailPoint(0, 64, 0),
            new TaskExecutor.RailPoint(1, 64, 0),
            new TaskExecutor.RailPoint(2, 64, 0),
            new TaskExecutor.RailPoint(3, 64, 0),
            new TaskExecutor.RailPoint(4, 64, 0),
            new TaskExecutor.RailPoint(5, 64, 0),
            new TaskExecutor.RailPoint(6, 64, 0),
            new TaskExecutor.RailPoint(7, 64, 0),
            new TaskExecutor.RailPoint(8, 64, 0)
        );
        int interval = 4;
        TaskExecutor.RailSegmentDefinition segment = new TaskExecutor.RailSegmentDefinition(
            path,
            "minecraft:overworld",
            "minecraft:stone",
            "minecraft:stone_bricks",
            "minecraft:redstone_block",
            "minecraft:stone_bricks",
            interval
        );

        for (int i = 0; i < path.size(); i++) {
            boolean powered = TaskExecutor.shouldPlacePoweredRail(path, i, interval);
            String expected = powered ? "minecraft:redstone_block" : "minecraft:stone";
            String actual = TaskExecutor.chooseTopSupportBlockId(segment, powered);
            assertEquals(expected, actual,
                mode + " segment, tile " + i + " (powered=" + powered + ") base block mismatch");
        }
    }

    @Test
    void straightEastWestSurfaceBisectionMatchesExpectedCrossSection() {
        // Surface segments clear a 3-tall headroom column at the rail position and
        // place a rail-bed foundation block at pos.down(). No sides are touched.
        String[] expected = {
            "~.~",
            "~.~",
            "~R~",
            "~#~"
        };

        String[] actual = renderCrossSection(
            List.of(TaskExecutor.foundationOffset()),
            TaskExecutor.headroomClearedOffsets(),
            Direction.Axis.X,
            2
        );

        assertArrayEquals(expected, actual,
            "surface cross-section (looking along east-west axis):\n"
                + "expected:\n" + String.join("\n", expected) + "\n"
                + "actual:\n" + String.join("\n", actual));
    }

    @Test
    void straightEastWestBridgeBisectionMatchesExpectedCrossSection() {
        // Bridge segments share clearHeadroom and ensureBase with surface, plus a
        // deeper support column extending downward. The bisection at the rail tile
        // matches surface — deeper support cells live at dy < -1 and aren't shown here.
        String[] expected = {
            "~.~",
            "~.~",
            "~R~",
            "~#~"
        };

        String[] actual = renderCrossSection(
            List.of(TaskExecutor.foundationOffset()),
            TaskExecutor.headroomClearedOffsets(),
            Direction.Axis.X,
            2
        );

        assertArrayEquals(expected, actual,
            "bridge cross-section (looking along east-west axis):\n"
                + "expected:\n" + String.join("\n", expected) + "\n"
                + "actual:\n" + String.join("\n", actual));
    }

    /**
     * Projects rail rendering offsets onto the plane perpendicular to {@code travelAxis}.
     * Rows are dy from {@code topDy} down to -1; columns are the in-plane horizontal
     * coordinate from -1 to 1.
     *   '#' = block placed by the renderer (tunnel lining)
     *   '.' = cleared to air by the renderer
     *   'R' = the rail position itself
     *   '~' = untouched terrain (no renderer action — preserved world block)
     *
     * Each cell's classification is exact: callers must pass the lined and cleared sets
     * the renderer actually produces. Cells that appear in neither set render as '~'.
     */
    private String[] renderCrossSection(
        List<BlockPos> linedOffsets,
        List<BlockPos> clearedOffsets,
        Direction.Axis travelAxis,
        int topDy
    ) {
        java.util.Set<BlockPos> lined = new java.util.HashSet<>(linedOffsets);
        java.util.Set<BlockPos> cleared = new java.util.HashSet<>(clearedOffsets);
        String[] rows = new String[topDy + 2];
        int rowIndex = 0;
        for (int dy = topDy; dy >= -1; dy--) {
            StringBuilder row = new StringBuilder(3);
            for (int column = -1; column <= 1; column++) {
                BlockPos cell = travelAxis == Direction.Axis.X
                    ? new BlockPos(0, dy, column)
                    : new BlockPos(column, dy, 0);
                if (dy == 0 && column == 0) {
                    row.append('R');
                } else if (lined.contains(cell)) {
                    row.append('#');
                } else if (cleared.contains(cell)) {
                    row.append('.');
                } else {
                    row.append('~');
                }
            }
            rows[rowIndex++] = row.toString();
        }
        return rows;
    }

    private String[] renderCrossSection(List<BlockPos> linedOffsets, List<BlockPos> clearedOffsets, Direction.Axis travelAxis) {
        return renderCrossSection(linedOffsets, clearedOffsets, travelAxis, 3);
    }

    /**
     * Returns every cell in the tunnel clearance envelope (dx -1..1, dy 0..3, dz -1..1) —
     * mirrors {@code TaskExecutor.clearTunnel}. Used by tunnel cross-section tests so the
     * helper can render cleared interior as '.' rather than untouched terrain.
     */
    private List<BlockPos> tunnelClearedOffsets() {
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cells.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        return cells;
    }

    private void assertThatOffsetMissing(List<BlockPos> offsets, BlockPos expectedMissing) {
        assertFalse(offsets.contains(expectedMissing), "Offset should not be lined: " + expectedMissing);
    }

    private List<BlockPos> withFoundation(List<BlockPos> linedOffsets) {
        List<BlockPos> combined = new java.util.ArrayList<>(linedOffsets);
        combined.add(TaskExecutor.foundationOffset());
        return combined;
    }
}
