package ca.waltermiller.mcpapi.endpoints;

/** Bounds checks performed before allocating, dispatching, or reading a world. */
public final class CheckedDimensions {
    private CheckedDimensions() {}

    public static long span(int first, int second) {
        return Math.abs((long) second - first) + 1;
    }

    public static int volume(long cap, long... dimensions) {
        long result = 1;
        for (long dimension : dimensions) {
            if (dimension <= 0 || dimension > cap / result) {
                throw new IllegalArgumentException("Invalid dimensions: maximum volume is " + cap);
            }
            result *= dimension;
        }
        return Math.toIntExact(result);
    }

    public static int end(int start, int size) {
        if (size <= 0) throw new IllegalArgumentException("Invalid dimensions: sizes must be positive");
        long end = (long) start + size - 1;
        if (end > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid dimensions: end coordinate overflows");
        return (int) end;
    }

    public static int fill(int x1, int y1, int z1, int x2, int y2, int z2) {
        return volume(100_000, span(x1, x2), span(y1, y2), span(z1, z2));
    }
}
