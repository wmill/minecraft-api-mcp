package ca.waltermiller.mcpapi.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaletteTest {

    @Test
    void namespacedLookupStripsMinecraftPrefix() {
        assertThat(Palette.colorFor("minecraft:stone"))
                .isEqualTo(Palette.colorFor("stone"));
    }

    @Test
    void stairsFallBackToMaterial() {
        int[] oakStairs = Palette.colorFor("minecraft:oak_stairs");
        int[] oakPlanks = Palette.colorFor("minecraft:oak_planks");
        assertThat(oakStairs).isEqualTo(oakPlanks);
    }

    @Test
    void wallsFallBackToMaterial() {
        assertThat(Palette.colorFor("minecraft:cobblestone_wall"))
                .isEqualTo(Palette.colorFor("minecraft:cobblestone"));
    }

    @Test
    void coloredCarpetFallsBackToMatchingWool() {
        assertThat(Palette.colorFor("minecraft:red_carpet"))
                .isEqualTo(Palette.colorFor("minecraft:red_wool"));
    }

    @Test
    void parentRewriteChainResolvesRedstoneWallTorch() {
        int[] direct = Palette.colorFor("minecraft:redstone_block");
        int[] viaChain = Palette.colorFor("minecraft:redstone_wall_torch");
        assertThat(viaChain).isEqualTo(direct);
    }

    @Test
    void unknownBlockProducesStableHashedColor() {
        int[] first = Palette.colorFor("modid:never_heard_of_it");
        int[] second = Palette.colorFor("modid:never_heard_of_it");
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(3);
        for (int channel : first) {
            assertThat(channel).isBetween(0, 255);
        }
    }

    @Test
    void strippedLogNormalizesToLog() {
        assertThat(Palette.normalize("minecraft:stripped_oak_log")).isEqualTo("oak_log");
    }

    @Test
    void woodRewritesToLog() {
        assertThat(Palette.normalize("minecraft:oak_wood")).isEqualTo("oak_log");
    }
}
