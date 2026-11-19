package ca.waltermiller.mcpapi.snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a placed template with what the world holds now, independent of the Minecraft runtime.
 * Block ids are compared without block state, like the dry-run overwrite report.
 */
public final class TemplateDrift {
    public static final int TOP_LIMIT = 10;
    public static final int SAMPLE_LIMIT = 20;

    public record Difference(String expected, String found, int count) {}
    public record Sample(int x, int y, int z, String expected, String found) {}
    /**
     * {@code missing}: template block replaced by air; {@code extra}: template air now filled;
     * {@code changed}: a different non-air block. Solid figures ignore template air cells.
     */
    public record Report(int template_blocks, int matching, int missing, int extra, int changed, double match_pct,
                         int solid_template_blocks, int solid_matching, double solid_match_pct,
                         List<Difference> top_differences, List<Sample> samples) {}

    private int total, matching, missing, extra, changed, solid, solidMatching;
    private final Map<String, Integer> pairs = new HashMap<>();
    private final List<Sample> samples = new ArrayList<>();

    public void record(int x, int y, int z, String expected, String found) {
        total++;
        boolean expectedAir = OverwriteTally.isAirId(expected);
        boolean foundAir = OverwriteTally.isAirId(found);
        if (!expectedAir) solid++;
        if (expected.equals(found) || (expectedAir && foundAir)) {
            matching++;
            if (!expectedAir) solidMatching++;
            return;
        }
        if (foundAir) missing++;
        else if (expectedAir) extra++;
        else changed++;
        pairs.merge(expected + "\u0000" + found, 1, Integer::sum);
        if (samples.size() < SAMPLE_LIMIT) samples.add(new Sample(x, y, z, expected, found));
    }

    public Report report() {
        List<Difference> top = pairs.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(TOP_LIMIT)
            .map(e -> {
                String[] parts = e.getKey().split("\u0000", 2);
                return new Difference(parts[0], parts[1], e.getValue());
            }).toList();
        return new Report(total, matching, missing, extra, changed, pct(matching, total),
            solid, solidMatching, pct(solidMatching, solid), top, List.copyOf(samples));
    }

    private static double pct(int part, int whole) {
        return whole == 0 ? 100.0 : Math.round(1000.0 * part / whole) / 10.0;
    }
}
