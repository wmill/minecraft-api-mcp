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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Javalin app;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        new BuildTaskEndpoint(app, mockServer, LoggerFactory.getLogger(BuildTaskEndpointTest.class),
            buildService, locationQueryService, railPlanningService);
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

        HttpResponse<String> response = send("POST", "/api/builds/" + buildId + "/audit", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("summary").get("warnings").asInt()).isEqualTo(1);
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
}
