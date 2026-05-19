package ca.waltermiller.mcpapi.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlocksEndpointTest {

    @Mock
    private MinecraftServer mockServer;

    @Mock
    private Logger mockLogger;

    @Mock
    private BlocksEndpointCore mockCore;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Javalin app;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        new BlocksEndpoint(app, mockServer, mockLogger, mockCore);
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
    void previewHeightmapRejectsInvalidIsoScale() throws Exception {
        HttpResponse<String> response = sendJson("/api/world/blocks/heightmap/preview", Map.of(
            "x1", 0,
            "z1", 0,
            "x2", 1,
            "z2", 1,
            "iso_scale", 33
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).get("error").asText()).isEqualTo("iso_scale must be between 1 and 32");
    }

    @Test
    void previewHeightmapRejectsInvalidViewDirection() throws Exception {
        HttpResponse<String> response = sendJson("/api/world/blocks/heightmap/preview", Map.of(
            "x1", 0,
            "z1", 0,
            "x2", 1,
            "z2", 1,
            "view_direction", "northeast"
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).get("error").asText()).isEqualTo("view_direction must be one of: south, west, north, east");
    }

    @Test
    void previewHeightmapRejectsInvalidHeightmapType() throws Exception {
        when(mockCore.getHeightmap(any())).thenReturn(CompletableFuture.completedFuture(
            new HeightmapResult(false, "Invalid heightmap type: BAD. Valid types: WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR",
                null, null, null, null, null, null)
        ));

        HttpResponse<String> response = sendJson("/api/world/blocks/heightmap/preview", Map.of(
            "x1", 0,
            "z1", 0,
            "x2", 1,
            "z2", 1,
            "heightmap_type", "BAD"
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).get("error").asText()).startsWith("Invalid heightmap type: BAD");
    }

    @Test
    void previewHeightmapRejectsOversizeArea() throws Exception {
        when(mockCore.getHeightmap(any())).thenReturn(CompletableFuture.completedFuture(
            new HeightmapResult(false, "Area too large. Maximum 10000 height points allowed",
                null, null, null, null, null, null)
        ));

        HttpResponse<String> response = sendJson("/api/world/blocks/heightmap/preview", Map.of(
            "x1", 0,
            "z1", 0,
            "x2", 200,
            "z2", 200
        ));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).get("error").asText()).isEqualTo("Area too large. Maximum 10000 height points allowed");
    }

    @Test
    void previewHeightmapReturnsPng() throws Exception {
        when(mockCore.getHeightmap(any())).thenReturn(CompletableFuture.completedFuture(
            new HeightmapResult(
                true,
                null,
                "minecraft:overworld",
                Map.of("min", Map.of("x", 0, "z", 0), "max", Map.of("x", 1, "z", 1)),
                Map.of("x", 2, "z", 2),
                "WORLD_SURFACE",
                Map.of("min", 63, "max", 66),
                new int[][] {
                    {64, 65},
                    {63, 66}
                }
            )
        ));

        HttpResponse<byte[]> response = sendJsonBytes("/api/world/blocks/heightmap/preview", Map.of(
            "x1", 0,
            "z1", 0,
            "x2", 1,
            "z2", 1,
            "iso_scale", 4,
            "view_direction", "west"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("image/png"));
        assertThat(response.body()).isNotEmpty();
    }

    private HttpResponse<String> sendJson(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendJsonBytes(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode readJson(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }
}
