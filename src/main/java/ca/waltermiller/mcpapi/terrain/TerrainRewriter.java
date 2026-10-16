package ca.waltermiller.mcpapi.terrain;

import ca.waltermiller.mcpapi.arealock.AreaBounds;

/** Applies a planned heightfield to the world, column by column. */
public final class TerrainRewriter {
    private TerrainRewriter() {}

    public record Written(int vegetationColumns, long vegetationBlocks) {}

    /**
     * Every block a rewrite may touch: from one below the lower of the old and new ground
     * (the buried or re-surfaced top block) up to the highest vegetation or new ground.
     */
    public static AreaBounds bounds(int minX, int minZ, GroundColumn[][] columns, int[][] planned) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = 0; x < columns.length; x++) {
            for (int z = 0; z < columns[x].length; z++) {
                GroundColumn c = columns[x][z];
                lo = Math.min(lo, Math.min(c.ground(), planned[x][z]) - 1);
                hi = Math.max(hi, Math.max(c.surface(), planned[x][z]) - 1);
            }
        }
        return new AreaBounds(minX, lo, minZ, minX + columns.length - 1, Math.max(lo, hi),
            minZ + columns[0].length - 1);
    }

    /** Clears vegetation everywhere first, then reshapes; masked columns are never reshaped. */
    public static Written apply(TerrainAccess world, int minX, int minZ, GroundColumn[][] columns, int[][] planned) {
        int vegetationColumns = 0;
        long vegetationBlocks = 0;
        for (int x = 0; x < columns.length; x++) {
            for (int z = 0; z < columns[x].length; z++) {
                GroundColumn c = columns[x][z];
                if (!c.clearsVegetation() || c.surface() <= c.ground()) continue;
                vegetationColumns++;
                for (int y = c.surface() - 1; y >= c.ground(); y--) {
                    world.clearVegetation(minX + x, y, minZ + z);
                    vegetationBlocks++;
                }
            }
        }
        for (int x = 0; x < columns.length; x++) {
            for (int z = 0; z < columns[x].length; z++) {
                GroundColumn c = columns[x][z];
                if (!c.masked()) reshape(world, minX + x, minZ + z, c, planned[x][z]);
            }
        }
        return new Written(vegetationColumns, vegetationBlocks);
    }

    static void reshape(TerrainAccess world, int x, int z, GroundColumn c, int newGround) {
        int old = c.ground();
        if (newGround == old) return;
        if (newGround < old) {
            for (int y = old - 1; y >= newGround; y--) world.setTerrain(x, y, z, null);
        } else {
            for (int y = old - 1; y < newGround - 1; y++) world.setTerrain(x, y, z, c.fill());
        }
        world.setTerrain(x, newGround - 1, z, c.top());
    }
}
