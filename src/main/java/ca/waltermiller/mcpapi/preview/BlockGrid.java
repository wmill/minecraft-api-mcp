package ca.waltermiller.mcpapi.preview;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

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
        return from(placed, (Function<BlockPos, BlockState>) null, 0);
    }

    public static BlockGrid from(Map<BlockPos, BlockState> placed, ServerWorld world, int terrainMargin) {
        return from(placed, terrainMargin > 0 && world != null ? world::getBlockState : null, terrainMargin);
    }

    static BlockGrid from(Map<BlockPos, BlockState> placed, Function<BlockPos, BlockState> terrainLookup, int terrainMargin) {
        Map<BlockPos, String> placedIds = new LinkedHashMap<>(placed.size());
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            String id = Registries.BLOCK.getId(entry.getValue().getBlock()).toString();
            if (!isAir(id)) {
                placedIds.put(entry.getKey(), id);
            }
        }

        Function<BlockPos, String> terrainIdLookup = terrainLookup == null
                ? null
                : pos -> {
                    BlockState state = terrainLookup.apply(pos);
                    return state == null ? null : Registries.BLOCK.getId(state.getBlock()).toString();
                };
        return fromIds(placedIds, terrainIdLookup, terrainMargin);
    }

    static BlockGrid fromIds(Map<BlockPos, String> placed, Function<BlockPos, String> terrainLookup, int terrainMargin) {
        if (placed.isEmpty()) {
            return new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        }

        Map<BlockPos, String> cells = terrainMargin > 0 && terrainLookup != null
                ? sampleTerrain(placed, terrainLookup, terrainMargin)
                : new LinkedHashMap<>(placed.size());

        for (Map.Entry<BlockPos, String> entry : placed.entrySet()) {
            if (isAir(entry.getValue())) continue;
            cells.put(entry.getKey(), entry.getValue());
        }

        if (cells.isEmpty()) {
            return new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        }

        return fromResolvedCells(cells);
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

    private static BlockGrid fromResolvedCells(Map<BlockPos, String> cells) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : cells.keySet()) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getY() > maxY) maxY = pos.getY();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }

        return new BlockGrid(new HashMap<>(cells), minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Map<BlockPos, String> sampleTerrain(
            Map<BlockPos, String> placed,
            Function<BlockPos, String> terrainLookup,
            int terrainMargin) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : placed.keySet()) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getY() > maxY) maxY = pos.getY();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }

        Map<BlockPos, String> cells = new LinkedHashMap<>();
        for (int x = minX - terrainMargin; x <= maxX + terrainMargin; x++) {
            for (int y = minY - terrainMargin; y <= maxY + terrainMargin; y++) {
                for (int z = minZ - terrainMargin; z <= maxZ + terrainMargin; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    String id = terrainLookup.apply(pos);
                    if (!isAir(id)) {
                        cells.put(pos, id);
                    }
                }
            }
        }
        return cells;
    }

    private static boolean isAir(String id) {
        return id == null
                || id.equals("minecraft:air")
                || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air");
    }
}
