package ca.waltermiller.mcpapi.survey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SiteSurveyTest {
    private static final SiteSurvey.Cell AIR = new SiteSurvey.Cell(true, false, false, false, false, false);
    private static final SiteSurvey.Cell SOLID = new SiteSurvey.Cell(false, false, false, false, true, true);
    private static final SiteSurvey.Cell TREE = new SiteSurvey.Cell(false, true, false, false, true, true);

    static class Terrain implements SiteSurvey.Terrain {
        int reads;
        public int bottomY() { return -64; }
        public int topYInclusive() { return 319; }
        public boolean ceiling() { return false; }
        public boolean chunkLoaded(int x, int z) { return true; }
        public int surfaceY(int x, int z) { return 64; }
        public SiteSurvey.Cell cell(int x, int y, int z) { reads++; return SOLID; }
    }

    @Test void flatFootprintAndSingleColumn() {
        var terrain = new Terrain();
        var flat = SiteSurvey.survey("world", SiteSurvey.Footprint.of(0, 0, 39, 39), terrain);
        assertThat(flat.ground().resolved_columns()).isEqualTo(1600);
        assertThat(flat.grading().suggested_walking_y()).isEqualTo(64);
        assertThat(flat.grading().cut_blocks()).isZero();
        assertThat(flat.grading().fill_blocks()).isZero();
        assertThat(flat.slope().adjacent_pairs()).isEqualTo(3120);
        assertThat(flat.slope().max_step()).isZero();
        var single = SiteSurvey.survey("world", SiteSurvey.Footprint.of(-5, -7, -5, -7), terrain);
        assertThat(single.slope()).isEqualTo(new SiteSurvey.Slope(0, 0, 0));
    }

    @Test void lowerMedianCutFillAndCliffAdjacency() {
        var terrain = new Terrain() {
            public int surfaceY(int x, int z) { return x == 0 ? 60 : 70; }
        };
        var result = SiteSurvey.survey("world", SiteSurvey.Footprint.of(1, 1, 0, 0), terrain);
        assertThat(result.ground().median_y()).isEqualTo(60);
        assertThat(result.ground().range()).isEqualTo(10);
        assertThat(result.grading().suggested_walking_y()).isEqualTo(60);
        assertThat(result.grading().cut_blocks()).isEqualTo(20);
        assertThat(result.slope()).isEqualTo(new SiteSurvey.Slope(4, 5.0, 10));
    }

    @Test void skipsLeavesLogsAndPlantsButCountsWaterloggedVegetation() {
        var terrain = new Terrain() {
            public SiteSurvey.Cell cell(int x, int y, int z) {
                return y > 60 ? (y == 63 ? new SiteSurvey.Cell(false, true, true, false, false, false) : TREE) : SOLID;
            }
        };
        var result = SiteSurvey.survey("world", SiteSurvey.Footprint.of(0, 0, 0, 0), terrain);
        assertThat(result.ground().median_y()).isEqualTo(61);
        assertThat(result.vegetation().fraction()).isEqualTo(1);
        assertThat(result.water().fraction()).isEqualTo(1);
        assertThat(result.grading().unavailable_reason()).isEqualTo("no_dry_ground");
    }

    @Test void shorelineUsesDryMedianButIncludesSubmergedFill() {
        var terrain = new Terrain() {
            public SiteSurvey.Cell cell(int x, int y, int z) {
                return x == 1 && y > 60 ? new SiteSurvey.Cell(false, false, true, false, false, false) : SOLID;
            }
        };
        var result = SiteSurvey.survey("world", SiteSurvey.Footprint.of(0, 0, 1, 0), terrain);
        assertThat(result.grading().suggested_walking_y()).isEqualTo(64);
        assertThat(result.grading().fill_blocks()).isEqualTo(3);
        assertThat(result.water().fraction()).isEqualTo(.5);
    }

    @Test void lavaSuppressesRecommendation() {
        var terrain = new Terrain() {
            public SiteSurvey.Cell cell(int x, int y, int z) {
                return y == 63 ? new SiteSurvey.Cell(false, false, false, true, false, false) : SOLID;
            }
        };
        var result = SiteSurvey.survey("world", SiteSurvey.Footprint.of(0, 0, 0, 0), terrain);
        assertThat(result.grading().suggested_walking_y()).isNull();
        assertThat(result.grading().unavailable_reason()).isEqualTo("lava_present");
    }

    @Test void unsupportedVoidAndScanLimitAreUnresolvedWithoutBridgingSlope() {
        var terrain = new Terrain() {
            public int surfaceY(int x, int z) { return x == 1 ? -64 : 64; }
            public SiteSurvey.Cell cell(int x, int y, int z) {
                reads++;
                return x == 0 ? new SiteSurvey.Cell(false, false, false, false, true, false) : AIR;
            }
        };
        var result = SiteSurvey.survey("world", SiteSurvey.Footprint.of(0, 0, 2, 0), terrain);
        assertThat(result.ground().unresolved_columns()).isEqualTo(3);
        assertThat(result.ground().unresolved_reasons()).containsEntry("void", 1)
            .containsEntry("unsupported_surface", 1).containsEntry("scan_limit", 1);
        assertThat(terrain.reads).isEqualTo(65);
        assertThat(result.slope().adjacent_pairs()).isZero();
        assertThat(result.grading().unavailable_reason()).isEqualTo("unresolved_ground");
    }

    @Test void scanIncludesThe64thBlock() {
        var terrain = new Terrain() {
            public SiteSurvey.Cell cell(int x, int y, int z) { return y == 0 ? SOLID : AIR; }
        };
        assertThat(SiteSurvey.sample(terrain, 0, 0).walking_y()).isEqualTo(1);
    }

    @Test void preflightRejectsMissingNegativeChunksBeforeAnySampling() {
        var terrain = new Terrain() {
            public boolean chunkLoaded(int x, int z) { return x != -1; }
            public int surfaceY(int x, int z) { throw new AssertionError("Must not sample unloaded footprint"); }
        };
        var bounds = SiteSurvey.Footprint.of(-1, 0, 16, 0);
        assertThatThrownBy(() -> SiteSurvey.survey("world", bounds, terrain))
            .isInstanceOfSatisfying(SiteSurvey.SurveyException.class, e -> {
                assertThat(e.payload().get("code")).isEqualTo("unloaded_chunks");
                assertThat(e.payload().get("missing_chunks")).isEqualTo(java.util.List.of(java.util.Map.of("x", -1, "z", 0)));
            });
        assertThat(terrain.reads).isZero();
    }

    @Test void rejectsCeilingAndOverflowingOrOversizedFootprints() {
        assertThatThrownBy(() -> SiteSurvey.survey("world", SiteSurvey.Footprint.of(0, 0, 0, 0), new Terrain() {
            public boolean ceiling() { return true; }
        })).isInstanceOf(SiteSurvey.SurveyException.class).hasMessageContaining("ceiling");
        assertThatThrownBy(() -> SiteSurvey.Footprint.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteSurvey.Footprint.of(0, 0, 100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SiteSurvey.Footprint.of(-99, -99, 0, 0).width()).isEqualTo(100);
    }
}
