package ca.waltermiller.mcpapi.buildtask.service;

import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.*;
import ca.waltermiller.mcpapi.buildtask.repository.*;
import ca.waltermiller.mcpapi.database.DatabaseManager;
import ca.waltermiller.mcpapi.endpoints.TaskExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Provenance and revision lineage against PostgreSQL (one transaction per record/undo/redo). */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class ProvenanceIntegrationTest {
    private BuildService service;
    private PostgreSQLBuildRepository builds;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        assertThat(System.getenv("DB_NAME")).as("Use an isolated test database").contains("test");
        var manager = DatabaseManager.getInstance();
        manager.initialize();
        var config = manager.getDatabaseConfig();
        builds = new PostgreSQLBuildRepository(config);
        service = new BuildService(builds, new PostgreSQLTaskRepository(config), new PostgreSQLBuildSourceRepository(config),
            mock(TaskExecutor.class));
    }

    @AfterEach void stop() throws Exception {
        // Children first: parent_build_id is ON DELETE SET NULL, sources and tasks cascade.
        Collections.reverse(created);
        for (UUID id : created) builds.deleteById(id);
        if (service != null) { service.shutdown(); service.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)); }
    }

    private Build record(BuildSource source, UUID parent, BoundingBox restore) throws Exception {
        Build build = service.recordNbtPlacement(new BuildService.PlacementRecord("slk_test.nbt", "minecraft:overworld",
            100, 64, 200, 5, 6, 7, "NONE", source, parent, restore));
        created.add(build.getId());
        return build;
    }

    private BuildSource starlark(int height) throws Exception {
        return BuildSource.fromJson(Json.MAPPER.readTree("""
            {"kind": "starlark", "artifact_id": "slk_%016d", "source": "def build(): pass", "entry": "build",
             "props": {"height": %d}, "root_size": [5, 6, 7], "y_offset": -1, "target": {"x": 100, "y": 65, "z": 200},
             "details": {"library_loads": ["tower@1"]}}
            """.formatted(height, height)));
    }

    @Test void provenanceRoundTripsAndSummariesOmitTheScript() throws Exception {
        Build build = record(starlark(8), null, null);
        BuildSource stored = service.getSource(build.getId()).orElseThrow();
        assertThat(stored.source()).isEqualTo("def build(): pass");
        assertThat(stored.props().get("height").asInt()).isEqualTo(8);
        assertThat(stored.rootSize().get(2).asInt()).isEqualTo(7);
        assertThat(stored.target().get("y").asInt()).isEqualTo(65);
        assertThat(stored.details().get("library_loads").get(0).asText()).isEqualTo("tower@1");
        assertThat(stored.yOffset()).isEqualTo(-1);

        Build bare = record(null, null, null);
        Map<UUID, BuildSource> summaries = service.getSourceSummaries(List.of(build.getId(), bare.getId()));
        assertThat(summaries).containsOnlyKeys(build.getId());
        assertThat(summaries.get(build.getId()).toMap(false)).containsEntry("has_source", true).doesNotContainKey("source");
        assertThat(service.getSource(bare.getId())).isEmpty();
    }

    @Test void revisionLineageMovesTheParentStatusAtomically() throws Exception {
        Build parent = record(starlark(8), null, null);
        Build child = record(starlark(12), parent.getId(), new BoundingBox(90, 60, 190, 110, 80, 210));

        assertThat(service.getBuild(parent.getId()).orElseThrow().getStatus()).isEqualTo(BuildStatus.SUPERSEDED);
        Build reloaded = service.getBuild(child.getId()).orElseThrow();
        assertThat(reloaded.getParentBuildId()).isEqualTo(parent.getId());
        assertThat(service.getChildren(parent.getId())).extracting(Build::getId).containsExactly(child.getId());
        var placement = service.getPlacementRecord(child.getId()).orElseThrow();
        assertThat(placement.restoreBounds()).isEqualTo(new BoundingBox(90, 60, 190, 110, 80, 210));
        assertThatIllegalStateException().isThrownBy(() -> service.getNbtPlacement(parent.getId()))
            .withMessageContaining(child.getId().toString());

        service.markReverted(reloaded);
        assertThat(service.getBuild(parent.getId()).orElseThrow().getStatus()).isEqualTo(BuildStatus.COMPLETED);
        service.markRestored(service.getBuild(child.getId()).orElseThrow());
        assertThat(service.getBuild(parent.getId()).orElseThrow().getStatus()).isEqualTo(BuildStatus.SUPERSEDED);
        assertThat(service.getBuild(child.getId()).orElseThrow().getStatus()).isEqualTo(BuildStatus.COMPLETED);
    }

    @Test void revisingANonCompletedBuildRollsBackTheWholeRecord() throws Exception {
        Build parent = record(starlark(8), null, null);
        service.markReverted(parent);
        int before = builds.findByLocationIntersection("minecraft:overworld", new BoundingBox(100, 64, 200, 100, 64, 200)).size();
        assertThatIllegalStateException().isThrownBy(() -> record(starlark(12), parent.getId(), null));
        assertThat(service.getChildren(parent.getId())).isEmpty();
        assertThat(builds.findByLocationIntersection("minecraft:overworld", new BoundingBox(100, 64, 200, 100, 64, 200)))
            .hasSize(before);
    }
}
