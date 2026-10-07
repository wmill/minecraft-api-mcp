package ca.waltermiller.mcpapi.survey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, read-only sampling and statistics, independent of the Minecraft runtime. */
public final class SiteSurvey {
    public static final int MAX_COLUMNS = 10_000;
    public static final int MAX_SCAN_DEPTH = 64;

    private SiteSurvey() {}

    public record Footprint(int min_x, int min_z, int max_x, int max_z) {
        public Footprint {
            long width = (long) max_x - min_x + 1;
            long depth = (long) max_z - min_z + 1;
            if (width < 1 || depth < 1 || width > MAX_COLUMNS || depth > MAX_COLUMNS
                    || width * depth > MAX_COLUMNS) {
                throw new IllegalArgumentException("Footprint must contain 1 to 10000 columns");
            }
        }

        public static Footprint of(int x1, int z1, int x2, int z2) {
            return new Footprint(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        }

        public int width() { return max_x - min_x + 1; }
        public int depth() { return max_z - min_z + 1; }
    }

    /** Fluid and vegetation flags are independent (e.g. waterlogged leaves). */
    public record Cell(boolean air, boolean vegetation, boolean water, boolean lava,
                       boolean collision, boolean full_top) {}

    public interface Terrain {
        int bottomY();
        int topYInclusive();
        boolean ceiling();
        boolean chunkLoaded(int chunkX, int chunkZ);
        int surfaceY(int x, int z);
        Cell cell(int x, int y, int z);
    }

    public record Column(Integer walking_y, boolean water, boolean lava, boolean vegetation, String unresolved_reason) {}
    public record Coverage(int columns, double fraction) {}
    public record Ground(int resolved_columns, int unresolved_columns, Map<String, Integer> unresolved_reasons,
                         Integer min_y, Integer max_y, Integer median_y, Integer range) {}
    public record Slope(int adjacent_pairs, double mean_step, int max_step) {}
    public record Grading(Integer suggested_walking_y, Long cut_blocks, Long fill_blocks, String unavailable_reason) {}
    public record Result(boolean success, String world, String surveyed_at, Footprint bounds,
                         Map<String, Integer> size, Ground ground, Slope slope, Coverage water,
                         Coverage lava, Coverage vegetation, Grading grading) {}

    public static final class SurveyException extends IllegalArgumentException {
        private final String code;
        private final List<Map<String, Integer>> missingChunks;

        public SurveyException(String code, String message, List<Map<String, Integer>> missingChunks) {
            super(message);
            this.code = code;
            this.missingChunks = List.copyOf(missingChunks);
        }

        public Map<String, Object> payload() {
            return Map.of("success", false, "code", code, "error", getMessage(), "missing_chunks", missingChunks);
        }
    }

    public static Result survey(String world, Footprint bounds, Terrain terrain) {
        if (terrain.ceiling()) {
            throw new SurveyException("ceiling_dimension", "Surface surveys do not support ceiling dimensions", List.of());
        }
        List<Map<String, Integer>> missing = new ArrayList<>();
        for (int cx = bounds.min_x() >> 4; cx <= (bounds.max_x() >> 4); cx++) {
            for (int cz = bounds.min_z() >> 4; cz <= (bounds.max_z() >> 4); cz++) {
                if (!terrain.chunkLoaded(cx, cz)) missing.add(Map.of("x", cx, "z", cz));
            }
        }
        if (!missing.isEmpty()) {
            throw new SurveyException("unloaded_chunks", "All footprint chunks must already be loaded and ready", missing);
        }
        Column[][] columns = new Column[bounds.width()][bounds.depth()];
        for (int x = 0; x < bounds.width(); x++) {
            for (int z = 0; z < bounds.depth(); z++) {
                columns[x][z] = sample(terrain, bounds.min_x() + x, bounds.min_z() + z);
            }
        }
        return summarize(world, bounds, columns);
    }

    static Column sample(Terrain terrain, int x, int z) {
        int top = Math.min(terrain.surfaceY(x, z) - 1, terrain.topYInclusive());
        boolean water = false, lava = false, vegetation = false;
        int y = top;
        for (int scanned = 0; scanned < MAX_SCAN_DEPTH && y >= terrain.bottomY(); scanned++, y--) {
            Cell cell = terrain.cell(x, y, z);
            water |= cell.water();
            lava |= cell.lava();
            vegetation |= cell.vegetation();
            if (cell.air() || cell.vegetation() || !cell.collision()) continue;
            if (!cell.full_top()) return new Column(null, water, lava, vegetation, "unsupported_surface");
            return new Column(y + 1, water, lava, vegetation, null);
        }
        return new Column(null, water, lava, vegetation, y < terrain.bottomY() ? "void" : "scan_limit");
    }

    static Result summarize(String world, Footprint bounds, Column[][] columns) {
        int total = bounds.width() * bounds.depth();
        int[] elevations = new int[total];
        int[] dry = new int[total];
        int resolved = 0, dryCount = 0, water = 0, lava = 0, vegetation = 0, pairs = 0, maxStep = 0;
        long stepSum = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (int x = 0; x < bounds.width(); x++) {
            for (int z = 0; z < bounds.depth(); z++) {
                Column c = columns[x][z];
                if (c.water()) water++;
                if (c.lava()) lava++;
                if (c.vegetation()) vegetation++;
                if (c.walking_y() == null) {
                    reasons.merge(c.unresolved_reason(), 1, Integer::sum);
                    continue;
                }
                elevations[resolved++] = c.walking_y();
                if (!c.water() && !c.lava()) dry[dryCount++] = c.walking_y();
                // Each horizontal edge is counted once, never across unresolved columns.
                for (Column neighbor : new Column[] { x > 0 ? columns[x - 1][z] : null, z > 0 ? columns[x][z - 1] : null }) {
                    if (neighbor != null && neighbor.walking_y() != null) {
                        int step = Math.abs(c.walking_y() - neighbor.walking_y());
                        stepSum += step;
                        maxStep = Math.max(maxStep, step);
                        pairs++;
                    }
                }
            }
        }
        Arrays.sort(elevations, 0, resolved);
        Arrays.sort(dry, 0, dryCount);
        Ground ground = new Ground(resolved, total - resolved, reasons,
            resolved == 0 ? null : elevations[0], resolved == 0 ? null : elevations[resolved - 1],
            resolved == 0 ? null : elevations[(resolved - 1) / 2],
            resolved == 0 ? null : elevations[resolved - 1] - elevations[0]);
        String unavailable = resolved != total ? "unresolved_ground" : lava > 0 ? "lava_present" : dryCount == 0 ? "no_dry_ground" : null;
        Grading grading;
        if (unavailable != null) grading = new Grading(null, null, null, unavailable);
        else {
            int suggested = dry[(dryCount - 1) / 2];
            long cut = 0, fill = 0;
            for (int i = 0; i < resolved; i++) {
                cut += Math.max(0, elevations[i] - suggested);
                fill += Math.max(0, suggested - elevations[i]);
            }
            grading = new Grading(suggested, cut, fill, null);
        }
        return new Result(true, world, Instant.now().toString(), bounds,
            Map.of("x", bounds.width(), "z", bounds.depth(), "columns", total), ground,
            new Slope(pairs, pairs == 0 ? 0 : (double) stepSum / pairs, maxStep),
            new Coverage(water, (double) water / total), new Coverage(lava, (double) lava / total),
            new Coverage(vegetation, (double) vegetation / total), grading);
    }
}
