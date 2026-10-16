package ca.waltermiller.mcpapi.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class TerrainSmootherTest {
    private static int[][] flat(int sx, int sz, int y) {
        int[][] h = new int[sx][sz];
        for (int[] column : h) Arrays.fill(column, y);
        return h;
    }

    private static boolean[][] none(int sx, int sz) {
        return new boolean[sx][sz];
    }

    private static TerrainSmoother.Options filter(HeightFilter f, int feather) {
        return new TerrainSmoother.Options(new TerrainSmoother.Filter(f, 1), 1.0, feather, 16);
    }

    @Test void gaussianSpreadsSpikeIntoNeighbours() {
        int[][] h = flat(21, 21, 64);
        h[10][10] = 76;
        var plan = TerrainSmoother.plan(h, none(21, 21), 0, filter(new HeightFilter.Gaussian(1.5), 0));
        assertThat(plan.heights()[10][10]).isBetween(64, 67);
        assertThat(plan.heights()[10][11]).isEqualTo(65);
        assertThat(plan.heights()[0][0]).isEqualTo(64);
        assertThat(plan.stats().cutBlocks()).isPositive();
        assertThat(plan.stats().fillBlocks()).isPositive();
    }

    @Test void medianRemovesPillarAndKeepsStraightCliff() {
        int[][] h = new int[20][20];
        for (int x = 0; x < 20; x++) Arrays.fill(h[x], x < 10 ? 64 : 72);
        h[4][4] = 80;
        var plan = TerrainSmoother.plan(h, none(20, 20), 0, filter(new HeightFilter.Median(1), 0));
        assertThat(plan.heights()[4][4]).isEqualTo(64);
        for (int z = 1; z < 19; z++) {
            assertThat(plan.heights()[9][z]).isEqualTo(64);
            assertThat(plan.heights()[10][z]).isEqualTo(72);
        }
    }

    @Test void bilateralKeepsLargeStepAndFlattensNoise() {
        int[][] h = new int[24][24];
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) h[x][z] = (x < 12 ? 64 : 70) + ((x * 7 + z * 3) % 5 == 0 ? 1 : 0);
        }
        var plan = TerrainSmoother.plan(h, none(24, 24), 0,
            filter(new HeightFilter.Bilateral(2.0, 1.5), 0));
        for (int z = 3; z < 21; z++) {
            assertThat(plan.heights()[6][z]).isEqualTo(64);
            assertThat(plan.heights()[17][z]).isEqualTo(70);
            assertThat(plan.heights()[12][z] - plan.heights()[11][z]).as("step at z=%d", z).isGreaterThanOrEqualTo(5);
        }
    }

    @Test void padHitsTargetInFootprintAndLeavesFarGroundAlone() {
        int[][] h = new int[30][30];
        for (int x = 0; x < 30; x++) for (int z = 0; z < 30; z++) h[x][z] = 60 + x / 3;
        var pad = new TerrainSmoother.Pad(66, 10, 10, 19, 19, 4);
        var plan = TerrainSmoother.plan(h, none(30, 30), 0, new TerrainSmoother.Options(pad, 1.0, 0, 16));
        for (int x = 10; x <= 19; x++) for (int z = 10; z <= 19; z++) assertThat(plan.heights()[x][z]).isEqualTo(66);
        assertThat(plan.stats().padColumns()).isEqualTo(100);
        assertThat(plan.stats().padColumnsAtTarget()).isEqualTo(100);
        for (int z = 0; z < 30; z++) {
            assertThat(plan.heights()[0][z]).isEqualTo(h[0][z]);
            assertThat(plan.heights()[29][z]).isEqualTo(h[29][z]);
        }
        // Blend zone sits between the pad and the original ground.
        assertThat(plan.heights()[22][15]).isBetween(66, h[22][15]);
    }

    @Test void maskedColumnsAndTheirNeighboursNeverChange() {
        int[][] h = flat(15, 15, 64);
        for (int x = 0; x < 15; x++) for (int z = 0; z < 15; z++) h[x][z] += (x * 7 + z * 3) % 5;
        boolean[][] masked = none(15, 15);
        masked[7][7] = true;
        h[7][7] = 90;
        var plan = TerrainSmoother.plan(h, masked, 0, filter(new HeightFilter.Gaussian(2.0), 0));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) assertThat(plan.heights()[7 + dx][7 + dz]).isEqualTo(h[7 + dx][7 + dz]);
        }
        assertThat(plan.stats().maskedColumns()).isEqualTo(1);
        assertThat(plan.stats().protectedColumns()).isEqualTo(8);
        // The roof did not drag the ground up two columns away.
        assertThat(plan.heights()[9][7]).isLessThan(70);
    }

    @Test void featherKeepsEdgeColumnsNearlyUnchanged() {
        int[][] h = new int[30][30];
        for (int x = 0; x < 30; x++) for (int z = 0; z < 30; z++) h[x][z] = 64 + ((x / 2 + z / 2) % 2) * 4;
        var plan = TerrainSmoother.plan(h, none(30, 30), 0, filter(new HeightFilter.Gaussian(2.0), 6));
        int edgeChanges = 0;
        for (int i = 0; i < 30; i++) {
            edgeChanges += Math.abs(plan.heights()[0][i] - h[0][i]) + Math.abs(plan.heights()[i][0] - h[i][0]);
        }
        assertThat(edgeChanges).isZero();
        assertThat(plan.stats().changedColumns()).isPositive();
    }

    @Test void marginColumnsAreReadButNotPlanned() {
        int[][] h = flat(14, 14, 64);
        for (int z = 0; z < 14; z++) h[0][z] = 80; // a ridge just outside the region
        var plan = TerrainSmoother.plan(h, none(14, 14), 2, filter(new HeightFilter.Gaussian(0.7), 0));
        assertThat(plan.heights()).hasDimensions(10, 10);
        assertThat(plan.heights()[0][5]).isEqualTo(64);
    }

    @Test void unknownContextIsSkippedWithoutProtectingNeighbours() {
        int[][] h = flat(12, 12, 64);
        h[6][6] = 70;
        boolean[][] unknown = none(12, 12);
        for (int z = 0; z < 12; z++) unknown[0][z] = true;
        var plan = TerrainSmoother.plan(h, unknown, none(12, 12), 1, filter(new HeightFilter.Median(1), 0));
        assertThat(plan.stats().protectedColumns()).isZero();
        assertThat(plan.stats().maskedColumns()).isZero();
        assertThat(plan.heights()[5][5]).isEqualTo(64);
    }

    @ParameterizedTest @ValueSource(ints = {1, 3, 5})
    void maxChangeClamps(int limit) {
        int[][] h = flat(30, 30, 64);
        var pad = new TerrainSmoother.Pad(50, 5, 5, 24, 24, 3);
        var plan = TerrainSmoother.plan(h, none(30, 30), 0, new TerrainSmoother.Options(pad, 1.0, 0, limit));
        assertThat(plan.heights()[15][15]).isEqualTo(64 - limit);
        assertThat(plan.stats().clampedColumns()).isPositive();
        assertThat(plan.stats().maxLowered()).isEqualTo(limit);
        assertThat(plan.stats().padColumnsAtTarget()).isZero();
    }

    @Test void strengthScalesTheChange() {
        int[][] h = flat(30, 30, 64);
        var pad = new TerrainSmoother.Pad(64, 0, 0, -1, -1, 0); // empty footprint: target = ground
        var full = TerrainSmoother.plan(h, none(30, 30), 0, new TerrainSmoother.Options(pad, 0.5, 0, 16));
        assertThat(full.stats().changedColumns()).isZero();

        h[15][15] = 74;
        var half = TerrainSmoother.plan(h, none(30, 30), 0,
            new TerrainSmoother.Options(new TerrainSmoother.Filter(new HeightFilter.Median(1), 1), 0.5, 0, 16));
        assertThat(half.heights()[15][15]).isEqualTo(69);
    }

    @Test void edgeWeightEasesInOverFeather() {
        assertThat(TerrainSmoother.edgeWeight(0, 10, 21, 21, 0)).isEqualTo(1.0);
        assertThat(TerrainSmoother.edgeWeight(0, 10, 21, 21, 4)).isLessThan(0.2);
        assertThat(TerrainSmoother.edgeWeight(4, 10, 21, 21, 4)).isEqualTo(1.0);
        assertThat(TerrainSmoother.margin(new TerrainSmoother.Filter(new HeightFilter.Gaussian(2.0), 1))).isEqualTo(6);
        assertThat(TerrainSmoother.margin(new TerrainSmoother.Filter(new HeightFilter.Gaussian(5.0), 1))).isEqualTo(15);
        assertThat(TerrainSmoother.margin(new TerrainSmoother.Pad(64, 0, 0, 1, 1, 8))).isZero();
    }
}
