package ca.waltermiller.mcpapi.terrain;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.endpoints.StructurePlacementEffect;
import ca.waltermiller.mcpapi.preview.SurfaceCategory;
import ca.waltermiller.mcpapi.snapshot.RegionSnapshot;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BambooShootBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Server-thread world access for terrain smoothing. */
public final class WorldTerrainAccess implements TerrainAccess {
    /** Deepest walk through vegetation before a column is treated as unknown (giant trees are ~30). */
    static final int MAX_VEGETATION_DEPTH = 48;
    private static final int VEGETATION_FLAGS = Block.NOTIFY_ALL | Block.SKIP_DROPS;
    private static final int TERRAIN_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    private static final Set<String> ICE = Set.of("ice", "packed_ice", "blue_ice", "frosted_ice");
    private static final Set<SurfaceCategory> NATURAL = Set.of(
        SurfaceCategory.GRASS_DIRT, SurfaceCategory.SAND_GRAVEL, SurfaceCategory.STONE, SurfaceCategory.SNOW_ICE);

    private final ServerWorld world;
    private final Map<String, BlockState> states = new HashMap<>();

    public WorldTerrainAccess(ServerWorld world) { this.world = world; }

    public boolean chunkLoaded(int chunkX, int chunkZ) {
        return world.getChunkManager().getWorldChunk(chunkX, chunkZ) != null;
    }

    public GroundColumn sample(int x, int z) {
        if (!chunkLoaded(x >> 4, z >> 4)) return GroundColumn.UNLOADED_COLUMN;
        int surface = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        BlockPos.Mutable pos = new BlockPos.Mutable(x, surface - 1, z);
        int y = surface;
        while (y > world.getBottomY() + 1 && isVegetation(world.getBlockState(pos.setY(y - 1)))) {
            if (surface - y >= MAX_VEGETATION_DEPTH) {
                return new GroundColumn(GroundColumn.Kind.BUILT, surface, surface, null, null);
            }
            y--;
        }
        BlockState topState = world.getBlockState(pos.setY(y - 1));
        String top = id(topState);
        String name = top.substring(top.indexOf(':') + 1);
        SurfaceCategory category = SurfaceCategory.classify(top);
        if (!topState.getFluidState().isEmpty() || ICE.contains(name)
                || category == SurfaceCategory.WATER || category == SurfaceCategory.LAVA) {
            return new GroundColumn(GroundColumn.Kind.FLUID, y, surface, top, null);
        }
        if (!NATURAL.contains(category)) return new GroundColumn(GroundColumn.Kind.BUILT, y, surface, top, null);
        return new GroundColumn(GroundColumn.Kind.GROUND, y, surface, top, fillFor(top, id(world.getBlockState(pos.setY(y - 2)))));
    }

    /** The block below the top when it is natural ground, otherwise a sensible stand-in. */
    static String fillFor(String top, String below) {
        String b = below.substring(below.indexOf(':') + 1);
        if (NATURAL.contains(SurfaceCategory.classify(below)) && !ICE.contains(b)) {
            if (b.endsWith("_ore")) return b.startsWith("deepslate_") ? "minecraft:deepslate" : "minecraft:stone";
            if (!b.equals("grass_block") && !b.equals("podzol") && !b.equals("mycelium") && !b.startsWith("snow")) {
                return below;
            }
        }
        String t = top.substring(top.indexOf(':') + 1);
        return switch (t) {
            case "grass_block", "podzol", "mycelium", "moss_block", "rooted_dirt", "coarse_dirt", "mud",
                 "snow_block", "powder_snow" -> "minecraft:dirt";
            default -> top;
        };
    }

    /** Plants, trees and snow layers sitting on the ground; never fluids or player-shaped wood. */
    static boolean isVegetation(BlockState state) {
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isAir()) return true;
        Block block = state.getBlock();
        String id = Registries.BLOCK.getId(block).getPath();
        if (id.startsWith("stripped_")) return false;
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) return true;
        if (block instanceof PlantBlock || block instanceof SugarCaneBlock || block instanceof VineBlock
                || block instanceof SnowBlock || block instanceof CactusBlock || block instanceof BambooBlock
                || block instanceof BambooShootBlock || block instanceof CocoaBlock) {
            return true;
        }
        return state.isReplaceable() || SurfaceCategory.classify(id) == SurfaceCategory.TREES;
    }

    public void clearVegetation(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.AIR.getDefaultState(), VEGETATION_FLAGS);
    }

    public void setTerrain(int x, int y, int z, String blockId) {
        BlockState state = blockId == null ? Blocks.AIR.getDefaultState()
            : states.computeIfAbsent(blockId, id -> {
                Identifier parsed = Identifier.tryParse(id);
                return parsed == null ? Blocks.DIRT.getDefaultState() : Registries.BLOCK.get(parsed).getDefaultState();
            });
        world.setBlockState(new BlockPos(x, y, z), state, TERRAIN_FLAGS);
    }

    public NbtCompound capture(AreaBounds bounds) {
        return RegionSnapshot.capture(world, bounds);
    }

    public void placed(AreaBounds bounds) {
        StructurePlacementEffect.emit(world, bounds);
    }

    private static String id(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }
}
