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
    void writePropagatesTokenAndReturnsStructuredConflict() throws Exception {
        var conflict = new ca.waltermiller.mcpapi.arealock.AreaLockException("area_locked", "reserved", Map.of("reservation", Map.of("label", "house")));
        when(mockCore.fillBox(any(), org.mockito.ArgumentMatchers.eq("owner")))
            .thenReturn(CompletableFuture.failedFuture(conflict));
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/world/blocks/fill"))
            .header("Content-Type", "application/json").header("X-Area-Lock-Id", "owner")
            .POST(HttpRequest.BodyPublishers.ofString("{\"x1\":0,\"y1\":60,\"z1\":0,\"x2\":1,\"y2\":61,\"z2\":1,\"block_type\":\"minecraft:stone\"}"))
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(readJson(response).get("code").asText()).isEqualTo("area_locked");
        assertThat(readJson(response).get("reservation").get("label").asText()).isEqualTo("house");
    }

    @Test
    void setBlocksReturnsOutsideLockBeforeExecutingWrite() throws Exception {
        var locks = new ca.waltermiller.mcpapi.arealock.AreaLockService();
        var bounds = new ca.waltermiller.mcpapi.arealock.AreaBounds(3109, 65, 950, 3110, 80, 951);
        String token = (String) locks.acquire("minecraft:overworld", bounds, "test").get("lock_id");
        var wrote = new java.util.concurrent.atomic.AtomicBoolean();
        when(mockCore.setBlocks(any(), org.mockito.ArgumentMatchers.eq(token))).thenAnswer(invocation -> {
            BlockSetRequest body = invocation.getArgument(0);
            assertThat(body.blocks[0][0][0].block_name).isEqualTo("minecraft:purpur_block");
            var future = new CompletableFuture<BlockSetResult>();
            GuardedPlacement.submit(Runnable::run, locks, "minecraft:overworld", token,
                () -> PlacementBounds.of(body), () -> {
                    wrote.set(true);
                    return new BlockSetResult(true, null, 1, 0, "minecraft:overworld");
                }, BlockSetResult::success, future);
            return future;
        });
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/world/blocks/set"))
            .header("Content-Type", "application/json").header("X-Area-Lock-Id", token)
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"start_x":3094,"start_y":71,"start_z":950,
                 "blocks":[[[{"block_name":"minecraft:purpur_block","block_states":{}}]]]}
                """))
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(readJson(response).get("code").asText()).isEqualTo("outside_area_lock");
        assertThat(wrote).isFalse();
    }

    private JsonNode readJson(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }
}
