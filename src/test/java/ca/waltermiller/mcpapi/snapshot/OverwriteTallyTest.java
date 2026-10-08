package ca.waltermiller.mcpapi.snapshot;

import org.junit.jupiter.api.Test;

import static ca.waltermiller.mcpapi.snapshot.OverwriteTally.Category.*;
import static org.assertj.core.api.Assertions.assertThat;

class OverwriteTallyTest {
    @Test
    void classifiesReplacementsCarvingAndUnchangedBlocks() {
        var tally = new OverwriteTally();
        tally.record("minecraft:grass_block", TERRAIN, "minecraft:oak_planks", false);
        tally.record("minecraft:dirt", TERRAIN, "minecraft:air", true);
        tally.record("minecraft:oak_log", LOGS_LEAVES, "minecraft:air", true);
        tally.record("minecraft:chest", BLOCK_ENTITIES, "minecraft:stone", false);
        tally.record("minecraft:air", AIR, "minecraft:stone", false);
        tally.record("minecraft:air", AIR, "minecraft:air", true);
        tally.record("minecraft:stone", TERRAIN, "minecraft:stone", false);

        var report = tally.report();
        assertThat(report.template_blocks()).isEqualTo(7);
        assertThat(report.replaced()).isEqualTo(4);
        assertThat(report.air_carved()).isEqualTo(2);
        assertThat(report.placed_into_air()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(2);
        assertThat(report.replaced_by_category()).containsEntry("terrain", 2).containsEntry("logs_leaves", 1)
            .containsEntry("block_entities", 1).containsEntry("fluids", 0).doesNotContainKey("air");
    }

    @Test
    void topReplacedIsCappedAndOrderedByCountThenName() {
        var tally = new OverwriteTally();
        for (int i = 0; i < 12; i++) tally.record("minecraft:block_" + (char) ('a' + i), OTHER, "minecraft:stone", false);
        tally.record("minecraft:dirt", TERRAIN, "minecraft:stone", false);
        tally.record("minecraft:dirt", TERRAIN, "minecraft:stone", false);

        var top = tally.report().top_replaced();
        assertThat(top).hasSize(OverwriteTally.TOP_LIMIT);
        assertThat(top.get(0)).isEqualTo(new OverwriteTally.BlockCount("minecraft:dirt", 2));
        assertThat(top.get(1).block()).isEqualTo("minecraft:block_a");
    }

    @Test
    void airIdsIncludeCaveAndVoidAir() {
        assertThat(OverwriteTally.isAirId("minecraft:cave_air")).isTrue();
        assertThat(OverwriteTally.isAirId("minecraft:void_air")).isTrue();
        assertThat(OverwriteTally.isAirId("minecraft:glass")).isFalse();
    }
}
