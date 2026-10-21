package ca.waltermiller.mcpapi.marker;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MarkerSpecTest {
    private static final AreaBounds BOUNDS = new AreaBounds(-10, 60, -20, 9, 70, -1);

    @Test void inclusiveBoundsProducePhysicalEdgesAndCenteredColumn() {
        var spec = new MarkerSpec(BOUNDS, "Station", "cyan", 60);
        assertThat(spec.centerX()).isEqualTo(0);
        assertThat(spec.centerZ()).isEqualTo(-10);
        var points = spec.points(319);
        assertThat(points).contains(new MarkerSpec.Point(-10, 60, -20), new MarkerSpec.Point(10, 71, 0),
            new MarkerSpec.Point(0, 95, -10));
        assertThat(points).allSatisfy(p -> {
            assertThat(p.x()).isBetween(-10.0, 10.0);
            assertThat(p.y()).isBetween(60.0, 95.0);
            assertThat(p.z()).isBetween(-20.0, 0.0);
        });
    }

    @Test void largestAllowedMarkerHasBoundedParticleCountAndRespectsWorldTop() {
        var spec = new MarkerSpec(new AreaBounds(0, 190, 0, 127, 317, 127), "", "yellow", 300);
        assertThat(spec.points(319)).hasSizeLessThanOrEqualTo(217)
            .allSatisfy(p -> assertThat(p.y()).isLessThanOrEqualTo(319));
    }

    @Test void rejectsInvalidInputsAndOverflowingDimensions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MarkerSpec(null, "", "yellow", 60));
        assertThatIllegalArgumentException().isThrownBy(() -> new MarkerSpec(BOUNDS, "x".repeat(121), "yellow", 60));
        assertThatIllegalArgumentException().isThrownBy(() -> new MarkerSpec(BOUNDS, "", "pink", 60));
        for (int duration : new int[] {0, -1, 301, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new MarkerSpec(BOUNDS, "", "yellow", duration));
        }
        for (var bounds : new AreaBounds[] {
            new AreaBounds(0, 0, 0, 128, 1, 1),
            new AreaBounds(0, Integer.MIN_VALUE, 0, 1, Integer.MAX_VALUE, 1),
            new AreaBounds(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 1, 1)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new MarkerSpec(bounds, "", "yellow", 60));
        }
    }
}
