package ca.waltermiller.mcpapi.access;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.IdentityHashMap;
import java.util.Map;

/** Server-thread adapter; never loads or generates chunks (callers check {@link #chunkLoaded} first). */
public final class WorldAccessTerrain implements AccessTerrain {
    private final ServerWorld world;
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final Map<BlockState, AccessCell> byState = new IdentityHashMap<>();

    public WorldAccessTerrain(ServerWorld world) { this.world = world; }

    public ServerWorld world() { return world; }
    public int bottomY() { return world.getBottomY(); }
    public int topYInclusive() { return world.getTopYInclusive(); }
    public boolean chunkLoaded(int chunkX, int chunkZ) { return world.getChunkManager().getWorldChunk(chunkX, chunkZ) != null; }
    public int skyY(int x, int z) { return world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z); }

    public AccessCell cell(int x, int y, int z) {
        cursor.set(x, y, z);
        BlockState state = world.getBlockState(cursor);
        AccessCell cached = byState.get(state);
        if (cached != null) return cached;
        AccessCell cell = describe(state);
        byState.put(state, cell);
        return cell;
    }

    private AccessCell describe(BlockState state) {
        var shape = state.getCollisionShape(world, cursor);
        boolean solid = !shape.isEmpty();
        double top = solid ? shape.getMax(Direction.Axis.Y) : 0;
        var fluid = state.getFluidState();
        boolean water = fluid.isIn(FluidTags.WATER) && !solid;
        boolean lava = fluid.isIn(FluidTags.LAVA);
        boolean hazard = state.isIn(BlockTags.FIRE)
            || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH)
            || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.WITHER_ROSE)
            || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL) || state.isOf(Blocks.END_GATEWAY)
            || (state.isIn(BlockTags.CAMPFIRES) && state.contains(CampfireBlock.LIT) && state.get(CampfireBlock.LIT));
        AccessCell.Openable openable = AccessCell.Openable.NONE;
        boolean open = false;
        if (state.getBlock() instanceof DoorBlock door) {
            openable = door.getBlockSetType().canOpenByHand() ? AccessCell.Openable.WOOD : AccessCell.Openable.IRON;
            open = state.get(DoorBlock.OPEN);
        } else if (state.getBlock() instanceof TrapdoorBlock) {
            openable = state.isOf(Blocks.IRON_TRAPDOOR) ? AccessCell.Openable.IRON : AccessCell.Openable.WOOD;
            open = state.get(TrapdoorBlock.OPEN);
        } else if (state.getBlock() instanceof FenceGateBlock) {
            openable = AccessCell.Openable.WOOD;
            open = state.get(FenceGateBlock.OPEN);
        }
        return new AccessCell(solid, top, state.isIn(BlockTags.CLIMBABLE), water, lava, hazard,
            state.isIn(BlockTags.STAIRS), openable, open);
    }
}
