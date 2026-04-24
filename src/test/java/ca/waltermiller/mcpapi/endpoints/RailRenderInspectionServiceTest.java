package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.server.MinecraftServer;
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
}
