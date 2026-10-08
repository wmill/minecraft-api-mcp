package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.buildtask.model.Build;
import ca.waltermiller.mcpapi.buildtask.service.BuildService;
import ca.waltermiller.mcpapi.buildtask.service.LocationQueryService;
import ca.waltermiller.mcpapi.buildtask.service.RailPlanningService;
import ca.waltermiller.mcpapi.snapshot.SnapshotStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildUndoEndpointTest {
    @Mock private MinecraftServer server;
    @Mock private BuildService buildService;
    @Mock private LocationQueryService locationQueryService;
    @Mock private RailPlanningService railPlanningService;
    @TempDir Path dir;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private Javalin app;

    private SnapshotStore start(boolean snapshotsEnabled) {
        SnapshotStore store = snapshotsEnabled ? new SnapshotStore(dir, 1_000) : null;
        app = Javalin.create(config -> config.http.defaultContentType = "application/json");
        new BuildTaskEndpoint(app, server, LoggerFactory.getLogger(BuildUndoEndpointTest.class), buildService,
            locationQueryService, railPlanningService, new TaskExecutor(server), new AreaLockService(), store);
        app.start(0);
        return store;
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
    }

    private HttpResponse<String> undo(UUID id, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/builds/" + id + "/undo"))
            .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private BuildService.NbtPlacement placement(UUID id) {
        Build build = new Build("NBT: hut.nbt", "", "minecraft:overworld");
        build.setId(id);
        build.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        return new BuildService.NbtPlacement(build, 10, 64, 20, 3, 4, 5, "CLOCKWISE_90");
    }

    @Test
    void unavailableWithoutSnapshotStore() throws Exception {
        start(false);
        assertThat(undo(UUID.randomUUID(), null).statusCode()).isEqualTo(503);
    }

    @Test
    void notFound() throws Exception {
        start(true);
        UUID id = UUID.randomUUID();
        when(buildService.getNbtPlacement(id)).thenReturn(Optional.empty());
        assertThat(undo(id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void notAnNbtPlacementOrAlreadyReverted() throws Exception {
        start(true);
        UUID notNbt = UUID.randomUUID(), reverted = UUID.randomUUID();
        when(buildService.getNbtPlacement(notNbt)).thenThrow(new IllegalArgumentException("Only NBT structure placements can be undone"));
        when(buildService.getNbtPlacement(reverted)).thenThrow(new IllegalStateException("already been reverted"));
        assertThat(undo(notNbt, null).statusCode()).isEqualTo(400);
        assertThat(undo(reverted, null).statusCode()).isEqualTo(409);
    }

    @Test
    void missingSnapshotIsBadRequest() throws Exception {
        start(true);
        UUID id = UUID.randomUUID();
        when(buildService.getNbtPlacement(id)).thenReturn(Optional.of(placement(id)));
        HttpResponse<String> response = undo(id, null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("error").asText()).contains("No undo snapshot");
    }

    @Test
    void laterOverlappingBuildsBlockUndoWithoutForce() throws Exception {
        SnapshotStore store = start(true);
        UUID id = UUID.randomUUID();
        BuildService.NbtPlacement placement = placement(id);
        store.save(id, new NbtCompound());
        Build later = new Build("cottage", "", "minecraft:overworld");
        later.setId(UUID.randomUUID());
        when(buildService.getNbtPlacement(id)).thenReturn(Optional.of(placement));
        ArgumentCaptor<BoundingBox> box = ArgumentCaptor.forClass(BoundingBox.class);
        when(buildService.findLaterOverlappingBuilds(eq(placement.build()), box.capture())).thenReturn(List.of(later));

        HttpResponse<String> response = undo(id, "{\"force\":false}");

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo("undo_conflict");
        assertThat(body.get("builds").get(0).get("build_id").asText()).isEqualTo(later.getId().toString());
        // CLOCKWISE_90 at x=10 with size_z=5 spans x 6..10, z 20..22.
        assertThat(box.getValue().getMinX()).isEqualTo(6);
        assertThat(box.getValue().getMaxZ()).isEqualTo(22);
        assertThat(store.exists(id)).isTrue();
        verify(buildService, never()).markReverted(any());
    }

    @Test
    void rejectsNonBooleanForce() throws Exception {
        start(true);
        assertThat(undo(UUID.randomUUID(), "{\"force\":\"yes\"}").statusCode()).isEqualTo(400);
    }
}
