package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RailRenderInspectionServiceTest {

    @Mock
    private MinecraftServer mockServer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RailRenderInspectionService service;
    private UUID buildId;

    @BeforeEach
    void setUp() {
        service = new RailRenderInspectionService(new TaskExecutor(mockServer));
        buildId = UUID.randomUUID();
    }

    @Test
    void connectedSegmentsDoNotProduceJoinIssues() {
        BuildTask first = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(
                pos(0, 64, 0),
                pos(1, 64, 0)
            ),
            0
        );
        BuildTask second = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(
                pos(1, 64, 0),
                pos(2, 64, 0)
            ),
            1
        );

        List<Map<String, Object>> issues = service.inspect(List.of(first, second), null);

        assertThat(issues).isEmpty();
    }

    @Test
    void disconnectedSegmentsProduceAuditIssue() {
        BuildTask first = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0)),
            0
        );
        BuildTask second = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(5, 64, 0), pos(6, 64, 0)),
            1
        );

        List<Map<String, Object>> issues = service.inspect(List.of(first, second), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_segment_disconnected");
    }

    @Test
    void slopedDiagonalStepIsFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 65, 1)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_sloped_step_not_straight");
    }

    @Test
    void steepSlopedStepIsFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 66, 0)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_sloped_step_not_straight");
    }

    @Test
    void descendingDiagonalStepIsFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 65, 0), pos(1, 64, 1)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_sloped_step_not_straight");
    }

    @Test
    void slopedStepAlongXOnlyIsNotFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 65, 0), pos(2, 65, 0)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_sloped_step_not_straight");
    }

    @Test
    void slopedStepAlongZOnlyIsNotFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(0, 65, 1), pos(0, 65, 2)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_sloped_step_not_straight");
    }

    @Test
    void slopedStepEndingAtCornerIsFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 65, 0), pos(1, 65, 1)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_sloped_step_at_corner");
    }

    @Test
    void slopedStepStartingAtCornerIsFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(1, 65, 1)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_sloped_step_at_corner");
    }

    @Test
    void slopedStepBetweenStraightTilesIsNotFlaggedAsCorner() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 65, 0), pos(2, 65, 0)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_sloped_step_at_corner");
    }

    @Test
    void flatTurnIsNotFlagged() {
        BuildTask task = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(1, 64, 1)),
            0
        );

        List<Map<String, Object>> issues = service.inspect(List.of(task), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_sloped_step_not_straight");
    }

    @Test
    void fillTaskOccupyingDirectHeadroomAboveRailIsFlagged() {
        BuildTask rail = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(2, 64, 0)),
            0
        );
        BuildTask fill = fillTask(1, 65, 0, 1, 65, 0, 1);

        List<Map<String, Object>> issues = service.inspect(List.of(rail, fill), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_headroom_blocked_by_task");
    }

    @Test
    void fillTaskOccupyingSecondHeadroomCellAboveRailIsFlagged() {
        BuildTask rail = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(2, 64, 0)),
            0
        );
        BuildTask fill = fillTask(1, 66, 0, 1, 66, 0, 1);

        List<Map<String, Object>> issues = service.inspect(List.of(rail, fill), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .contains("rail_headroom_blocked_by_task");
    }

    @Test
    void fillTaskFarFromRailIsNotFlagged() {
        BuildTask rail = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(2, 64, 0)),
            0
        );
        BuildTask fill = fillTask(50, 100, 50, 52, 102, 52, 1);

        List<Map<String, Object>> issues = service.inspect(List.of(rail, fill), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_headroom_blocked_by_task");
    }

    @Test
    void fillTaskBesideRailButNotAboveIsNotFlagged() {
        BuildTask rail = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(2, 64, 0)),
            0
        );
        BuildTask fill = fillTask(1, 64, 1, 1, 64, 1, 1);

        List<Map<String, Object>> issues = service.inspect(List.of(rail, fill), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_headroom_blocked_by_task");
    }

    @Test
    void neighboringRailSegmentDoesNotTriggerHeadroomCheck() {
        BuildTask first = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(pos(0, 64, 0), pos(1, 64, 0), pos(2, 64, 0)),
            0
        );
        BuildTask second = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(pos(2, 64, 0), pos(3, 64, 0), pos(4, 64, 0)),
            1
        );

        List<Map<String, Object>> issues = service.inspect(List.of(first, second), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_headroom_blocked_by_task");
    }

    @Test
    void shortTunnelBetweenSurfaceSegmentsDoesNotFlagPortalJoinAsBlocked() {
        List<TaskExecutor.RailPoint> tunnelPath = List.of(
            point(524, 80, -103),
            point(525, 80, -103),
            point(526, 80, -103),
            point(527, 80, -103),
            point(528, 80, -103),
            point(529, 80, -103)
        );

        assertThat(RailRenderInspectionService.getPortalDirection(tunnelPath, 0)).isEqualTo(Direction.WEST);
        assertThat(RailRenderInspectionService.getPortalDirection(tunnelPath, 1)).isNull();
        assertThat(RailRenderInspectionService.getPortalDirection(tunnelPath, tunnelPath.size() - 2)).isNull();
        assertThat(RailRenderInspectionService.getPortalDirection(tunnelPath, tunnelPath.size() - 1)).isEqualTo(Direction.EAST);
    }

    @Test
    void overlappingSurfaceTunnelSurfaceJoinStillJoinsCleanly() {
        BuildTask surfaceBefore = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(
                pos(518, 80, -103),
                pos(519, 80, -103),
                pos(520, 80, -103),
                pos(521, 80, -103),
                pos(522, 80, -103),
                pos(523, 80, -103),
                pos(524, 80, -103),
                pos(525, 80, -103)
            ),
            13
        );
        BuildTask tunnel = railTask(
            TaskType.RAIL_TUNNEL_SEGMENT,
            List.of(
                pos(524, 80, -103),
                pos(525, 80, -103),
                pos(526, 80, -103),
                pos(527, 80, -103),
                pos(528, 80, -103),
                pos(529, 80, -103)
            ),
            14
        );
        BuildTask surfaceAfter = railTask(
            TaskType.RAIL_SURFACE_SEGMENT,
            List.of(
                pos(528, 80, -103),
                pos(529, 80, -103),
                pos(530, 80, -103),
                pos(530, 80, -104),
                pos(530, 80, -105)
            ),
            15
        );

        List<Map<String, Object>> issues = service.inspect(List.of(surfaceBefore, tunnel, surfaceAfter), null);

        assertThat(issues)
            .extracting(issue -> issue.get("check"))
            .doesNotContain("rail_segment_disconnected");
    }

    private BuildTask fillTask(int x1, int y1, int z1, int x2, int y2, int z2, int taskOrder) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("x1", x1);
        data.put("y1", y1);
        data.put("z1", z1);
        data.put("x2", x2);
        data.put("y2", y2);
        data.put("z2", z2);
        data.put("block_type", "minecraft:stone");
        return new BuildTask(buildId, taskOrder, TaskType.BLOCK_FILL, data, "fill");
    }

    private BuildTask railTask(TaskType type, List<int[]> points, int taskOrder) {
        ObjectNode data = objectMapper.createObjectNode();
        ArrayNode path = data.putArray("path");
        for (int[] point : points) {
            ObjectNode node = path.addObject();
            node.put("x", point[0]);
            node.put("y", point[1]);
            node.put("z", point[2]);
        }
        data.put("world", "minecraft:overworld");
        data.put("rail_bed_block", "minecraft:stone");
        data.put("support_block", "minecraft:stone_bricks");
        data.put("power_block", "minecraft:redstone_block");
        data.put("tunnel_lining_block", "minecraft:stone_bricks");
        data.put("powered_rail_interval", 8);
        return new BuildTask(buildId, taskOrder, type, data, type.name());
    }

    private int[] pos(int x, int y, int z) {
        return new int[]{x, y, z};
    }

    private TaskExecutor.RailPoint point(int x, int y, int z) {
        return new TaskExecutor.RailPoint(x, y, z);
    }
}
