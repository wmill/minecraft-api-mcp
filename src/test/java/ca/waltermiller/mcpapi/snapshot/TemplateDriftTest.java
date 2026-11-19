package ca.waltermiller.mcpapi.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDriftTest {
    @Test
    void untouchedPlacementMatchesFully() {
        TemplateDrift drift = new TemplateDrift();
        drift.record(0, 0, 0, "minecraft:stone", "minecraft:stone");
        drift.record(0, 1, 0, "minecraft:air", "minecraft:cave_air");
        TemplateDrift.Report report = drift.report();
        assertThat(report.match_pct()).isEqualTo(100.0);
        assertThat(report.solid_template_blocks()).isEqualTo(1);
        assertThat(report.samples()).isEmpty();
    }

    @Test
    void classifiesMissingExtraAndChangedBlocks() {
        TemplateDrift drift = new TemplateDrift();
        drift.record(0, 0, 0, "minecraft:stone", "minecraft:stone");
        drift.record(1, 0, 0, "minecraft:stone", "minecraft:air");
        drift.record(2, 0, 0, "minecraft:air", "minecraft:dirt");
        drift.record(3, 0, 0, "minecraft:oak_planks", "minecraft:spruce_planks");
        drift.record(4, 0, 0, "minecraft:stone", "minecraft:air");

        TemplateDrift.Report report = drift.report();
        assertThat(report.template_blocks()).isEqualTo(5);
        assertThat(report.matching()).isEqualTo(1);
        assertThat(report.missing()).isEqualTo(2);
        assertThat(report.extra()).isEqualTo(1);
        assertThat(report.changed()).isEqualTo(1);
        assertThat(report.match_pct()).isEqualTo(20.0);
        assertThat(report.solid_template_blocks()).isEqualTo(4);
        assertThat(report.solid_match_pct()).isEqualTo(25.0);
        assertThat(report.top_differences().get(0))
            .isEqualTo(new TemplateDrift.Difference("minecraft:stone", "minecraft:air", 2));
        assertThat(report.samples()).hasSize(4)
            .first().isEqualTo(new TemplateDrift.Sample(1, 0, 0, "minecraft:stone", "minecraft:air"));
    }

    @Test
    void samplesAreCapped() {
        TemplateDrift drift = new TemplateDrift();
        for (int i = 0; i < TemplateDrift.SAMPLE_LIMIT + 5; i++) drift.record(i, 0, 0, "minecraft:stone", "minecraft:air");
        assertThat(drift.report().samples()).hasSize(TemplateDrift.SAMPLE_LIMIT);
        assertThat(drift.report().missing()).isEqualTo(TemplateDrift.SAMPLE_LIMIT + 5);
    }
}
