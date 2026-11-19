package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaLockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Request validation that runs before any world access. */
@ExtendWith(MockitoExtension.class)
class NBTStructureEndpointTest {
    private static final String BOUNDARY = "----mcpapi-test";
    @Mock private MinecraftServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private Javalin app;

    @BeforeEach
    void start() {
        app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        // No build service or snapshot store: provenance is still validated, revisions are unavailable.
        new NBTStructureEndpoint(app, server, LoggerFactory.getLogger(NBTStructureEndpointTest.class), new AreaLockService());
        app.start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> place(Map<String, String> fields) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"nbt_file\"; filename=\"a.nbt\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(new byte[] {10, 0, 0, 0});
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/world/structure/place"))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void malformedProvenanceIsRejected() throws Exception {
        HttpResponse<String> notJson = place(Map.of("provenance", "{kind"));
        assertThat(notJson.statusCode()).isEqualTo(400);
        assertThat(json.readTree(notJson.body()).get("error").asText()).startsWith("Invalid provenance JSON");

        HttpResponse<String> badKind = place(Map.of("provenance", "{\"kind\": \"mod\"}"));
        assertThat(badKind.statusCode()).isEqualTo(400);
        assertThat(json.readTree(badKind.body()).get("error").asText()).contains("provenance.kind");
    }

    @Test
    void malformedReplacesBuildIdIsRejected() throws Exception {
        HttpResponse<String> response = place(Map.of("replaces_build_id", "not-a-uuid"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("error").asText()).contains("replaces_build_id");
    }

    @Test
    void revisionsNeedTheBuildDatabaseAndSnapshots() throws Exception {
        HttpResponse<String> response = place(Map.of("replaces_build_id", "00000000-0000-0000-0000-000000000001"));
        assertThat(response.statusCode()).isEqualTo(503);
    }
}
