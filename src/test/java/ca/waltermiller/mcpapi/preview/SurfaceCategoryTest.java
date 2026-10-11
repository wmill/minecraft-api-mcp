package ca.waltermiller.mcpapi.preview;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SurfaceCategoryTest {

    @ParameterizedTest
    @CsvSource({
        "minecraft:water, WATER",
        "minecraft:kelp_plant, WATER",
        "minecraft:tall_seagrass, WATER",
        "minecraft:brain_coral_block, WATER",
        "minecraft:lava, LAVA",
        "minecraft:snow, SNOW_ICE",
        "minecraft:packed_ice, SNOW_ICE",
        "minecraft:sand, SAND_GRAVEL",
        "minecraft:gravel, SAND_GRAVEL",
        "minecraft:grass_block, GRASS_DIRT",
        "minecraft:short_grass, GRASS_DIRT",
        "minecraft:red_tulip, GRASS_DIRT",
        "minecraft:oak_sapling, GRASS_DIRT",
        "minecraft:oak_leaves, TREES",
        "minecraft:spruce_log, TREES",
        "minecraft:crimson_stem, TREES",
        "minecraft:stone, STONE",
        "minecraft:deepslate_iron_ore, STONE",
        "minecraft:orange_terracotta, STONE",
        "minecraft:wheat, FARM",
        "minecraft:farmland, FARM",
        "minecraft:pumpkin_stem, FARM",
        "minecraft:dirt_path, PATH",
        "minecraft:oak_planks, BUILT",
        "minecraft:stripped_oak_log, BUILT",
        "minecraft:orange_glazed_terracotta, BUILT",
        "minecraft:cobblestone, BUILT",
        "minecraft:glass_pane, BUILT",
        "minecraft:air, NONE",
        "minecraft:void_air, NONE",
        "stone, STONE",
    })
    void classifiesBlockIds(String blockId, SurfaceCategory expected) {
        assertThat(SurfaceCategory.classify(blockId)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"''", "minecraft:"})
    void emptyIdsAreNone(String blockId) {
        assertThat(SurfaceCategory.classify(blockId)).isEqualTo(SurfaceCategory.NONE);
        assertThat(SurfaceCategory.classify(null)).isEqualTo(SurfaceCategory.NONE);
    }
}
