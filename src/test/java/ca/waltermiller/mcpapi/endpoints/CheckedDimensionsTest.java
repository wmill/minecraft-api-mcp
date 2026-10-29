package ca.waltermiller.mcpapi.endpoints;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CheckedDimensionsTest {
    @Test void invalidRequestsFailBeforeAccessingEvenAMissingServer() {
        var core = new BlocksEndpointCore(null, org.slf4j.LoggerFactory.getLogger(getClass()));
        var fill = new FillBoxRequest(); fill.x2 = 65_535; fill.z2 = 65_535;
        assertThatIllegalArgumentException().isThrownBy(() -> core.fillBox(fill));
        var heightmap = new HeightmapRequest(); heightmap.x2 = 65_535; heightmap.z2 = 65_535;
        assertThatIllegalArgumentException().isThrownBy(() -> core.getHeightmap(heightmap));
        var chunk = new ChunkRequest(); chunk.size_x = -1; chunk.size_y = 1; chunk.size_z = 1;
        assertThatIllegalArgumentException().isThrownBy(() -> core.getChunk(chunk));
        chunk.size_x = 2; chunk.start_x = Integer.MAX_VALUE;
        assertThatIllegalArgumentException().isThrownBy(() -> core.getChunk(chunk));
    }
    @Test void inclusiveCapsAndReversedCorners() {
        assertThat(CheckedDimensions.fill(0, 0, 0, 99, 99, 9)).isEqualTo(100_000);
        assertThat(CheckedDimensions.fill(99, 99, 9, 0, 0, 0)).isEqualTo(100_000);
        assertThat(CheckedDimensions.volume(10_000, 100, 100)).isEqualTo(10_000);
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.fill(0, 0, 0, 100_000, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.volume(10_000, 10_001));
    }
    @Test void extremeCoordinatesAndMultiplicationNeverWrap() {
        assertThat(CheckedDimensions.span(Integer.MIN_VALUE, Integer.MAX_VALUE)).isEqualTo(4_294_967_296L);
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.fill(0, 0, 0, 65_535, 0, 65_535));
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.fill(Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.volume(10_000,
            CheckedDimensions.span(Integer.MIN_VALUE, Integer.MAX_VALUE), CheckedDimensions.span(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.volume(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }
    @Test void chunkDimensionsAndEndCoordinates() {
        assertThat(CheckedDimensions.volume(64, 64)).isEqualTo(64);
        for (int size : new int[] {0, -1, 65, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.volume(64, size));
        }
        assertThat(CheckedDimensions.end(Integer.MAX_VALUE, 1)).isEqualTo(Integer.MAX_VALUE);
        assertThatIllegalArgumentException().isThrownBy(() -> CheckedDimensions.end(Integer.MAX_VALUE, 2));
    }
}
