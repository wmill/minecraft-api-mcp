package ca.waltermiller.mcpapi.access;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Breadth-first search over the positions a player can stand, hang or swim in.
 *
 * <p>Model (feet position = the block the player's feet are in): walking to the 4 horizontal neighbours,
 * stepping up at most 0.6 (slabs, carpet), jumping up at most 1.25 with headroom in the source column,
 * walking up stairs, dropping at most 3 blocks (any height into water), climbing ladders/vines/scaffolding,
 * swimming, and passing doors, trapdoors and fence gates that the {@link Doors} policy lets the player open.
 * Lava and damaging blocks are never entered. The player is 1.8 tall and treated as one block wide.
 * Only already-loaded chunks inside the search box are read.
 */
public final class PlayerAccessSearch {
    public enum Doors { NONE, WOODEN, ALL }
    public enum Mode { GROUND, CLIMB, SWIM }

    public record Pos(int x, int y, int z) {
        public long pack() { return PlayerAccessSearch.pack(x, y, z); }
        public static Pos unpack(long p) { return new Pos(unpackX(p), unpackY(p), unpackZ(p)); }
        double distance(Pos o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Inclusive cuboid. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }
        public boolean containsXZ(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        public boolean contains(int x, int y, int z) { return containsXZ(x, z) && y >= minY && y <= maxY; }
        public boolean contains(Pos p) { return contains(p.x(), p.y(), p.z()); }
        public long volume() { return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }
        public Box union(Box o) {
            return new Box(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
        }
        public Map<String, Integer> json() {
            var m = new java.util.LinkedHashMap<String, Integer>();
            m.put("min_x", minX); m.put("min_y", minY); m.put("min_z", minZ);
            m.put("max_x", maxX); m.put("max_y", maxY); m.put("max_z", maxZ);
            return m;
        }
    }

    public record Stand(double feet, Mode mode) {}

    /** Result of one flood: every reached node mapped to the node it was reached from (the start maps to itself). */
    public record Flood(long start, Map<Long, Long> parent, int expanded, boolean nodeLimit,
                        boolean touchedBoundary, boolean touchedUnloaded) {
        public boolean reached(Pos p) { return parent.containsKey(p.pack()); }

        /** Positions from the start to {@code p}, inclusive, or an empty list if {@code p} was not reached. */
        public List<Pos> route(Pos p) {
            long node = p.pack();
            if (!parent.containsKey(node)) return List.of();
            var out = new ArrayList<Pos>();
            while (true) {
                out.add(Pos.unpack(node));
                long prev = parent.get(node);
                if (prev == node) break;
                node = prev;
            }
            Collections.reverse(out);
            return out;
        }

        public Pos nearest(Pos target) {
            Pos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (long node : parent.keySet()) {
                Pos p = Pos.unpack(node);
                double d = p.distance(target);
                if (d < bestDistance) { bestDistance = d; best = p; }
            }
            return best;
        }
    }

    static final double STEP = 0.6;
    static final double JUMP = 1.25;
    static final double HEIGHT = 1.8;
    static final double EPS = 1e-6;
    static final int SAFE_DROP = 3;
    static final int SCAN_DOWN = 24;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final AccessTerrain terrain;
    private final Doors doors;
    private final Box box;
    private final int cacheMinY;
    private final int cacheHeight;
    private final Map<Long, AccessCell[]> columns = new HashMap<>();
    private final Map<Long, Boolean> chunks = new HashMap<>();
    private boolean touchedBoundary;
    private boolean touchedUnloaded;

    public PlayerAccessSearch(AccessTerrain terrain, Doors doors, Box box) {
        this.terrain = terrain;
        this.doors = doors;
        this.box = box;
        this.cacheMinY = box.minY() - 1;
        this.cacheHeight = box.maxY() - box.minY() + 4;
    }

    public Box box() { return box; }

    public boolean columnLoaded(int x, int z) {
        return chunks.computeIfAbsent(pack(x >> 4, 0, z >> 4), k -> terrain.chunkLoaded(x >> 4, z >> 4));
    }

    AccessCell cell(int x, int y, int z) {
        if (y < terrain.bottomY() || y > terrain.topYInclusive()) return AccessCell.AIR;
        int index = y - cacheMinY;
        if (index < 0 || index >= cacheHeight) return terrain.cell(x, y, z);
        AccessCell[] column = columns.computeIfAbsent(pack(x, 0, z), k -> new AccessCell[cacheHeight]);
        AccessCell c = column[index];
        if (c == null) column[index] = c = terrain.cell(x, y, z);
        return c;
    }

    boolean doorPassable(AccessCell c) {
        if (c.openable() == AccessCell.Openable.NONE) return false;
        if (c.open()) return true;
        return switch (doors) {
            case NONE -> false;
            case WOODEN -> c.openable() == AccessCell.Openable.WOOD;
            case ALL -> true;
        };
    }

    boolean blocksBody(AccessCell c) {
        return c.solid() && !c.climbable() && !doorPassable(c);
    }

    boolean bodyClear(AccessCell c) {
        return !c.lava() && !c.hazard() && !blocksBody(c);
    }

    private boolean columnClear(int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) if (!bodyClear(cell(x, y, z))) return false;
        return true;
    }

    private static int headTop(double feet) { return (int) Math.floor(feet + HEIGHT - EPS); }

    /** Whether a player can occupy feet position (x, y, z), and at what feet height. Null when not. */
    public Stand stand(int x, int y, int z) {
        if (y < terrain.bottomY() || y > terrain.topYInclusive()) return null;
        AccessCell c = cell(x, y, z);
        if (c.lava() || c.hazard()) return null;
        double feet;
        Mode mode;
        if (c.water()) { feet = y; mode = Mode.SWIM; }
        else if (c.climbable()) { feet = y; mode = Mode.CLIMB; }
        else if (c.solid() && c.top() > 0 && c.top() <= STEP + EPS) { feet = y + c.top(); mode = Mode.GROUND; }
        else if (blocksBody(c)) return null;
        else {
            AccessCell below = cell(x, y - 1, z);
            if (!below.solid() || below.climbable() || below.hazard() || below.top() <= STEP + EPS) return null;
            feet = y - 1 + below.top();
            mode = Mode.GROUND;
        }
        return columnClear(x, z, y + 1, headTop(feet)) ? new Stand(feet, mode) : null;
    }

    /** {@code p} itself if it is occupiable, else the first occupiable of y+1, y-1, y+2 in its column, else null. */
    public Pos resolve(Pos p) {
        if (!columnLoaded(p.x(), p.z())) return null;
        for (int dy : new int[] {0, 1, -1, 2}) {
            if (stand(p.x(), p.y() + dy, p.z()) != null) return new Pos(p.x(), p.y() + dy, p.z());
        }
        return null;
    }

    /** Nearest occupiable position within 2 blocks horizontally and 4 vertically, or null. */
    public Pos suggest(Pos p) {
        Pos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = p.x() + dx, z = p.z() + dz;
                if (!box.containsXZ(x, z) || !columnLoaded(x, z)) continue;
                for (int dy = -4; dy <= 4; dy++) {
                    int y = p.y() + dy;
                    if (y < box.minY() || y > box.maxY() || stand(x, y, z) == null) continue;
                    Pos r = new Pos(x, y, z);
                    if (r.distance(p) < bestDistance) { bestDistance = r.distance(p); best = r; }
                }
            }
        }
        return best;
    }

    /** True when {@code p} (or the block above it) is a door, trapdoor or gate the doors policy can't get through. */
    public boolean closedDoor(Pos p) {
        if (!columnLoaded(p.x(), p.z())) return false;
        for (int dy = 0; dy <= 1; dy++) {
            AccessCell c = cell(p.x(), p.y() + dy, p.z());
            if (c.openable() != AccessCell.Openable.NONE && blocksBody(c)) return true;
        }
        return false;
    }

    /**
     * Floods from {@code start} (which must be occupiable). Stops when every position in {@code stopAt} is reached
     * (null = explore everything reachable), when the search runs out of nodes, or when nothing is left.
     */
    public Flood flood(Pos start, Set<Long> stopAt, int maxNodes) {
        touchedBoundary = false;
        touchedUnloaded = false;
        long first = start.pack();
        Map<Long, Long> parent = new HashMap<>();
        parent.put(first, first);
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(first);
        Set<Long> remaining = stopAt == null ? null : new HashSet<>(stopAt);
        if (remaining != null) remaining.remove(first);
        int expanded = 0;
        boolean nodeLimit = false;
        while (!queue.isEmpty()) {
            if (remaining != null && remaining.isEmpty()) break;
            if (expanded >= maxNodes) { nodeLimit = true; break; }
            long node = queue.poll();
            expanded++;
            successors(node, next -> {
                if (parent.putIfAbsent(next, node) == null) {
                    queue.add(next);
                    if (remaining != null) remaining.remove(next);
                }
            });
        }
        return new Flood(first, parent, expanded, nodeLimit, touchedBoundary, touchedUnloaded);
    }

    void successors(long node, LongConsumer out) {
        int x = unpackX(node), y = unpackY(node), z = unpackZ(node);
        Stand a = stand(x, y, z);
        if (a == null) return;
        double effective = a.feet();
        if (a.mode() == Mode.CLIMB && !cell(x, y + 1, z).climbable()) effective = y + 1.0;
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            if (!box.containsXZ(nx, nz)) { touchedBoundary = true; continue; }
            if (!columnLoaded(nx, nz)) { touchedUnloaded = true; continue; }
            long next = horizontal(x, y, z, effective, nx, nz);
            if (next != Long.MIN_VALUE) out.accept(next);
        }
        // Up: onto a ladder above, or further up a ladder / water column.
        if (y + 1 <= box.maxY()) {
            Stand up = stand(x, y + 1, z);
            if (up != null && (up.mode() == Mode.CLIMB
                    || (a.mode() == Mode.CLIMB && up.mode() != Mode.SWIM)
                    || (a.mode() == Mode.SWIM && up.mode() == Mode.SWIM))) {
                out.accept(pack(x, y + 1, z));
            }
        } else touchedBoundary = true;
        // Down: let go of a ladder, swim down, or go through an openable floor (trapdoor) we are standing on.
        if (y - 1 >= box.minY()) {
            AccessCell feetCell = cell(x, y, z);
            boolean openableFloor = (feetCell.solid() && feetCell.top() <= STEP + EPS && doorPassable(feetCell))
                || (a.mode() == Mode.GROUND && !feetCell.solid() && doorPassable(cell(x, y - 1, z)));
            if (a.mode() != Mode.GROUND || openableFloor) {
                long down = landBelow(x, z, y - 1, a.feet());
                if (down != Long.MIN_VALUE) out.accept(down);
            }
        } else touchedBoundary = true;
    }

    private long landBelow(int x, int z, int fromY, double fromFeet) {
        for (int y = fromY; y >= Math.max(fromY - SCAN_DOWN, box.minY()); y--) {
            Stand s = stand(x, y, z);
            if (s != null) {
                boolean safe = fromFeet - s.feet() <= SAFE_DROP + EPS || s.mode() == Mode.SWIM;
                return safe ? pack(x, y, z) : Long.MIN_VALUE;
            }
            if (!bodyClear(cell(x, y, z))) return Long.MIN_VALUE;
        }
        return Long.MIN_VALUE;
    }

    private long horizontal(int x, int y, int z, double fromFeet, int nx, int nz) {
        int lowest = Math.max(y - SCAN_DOWN, box.minY());
        for (int ny = Math.min(y + 2, box.maxY()); ny >= lowest; ny--) {
            Stand b = stand(nx, ny, nz);
            if (b == null) {
                if (ny < y && !bodyClear(cell(nx, ny, nz))) break;
                continue;
            }
            double rise = b.feet() - fromFeet;
            if (rise > JUMP + EPS) continue;
            boolean ok;
            if (rise > STEP + EPS && !(rise <= 1.0 + EPS && cell(nx, ny - 1, nz).stair())) {
                ok = columnClear(x, z, y + 1, headTop(b.feet()));
            } else if (rise < -EPS) {
                ok = columnClear(nx, nz, ny + 1, headTop(fromFeet))
                    && (-rise <= SAFE_DROP + EPS || b.mode() == Mode.SWIM);
            } else ok = true;
            return ok ? pack(nx, ny, nz) : Long.MIN_VALUE;
        }
        return Long.MIN_VALUE;
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    static int unpackX(long p) { return (int) (p >> 38); }
    static int unpackZ(long p) { return (int) (p << 26 >> 38); }
    static int unpackY(long p) { return (int) (p << 52 >> 52); }
}
