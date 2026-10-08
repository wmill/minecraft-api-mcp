package ca.waltermiller.mcpapi.survey;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import ca.waltermiller.mcpapi.buildtask.model.BuildStatus;
import ca.waltermiller.mcpapi.buildtask.repository.BuildRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Optional build-history integration. Query on the HTTP thread, never the world thread. */
public final class SurveyOccupancy {
    private volatile BuildRepository builds;

    public void setBuildRepository(BuildRepository repository) { builds = repository; }

    public record Overlaps(String status, Integer total, boolean truncated, List<Map<String, Object>> entries) {}

    public static Overlaps reservations(AreaLockService locks, String world, AreaBounds bounds) {
        var matches = locks.list(world, bounds);
        var entries = matches.stream().limit(5).map(row -> Map.<String, Object>of(
            "label", compact((String) row.get("label")), "bounds", row.get("bounds"), "expires_at", row.get("expires_at"))).toList();
        return new Overlaps("available", matches.size(), matches.size() > entries.size(), entries);
    }

    public Overlaps builds(String world, AreaBounds bounds) {
        var repository = builds;
        if (repository == null) return new Overlaps("unavailable", null, false, List.of());
        try {
            var matches = repository.findByLocationIntersection(world, new BoundingBox(
                bounds.min_x(), bounds.min_y(), bounds.min_z(), bounds.max_x(), bounds.max_y(), bounds.max_z()))
                .stream().filter(b -> b.getStatus() != BuildStatus.REVERTED).toList();
            var entries = matches.stream().sorted(Comparator.comparing(b -> b.getId().toString())).limit(5)
                .map(b -> Map.<String, Object>of("build_id", b.getId().toString(), "name", compact(b.getName()),
                    "status", b.getStatus().name())).toList();
            return new Overlaps("available", matches.size(), matches.size() > entries.size(), entries);
        } catch (java.sql.SQLException e) {
            return new Overlaps("unavailable", null, false, List.of());
        }
    }

    private static String compact(String value) {
        return value == null ? "" : value.substring(0, Math.min(80, value.length()));
    }
}
