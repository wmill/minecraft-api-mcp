package ca.waltermiller.mcpapi.preview;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts sampled heightmap data into a sparse surface-only voxel grid for the
 * existing isometric preview renderer.
 */
public final class TerrainHeightmapGridAdapter {

    static final String DEFAULT_TERRAIN_BLOCK_ID = "minecraft:grass_block";

    private TerrainHeightmapGridAdapter() {}

    public static BlockGrid fromHeights(int[][] heights) {
        return fromHeights(heights, DEFAULT_TERRAIN_BLOCK_ID);
    }

    static BlockGrid fromHeights(int[][] heights, String blockId) {
        if (heights == null || heights.length == 0) {
            return new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        }

        Map<BlockPos, String> cells = new LinkedHashMap<>();
        int sizeX = heights.length;
        int sizeZ = 0;

        for (int x = 0; x < sizeX; x++) {
            int[] column = heights[x];
            if (column == null || column.length == 0) {
                continue;
            }
            sizeZ = Math.max(sizeZ, column.length);
            for (int z = 0; z < column.length; z++) {
                cells.put(new BlockPos(x, column[z], z), blockId);
            }
        }

        if (cells.isEmpty() || sizeZ == 0) {
            return new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        }

        int minY = cells.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxY = cells.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0);
        return new BlockGrid(cells, 0, minY, 0, sizeX - 1, maxY, sizeZ - 1);
    }
}
