package ca.waltermiller.mcpapi.preview;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BlockSink that records writes in memory without touching the world.
 * Reads return the recorded state if the position has been written; otherwise they
 * fall back to the underlying world so task logic that inspects existing terrain
 * (support fills, pane connections, torch attachment) continues to work.
 *
 * Insertion order is preserved for downstream consumers that care about task order.
 */
public final class RecordingBlockSink implements BlockSink {
    private final ServerWorld world;
    private final LinkedHashMap<BlockPos, BlockState> placed = new LinkedHashMap<>();

    public RecordingBlockSink(ServerWorld world) {
        this.world = world;
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
        placed.put(pos.toImmutable(), state);
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState recorded = placed.get(pos);
        if (recorded != null) {
            return recorded;
        }
        return world.getBlockState(pos);
    }

    @Override
    public ServerWorld world() {
        return world;
    }

    /**
     * Ordered view of all blocks the sink has recorded. The returned map is
     * unmodifiable; the underlying data is not copied, so do not mutate the sink
     * while iterating.
     */
    public Map<BlockPos, BlockState> placedBlocks() {
        return Collections.unmodifiableMap(placed);
    }
}
