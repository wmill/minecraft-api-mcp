package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaLockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class AreaLockEndpointTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final ExecutorService serverThread = Executors.newSingleThreadExecutor();
    private Javalin app;
    private String base;
    private static final String BOUNDS = "{\"min_x\":0,\"min_y\":60,\"min_z\":0,\"max_x\":9,\"max_y\":80,\"max_z\":9}";

    @BeforeEach
    void start() {
        app = Javalin.create();
        new AreaLockEndpoint(app, serverThread, value -> {
            if (value == null || value.equals("overworld") || value.equals("minecraft:overworld")) return "minecraft:overworld";
            throw new IllegalArgumentException("Unknown world");
        }, new AreaLockService(Clock.systemUTC(), Duration.ofMinutes(15)));
        app.start(0);
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void stop() { app.stop(); serverThread.shutdownNow(); }

    @Test
    void completeReservationLifecycleAndRedactedConflict() throws Exception {
        var acquired = request("POST", "", "{\"world\":\"overworld\",\"label\":\"house\",\"bounds\":" + BOUNDS + "}");
        assertThat(acquired.statusCode()).isEqualTo(200);
        JsonNode lease = json.readTree(acquired.body());
        String id = lease.get("lock_id").asText();
        assertThat(lease.get("world").asText()).isEqualTo("minecraft:overworld");
        assertThat(lease.get("idle_timeout_seconds").intValue()).isEqualTo(900);
        assertThat(lease.get("bounds")).isEqualTo(json.readTree(BOUNDS));

        var conflict = request("POST", "", "{\"bounds\":" + BOUNDS + "}");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).get("code").asText()).isEqualTo("area_locked");
        assertThat(conflict.body()).doesNotContain(id).contains("expires_at");
        assertThat(request("GET", "", null).body()).doesNotContain(id).contains("house");
        assertThat(request("PATCH", "/" + id, "{}").statusCode()).isEqualTo(200);
        assertThat(request("PATCH", "/" + id, "{\"bounds\":" + BOUNDS + "}").statusCode()).isEqualTo(200);
        assertThat(request("DELETE", "/" + id, null).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", "/" + id, null).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET", "", null).body()).get("locks")).isEmpty();
        assertThat(request("PATCH", "/" + id, "{}").statusCode()).isEqualTo(409);
    }

    @Test
    void malformedPayloadsAndPartialFiltersAreBadRequests() throws Exception {
        for (String body : new String[]{"{}", "null", "[]", "{", "{\"bounds\":{\"min_x\":0}}"}) {
            assertThat(request("POST", "", body).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(request("GET", "?min_x=0", null).statusCode()).isEqualTo(400);
        assertThat(request("GET", "?world=missing", null).statusCode()).isEqualTo(400);
        assertThat(request("PATCH", "/unknown", "{\"world\":\"overworld\"}").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/area-locks" + path))
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }
}
