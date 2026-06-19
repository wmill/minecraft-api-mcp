package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.model.BuildTask;
import ca.waltermiller.mcpapi.buildtask.model.RailPlanningJob;
import ca.waltermiller.mcpapi.buildtask.model.RailPlanningStatus;
import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import ca.waltermiller.mcpapi.buildtask.service.LocationQueryService;
import ca.waltermiller.mcpapi.buildtask.service.RailPlanningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildTaskEndpointTest {

    @Mock
    private MinecraftServer mockServer;
    @Mock
    private BuildService buildService;
    @Mock
    private LocationQueryService locationQueryService;
    @Mock
    private RailPlanningService railPlanningService;

    private TaskExecutor taskExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Javalin app;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor(mockServer);
        app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        new BuildTaskEndpoint(app, mockServer, LoggerFactory.getLogger(BuildTaskEndpointTest.class),
            buildService, locationQueryService, railPlanningService, taskExecutor);
        app.start(0);
        baseUrl = "http://localhost:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void createBuildReturnsCreatedJson() throws Exception {
        Build build = new Build("rail", "desc", "minecraft:overworld");
        build.setId(UUID.randomUUID());
        when(buildService.createBuild(any(BuildService.CreateBuildRequest.class))).thenReturn(build);

        HttpResponse<String> response = sendJson("POST", "/api/builds",
            "{\"name\":\"rail\",\"description\":\"desc\",\"world\":\"minecraft:overworld\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("build").get("id").asText()).isEqualTo(build.getId().toString());
    }

    @Test
    void getBuildReturnsBadRequestForInvalidId() throws Exception {
        HttpResponse<String> response = send("GET", "/api/builds/not-a-uuid", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).get("error").asText()).contains("Invalid build ID format");
    }

    @Test
    void addTaskReturnsBadRequestWhenTaskTypeMissing() throws Exception {
        HttpResponse<String> response = sendJson("POST", "/api/builds/" + UUID.randomUUID() + "/tasks",
            "{\"task_data\":{\"x1\":0}}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).get("error").asText()).contains("Task type is required");
    }

    @Test
    void executeBuildReturnsAccepted() throws Exception {
        UUID buildId = UUID.randomUUID();
        when(buildService.executeBuild(buildId))
            .thenReturn(CompletableFuture.completedFuture(
                new BuildService.BuildExecutionResult(buildId, true, 0, 0, List.of(), null)));

        HttpResponse<String> response = send("POST", "/api/builds/" + buildId + "/execute", null);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(objectMapper.readTree(response.body()).get("status").asText()).isEqualTo("accepted");
    }

    @Test
    void queryLocationRejectsInvalidCoordinateRange() throws Exception {
        HttpResponse<String> response = sendJson("POST", "/api/builds/query-location",
            "{\"min_x\":5,\"min_y\":0,\"min_z\":0,\"max_x\":1,\"max_y\":10,\"max_z\":10}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).get("error").asText()).contains("Invalid coordinate range");
    }

    @Test
    void planRailUsesBuildWorldWhenRequestWorldMissing() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("rail", "desc", "minecraft:the_nether");
        build.setId(buildId);
        RailPlanningJob job = new RailPlanningJob();
        job.setBuildId(buildId);
        job.setStatus(RailPlanningStatus.PLANNING);
        job.setPhase("queued");
        job.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        job.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));
        when(railPlanningService.startPlanning(any(RailPlanningService.StartRailPlanningRequest.class))).thenReturn(job);

        HttpResponse<String> response = sendJson("POST", "/api/builds/" + buildId + "/plan-rail",
            "{\"world\":\"\",\"start_x\":0,\"start_y\":64,\"start_z\":0,\"end_x\":10,\"end_y\":64,\"end_z\":10}");

        assertThat(response.statusCode()).isEqualTo(202);
        verify(buildService).getBuild(buildId);
        assertThat(objectMapper.readTree(response.body()).get("planning_job").get("build_id").asText())
            .isEqualTo(buildId.toString());
    }

    @Test
    void getRailPlanStatusReturnsNotFoundWhenJobMissing() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(railPlanningService.getJob(jobId)).thenReturn(Optional.empty());

        HttpResponse<String> response = send("GET", "/api/rail-plans/" + jobId, null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(response.body()).get("error").asText()).contains("Planning job not found");
    }

    @Test
    void auditBuildReturnsWarningForOverlappingFill() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);
        BuildTask structure = new BuildTask(buildId, 0, TaskType.PREFAB_DOOR, prefabDoorData(), "door");
        BuildTask fill = new BuildTask(buildId, 1, TaskType.BLOCK_FILL, fillData(), "fill");

        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));
        when(buildService.getTasks(buildId)).thenReturn(new java.util.ArrayList<>(List.of(fill, structure)));
        when(locationQueryService.queryBuildsByLocation(any(LocationQueryService.LocationQueryRequest.class)))
            .thenReturn(new LocationQueryService.LocationQueryResult(List.of(), null));

        HttpResponse<String> response = send("POST", "/api/builds/" + buildId + "/audit", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("summary").get("warnings").asInt()).isEqualTo(1);
    }

    @Test
    void auditBuildReportsCrossBuildOverlap() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("build", "desc");
        build.setId(buildId);
        BuildTask door = new BuildTask(buildId, 0, TaskType.PREFAB_DOOR, prefabDoorData(), "door");

        UUID otherBuildId = UUID.randomUUID();
        Build otherBuild = new Build("NBT: house.nbt", "Auto-recorded NBT structure placement");
        otherBuild.setId(otherBuildId);
        otherBuild.setStatus(ca.waltermiller.mcpapi.buildtask.model.BuildStatus.COMPLETED);
        BuildTask otherTask = new BuildTask(otherBuildId, 0, TaskType.NBT_STRUCTURE,
            objectMapper.createObjectNode().put("x", 0).put("y", 64).put("z", 0)
                .put("size_x", 1).put("size_y", 1).put("size_z", 1)
                .put("filename", "house.nbt").put("rotation", "NONE"),
            "NBT structure placement");

        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));
        when(buildService.getTasks(buildId)).thenReturn(new java.util.ArrayList<>(List.of(door)));
        when(locationQueryService.queryBuildsByLocation(any(LocationQueryService.LocationQueryRequest.class)))
            .thenReturn(new LocationQueryService.LocationQueryResult(
                List.of(new LocationQueryService.BuildLocationResult(otherBuild, List.of(otherTask))), null));

        HttpResponse<String> response = send("POST", "/api/builds/" + buildId + "/audit", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("summary").get("errors").asInt()).isGreaterThan(0);
        String issuesText = json.get("issues").toString();
        assertThat(issuesText).contains("cross_build_overlap");
        assertThat(issuesText).contains(otherBuildId.toString());
    }

    @Test
    void auditBuildAcceptsConnectedRailSegments() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("rail", "desc");
        build.setId(buildId);
        BuildTask first = new BuildTask(buildId, 0, TaskType.RAIL_SURFACE_SEGMENT, railData(List.of(
            point(0, 64, 0),
            point(1, 64, 0)
        )), "first");
        BuildTask second = new BuildTask(buildId, 1, TaskType.RAIL_SURFACE_SEGMENT, railData(List.of(
            point(1, 64, 0),
            point(2, 64, 0)
        )), "second");

        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));
        when(buildService.getTasks(buildId)).thenReturn(new java.util.ArrayList<>(List.of(first, second)));
        when(locationQueryService.queryBuildsByLocation(any(LocationQueryService.LocationQueryRequest.class)))
            .thenReturn(new LocationQueryService.LocationQueryResult(List.of(), null));

        HttpResponse<String> response = send("POST", "/api/builds/" + buildId + "/audit", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("summary").get("errors").asInt()).isZero();
        assertThat(json.get("issues")).isEmpty();
    }

    @Test
    void auditBuildReportsDisconnectedRailSegments() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("rail", "desc");
        build.setId(buildId);
        BuildTask first = new BuildTask(buildId, 0, TaskType.RAIL_SURFACE_SEGMENT, railData(List.of(
            point(0, 64, 0),
            point(1, 64, 0)
        )), "first");
        BuildTask second = new BuildTask(buildId, 1, TaskType.RAIL_SURFACE_SEGMENT, railData(List.of(
            point(5, 64, 0),
            point(6, 64, 0)
        )), "second");

        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));
        when(buildService.getTasks(buildId)).thenReturn(new java.util.ArrayList<>(List.of(first, second)));
        when(locationQueryService.queryBuildsByLocation(any(LocationQueryService.LocationQueryRequest.class)))
            .thenReturn(new LocationQueryService.LocationQueryResult(List.of(), null));

        HttpResponse<String> response = send("POST", "/api/builds/" + buildId + "/audit", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("summary").get("errors").asInt()).isGreaterThan(0);
        assertThat(json.get("issues").toString()).contains("rail_segment_disconnected");
    }

    @Test
    void translateBuildShiftsTasksAndReturnsTaskCount() throws Exception {
        UUID buildId = UUID.randomUUID();
        BuildTask task = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, fillData(), "fill");
        when(buildService.translateBuild(buildId, 5, 0, -5)).thenReturn(List.of(task));

        HttpResponse<String> response = sendJson("POST", "/api/builds/" + buildId + "/translate",
            "{\"dx\":5,\"dy\":0,\"dz\":-5}");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("task_count").asInt()).isEqualTo(1);
    }

    @Test
    void translateBuildReturnsConflictWhenTaskAlreadyExecuted() throws Exception {
        UUID buildId = UUID.randomUUID();
        when(buildService.translateBuild(buildId, 1, 0, 0))
            .thenThrow(new IllegalStateException("Cannot translate build " + buildId + ": task already executed"));

        HttpResponse<String> response = sendJson("POST", "/api/builds/" + buildId + "/translate",
            "{\"dx\":1,\"dy\":0,\"dz\":0}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.body()).get("error").asText()).contains("already executed");
    }

    @Test
    void cloneBuildReturnsNewBuildId() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        Build newBuild = new Build("build", "desc", "minecraft:overworld");
        newBuild.setId(newId);
        when(buildService.cloneBuild(sourceId)).thenReturn(newBuild);
        when(buildService.getTasks(newId)).thenReturn(List.of(
            new BuildTask(newId, 0, TaskType.BLOCK_FILL, objectMapper.createObjectNode(), "t1"),
            new BuildTask(newId, 1, TaskType.BLOCK_FILL, objectMapper.createObjectNode(), "t2")
        ));

        HttpResponse<String> response = send("POST", "/api/builds/" + sourceId + "/clone", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("source_build_id").asText()).isEqualTo(sourceId.toString());
        assertThat(body.get("new_build_id").asText()).isEqualTo(newId.toString());
        assertThat(body.get("tasks_cloned").asInt()).isEqualTo(2);
    }

    @Test
    void cloneBuildReturns409WhenBuildInProgress() throws Exception {
        UUID sourceId = UUID.randomUUID();
        when(buildService.cloneBuild(sourceId))
            .thenThrow(new IllegalStateException("Cannot clone build that is currently executing: " + sourceId));

        HttpResponse<String> response = send("POST", "/api/builds/" + sourceId + "/clone", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.body()).get("error").asText()).contains("currently executing");
    }

    @Test
    void previewBuildRejectsInvalidTerrainMargin() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("preview", "desc", "minecraft:overworld");
        build.setId(buildId);
        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));

        HttpResponse<String> response = send("GET", "/api/builds/" + buildId + "/preview?terrain_margin=abc", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.valueToTree(
            Map.of("error", "terrain_margin must be an integer")));
    }

    @Test
    void previewBuildRejectsInvalidViewDirection() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("preview", "desc", "minecraft:overworld");
        build.setId(buildId);
        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));

        HttpResponse<String> response = send("GET", "/api/builds/" + buildId + "/preview?view_direction=northeast", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.valueToTree(
            Map.of("error", "view_direction must be one of: south, west, north, east")));
    }

    @Test
    void getBuildStatusReturnsBoundingBox() throws Exception {
        UUID buildId = UUID.randomUUID();
        Build build = new Build("test", "desc", "minecraft:overworld");
        build.setId(buildId);

        ObjectNode data1 = objectMapper.createObjectNode();
        data1.put("x1", 0); data1.put("y1", 64); data1.put("z1", 0);
        data1.put("x2", 5); data1.put("y2", 64); data1.put("z2", 5);
        data1.put("block_type", "minecraft:stone");

        ObjectNode data2 = objectMapper.createObjectNode();
        data2.put("x1", 10); data2.put("y1", 60); data2.put("z1", 0);
        data2.put("x2", 15); data2.put("y2", 65); data2.put("z2", 10);
        data2.put("block_type", "minecraft:dirt");

        BuildTask task1 = new BuildTask(buildId, 0, TaskType.BLOCK_FILL, data1, "fill1");
        BuildTask task2 = new BuildTask(buildId, 1, TaskType.BLOCK_FILL, data2, "fill2");

        when(buildService.getBuild(buildId)).thenReturn(Optional.of(build));
        when(buildService.getTasks(buildId)).thenReturn(List.of(task1, task2));

        HttpResponse<String> response = send("GET", "/api/builds/" + buildId, null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode bb = json.get("bounding_box");
        assertThat(bb).isNotNull();
        assertThat(bb.get("min_x").asInt()).isEqualTo(0);
        assertThat(bb.get("min_y").asInt()).isEqualTo(60);
        assertThat(bb.get("min_z").asInt()).isEqualTo(0);
        assertThat(bb.get("max_x").asInt()).isEqualTo(15);
        assertThat(bb.get("max_y").asInt()).isEqualTo(65);
        assertThat(bb.get("max_z").asInt()).isEqualTo(10);
        assertThat(bb.get("size_x").asInt()).isEqualTo(16);
        assertThat(bb.get("size_y").asInt()).isEqualTo(6);
        assertThat(bb.get("size_z").asInt()).isEqualTo(11);
    }

    private HttpResponse<String> sendJson(String method, String path, String json) throws IOException, InterruptedException {
        return send(method, path, HttpRequest.BodyPublishers.ofString(json));
    }

    private HttpResponse<String> send(String method, String path, HttpRequest.BodyPublisher bodyPublisher)
        throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(method, bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody())
            .header("Accept", "application/json");
        if (bodyPublisher != null) {
            builder.header("Content-Type", "application/json");
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode fillData() {
        ObjectNode fill = objectMapper.createObjectNode();
        fill.put("x1", 0);
        fill.put("y1", 64);
        fill.put("z1", 0);
        fill.put("x2", 0);
        fill.put("y2", 64);
        fill.put("z2", 0);
        fill.put("block_type", "minecraft:stone");
        return fill;
    }

    private ObjectNode prefabDoorData() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("start_x", 0);
        node.put("start_y", 64);
        node.put("start_z", 0);
        node.put("facing", "north");
        node.put("block_type", "minecraft:oak_door");
        return node;
    }

    private ObjectNode railData(List<int[]> points) {
        ObjectNode node = objectMapper.createObjectNode();
        var path = node.putArray("path");
        for (int[] point : points) {
            ObjectNode pointNode = path.addObject();
            pointNode.put("x", point[0]);
            pointNode.put("y", point[1]);
            pointNode.put("z", point[2]);
        }
        node.put("world", "minecraft:overworld");
        node.put("rail_bed_block", "minecraft:stone");
        node.put("support_block", "minecraft:stone_bricks");
        node.put("power_block", "minecraft:redstone_block");
        node.put("tunnel_lining_block", "minecraft:stone_bricks");
        node.put("powered_rail_interval", 8);
        return node;
    }

    private int[] point(int x, int y, int z) {
        return new int[]{x, y, z};
    }
}
