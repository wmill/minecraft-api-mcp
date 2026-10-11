package ca.waltermiller.mcpapi.preview;

import java.util.Set;

/**
 * Coarse material classes for the top-down surface map. Colours are deliberately few
 * and far apart so a reader can tell categories apart at a glance; anything that is
 * not recognisably natural terrain counts as {@link #BUILT}. {@link #PAVING} is never
 * returned by {@link #classify}: the terrain map assigns it to built columns that sit
 * at the level of the surrounding natural ground (plazas, roads, low walls).
 */
public enum SurfaceCategory {
    WATER("water", 0x1F6FE0),
    LAVA("lava", 0xFF4500),
    SNOW_ICE("snow/ice", 0xE8F4FF),
    SAND_GRAVEL("sand/gravel", 0xEAD48A),
    GRASS_DIRT("grass/dirt", 0x7BC74D),
    TREES("trees", 0x1B5E20),
    STONE("stone", 0x8A8A8A),
    FARM("farm", 0xA0642D),
    PATH("path", 0x4E342E),
    BUILT("building", 0xD81B60),
    PAVING("paving", 0xF4A6C6),
    NONE("void", 0x202020);

    private static final Set<String> WATER_IDS = Set.of(
        "water", "bubble_column", "lily_pad", "sea_pickle");
    private static final Set<String> LAVA_IDS = Set.of("lava", "magma_block");
    private static final Set<String> SNOW_IDS = Set.of(
        "snow", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice", "frosted_ice");
    private static final Set<String> SAND_IDS = Set.of(
        "sand", "red_sand", "gravel", "clay", "suspicious_sand", "suspicious_gravel",
        "sandstone", "red_sandstone");
    private static final Set<String> FARM_IDS = Set.of(
        "farmland", "wheat", "carrots", "potatoes", "beetroots", "pumpkin", "melon",
        "pumpkin_stem", "melon_stem", "attached_pumpkin_stem", "attached_melon_stem",
        "torchflower_crop", "pitcher_crop", "hay_block");
    private static final Set<String> TREE_IDS = Set.of(
        "bamboo", "vine", "mushroom_stem", "brown_mushroom_block", "red_mushroom_block",
        "nether_wart_block", "warped_wart_block", "shroomlight", "mangrove_roots",
        "muddy_mangrove_roots", "cocoa", "bee_nest");
    private static final Set<String> GRASS_IDS = Set.of(
        "grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "mud",
        "moss_block", "moss_carpet", "pale_moss_block", "pale_moss_carpet",
        "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "bush", "firefly_bush",
        "short_dry_grass", "tall_dry_grass", "leaf_litter", "wildflowers", "pink_petals",
        "sweet_berry_bush", "cactus", "cactus_flower", "sugar_cane",
        "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
        "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush",
        "peony", "torchflower", "pitcher_plant", "open_eyeblossom", "closed_eyeblossom",
        "azalea", "flowering_azalea", "glow_lichen", "hanging_roots", "big_dripleaf",
        "small_dripleaf", "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
        "crimson_roots", "warped_roots", "nether_sprouts", "crimson_nylium", "warped_nylium");
    private static final Set<String> STONE_IDS = Set.of(
        "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite",
        "dripstone_block", "pointed_dripstone", "bedrock", "obsidian", "netherrack", "basalt",
        "smooth_basalt", "blackstone", "soul_sand", "soul_soil", "end_stone", "terracotta",
        "amethyst_block", "budding_amethyst", "packed_mud");

    private final String label;
    private final int rgb;

    SurfaceCategory(String label, int rgb) {
        this.label = label;
        this.rgb = rgb;
    }

    public String label() { return label; }
    public int rgb() { return rgb; }

    public static SurfaceCategory classify(String blockId) {
        if (blockId == null) return NONE;
        int colon = blockId.indexOf(':');
        String n = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        if (n.isEmpty() || n.endsWith("air")) return NONE;

        if (WATER_IDS.contains(n) || n.contains("kelp") || n.contains("seagrass") || n.contains("coral")) {
            return WATER;
        }
        if (LAVA_IDS.contains(n)) return LAVA;
        if (SNOW_IDS.contains(n)) return SNOW_ICE;
        if (SAND_IDS.contains(n)) return SAND_GRAVEL;
        if (FARM_IDS.contains(n)) return FARM;
        if (n.equals("dirt_path")) return PATH;
        // Stripped logs and wood are almost always placed by players.
        if (n.startsWith("stripped_")) return BUILT;
        if (TREE_IDS.contains(n) || n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("_wood")
                || n.endsWith("_stem") || n.endsWith("_hyphae") || n.endsWith("_propagule")) {
            return TREES;
        }
        if (GRASS_IDS.contains(n) || n.endsWith("_tulip") || n.endsWith("_sapling")) return GRASS_DIRT;
        if (STONE_IDS.contains(n) || n.endsWith("_ore")
                || (n.endsWith("_terracotta") && !n.contains("glazed"))) {
            return STONE;
        }
        return BUILT;
    }
}
