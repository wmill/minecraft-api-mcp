package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.access.AccessCell;
import ca.waltermiller.mcpapi.access.AccessException;
import ca.waltermiller.mcpapi.access.GridTerrain;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AccessCheckEndpointTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID BUILD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REVERTED = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void httpContractValidationAndBuildMaps() throws Exception {
        var app = Javalin.create();
        try {
            var terrain = new GridTerrain().house(66, AccessCell.door(AccessCell.Openable.WOOD, false));
            var endpoint = new AccessCheckEndpoint(app, Runnable::run, name -> {
                if (name != null && !name.equals("minecraft:overworld")) throw new AccessException("invalid_request", "Unknown world");
                return new AccessCheckEndpoint.WorldAccess("minecraft:overworld", terrain, (box, doors) -> null);
            });
            app.start(0);

            String good = "{\"start\":{\"x\":10,\"y\":64,\"z\":0},\"targets\":[{\"name\":\"inside\",\"x\":0,\"y\":64,\"z\":0,"
                + "\"via\":{\"x\":3,\"y\":64,\"z\":0}}],\"map\":{\"x1\":-3,\"y1\":63,\"z1\":-3,\"x2\":3,\"y2\":69,\"z2\":3}}";
            var response = send(app, good);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode json = JSON.readTree(response.body());
            assertThat(json.path("targets").get(0).path("status").asText()).isEqualTo("reachable");
            assertThat(json.path("map").path("standable").asInt()).isPositive();
            assertThat(json.has("route")).isFalse();
            assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isLessThan(8000);

            for (String body : new String[] {"{", "[]", "{}",
                "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":64,\"z\":0}],\"world\":\"missing\"}"}) {
                var invalid = send(app, body);
                assertThat(invalid.statusCode()).as(body).isEqualTo(400);
                assertThat(JSON.readTree(invalid.body()).path("code").asText()).as(body).isEqualTo("invalid_request");
            }

            String byBuild = "{\"start\":{\"x\":10,\"y\":64,\"z\":0},\"map\":{\"build_id\":\"%s\"}}";
            assertThat(code(send(app, byBuild.formatted(BUILD)))).isEqualTo("build_lookup_unavailable");
            endpoint.setBuildLookup(id -> id.equals(BUILD)
                ? Optional.of(new AccessCheckEndpoint.BuildArea("minecraft:overworld", false, new Box(-3, 63, -3, 3, 67, 3)))
                : id.equals(REVERTED) ? Optional.of(new AccessCheckEndpoint.BuildArea("minecraft:overworld", true, null))
                : Optional.empty());
            var mapped = send(app, byBuild.formatted(BUILD));
            assertThat(mapped.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(mapped.body()).path("map").path("region").path("max_y").asInt()).isEqualTo(69);
            assertThat(code(send(app, byBuild.formatted(REVERTED)))).isEqualTo("build_removed");
            assertThat(code(send(app, byBuild.formatted(UUID.randomUUID())))).isEqualTo("build_not_found");
        } finally { app.stop(); }
    }

    private static String code(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        return JSON.readTree(response.body()).path("code").asText();
    }

    private static HttpResponse<String> send(Javalin app, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/world/access/check"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
