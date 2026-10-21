package ca.waltermiller.mcpapi.marker;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Inclusive block bounds; geometry is bounded independently of the cuboid's volume. */
public record MarkerSpec(AreaBounds bounds, String label, String color, int durationSeconds) {
    public static final Map<String, Integer> COLORS = Map.of(
        "yellow", 0xffff55, "red", 0xff5555, "green", 0x55ff55,
        "blue", 0x5555ff, "cyan", 0x55ffff, "purple", 0xff55ff, "white", 0xffffff);

    public MarkerSpec {
        if (bounds == null) throw new IllegalArgumentException("bounds is required");
        if (label == null || label.length() > 120) throw new IllegalArgumentException("label must be at most 120 characters");
        if (!COLORS.containsKey(color)) throw new IllegalArgumentException("Unknown marker color");
        if (durationSeconds < 1 || durationSeconds > 300) throw new IllegalArgumentException("duration_seconds must be between 1 and 300");
        if (Math.abs((long) bounds.min_x()) > 29999984 || Math.abs((long) bounds.max_x()) > 29999984
            || Math.abs((long) bounds.min_z()) > 29999984 || Math.abs((long) bounds.max_z()) > 29999984) {
            throw new IllegalArgumentException("Marker is outside the horizontal world limits");
        }
        if ((long) bounds.max_x() - bounds.min_x() >= 128
            || (long) bounds.max_y() - bounds.min_y() >= 128
            || (long) bounds.max_z() - bounds.min_z() >= 128) {
            throw new IllegalArgumentException("Each marker dimension must be at most 128 blocks");
        }
    }

    public record Point(double x, double y, double z) {}

    public double centerX() { return ((double) bounds.min_x() + bounds.max_x() + 1) / 2; }
    public double centerZ() { return ((double) bounds.min_z() + bounds.max_z() + 1) / 2; }

    public List<Point> points(int topY) {
        double[] low = {bounds.min_x(), bounds.min_y(), bounds.min_z()};
        double[] high = {bounds.max_x() + 1.0, bounds.max_y() + 1.0, bounds.max_z() + 1.0};
        List<Point> points = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            int a = (axis + 1) % 3, b = (axis + 2) % 3;
            int steps = Math.min(15, Math.max(1, (int) Math.ceil((high[axis] - low[axis]) / 2)));
            for (int corner = 0; corner < 4; corner++) {
                for (int i = 0; i <= steps; i++) {
                    double[] p = low.clone();
                    p[axis] += (high[axis] - low[axis]) * i / steps;
                    p[a] = (corner & 1) == 0 ? low[a] : high[a];
                    p[b] = (corner & 2) == 0 ? low[b] : high[b];
                    points.add(new Point(p[0], p[1], p[2]));
                }
            }
        }
        double start = high[1], end = Math.min(topY, start + 24);
        for (double y = start; y <= end; y += 1) points.add(new Point(centerX(), y, centerZ()));
        return List.copyOf(points);
    }
}
