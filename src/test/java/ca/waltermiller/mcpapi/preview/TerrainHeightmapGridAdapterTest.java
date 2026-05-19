package ca.waltermiller.mcpapi.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerrainHeightmapGridAdapterTest {

    @Test
    void rectangularAreaMapsToExpectedGridFootprint() {
        int[][] heights = new int[][] {
            {64, 65, 66},
            {67, 68, 69}
        };

        BlockGrid grid = TerrainHeightmapGridAdapter.fromHeights(heights);

        assertThat(grid.width()).isEqualTo(2);
        assertThat(grid.depth()).isEqualTo(3);
        assertThat(grid.minX()).isZero();
        assertThat(grid.minZ()).isZero();
        assertThat(grid.blockAt(0, 64, 0)).isEqualTo("minecraft:grass_block");
        assertThat(grid.blockAt(1, 69, 2)).isEqualTo("minecraft:grass_block");
    }

    @Test
    void differingHeightsProduceDistinctVerticalPositions() {
        int[][] heights = new int[][] {
            {62, 70}
        };

        BlockGrid grid = TerrainHeightmapGridAdapter.fromHeights(heights);

        assertThat(grid.minY()).isEqualTo(62);
        assertThat(grid.height()).isEqualTo(9);
        assertThat(grid.blockAt(0, 62, 0)).isEqualTo("minecraft:grass_block");
        assertThat(grid.blockAt(0, 70, 1)).isEqualTo("minecraft:grass_block");
        assertThat(grid.blockAt(0, 63, 0)).isNull();
    }

    @Test
    void viewDirectionRotationYieldsDifferentRenderedImages() throws Exception {
        int[][] heights = new int[][] {
            {64, 64, 70},
            {68, 63, 63}
        };

        BlockGrid grid = TerrainHeightmapGridAdapter.fromHeights(heights);

        byte[] south = IsoRenderer.renderPng(grid, 4, PreviewViewDirection.SOUTH);
        byte[] east = IsoRenderer.renderPng(grid, 4, PreviewViewDirection.EAST);

        assertThat(south).isNotEqualTo(east);
    }
}
