package ca.waltermiller.mcpapi.preview;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Sparse voxel container built from RecordingBlockSink.placedBlocks(). Stores
 * block identifier strings (e.g. "minecraft:stone") keyed by BlockPos, along
 * with the enclosing bounding box.
 */
public final class BlockGrid {
    private final Map<BlockPos, String> cells;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    public BlockGrid(Map<BlockPos, String> cells, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.cells = cells;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static BlockGrid from(Map<BlockPos, BlockState> placed) {
        if (placed.isEmpty()) {
            return new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<BlockPos, String> cells = new HashMap<>(placed.size());

        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            BlockPos pos = entry.getKey();
            String id = Registries.BLOCK.getId(entry.getValue().getBlock()).toString();
            if (isAir(id)) continue;
            cells.put(pos, id);
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getY() > maxY) maxY = pos.getY();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }
        if (cells.isEmpty()) {
            return new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        }
        return new BlockGrid(cells, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public String blockAt(int x, int y, int z) {
        return cells.get(new BlockPos(x, y, z));
    }

    public boolean isAir(int x, int y, int z) {
        String id = blockAt(x, y, z);
        return id == null || isAir(id);
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }

    private static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }
}
