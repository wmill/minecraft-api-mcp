package ca.waltermiller.mcpapi.survey;

import net.minecraft.block.PlantBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

/** Only used on the server thread; preflight never waits for or creates chunks. */
public final class WorldSurveyTerrain implements SiteSurvey.Terrain {
    private final ServerWorld world;

    public WorldSurveyTerrain(ServerWorld world) { this.world = world; }
    public int bottomY() { return world.getBottomY(); }
    public int topYInclusive() { return world.getTopYInclusive(); }
    public boolean ceiling() { return world.getDimension().hasCeiling(); }
    public boolean chunkLoaded(int x, int z) {
        return world.getChunkManager().getWorldChunk(x, z) != null;
    }
    public int surfaceY(int x, int z) { return world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z); }
    public SiteSurvey.Cell cell(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        var state = world.getBlockState(pos);
        var fluid = state.getFluidState();
        boolean collision = !state.getCollisionShape(world, pos).isEmpty();
        boolean vegetation = state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)
            || (!collision && (state.getBlock() instanceof PlantBlock
                || state.getBlock() instanceof SugarCaneBlock || state.getBlock() instanceof VineBlock));
        return new SiteSurvey.Cell(state.isAir(), vegetation, fluid.isIn(FluidTags.WATER), fluid.isIn(FluidTags.LAVA),
            collision, state.isSideSolidFullSquare(world, pos, Direction.UP));
    }
}
