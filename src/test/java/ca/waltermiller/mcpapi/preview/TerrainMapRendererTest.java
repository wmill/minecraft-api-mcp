package ca.waltermiller.mcpapi.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerrainMapRendererTest {

    private static final TerrainMapRenderer.Options BOTH = new TerrainMapRenderer.Options(null, null, true, true);

    /** A cone peaking at the centre: concentric contours, heights from base to base + radius. */
    static int[][] cone(int size, int base) {
        int[][] h = new int[size][size];
        double c = (size - 1) / 2.0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h[x][z] = base + (int) Math.round(c - Math.hypot(x - c, z - c));
            }
        }
        return h;
    }

    static String[][] fill(int sx, int sz, String id) {
        String[][] b = new String[sx][sz];
        for (String[] column : b) java.util.Arrays.fill(column, id);
        return b;
    }

    static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void imageSizeFollowsPxPerBlockAndMargins() throws Exception {
        int[][] heights = cone(40, 64);
        var result = TerrainMapRenderer.render(heights, null, 100, -20,
            new TerrainMapRenderer.Options(5, null, true, false));

        BufferedImage img = decode(result.reliefPng());
        int mapW = 40 * 5;
        assertThat(img.getWidth()).isEqualTo(Math.max(
            TerrainMapRenderer.MARGIN_LEFT + mapW + TerrainMapRenderer.MARGIN_RIGHT + 10 + TerrainMapRenderer.PANEL_WIDTH,
            TerrainMapRenderer.MIN_IMAGE_WIDTH));
        assertThat(img.getHeight()).isEqualTo(Math.max(
            TerrainMapRenderer.MARGIN_TOP + 40 * 5 + TerrainMapRenderer.MARGIN_BOTTOM,
            TerrainMapRenderer.MIN_IMAGE_HEIGHT));
        assertThat(result.surfacePng()).isNull();
    }

    @Test
    void metaReportsHeightStatsAndAutoChoices() throws Exception {
        var result = TerrainMapRenderer.render(cone(41, 60), null, 0, 0, BOTH);
        var meta = result.meta();

        assertThat(meta.minY()).isEqualTo(60 + Math.round(20 - Math.hypot(20, 20)));
        assertThat(meta.maxY()).isEqualTo(80);
        assertThat(meta.medianY()).isBetween(meta.minY(), meta.maxY());
        assertThat(meta.pxPerBlock()).isEqualTo(TerrainMapRenderer.autoPxPerBlock(41, 41));
        assertThat(meta.contourInterval()).isEqualTo(TerrainMapRenderer.autoContourInterval(meta.minY(), meta.maxY()));
        // No surface blocks: no surface image and no coverage.
        assertThat(result.surfacePng()).isNull();
        assertThat(meta.coverage()).isEmpty();
    }

    @Test
    void surfaceLayerReportsCoverageLargestFirst() throws Exception {
        int[][] heights = new int[10][10];
        String[][] blocks = fill(10, 10, "minecraft:grass_block");
        for (int z = 0; z < 10; z++) {
            blocks[0][z] = "minecraft:water";
            blocks[1][z] = "minecraft:water";
            blocks[2][z] = "minecraft:oak_planks";
        }
        var result = TerrainMapRenderer.render(heights, blocks, 0, 0, BOTH);

        assertThat(decode(result.surfacePng())).isNotNull();
        assertThat(result.meta().coverage()).containsExactly(
            java.util.Map.entry("grass/dirt", 0.7),
            java.util.Map.entry("water", 0.2),
            // Flat planks at ground level read as paving, not a building.
            java.util.Map.entry("paving", 0.1));
    }

    @Test
    void flatTerrainRendersWithoutContours() throws Exception {
        var result = TerrainMapRenderer.render(new int[30][20], fill(30, 20, "minecraft:stone"), 0, 0, BOTH);
        assertThat(decode(result.reliefPng())).isNotNull();
        assertThat(result.meta().minY()).isEqualTo(result.meta().maxY());
        assertThat(result.meta().contourInterval()).isEqualTo(1);
    }

    @Test
    void singleColumnAndNegativeCoordinatesRender() throws Exception {
        var result = TerrainMapRenderer.render(new int[][]{{-12}}, fill(1, 1, "minecraft:sand"), -31, -17, BOTH);
        assertThat(decode(result.reliefPng())).isNotNull();
        assertThat(decode(result.surfacePng())).isNotNull();
        assertThat(result.meta().pxPerBlock()).isEqualTo(TerrainMapRenderer.MAX_PX_PER_BLOCK);
    }

    @Test
    void contourPixelsAppearOnCone() throws Exception {
        var result = TerrainMapRenderer.render(cone(41, 60), null, 0, 0,
            new TerrainMapRenderer.Options(8, 2, true, false));
        BufferedImage img = decode(result.reliefPng());
        // Walk the map's middle row outward from the peak: contour edges paint near-black pixels.
        int y = TerrainMapRenderer.MARGIN_TOP + 20 * 8 + 1;
        int dark = 0;
        for (int x = TerrainMapRenderer.MARGIN_LEFT; x < TerrainMapRenderer.MARGIN_LEFT + 41 * 8; x++) {
            int rgb = img.getRGB(x, y) & 0xffffff;
            if (((rgb >> 16) & 0xff) < 50) dark++;
        }
        assertThat(dark).isGreaterThan(5);
    }

    @Test
    void rejectsRaggedHeights() {
        assertThatThrownBy(() -> TerrainMapRenderer.render(new int[][]{{1, 2}, {3}}, null, 0, 0, BOTH))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerrainMapRenderer.render(new int[2][2], new String[3][2], 0, 0, BOTH))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitBuiltSeparatesPavingFromBuildings() {
        int[][] h = new int[20][20];
        SurfaceCategory[][] c = new SurfaceCategory[20][20];
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                h[x][z] = 70;
                c[x][z] = SurfaceCategory.GRASS_DIRT;
            }
        }
        c[2][2] = SurfaceCategory.BUILT;                    // flush paving
        c[3][2] = SurfaceCategory.BUILT; h[3][2] = 71;      // slab step / low wall
        c[10][10] = SurfaceCategory.BUILT; h[10][10] = 90;  // tower roof
        c[15][15] = SurfaceCategory.TREES; h[15][15] = 80;  // canopy is not ground

        SurfaceCategory[][] out = TerrainMapRenderer.splitBuilt(h, c);

        assertThat(out[2][2]).isEqualTo(SurfaceCategory.PAVING);
        assertThat(out[3][2]).isEqualTo(SurfaceCategory.PAVING);
        assertThat(out[10][10]).isEqualTo(SurfaceCategory.BUILT);
        assertThat(out[15][15]).isEqualTo(SurfaceCategory.TREES);
        assertThat(c[2][2]).as("input is not modified").isEqualTo(SurfaceCategory.BUILT);
    }

    @Test
    void splitBuiltSearchesWiderForInteriorsOfLargeBuildings() {
        // 40x40 roof at Y 85 with a single strip of grass at Y 70 along the west edge.
        int[][] h = new int[41][40];
        SurfaceCategory[][] c = new SurfaceCategory[41][40];
        for (int x = 0; x < 41; x++) {
            for (int z = 0; z < 40; z++) {
                boolean grass = x == 0;
                h[x][z] = grass ? 70 : 85;
                c[x][z] = grass ? SurfaceCategory.GRASS_DIRT : SurfaceCategory.BUILT;
            }
        }
        SurfaceCategory[][] out = TerrainMapRenderer.splitBuilt(h, c);
        assertThat(out[30][20]).isEqualTo(SurfaceCategory.BUILT);
    }

    @Test
    void splitBuiltFallsBackToLowestColumnWhenAllBuilt() {
        int[][] h = {{70, 70}, {70, 80}};
        SurfaceCategory[][] c = {
            {SurfaceCategory.BUILT, SurfaceCategory.BUILT},
            {SurfaceCategory.BUILT, SurfaceCategory.BUILT}};
        SurfaceCategory[][] out = TerrainMapRenderer.splitBuilt(h, c);
        assertThat(out[0][0]).isEqualTo(SurfaceCategory.PAVING);
        assertThat(out[1][1]).isEqualTo(SurfaceCategory.BUILT);
    }

    @Test
    void keyTicksKeepEndsAndDropCrowdedLevels() {
        // The harbour case: 75 sat 14 px above 70 and collided with it.
        assertThat(TerrainMapRenderer.keyTicks(70, 124, 5)).containsExactly(124, 100, 70);
        assertThat(TerrainMapRenderer.keyTicks(64, 64, 1)).containsExactly(64);
        for (int min = 50; min < 80; min++) {
            List<Integer> ticks = TerrainMapRenderer.keyTicks(min, min + 37, 1);
            assertThat(ticks).startsWith(min + 37).endsWith(min);
            for (int i = 1; i < ticks.size(); i++) {
                double gap = (ticks.get(i - 1) - ticks.get(i)) * (TerrainMapRenderer.KEY_BAR_HEIGHT - 1) / 37.0;
                assertThat(gap).isGreaterThanOrEqualTo(TerrainMapRenderer.MIN_KEY_TICK_GAP - 1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "64, 64, 1",
        "60, 75, 1",
        "60, 76, 2",
        "60, 90, 2",
        "60, 100, 5",
        "0, 200, 20",
        "-64, 320, 50",
    })
    void autoContourIntervalKeepsLineCountReadable(int minY, int maxY, int expected) {
        assertThat(TerrainMapRenderer.autoContourInterval(minY, maxY)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"100, 100, 7", "10, 10, 16", "10000, 1, 1", "200, 50, 3"})
    void autoPxPerBlockFitsTargetWidth(int sx, int sz, int expected) {
        assertThat(TerrainMapRenderer.autoPxPerBlock(sx, sz)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"16, 4", "7, 16", "4, 16", "1, 64"})
    void gridStepKeepsLinesApart(int px, int expected) {
        assertThat(TerrainMapRenderer.gridStep(px)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "70, 71, 1, 71",
        "71, 70, 1, 71",
        "70, 74, 5, ",
        "70, 76, 5, 75",
        "-3, 1, 5, 0",
        "-7, -4, 5, -5",
        "70, 70, 1, ",
    })
    void crossedLevelFindsHighestMultipleInHalfOpenRange(int a, int b, int step, Integer expected) {
        assertThat(TerrainMapRenderer.crossedLevel(a, b, step)).isEqualTo(expected);
    }

    @Test
    void hillshadeIsNeutralOnFlatGroundAndBrighterFacingNorthWest() {
        double[][] flat = TerrainMapRenderer.hillshade(new int[3][3]);
        assertThat(flat[1][1]).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

        // Ground rising toward +X faces west, towards the light.
        int[][] risingEast = {{0, 0, 0}, {1, 1, 1}, {2, 2, 2}};
        int[][] risingWest = {{2, 2, 2}, {1, 1, 1}, {0, 0, 0}};
        assertThat(TerrainMapRenderer.hillshade(risingEast)[1][1]).isGreaterThan(1.0);
        assertThat(TerrainMapRenderer.hillshade(risingWest)[1][1]).isLessThan(1.0);
    }

    @Test
    void sharedRangeMakesBeforeAndAfterUseTheSameContourInterval() throws Exception {
        int[][] gentle = cone(20, 64);
        var alone = TerrainMapRenderer.render(gentle, null, 0, 0, new TerrainMapRenderer.Options(4, null, true, false));
        var shared = TerrainMapRenderer.render(gentle, null, 0, 0,
            new TerrainMapRenderer.Options(4, null, true, false, 40, 120, "After"));
        assertThat(alone.meta().contourInterval()).isEqualTo(1);
        assertThat(shared.meta().contourInterval()).isEqualTo(10);
        assertThat(shared.meta().minY()).isEqualTo(alone.meta().minY());
    }

    @Test
    void deltaMapColoursCutRedFillBlueAndMaskedGrey() throws Exception {
        int[][] delta = new int[12][12];
        boolean[][] masked = new boolean[12][12];
        delta[1][1] = -4;
        delta[10][10] = 3;
        masked[5][5] = true;
        var result = TerrainMapRenderer.renderDelta(delta, masked, 200, 300, 8);
        assertThat(result.maxLowered()).isEqualTo(4);
        assertThat(result.maxRaised()).isEqualTo(3);

        BufferedImage img = decode(result.png());
        java.util.function.BiFunction<Integer, Integer, Integer> at = (x, z) -> img.getRGB(
            TerrainMapRenderer.MARGIN_LEFT + x * 8 + 1, TerrainMapRenderer.MARGIN_TOP + z * 8 + 1) & 0xFFFFFF;
        int cut = at.apply(1, 1), fill = at.apply(10, 10), kept = at.apply(5, 5);
        assertThat((cut >> 16) & 0xFF).isGreaterThan(cut & 0xFF);
        assertThat(fill & 0xFF).isGreaterThan((fill >> 16) & 0xFF);
        assertThat(kept).isEqualTo(0x9E9E9E);
        assertThat(TerrainMapRenderer.deltaRgb(0, 4)).isEqualTo(0xECECEA);
        assertThat(TerrainMapRenderer.deltaRgb(-1, 10)).isNotEqualTo(TerrainMapRenderer.deltaRgb(0, 10));
    }

    @Test
    void deltaMapRejectsRaggedInput() {
        assertThatThrownBy(() -> TerrainMapRenderer.renderDelta(new int[][] {{1, 2}, {3}}, null, 0, 0, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
