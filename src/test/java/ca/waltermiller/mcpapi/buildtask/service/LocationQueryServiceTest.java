package ca.waltermiller.mcpapi.buildtask.service;

import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.model.BuildStatus;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import ca.waltermiller.mcpapi.buildtask.repository.BuildRepository;
import ca.waltermiller.mcpapi.buildtask.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationQueryServiceTest {

    @Mock
    private BuildRepository buildRepository;
    @Mock
    private TaskRepository taskRepository;

    private LocationQueryService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = new LocationQueryService(buildRepository, taskRepository);
        mapper = new ObjectMapper();
    }

    @Test
    void queryBuildsByLocationRejectsNullRequest() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> service.queryBuildsByLocation(null));

        assertThat(exception.getMessage()).contains("Location query request cannot be null");
    }

    @Test
    void queryBuildsByLocationFiltersIncompleteAndSortsChronologically() throws Exception {
        Build newer = build(UUID.randomUUID(), BuildStatus.COMPLETED, Instant.parse("2025-01-02T00:00:00Z"));
        Build older = build(UUID.randomUUID(), BuildStatus.COMPLETED, Instant.parse("2025-01-01T00:00:00Z"));
        Build inProgress = build(UUID.randomUUID(), BuildStatus.IN_PROGRESS, Instant.parse("2024-12-31T00:00:00Z"));
        BuildTask taskOlder = task(older.getId());
        BuildTask taskNewer = task(newer.getId());

        when(buildRepository.findByLocationIntersection(eq("minecraft:overworld"), any()))
            .thenReturn(List.of(newer, inProgress, older));
        when(taskRepository.findByLocationIntersection(eq("minecraft:overworld"), any()))
            .thenReturn(List.of(taskOlder, taskNewer));

        LocationQueryService.LocationQueryRequest request = new LocationQueryService.LocationQueryRequest();
        request.world = "minecraft:overworld";
        request.min_x = 0;
        request.min_y = 0;
        request.min_z = 0;
        request.max_x = 10;
        request.max_y = 10;
        request.max_z = 10;

        LocationQueryService.LocationQueryResult result = service.queryBuildsByLocation(request);

        assertThat(result.builds).hasSize(2);
        assertThat(result.builds.get(0).build.getId()).isEqualTo(older.getId());
        assertThat(result.builds.get(1).build.getId()).isEqualTo(newer.getId());
        assertThat(result.getTotalTaskCount()).isEqualTo(2);
    }

    @Test
    void queryBuildsByLocationFallsBackToEmptyTasksOnTaskLookupFailure() throws Exception {
        Build build = build(UUID.randomUUID(), BuildStatus.COMPLETED, Instant.parse("2025-01-01T00:00:00Z"));
        when(buildRepository.findByLocationIntersection(eq("minecraft:overworld"), any()))
            .thenReturn(List.of(build));
        when(taskRepository.findByLocationIntersection(eq("minecraft:overworld"), any()))
            .thenThrow(new SQLException("boom"));

        LocationQueryService.LocationQueryRequest request = new LocationQueryService.LocationQueryRequest();
        request.world = "minecraft:overworld";
        request.min_x = 0;
        request.min_y = 0;
        request.min_z = 0;
        request.max_x = 10;
        request.max_y = 10;
        request.max_z = 10;

        LocationQueryService.LocationQueryResult result = service.queryBuildsByLocation(request);

        assertThat(result.builds).hasSize(1);
        assertThat(result.builds.get(0).intersecting_tasks).isEmpty();
    }

    private Build build(UUID id, BuildStatus status, Instant createdAt) {
        Build build = new Build("name", "desc");
        build.setId(id);
        build.setStatus(status);
        build.setCreatedAt(createdAt);
        return build;
    }

    private BuildTask task(UUID buildId) {
        ObjectNode fill = mapper.createObjectNode();
        fill.put("x1", 0);
        fill.put("y1", 64);
        fill.put("z1", 0);
        fill.put("x2", 1);
        fill.put("y2", 64);
        fill.put("z2", 1);
        fill.put("block_type", "minecraft:stone");
        return new BuildTask(buildId, 0, TaskType.BLOCK_FILL, fill, "fill");
    }
}
