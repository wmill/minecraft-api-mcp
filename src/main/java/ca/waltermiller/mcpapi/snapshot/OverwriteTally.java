package ca.waltermiller.mcpapi.snapshot;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counts what a placement would do to existing blocks, independent of the Minecraft runtime. */
public final class OverwriteTally {
    public static final int TOP_LIMIT = 10;

    public enum Category { AIR, TERRAIN, LOGS_LEAVES, VEGETATION, FLUIDS, BLOCK_ENTITIES, OTHER }

    public record BlockCount(String block, int count) {}
    public record Report(int template_blocks, int replaced, int unchanged, int air_carved, int placed_into_air,
                         Map<String, Integer> replaced_by_category, List<BlockCount> top_replaced) {}

    private int total, replaced, unchanged, airCarved, placedIntoAir;
    private final EnumMap<Category, Integer> byCategory = new EnumMap<>(Category.class);
    private final Map<String, Integer> byBlock = new HashMap<>();

    /** One template block over one existing world block. Block ids compare without block state. */
    public void record(String existingBlock, Category existing, String incomingBlock, boolean incomingAir) {
        total++;
        if (existingBlock.equals(incomingBlock)) {
            unchanged++;
        } else if (existing == Category.AIR) {
            if (!incomingAir) placedIntoAir++;
        } else {
            replaced++;
            if (incomingAir) airCarved++;
            byCategory.merge(existing, 1, Integer::sum);
            byBlock.merge(existingBlock, 1, Integer::sum);
        }
    }

    public Report report() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            if (category != Category.AIR) categories.put(category.name().toLowerCase(), byCategory.getOrDefault(category, 0));
        }
        List<BlockCount> top = byBlock.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(TOP_LIMIT).map(e -> new BlockCount(e.getKey(), e.getValue())).toList();
        return new Report(total, replaced, unchanged, airCarved, placedIntoAir, categories, top);
    }

    public static boolean isAirId(String block) {
        return "minecraft:air".equals(block) || "minecraft:cave_air".equals(block) || "minecraft:void_air".equals(block);
    }
}
