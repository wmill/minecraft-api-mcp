package ca.waltermiller.mcpapi.arealock;

/** Inclusive block coordinates. */
public record AreaBounds(int min_x, int min_y, int min_z, int max_x, int max_y, int max_z) {
    public AreaBounds {
        if (min_x > max_x || min_y > max_y || min_z > max_z) {
            throw new IllegalArgumentException("Invalid bounds: minima must not exceed maxima");
        }
    }

    public boolean intersects(AreaBounds other) {
        return min_x <= other.max_x && max_x >= other.min_x
            && min_y <= other.max_y && max_y >= other.min_y
            && min_z <= other.max_z && max_z >= other.min_z;
    }

    public boolean contains(AreaBounds other) {
        return min_x <= other.min_x && max_x >= other.max_x
            && min_y <= other.min_y && max_y >= other.max_y
            && min_z <= other.min_z && max_z >= other.max_z;
    }

    public AreaBounds union(AreaBounds other) {
        return new AreaBounds(Math.min(min_x, other.min_x), Math.min(min_y, other.min_y),
            Math.min(min_z, other.min_z), Math.max(max_x, other.max_x),
            Math.max(max_y, other.max_y), Math.max(max_z, other.max_z));
    }
}
