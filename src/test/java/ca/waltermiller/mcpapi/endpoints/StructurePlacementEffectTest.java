package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StructurePlacementEffectTest {
    @Test
    void singleBlockHasSmallBurstAboveBottom() {
        var bounds = new AreaBounds(0, 64, 0, 0, 64, 0);
        assertThat(StructurePlacementEffect.particlePoints(bounds)).hasSize(3);
        assertPerimeter(bounds);
    }

    @Test
    void rectangleHasEvenSpacingAroundAllFourSides() {
        var bounds = new AreaBounds(10, 60, 20, 17, 80, 23);
        var points = StructurePlacementEffect.particlePoints(bounds);
        assertThat(points).hasSize(13);
        assertPerimeter(bounds);
        double previous = -1;
        double spacing = 25.2 / 13;
        for (var point : points) {
            double distance;
            if (Math.abs(point.z() - 19.85) < 1e-8) {
                distance = point.x() - 9.85;
            } else if (Math.abs(point.x() - 18.15) < 1e-8) {
                distance = 8.3 + point.z() - 19.85;
            } else if (Math.abs(point.z() - 24.15) < 1e-8) {
                distance = 12.6 + 18.15 - point.x();
            } else {
                distance = 20.9 + 24.15 - point.z();
            }
            assertThat(distance).isCloseTo(previous < 0 ? spacing / 2 : previous + spacing, within(1e-8));
            previous = distance;
        }
    }

    @Test
    void rotationsUseTheirTransformedFootprints() {
        for (String rotation : new String[]{"NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"}) {
            var bounds = PlacementBounds.structure(10, 60, 20, 3, 4, 5, rotation);
            assertThat(StructurePlacementEffect.particlePoints(bounds)).hasSize(9);
            assertPerimeter(bounds);
        }
    }

    @Test
    void negativeCoordinatesTranslateTheBurst() {
        var origin = StructurePlacementEffect.particlePoints(new AreaBounds(0, 0, 0, 7, 4, 3));
        var shiftedBounds = new AreaBounds(-100, -60, -200, -93, -56, -197);
        var shifted = StructurePlacementEffect.particlePoints(shiftedBounds);
        assertThat(shifted).hasSameSizeAs(origin);
        for (int i = 0; i < origin.size(); i++) {
            assertThat(shifted.get(i).x()).isCloseTo(origin.get(i).x() - 100, within(1e-8));
            assertThat(shifted.get(i).y()).isEqualTo(-59.5);
            assertThat(shifted.get(i).z()).isCloseTo(origin.get(i).z() - 200, within(1e-8));
        }
        assertPerimeter(shiftedBounds);
    }

    @Test
    void hugeBoundsAreCappedWithoutIntegerOverflow() {
        var bounds = new AreaBounds(Integer.MIN_VALUE, 0, Integer.MIN_VALUE,
            Integer.MAX_VALUE, 100, Integer.MAX_VALUE);
        assertThat(StructurePlacementEffect.particlePoints(bounds)).hasSize(32);
        assertPerimeter(bounds);
    }

    private static void assertPerimeter(AreaBounds bounds) {
        var points = StructurePlacementEffect.particlePoints(bounds);
        assertThat(points).doesNotHaveDuplicates();
        double left = bounds.min_x() - 0.15;
        double right = (double) bounds.max_x() + 1.15;
        double north = bounds.min_z() - 0.15;
        double south = (double) bounds.max_z() + 1.15;
        for (var point : points) {
            assertThat(point.y()).isEqualTo(bounds.min_y() + 0.5);
            assertThat(point.x()).isBetween(left - 1e-6, right + 1e-6);
            assertThat(point.z()).isBetween(north - 1e-6, south + 1e-6);
            assertThat(Math.abs(point.x() - left) < 1e-6 || Math.abs(point.x() - right) < 1e-6
                || Math.abs(point.z() - north) < 1e-6 || Math.abs(point.z() - south) < 1e-6).isTrue();
        }
    }
}
