package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.arealock.AreaLockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PlacementBoundsTest {
    @Test
    void sparseBlockArrayIgnoresNullsAndUsesXYZOrder() {
        BlockSetRequest req = new BlockSetRequest();
        req.start_x = 10; req.start_y = 60; req.start_z = -10;
        req.blocks = new BlockData[3][2][4];
        req.blocks[2][1][3] = new BlockData();
        assertThat(PlacementBounds.of(req)).isEqualTo(new AreaBounds(12, 61, -7, 12, 61, -7));
    }

    @Test
    void stairsIncludeClearedHeadroomAndSupport() {
        StairRequest req = new StairRequest();
        req.start_x = 10; req.start_y = 70; req.start_z = 4;
        req.end_x = 0; req.end_y = 60; req.end_z = 2;
        req.fill_support = true;
        assertThat(PlacementBounds.of(req)).isEqualTo(new AreaBounds(0, 59, 2, 10, 74, 4));
    }

    @Test
    void structureRotationAccountsForNegativeOffsets() {
        assertThat(PlacementBounds.structure(10, 60, 20, 3, 4, 5, "NONE"))
            .isEqualTo(new AreaBounds(10, 60, 20, 12, 63, 24));
        assertThat(PlacementBounds.structure(10, 60, 20, 3, 4, 5, "CLOCKWISE_90"))
            .isEqualTo(new AreaBounds(6, 60, 20, 10, 63, 22));
        assertThat(PlacementBounds.structure(10, 60, 20, 3, 4, 5, "CLOCKWISE_180"))
            .isEqualTo(new AreaBounds(8, 60, 16, 10, 63, 20));
        assertThat(PlacementBounds.structure(10, 60, 20, 3, 4, 5, "COUNTERCLOCKWISE_90"))
            .isEqualTo(new AreaBounds(10, 60, 18, 14, 63, 20));
    }

    @Test
    void railsIncludeDeepSupportsExcavationAndNeighborReconciliation() throws Exception {
        var path = new ObjectMapper().readTree("[{\"x\":0,\"y\":70,\"z\":0},{\"x\":1,\"y\":71,\"z\":0}]");
        assertThat(PlacementBounds.rail(path, "bridge")).isEqualTo(new AreaBounds(-1, 46, -1, 2, 74, 1));
        assertThat(PlacementBounds.rail(path, "tunnel")).isEqualTo(new AreaBounds(-1, 62, -1, 2, 74, 1));
    }

    @Test
    void everyPrefabAndFillFootprintIsBlockedBeforeMutation() {
        DoorRequest door = new DoorRequest(); door.facing = "south"; door.width = 3;
        StairRequest stair = new StairRequest(); stair.end_y = 4;
        WindowPaneRequest window = new WindowPaneRequest(); window.height = 4; window.end_x = 4;
        LadderRequest ladder = new LadderRequest(); ladder.height = 4;
        FillBoxRequest fill = new FillBoxRequest(); fill.x1 = 4; fill.y2 = 4; fill.z2 = 4;
        List<AreaBounds> footprints = List.of(PlacementBounds.of(door), PlacementBounds.of(stair),
            PlacementBounds.of(window), PlacementBounds.of(ladder), PlacementBounds.of(new TorchRequest()),
            PlacementBounds.of(new SignRequest()), PlacementBounds.of(fill));
        var locks = new AreaLockService(Clock.systemUTC(), Duration.ofMinutes(15));
        locks.acquire("world", new AreaBounds(0, 0, 0, 0, 0, 0), "origin");
        var writes = new AtomicInteger();
        for (AreaBounds footprint : footprints) {
            assertThatThrownBy(() -> locks.execute("world", footprint, null, writes::incrementAndGet, n -> true))
                .isInstanceOf(AreaLockException.class);
        }
        assertThat(writes.get()).isZero();
        assertThat(PlacementBounds.of(door)).isEqualTo(new AreaBounds(-2, 0, 0, 0, 1, 0));
    }

    @Test
    void malformedBoundsAndOverflowFailValidation() throws Exception {
        var mapper = new ObjectMapper();
        assertThatThrownBy(() -> AreaLockEndpoint.bounds(mapper.readTree("{\"min_x\":0}")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlacementBounds.structure(Integer.MAX_VALUE, 0, 0, 2, 1, 1, "NONE"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlacementBounds.of(new LadderRequest())).isInstanceOf(IllegalArgumentException.class);
    }
}
