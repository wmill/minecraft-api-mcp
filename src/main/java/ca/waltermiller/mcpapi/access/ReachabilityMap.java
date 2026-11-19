package ca.waltermiller.mcpapi.access;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Flood;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Mode;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Which standing positions inside a region the flood reached, by level, and the groups it didn't. */
public final class ReachabilityMap {
    static final int MAX_LEVELS = 64;
    static final int MAX_REGIONS = 10;

    public record Level(int y, int standable, int reachable, int outdoor, double fraction) {}

    public record Unreached(int cells, boolean outdoor, Map<String, Integer> bounds, Pos sample) {}

    public record Result(Map<String, Integer> region, int standable, int reachable, boolean complete,
                         List<Level> floors, boolean floors_truncated, int unreached_region_count,
                         List<Unreached> unreached_regions) {}

    private ReachabilityMap() {}

    public static Result compute(PlayerAccessSearch search, AccessTerrain terrain, Box region, Flood flood) {
        Map<Long, Boolean> outdoorCache = new HashMap<>();
        Map<Long, Integer> sky = new HashMap<>();
        TreeMap<Integer, int[]> levels = new TreeMap<>();
        Set<Long> unreached = new HashSet<>();
        int standable = 0, reachable = 0;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                if (!search.columnLoaded(x, z)) continue;
                int skyY = terrain.skyY(x, z);
                sky.put(PlayerAccessSearch.pack(x, 0, z), skyY);
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    var s = search.stand(x, y, z);
                    if (s == null || s.mode() != Mode.GROUND) continue;
                    long node = PlayerAccessSearch.pack(x, y, z);
                    boolean outdoor = y >= skyY;
                    outdoorCache.put(node, outdoor);
                    int[] counts = levels.computeIfAbsent(y, k -> new int[3]);
                    counts[0]++;
                    standable++;
                    if (outdoor) counts[2]++;
                    if (flood.parent().containsKey(node)) { counts[1]++; reachable++; }
                    else unreached.add(node);
                }
            }
        }
        var floors = new ArrayList<Level>();
        for (var e : levels.entrySet()) {
            int[] c = e.getValue();
            floors.add(new Level(e.getKey(), c[0], c[1], c[2], round(c[0] == 0 ? 0 : (double) c[1] / c[0])));
        }
        boolean truncated = floors.size() > MAX_LEVELS;
        if (truncated) {
            floors.sort(Comparator.comparingInt(Level::standable).reversed());
            floors = new ArrayList<>(floors.subList(0, MAX_LEVELS));
            floors.sort(Comparator.comparingInt(Level::y));
        }

        var groups = new ArrayList<Unreached>();
        Set<Long> seen = new HashSet<>();
        for (long first : unreached) {
            if (!seen.add(first)) continue;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(first);
            int cells = 0, outdoor = 0;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            Pos sample = null;
            while (!queue.isEmpty()) {
                long n = queue.poll();
                Pos p = Pos.unpack(n);
                cells++;
                if (outdoorCache.getOrDefault(n, false)) outdoor++;
                minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
                if (sample == null || compare(p, sample) < 0) sample = p;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int dy = -1; dy <= 1; dy++) {
                        long m = PlayerAccessSearch.pack(p.x() + d[0], p.y() + dy, p.z() + d[1]);
                        if (unreached.contains(m) && seen.add(m)) queue.add(m);
                    }
                }
                for (int dy : new int[] {-1, 1}) {
                    long m = PlayerAccessSearch.pack(p.x(), p.y() + dy, p.z());
                    if (unreached.contains(m) && seen.add(m)) queue.add(m);
                }
            }
            groups.add(new Unreached(cells, outdoor * 2 > cells,
                new Box(minX, minY, minZ, maxX, maxY, maxZ).json(), sample));
        }
        // Indoor groups first (sealed rooms, unreachable floors), then roofs and other outdoor surfaces; largest first.
        groups.sort(Comparator.comparing(Unreached::outdoor).thenComparing(Comparator.comparingInt(Unreached::cells).reversed())
            .thenComparing(u -> u.sample(), ReachabilityMap::compare));
        int count = groups.size();
        List<Unreached> top = groups.size() > MAX_REGIONS ? groups.subList(0, MAX_REGIONS) : groups;
        return new Result(region.json(), standable, reachable, !flood.nodeLimit(), floors, truncated, count, List.copyOf(top));
    }

    private static int compare(Pos a, Pos b) {
        if (a.y() != b.y()) return Integer.compare(a.y(), b.y());
        if (a.x() != b.x()) return Integer.compare(a.x(), b.x());
        return Integer.compare(a.z(), b.z());
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

}
