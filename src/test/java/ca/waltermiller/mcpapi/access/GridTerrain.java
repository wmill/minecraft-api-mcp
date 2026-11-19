package ca.waltermiller.mcpapi.access;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** Flat stone up to y=63 (feet at 64) with explicit overrides. */
public final class GridTerrain implements AccessTerrain {
    final Map<Long, AccessCell> cells = new HashMap<>();
    BiPredicate<Integer, Integer> loaded = (cx, cz) -> true;

    public int bottomY() { return -64; }
    public int topYInclusive() { return 319; }
    public boolean chunkLoaded(int cx, int cz) { return loaded.test(cx, cz); }

    public AccessCell cell(int x, int y, int z) {
        AccessCell c = cells.get(PlayerAccessSearch.pack(x, y, z));
        if (c != null) return c;
        return y <= 63 ? AccessCell.STONE : AccessCell.AIR;
    }

    public int skyY(int x, int z) {
        for (int y = 120; y > -64; y--) if (cell(x, y, z).solid()) return y + 1;
        return -64;
    }

    public GridTerrain set(int x, int y, int z, AccessCell c) { cells.put(PlayerAccessSearch.pack(x, y, z), c); return this; }

    public GridTerrain fill(int x1, int y1, int z1, int x2, int y2, int z2, AccessCell c) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, c);
        return this;
    }

    /** Hollow box house, outer walls at x/z = ±3 from wall y 64..wallTop, roof one above, door gap on the east wall. */
    public GridTerrain house(int wallTop, AccessCell door) {
        fill(-3, 64, -3, 3, wallTop, 3, AccessCell.STONE);
        fill(-2, 64, -2, 2, wallTop, 2, AccessCell.AIR);
        fill(-3, wallTop + 1, -3, 3, wallTop + 1, 3, AccessCell.STONE);
        if (door != null) { set(3, 64, 0, door); set(3, 65, 0, door); }
        return this;
    }
}
