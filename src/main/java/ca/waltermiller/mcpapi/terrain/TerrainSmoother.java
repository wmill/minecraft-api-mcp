package ca.waltermiller.mcpapi.terrain;

/**
 * Plans new ground heights for a region. Pure: no world access, so every rule here is
 * unit-tested directly.
 *
 * <p>Input grids cover the region plus {@code margin} context columns on every side, so
 * filters near the region edge read real terrain instead of a cut-off. Only region
 * columns are planned. A column changes by {@code w * (target - old)}, where {@code w} is
 * {@code strength} eased in over {@code feather} columns from the region edge, so the
 * result joins the untouched terrain outside without a step. Masked columns (buildings,
 * water, unloaded) never change and are left out of every filter neighbourhood; the 8
 * neighbours of an anchoring mask (anything but unknown context) never change either,
 * so walls are not undercut and water is not opened up.
 */
public final class TerrainSmoother {
    private TerrainSmoother() {}

    /** A filter, or a flat pad. */
    public sealed interface Method permits Filter, Pad {}

    public record Filter(HeightFilter filter, int iterations) implements Method {}

    /**
     * Flat at {@code y} over a footprint (region-local, inclusive), easing back to the
     * existing ground over {@code blend} columns around it. Footprint columns ignore
     * strength and feather; only masks and {@code max_change} stop them reaching {@code y}.
     */
    public record Pad(int y, int minX, int minZ, int maxX, int maxZ, int blend) implements Method {}

    public record Options(Method method, double strength, int feather, int maxChange) {}

    public record Stats(int columns, int changedColumns, int maskedColumns, int protectedColumns,
                        int clampedColumns, long cutBlocks, long fillBlocks, int maxLowered, int maxRaised,
                        int padColumns, int padColumnsAtTarget) {}

    /** {@code heights} is region-sized ([x][z] from the region's min corner). */
    public record Plan(int[][] heights, Stats stats) {}

    public static int margin(Method method) {
        return method instanceof Filter f ? f.filter().radius() : 0;
    }

    public static Plan plan(int[][] ground, boolean[][] masked, int margin, Options options) {
        return plan(ground, masked, masked, margin, options);
    }

    /** {@code anchors} is the subset of {@code masked} whose neighbours are protected. */
    public static Plan plan(int[][] ground, boolean[][] masked, boolean[][] anchors, int margin, Options options) {
        int sx = ground.length, sz = ground[0].length;
        int rx = sx - 2 * margin, rz = sz - 2 * margin;
        if (rx <= 0 || rz <= 0) throw new IllegalArgumentException("Grid is smaller than its margin");

        double[][] target = switch (options.method()) {
            case Filter f -> filtered(ground, masked, margin, f);
            case Pad p -> padTarget(ground, margin, p);
        };

        int[][] out = new int[rx][rz];
        int changed = 0, maskedCount = 0, protectedCount = 0, clamped = 0, padColumns = 0, padAtTarget = 0;
        int maxLowered = 0, maxRaised = 0;
        long cut = 0, fill = 0;
        for (int x = 0; x < rx; x++) {
            for (int z = 0; z < rz; z++) {
                int gx = x + margin, gz = z + margin;
                int old = ground[gx][gz];
                out[x][z] = old;
                boolean inPad = options.method() instanceof Pad p && x >= p.minX() && x <= p.maxX()
                    && z >= p.minZ() && z <= p.maxZ();
                if (inPad) padColumns++;
                if (masked[gx][gz]) {
                    maskedCount++;
                    continue;
                }
                if (nextToMask(anchors, gx, gz)) {
                    protectedCount++;
                    continue;
                }
                double w = inPad ? 1 : options.strength() * edgeWeight(x, z, rx, rz, options.feather());
                int delta = (int) Math.round(w * (target[gx][gz] - old));
                if (Math.abs(delta) > options.maxChange()) {
                    delta = Integer.signum(delta) * options.maxChange();
                    clamped++;
                }
                out[x][z] = old + delta;
                if (inPad && options.method() instanceof Pad p && out[x][z] == p.y()) padAtTarget++;
                if (delta == 0) continue;
                changed++;
                if (delta < 0) {
                    cut += -delta;
                    maxLowered = Math.max(maxLowered, -delta);
                } else {
                    fill += delta;
                    maxRaised = Math.max(maxRaised, delta);
                }
            }
        }
        return new Plan(out, new Stats(rx * rz, changed, maskedCount, protectedCount, clamped, cut, fill,
            maxLowered, maxRaised, padColumns, padAtTarget));
    }

    private static double[][] filtered(int[][] ground, boolean[][] masked, int margin, Filter f) {
        int sx = ground.length, sz = ground[0].length;
        double[][] original = new double[sx][sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) original[x][z] = ground[x][z];
        }
        double[][] h = original;
        for (int i = 0; i < f.iterations(); i++) {
            h = f.filter().apply(h, masked);
            // Context columns outside the region are read but never smoothed themselves.
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    boolean outside = x < margin || z < margin || x >= sx - margin || z >= sz - margin;
                    if (outside) h[x][z] = original[x][z];
                }
            }
        }
        return h;
    }

    private static double[][] padTarget(int[][] ground, int margin, Pad p) {
        int sx = ground.length, sz = ground[0].length;
        double[][] t = new double[sx][sz];
        for (int gx = 0; gx < sx; gx++) {
            for (int gz = 0; gz < sz; gz++) {
                int x = gx - margin, z = gz - margin;
                double dx = Math.max(0, Math.max(p.minX() - x, x - p.maxX()));
                double dz = Math.max(0, Math.max(p.minZ() - z, z - p.maxZ()));
                double d = Math.hypot(dx, dz);
                double a = d == 0 ? 1 : d > p.blend() ? 0 : 1 - smoothstep(d / (p.blend() + 1));
                t[gx][gz] = ground[gx][gz] + a * (p.y() - ground[gx][gz]);
            }
        }
        return t;
    }

    /** 1 deep inside the region, easing to near 0 at its outermost columns. */
    static double edgeWeight(int x, int z, int rx, int rz, int feather) {
        if (feather <= 0) return 1;
        int d = 1 + Math.min(Math.min(x, rx - 1 - x), Math.min(z, rz - 1 - z));
        return smoothstep(Math.min(1.0, d / (double) (feather + 1)));
    }

    static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static boolean nextToMask(boolean[][] masked, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx, nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < masked.length && nz < masked[0].length && masked[nx][nz]) return true;
            }
        }
        return false;
    }
}
