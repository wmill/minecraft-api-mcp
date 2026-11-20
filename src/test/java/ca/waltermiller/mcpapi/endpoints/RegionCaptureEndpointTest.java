package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.endpoints.RegionCaptureRequest.RegionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RegionCaptureEndpointTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID BUILD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REVERTED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NO_BOUNDS = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final byte[] NBT = {0x1f, (byte) 0x8b, 1, 2, 3};

    @Test void capturesCuboidsAndBuilds() throws Exception {
        var app = Javalin.create();
        List<AreaBounds> captured = new ArrayList<>();
        try {
            var reader = new RegionCaptureEndpoint.RegionReader() {
                @Override public List<Map<String, Integer>> missingChunks(AreaBounds bounds) {
                    return bounds.max_x() > 1000 ? List.of(Map.of("x", 63, "z", 0)) : List.of();
                }
                @Override public byte[] capture(AreaBounds bounds) { captured.add(bounds); return NBT; }
            };
            var endpoint = new RegionCaptureEndpoint(app, Runnable::run, name -> {
                if (name != null && !name.equals("minecraft:overworld")) throw new RegionException(400, "invalid_request", "Unknown world");
                return new RegionCaptureEndpoint.WorldRegion("minecraft:overworld", -64, 319, reader);
            }, 10_000);
            app.start(0);

            var ok = get(app, "x1=0&y1=60&z1=0&x2=9&y2=69&z2=9");
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo(NBT);
            assertThat(ok.headers().firstValue("X-Region-Origin")).contains("0,60,0");
            assertThat(ok.headers().firstValue("X-Region-Size")).contains("10,10,10");
            assertThat(ok.headers().firstValue("X-Region-World")).contains("minecraft:overworld");
            assertThat(ok.headers().firstValue("X-Build-Removed")).isEmpty();

            assertError(get(app, "x1=0&y1=60&z1=0&x2=99&y2=69&z2=99"), 400, "region_too_large");
            assertError(get(app, "x1=1001&y1=60&z1=0&x2=1002&y2=61&z2=1"), 409, "chunks_not_loaded");
            assertError(get(app, "x1=0&y1=60&z1=0&x2=1&y2=61&z2=1&world=nowhere"), 400, "invalid_request");
            assertError(get(app, "x1=0"), 400, "invalid_request");

            String byBuild = "build_id=" + BUILD;
            assertError(get(app, byBuild), 503, "builds_unavailable");
            endpoint.setBuildLookup(id -> id.equals(BUILD)
                ? Optional.of(new AccessCheckEndpoint.BuildArea("minecraft:overworld", false, new Box(0, 64, 0, 9, 70, 9)))
                : id.equals(REVERTED) ? Optional.of(new AccessCheckEndpoint.BuildArea("minecraft:overworld", true, new Box(0, 64, 0, 1, 64, 1)))
                : id.equals(NO_BOUNDS) ? Optional.of(new AccessCheckEndpoint.BuildArea("minecraft:overworld", false, null))
                : Optional.empty());
            var build = get(app, byBuild + "&margin=2&below=1&above=1");
            assertThat(build.statusCode()).isEqualTo(200);
            assertThat(captured.getLast()).isEqualTo(new AreaBounds(-2, 63, -2, 11, 71, 11));
            assertThat(build.headers().firstValue("X-Build-Removed")).contains("false");
            assertThat(get(app, "build_id=" + REVERTED).headers().firstValue("X-Build-Removed")).contains("true");
            assertError(get(app, "build_id=" + NO_BOUNDS), 400, "build_has_no_bounds");
            assertError(get(app, "build_id=" + UUID.randomUUID()), 404, "build_not_found");
            assertError(get(app, byBuild + "&world=minecraft:the_nether"), 400, "invalid_request");
        } finally { app.stop(); }
    }

    private static void assertError(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    private static HttpResponse<byte[]> get(Javalin app, String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + RegionCaptureEndpoint.PATH + "?" + query))
            .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
