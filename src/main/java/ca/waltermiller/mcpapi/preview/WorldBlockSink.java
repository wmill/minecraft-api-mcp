package ca.waltermiller.mcpapi.preview;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Pass-through BlockSink that applies writes directly to a ServerWorld.
 * This preserves current task execution behavior.
 */
public final class WorldBlockSink implements BlockSink {
    private final ServerWorld world;

    public WorldBlockSink(ServerWorld world) {
        this.world = world;
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
        return world.setBlockState(pos, state, flags);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return world.getBlockState(pos);
    }

    @Override
    public ServerWorld world() {
        return world;
    }
}
