package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.survey.SiteSurvey;
import ca.waltermiller.mcpapi.survey.SurveyOccupancy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.*;

class SiteSurveyEndpointTest {
    private static class Terrain implements SiteSurvey.Terrain {
        public int bottomY() { return -64; }
        public int topYInclusive() { return 319; }
        public boolean ceiling() { return false; }
        public boolean chunkLoaded(int x, int z) { return x >= 0; }
        public int surfaceY(int x, int z) { return 64; }
        public SiteSurvey.Cell cell(int x, int y, int z) { return new SiteSurvey.Cell(false, false, false, false, true, true); }
    }

    @Test void httpContractAndValidation() throws Exception {
        var app = Javalin.create();
        try {
            new SiteSurveyEndpoint(app, Runnable::run, name -> {
                if (name != null && !name.equals("minecraft:overworld")) throw new IllegalArgumentException("Unknown world");
                return new SiteSurveyEndpoint.WorldTerrain("minecraft:overworld", new Terrain());
            }, new AreaLockService(), new SurveyOccupancy());
            app.start(0);
            var response = send(app, "{\"x1\":39,\"z1\":39,\"x2\":0,\"z2\":0}");
            assertThat(response.statusCode()).isEqualTo(200);
            var json = new ObjectMapper().readTree(response.body());
            assertThat(json.path("bounds").path("min_x").asInt()).isZero();
            assertThat(json.path("size").path("columns").asInt()).isEqualTo(1600);
            assertThat(json.path("grading").path("suggested_walking_y").asInt()).isEqualTo(64);
            assertThat(json.path("builds").path("status").asText()).isEqualTo("unavailable");
            assertThat(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(6000);
            assertThat(json.has("heights")).isFalse();
            for (String body : new String[] {
                "{}", "[]", "{", "{\"x1\":0.1,\"z1\":0,\"x2\":0,\"z2\":0}",
                "{\"x1\":2147483648,\"z1\":0,\"x2\":0,\"z2\":0}",
                "{\"x1\":0,\"z1\":0,\"x2\":100,\"z2\":100}",
                "{\"x1\":0,\"z1\":0,\"x2\":0,\"z2\":0,\"world\":5}",
                "{\"x1\":0,\"z1\":0,\"x2\":0,\"z2\":0,\"world\":\"missing\"}"
            }) {
                var invalid = send(app, body);
                assertThat(invalid.statusCode()).as(body).isEqualTo(400);
                assertThat(new ObjectMapper().readTree(invalid.body()).path("code").asText()).isEqualTo("invalid_request");
            }
            var unloaded = send(app, "{\"x1\":-1,\"z1\":0,\"x2\":0,\"z2\":0}");
            assertThat(unloaded.statusCode()).isEqualTo(400);
            assertThat(new ObjectMapper().readTree(unloaded.body()).path("missing_chunks").get(0).path("x").asInt()).isEqualTo(-1);
        } finally { app.stop(); }
    }

    private HttpResponse<String> send(Javalin app, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/world/blocks/survey"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
