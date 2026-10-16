package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.terrain.GroundColumn;
import ca.waltermiller.mcpapi.terrain.TerrainAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TerrainSmoothEndpointTest {
    /** Bumpy grass in loaded chunks x >= 0; a house at (5,5). */
    static final class FakeTerrain implements TerrainAccess {
        final List<String> writes = new ArrayList<>();

        public boolean chunkLoaded(int chunkX, int chunkZ) { return chunkX >= 0; }

        public GroundColumn sample(int x, int z) {
            if (!chunkLoaded(x >> 4, z >> 4)) return GroundColumn.UNLOADED_COLUMN;
            if (x == 5 && z == 5) return new GroundColumn(GroundColumn.Kind.BUILT, 72, 72, "minecraft:oak_planks", null);
            int ground = 64 + ((x * 7 + z * 3) % 5);
            int surface = x == 8 && z == 8 ? ground + 6 : ground;
            return new GroundColumn(GroundColumn.Kind.GROUND, ground, surface, "minecraft:grass_block", "minecraft:dirt");
        }

        public void clearVegetation(int x, int y, int z) { writes.add("clear"); }
        public void setTerrain(int x, int y, int z, String blockId) { writes.add(blockId == null ? "air" : blockId); }
        public NbtCompound capture(AreaBounds bounds) { return null; }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final FakeTerrain terrain = new FakeTerrain();
    private final AreaLockService locks = new AreaLockService();
    private Javalin app;

    @BeforeEach void start() {
        app = Javalin.create();
        new TerrainSmoothEndpoint(app, Runnable::run, name -> {
            if (name != null && !name.equals("minecraft:overworld")) {
                throw new TerrainSmoothEndpoint.RequestException("Unknown world: " + name);
            }
            return new TerrainSmoothEndpoint.Target("minecraft:overworld", terrain);
        }, locks, null, null, LoggerFactory.getLogger("test"));
        app.start(0);
    }

    @AfterEach void stop() { app.stop(); }

    @Test void rejectsInvalidRequests() throws Exception {
        for (String body : new String[] {
            "[]", "{", "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"blur\"}",
            "{\"x1\":0,\"z1\":0,\"x2\":200,\"z2\":200,\"method\":\"gaussian\"}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"gaussian\",\"sigma\":9}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"gaussian\",\"sigmaa\":2}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"gaussian\",\"strength\":0}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"pad\"}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"pad\",\"pad\":{\"y\":64,\"x1\":5,\"z1\":5,\"x2\":12,\"z2\":8}}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"median\",\"pad\":{}}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"median\",\"dry_run\":\"yes\"}",
            "{\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"method\":\"median\",\"world\":\"minecraft:the_moon\"}",
        }) {
            var response = send(body, null);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).as(body).isEqualTo("invalid_request");
        }
        assertThat(terrain.writes).isEmpty();
    }

    @Test void refusesUnloadedChunks() throws Exception {
        var response = send("{\"x1\":-3,\"z1\":0,\"x2\":5,\"z2\":5,\"method\":\"median\"}", null);
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("unloaded_chunks");
        assertThat(body.path("missing_chunks").get(0).path("x").asInt()).isEqualTo(-1);
    }

    @Test void dryRunReturnsThreeMapsAndWritesNothing() throws Exception {
        var response = send("{\"x1\":0,\"z1\":0,\"x2\":19,\"z2\":19,\"method\":\"gaussian\",\"sigma\":1.5,"
            + "\"dry_run\":true,\"px_per_block\":4}", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("dry_run").asBoolean()).isTrue();
        for (String image : new String[] {"before_png", "after_png", "change_png"}) {
            assertThat(body.path(image).asText()).as(image).isNotEmpty();
        }
        JsonNode summary = body.path("summary");
        assertThat(summary.path("columns").asInt()).isEqualTo(400);
        assertThat(summary.path("masked_columns").asInt()).isEqualTo(1);
        assertThat(summary.path("masked_by_kind").path("built").asInt()).isEqualTo(1);
        assertThat(summary.path("protected_columns").asInt()).isEqualTo(8);
        assertThat(summary.path("changed_columns").asInt()).isPositive();
        assertThat(summary.path("vegetation_columns").asInt()).isEqualTo(1);
        assertThat(body.path("lock_check").path("ok").asBoolean()).isTrue();
        assertThat(body.path("undo_snapshot").asBoolean()).isFalse();
        assertThat(body.path("parameters").path("sigma").asDouble()).isEqualTo(1.5);
        assertThat(body.path("parameters").has("dry_run")).isFalse();
        assertThat(body.path("write_bounds").path("max_y").asInt()).isGreaterThanOrEqualTo(69);
        assertThat(terrain.writes).isEmpty();
    }

    @Test void appliesReportsUndoUnavailableWithoutSnapshots() throws Exception {
        var response = send("{\"x1\":0,\"z1\":0,\"x2\":19,\"z2\":19,\"method\":\"pad\","
            + "\"pad\":{\"y\":66,\"x1\":8,\"z1\":10,\"x2\":14,\"z2\":16,\"blend\":3}}", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.has("before_png")).isFalse();
        assertThat(body.path("summary").path("pad_columns").asInt()).isEqualTo(49);
        assertThat(body.path("summary").path("pad_columns_at_target").asInt()).isEqualTo(49);
        assertThat(body.path("vegetation_blocks_cleared").asInt()).isEqualTo(6);
        assertThat(body.path("undo_available").asBoolean()).isFalse();
        assertThat(body.path("undo_unavailable_reason").asText()).isEqualTo("snapshots_disabled");
        assertThat(terrain.writes).contains("clear", "minecraft:grass_block");
    }

    @Test void respectsAreaLocks() throws Exception {
        locks.acquire("minecraft:overworld", new AreaBounds(10, -64, 10, 12, 320, 12), "someone else");
        String request = "{\"x1\":0,\"z1\":0,\"x2\":19,\"z2\":19,\"method\":\"median\"}";
        var response = send(request, null);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("area_locked");
        assertThat(terrain.writes).isEmpty();

        var dryRun = send(request.replace("}", ",\"dry_run\":true}"), null);
        assertThat(dryRun.statusCode()).isEqualTo(200);
        assertThat(json.readTree(dryRun.body()).path("lock_check").path("ok").asBoolean()).isFalse();
    }

    private HttpResponse<String> send(String body, String lockId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/world/terrain/smooth"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (lockId != null) builder.header(AreaLockService.HEADER, lockId);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
