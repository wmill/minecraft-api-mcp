package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.arealock.AreaBounds;

import java.util.Map;
import java.util.UUID;

/**
 * Query parameters of {@code GET /api/world/region/nbt}: either a cuboid ({@code x1..z2}) or a recorded build
 * ({@code build_id}) widened by {@code margin} sideways, {@code below} under its lowest block and {@code above}
 * over its highest. Exactly one form must be given. Pure, so it can be tested without a world.
 */
public record RegionCaptureRequest(String world, AreaBounds cuboid, UUID buildId, int margin, int below, int above) {
    public static final int DEFAULT_MARGIN = 6;
    public static final int DEFAULT_BELOW = 3;
    public static final int DEFAULT_ABOVE = 4;
    public static final int MAX_PADDING = 32;
    public static final long DEFAULT_MAX_VOLUME = 1_000_000;

    private static final String[] CORNERS = {"x1", "y1", "z1", "x2", "y2", "z2"};

    /** A request failure with its HTTP status and machine-readable code. */
    public static final class RegionException extends RuntimeException {
        private final int status;
        private final String code;

        public RegionException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
        public Map<String, Object> payload() { return Map.of("success", false, "code", code, "error", getMessage()); }
    }

    public static RegionCaptureRequest parse(Map<String, String> query) {
        String world = blankToNull(query.get("world"));
        String build = blankToNull(query.get("build_id"));
        int given = 0;
        for (String name : CORNERS) if (blankToNull(query.get(name)) != null) given++;
        if (build != null && given > 0) throw invalid("Give either build_id or x1,y1,z1,x2,y2,z2, not both");
        if (build == null && given == 0) throw invalid("Give build_id or all of x1,y1,z1,x2,y2,z2");

        if (build == null) {
            if (given < CORNERS.length) throw invalid("A cuboid needs all of x1,y1,z1,x2,y2,z2");
            for (String name : new String[] {"margin", "below", "above"}) {
                if (blankToNull(query.get(name)) != null) throw invalid(name + " only applies with build_id");
            }
            int[] c = new int[CORNERS.length];
            for (int i = 0; i < c.length; i++) c[i] = integer(query, CORNERS[i]);
            var cuboid = new AreaBounds(Math.min(c[0], c[3]), Math.min(c[1], c[4]), Math.min(c[2], c[5]),
                Math.max(c[0], c[3]), Math.max(c[1], c[4]), Math.max(c[2], c[5]));
            return new RegionCaptureRequest(world, cuboid, null, 0, 0, 0);
        }
        UUID id;
        try { id = UUID.fromString(build); }
        catch (IllegalArgumentException e) { throw invalid("build_id must be a UUID"); }
        return new RegionCaptureRequest(world, null, id, padding(query, "margin", DEFAULT_MARGIN),
            padding(query, "below", DEFAULT_BELOW), padding(query, "above", DEFAULT_ABOVE));
    }

    /** The capture region around a build's recorded bounds. */
    public AreaBounds aroundBuild(Box build) {
        return new AreaBounds(clampInt((long) build.minX() - margin), clampInt((long) build.minY() - below),
            clampInt((long) build.minZ() - margin), clampInt((long) build.maxX() + margin),
            clampInt((long) build.maxY() + above), clampInt((long) build.maxZ() + margin));
    }

    /** Cuts the region to the world's build height; fails when nothing is left. */
    public static AreaBounds clampY(AreaBounds bounds, int bottomY, int topYInclusive) {
        int minY = Math.max(bounds.min_y(), bottomY);
        int maxY = Math.min(bounds.max_y(), topYInclusive);
        if (minY > maxY) {
            throw new RegionException(400, "invalid_request",
                "The region is outside the world's height range (" + bottomY + ".." + topYInclusive + ")");
        }
        return new AreaBounds(bounds.min_x(), minY, bounds.min_z(), bounds.max_x(), maxY, bounds.max_z());
    }

    /** The voxel count, or {@code region_too_large} past the cap. */
    public static long checkVolume(AreaBounds bounds, long maxVolume) {
        long sx = CheckedDimensions.span(bounds.min_x(), bounds.max_x());
        long sy = CheckedDimensions.span(bounds.min_y(), bounds.max_y());
        long sz = CheckedDimensions.span(bounds.min_z(), bounds.max_z());
        long volume = 1;
        for (long span : new long[] {sx, sy, sz}) {
            if (span > maxVolume / volume) {
                throw new RegionException(400, "region_too_large", "The region is " + sx + "x" + sy + "x" + sz
                    + " blocks; the maximum is " + maxVolume + " blocks. Use a smaller cuboid or a smaller margin.");
            }
            volume *= span;
        }
        return volume;
    }

    /** {@code REGION_RENDER_MAX_VOLUME} from the environment, or the default. */
    public static long maxVolumeFromEnv() {
        String value = System.getenv("REGION_RENDER_MAX_VOLUME");
        if (value == null || value.isBlank()) return DEFAULT_MAX_VOLUME;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_VOLUME;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_VOLUME;
        }
    }

    private static int padding(Map<String, String> query, String name, int fallback) {
        if (blankToNull(query.get(name)) == null) return fallback;
        int value = integer(query, name);
        if (value < 0 || value > MAX_PADDING) throw invalid(name + " must be between 0 and " + MAX_PADDING);
        return value;
    }

    private static int integer(Map<String, String> query, String name) {
        try { return Integer.parseInt(query.get(name).trim()); }
        catch (NumberFormatException e) { throw invalid(name + " must be a 32-bit integer"); }
    }

    private static int clampInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static RegionException invalid(String message) {
        return new RegionException(400, "invalid_request", message);
    }
}
