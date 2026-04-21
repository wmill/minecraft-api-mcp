package ca.waltermiller.mcpapi.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ported from tools/nbt-image-gen/src/nbt_image_gen/palette.py. Maps a Minecraft
 * block identifier to a flat RGB color via a lookup chain: direct colors.json hit,
 * strip minecraft: prefix and known prefixes, chase parent rewrites, color-prefix
 * fallbacks, then suffix strip. Unknown blocks get a deterministic MD5-derived HSL.
 */
public final class Palette {

    private static final String COLORS_RESOURCE = "/preview/colors.json";

    private static final String[] STRIP_SUFFIXES = {
            "_stairs", "_slab", "_fence_gate", "_fence", "_pressure_plate",
            "_button", "_trapdoor", "_wall_sign", "_sign", "_wall_banner", "_banner", "_wall",
            "_pane",
    };

    private static final String[][] SUFFIX_REWRITES = {
            {"_wood", "_log"},
            {"_hyphae", "_stem"},
    };

    private static final String[] STRIP_PREFIXES = {"stripped_", "potted_"};

    private static final String[] COLORS_16 = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    };

    private static final Set<String> COLOR_WOOL_PATTERNS = Set.of(
            "bed", "carpet", "banner", "wall_banner", "shulker_box", "glazed_terracotta", "candle");

    private static final Set<String> COLOR_CONCRETE_PATTERNS = Set.of("concrete_powder");

    private static final Map<String, String> PARENT_REWRITES = parentRewrites();

    private static final Map<String, int[]> COLORS = loadColors();

    private Palette() {}

    public static int[] colorFor(String name) {
        String raw = stripNamespace(name);
        int[] direct = COLORS.get(raw);
        if (direct != null) return direct;

        String normalized = normalize(name);
        int[] hit = COLORS.get(normalized);
        if (hit != null) return hit;

        int[] planks = COLORS.get(normalized + "_planks");
        if (planks != null) return planks;

        if (normalized.endsWith("_log")) {
            int[] logPlanks = COLORS.get(normalized.substring(0, normalized.length() - 4) + "_planks");
            if (logPlanks != null) return logPlanks;
        }

        return hashedColor(name);
    }

    static String normalize(String name) {
        String n = stripNamespace(name);
        for (String prefix : STRIP_PREFIXES) {
            if (n.startsWith(prefix)) {
                n = n.substring(prefix.length());
                break;
            }
        }
        for (int i = 0; i < 4; i++) {
            String rewritten = PARENT_REWRITES.get(n);
            if (rewritten == null) break;
            n = rewritten;
        }
        String colorMatch = colorPrefixFallback(n);
        if (colorMatch != null) return colorMatch;

        for (String[] pair : SUFFIX_REWRITES) {
            String suffix = pair[0];
            if (n.endsWith(suffix)) {
                return n.substring(0, n.length() - suffix.length()) + pair[1];
            }
        }
        for (String suffix : STRIP_SUFFIXES) {
            if (n.endsWith(suffix)) {
                String stripped = n.substring(0, n.length() - suffix.length());
                return PARENT_REWRITES.getOrDefault(stripped, stripped);
            }
        }
        return n;
    }

    private static String colorPrefixFallback(String name) {
        for (String color : COLORS_16) {
            String prefix = color + "_";
            if (!name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            if (COLOR_WOOL_PATTERNS.contains(rest)) return color + "_wool";
            if (COLOR_CONCRETE_PATTERNS.contains(rest)) return color + "_concrete";
        }
        return null;
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static int[] hashedColor(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(name.getBytes());
            double hue = (digest[0] & 0xff) / 255.0;
            return hlsToRgb(hue, 0.55, 0.45);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static int[] hlsToRgb(double h, double l, double s) {
        double m2 = l <= 0.5 ? l * (1 + s) : l + s - l * s;
        double m1 = 2 * l - m2;
        double r = hueToRgb(m1, m2, h + 1.0 / 3.0);
        double g = hueToRgb(m1, m2, h);
        double b = hueToRgb(m1, m2, h - 1.0 / 3.0);
        return new int[] {(int) (r * 255), (int) (g * 255), (int) (b * 255)};
    }

    private static double hueToRgb(double m1, double m2, double h) {
        h = h - Math.floor(h);
        if (h < 1.0 / 6.0) return m1 + (m2 - m1) * 6 * h;
        if (h < 0.5) return m2;
        if (h < 2.0 / 3.0) return m1 + (m2 - m1) * (2.0 / 3.0 - h) * 6;
        return m1;
    }

    private static Map<String, int[]> loadColors() {
        try (InputStream in = Palette.class.getResourceAsStream(COLORS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + COLORS_RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, int[]> map = new HashMap<>(root.size());
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode rgb = entry.getValue();
                map.put(entry.getKey(), new int[] {rgb.get(0).asInt(), rgb.get(1).asInt(), rgb.get(2).asInt()});
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + COLORS_RESOURCE, e);
        }
    }

    private static Map<String, String> parentRewrites() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("smooth_stone_slab", "smooth_stone");
        m.put("cobblestone_wall", "cobblestone");
        m.put("mossy_cobblestone_wall", "mossy_cobblestone");
        m.put("stonebrick", "stone_bricks");
        m.put("stone_brick", "stone_bricks");
        m.put("mossy_stone_brick", "mossy_stone_bricks");
        m.put("cracked_stone_brick", "cracked_stone_bricks");
        m.put("chiseled_stone_brick", "chiseled_stone_bricks");
        m.put("deepslate_brick", "deepslate_bricks");
        m.put("deepslate_tile", "deepslate_tiles");
        m.put("nether_brick", "nether_bricks");
        m.put("wooden_door", "oak_door");
        m.put("wooden_pressure_plate", "oak_planks");
        m.put("wooden_button", "oak_planks");
        m.put("wooden_trapdoor", "oak_planks");
        m.put("fence", "oak_planks");
        m.put("fence_gate", "oak_planks");
        m.put("log", "oak_log");
        m.put("log2", "oak_log");
        m.put("planks", "oak_planks");
        m.put("leaves", "oak_leaves");
        m.put("leaves2", "oak_leaves");
        m.put("still_water", "water");
        m.put("flowing_water", "water");
        m.put("still_lava", "lava");
        m.put("flowing_lava", "lava");
        m.put("iron_bars", "iron_block");
        m.put("jukebox", "oak_planks");
        m.put("ender_chest", "obsidian");
        m.put("nether_brick_fence", "nether_bricks");
        m.put("red_nether_brick", "red_nether_bricks");
        m.put("wool", "white_wool");
        m.put("carpet", "white_wool");
        m.put("stained_glass", "glass");
        m.put("concrete", "light_gray_concrete");
        m.put("concrete_powder", "light_gray_concrete");
        m.put("spruce_door", "spruce_planks");
        m.put("birch_door", "birch_planks");
        m.put("jungle_door", "jungle_planks");
        m.put("acacia_door", "acacia_planks");
        m.put("dark_oak_door", "dark_oak_planks");
        m.put("mangrove_door", "mangrove_planks");
        m.put("cherry_door", "cherry_planks");
        m.put("crimson_door", "crimson_planks");
        m.put("warped_door", "warped_planks");
        m.put("quartz", "quartz_block");
        m.put("quartz_pillar", "quartz_block");
        m.put("chiseled_quartz_block", "quartz_block");
        m.put("smooth_quartz", "quartz_block");
        m.put("prismarine_brick", "prismarine_bricks");
        m.put("dark_prismarine", "prismarine");
        m.put("piston", "iron_block");
        m.put("sticky_piston", "iron_block");
        m.put("piston_head", "iron_block");
        m.put("dispenser", "cobblestone");
        m.put("dropper", "cobblestone");
        m.put("hopper", "iron_block");
        m.put("furnace", "cobblestone");
        m.put("crafting_table", "oak_planks");
        m.put("bookshelf", "oak_planks");
        m.put("chest", "oak_planks");
        m.put("trapped_chest", "oak_planks");
        m.put("note_block", "oak_planks");
        m.put("spawner", "cobblestone");
        m.put("enchanting_table", "obsidian");
        m.put("brewing_stand", "iron_block");
        m.put("cauldron", "iron_block");
        m.put("anvil", "iron_block");
        m.put("ladder", "oak_planks");
        m.put("redstone_torch", "redstone_block");
        m.put("torch", "oak_planks");
        m.put("flower_pot", "bricks");
        m.put("tripwire_hook", "iron_block");
        m.put("command_block", "stone");
        m.put("end_portal_frame", "end_stone");
        m.put("dragon_egg", "obsidian");
        m.put("slime_block", "slime_block");
        m.put("beacon", "glass");
        m.put("barrier", "bedrock");
        m.put("cobweb", "white_wool");
        m.put("sand_slab", "sandstone");
        m.put("magma_block", "lava");
        m.put("observer", "polished_andesite");
        m.put("bamboo", "warped_planks");
        m.put("wheat", "hay_block");
        m.put("end_stone_bricks", "end_stone");
        m.put("brick", "bricks");
        m.put("barrel", "oak_log");
        m.put("vine", "oak_leaves");
        m.put("grindstone", "stone");
        m.put("stonecutter", "stone");
        m.put("smoker", "stone");
        m.put("blast_furnace", "stone");
        m.put("loom", "oak_planks");
        m.put("cartography_table", "oak_planks");
        m.put("fletching_table", "oak_planks");
        m.put("smithing_table", "oak_planks");
        m.put("lectern", "oak_planks");
        m.put("composter", "oak_planks");
        m.put("bell", "gold_block");
        m.put("campfire", "oak_planks");
        m.put("soul_campfire", "soul_soil");
        m.put("lantern", "iron_block");
        m.put("soul_lantern", "iron_block");
        m.put("sugar_cane", "lime_wool");
        m.put("kelp", "green_wool");
        m.put("kelp_plant", "green_wool");
        m.put("seagrass", "lime_wool");
        m.put("tall_seagrass", "lime_wool");
        m.put("cornflower", "blue_wool");
        m.put("dandelion", "yellow_wool");
        m.put("poppy", "red_wool");
        m.put("blue_orchid", "blue_wool");
        m.put("allium", "magenta_wool");
        m.put("azure_bluet", "white_wool");
        m.put("orange_tulip", "orange_wool");
        m.put("pink_tulip", "pink_wool");
        m.put("red_tulip", "red_wool");
        m.put("white_tulip", "white_wool");
        m.put("oxeye_daisy", "white_wool");
        m.put("lily_of_the_valley", "white_wool");
        m.put("wither_rose", "black_wool");
        m.put("sunflower", "yellow_wool");
        m.put("lilac", "magenta_wool");
        m.put("rose_bush", "red_wool");
        m.put("peony", "pink_wool");
        m.put("red_mushroom", "red_wool");
        m.put("brown_mushroom", "brown_wool");
        m.put("red_mushroom_block", "red_wool");
        m.put("brown_mushroom_block", "brown_wool");
        m.put("mushroom_stem", "white_wool");
        m.put("sculk_sensor", "sculk");
        m.put("sculk_catalyst", "sculk");
        m.put("sculk_shrieker", "sculk");
        m.put("wall_torch", "torch");
        m.put("redstone_wall_torch", "redstone_torch");
        m.put("soul_torch", "torch");
        m.put("soul_wall_torch", "torch");
        m.put("infested_stone", "stone");
        m.put("infested_cobblestone", "cobblestone");
        m.put("infested_stone_bricks", "stone_bricks");
        m.put("infested_deepslate", "deepslate");
        m.put("rail", "iron_block");
        m.put("activator_rail", "iron_block");
        m.put("powered_rail", "gold_block");
        m.put("golden_rail", "gold_block");
        m.put("detector_rail", "iron_block");
        m.put("redstone_wire", "redstone_block");
        m.put("standing_sign", "oak_planks");
        m.put("wall_sign", "oak_planks");
        m.put("lever", "oak_planks");
        m.put("repeater", "oak_planks");
        m.put("comparator", "oak_planks");
        m.put("chain", "iron_block");
        m.put("acacia_sapling", "acacia_leaves");
        m.put("oak_sapling", "oak_leaves");
        m.put("spruce_sapling", "spruce_leaves");
        m.put("birch_sapling", "birch_leaves");
        m.put("jungle_sapling", "jungle_leaves");
        m.put("dark_oak_sapling", "dark_oak_leaves");
        m.put("mangrove_propagule", "mangrove_leaves");
        m.put("cherry_sapling", "cherry_leaves");
        m.put("azalea_bush", "azalea_leaves");
        m.put("potatoes", "grass");
        m.put("carrots", "grass");
        m.put("beetroots", "grass");
        m.put("cocoa", "oak_leaves");
        m.put("melon_stem", "grass");
        m.put("pumpkin_stem", "grass");
        m.put("attached_melon_stem", "grass");
        m.put("attached_pumpkin_stem", "grass");
        m.put("tripwire", "white_wool");
        m.put("damaged_anvil", "anvil");
        m.put("chipped_anvil", "anvil");
        m.put("grass_path", "dirt_path");
        m.put("petrified_oak_slab", "oak_planks");
        m.put("heavy_weighted_pressure_plate", "iron_block");
        m.put("light_weighted_pressure_plate", "gold_block");
        m.put("player_head", "bone_block");
        m.put("player_wall_head", "bone_block");
        m.put("zombie_head", "lime_wool");
        m.put("zombie_wall_head", "lime_wool");
        m.put("skeleton_skull", "bone_block");
        m.put("skeleton_wall_skull", "bone_block");
        m.put("wither_skeleton_skull", "coal_block");
        m.put("wither_skeleton_wall_skull", "coal_block");
        m.put("creeper_head", "lime_wool");
        m.put("creeper_wall_head", "lime_wool");
        m.put("dragon_head", "black_wool");
        m.put("dragon_wall_head", "black_wool");
        m.put("piglin_head", "pink_wool");
        m.put("piglin_wall_head", "pink_wool");
        m.put("bubble_column", "water");
        m.put("sea_pickle", "lime_wool");
        m.put("fire_coral_block", "red_wool");
        m.put("brain_coral_block", "pink_wool");
        m.put("bubble_coral_block", "magenta_wool");
        m.put("horn_coral_block", "yellow_wool");
        m.put("tube_coral_block", "blue_wool");
        m.put("dead_fire_coral_block", "gray_wool");
        m.put("dead_brain_coral_block", "gray_wool");
        m.put("dead_bubble_coral_block", "gray_wool");
        m.put("dead_horn_coral_block", "gray_wool");
        m.put("dead_tube_coral_block", "gray_wool");
        m.put("end_portal", "obsidian");
        m.put("nether_portal", "purple_wool");
        m.put("infested_cracked_stone_bricks", "cracked_stone_bricks");
        m.put("infested_mossy_stone_bricks", "mossy_stone_bricks");
        m.put("infested_chiseled_stone_bricks", "chiseled_stone_bricks");
        m.put("shulker_box", "light_gray_wool");
        m.put("light", "glass");
        m.put("polished_blackstone", "blackstone");
        m.put("polished_blackstone_bricks", "blackstone");
        m.put("polished_blackstone_brick", "blackstone");
        m.put("chiseled_polished_blackstone", "blackstone");
        m.put("cracked_polished_blackstone_bricks", "blackstone");
        m.put("gilded_blackstone", "blackstone");
        m.put("exposed_copper", "copper_block");
        m.put("weathered_copper", "lime_terracotta");
        m.put("oxidized_copper", "cyan_wool");
        m.put("waxed_copper_block", "copper_block");
        m.put("waxed_exposed_copper", "copper_block");
        m.put("waxed_weathered_copper", "lime_terracotta");
        m.put("waxed_oxidized_copper", "cyan_wool");
        m.put("cut_copper", "copper_block");
        m.put("exposed_cut_copper", "copper_block");
        m.put("weathered_cut_copper", "lime_terracotta");
        m.put("oxidized_cut_copper", "cyan_wool");
        m.put("candle", "yellow_wool");
        return Collections.unmodifiableMap(m);
    }
}
