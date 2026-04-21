package ca.waltermiller.mcpapi.preview;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Abstraction over block writes so task execution can be redirected from the live world
 * to an in-memory recorder (for dry-run previews). Reads delegate to the underlying
 * world but, for recording sinks, pending writes shadow the world so later tasks see
 * earlier tasks' placements.
 */
public interface BlockSink {
    boolean setBlockState(BlockPos pos, BlockState state, int flags);

    default boolean setBlockState(BlockPos pos, BlockState state) {
        return setBlockState(pos, state, Block.NOTIFY_ALL);
    }

    BlockState getBlockState(BlockPos pos);

    /**
     * The underlying ServerWorld. Used for operations that need world context
     * (block entities, solid-block queries). Recording sinks still return the real
     * world so read-only world queries continue to work; pending writes are not
     * visible to those queries.
     */
    ServerWorld world();
}
