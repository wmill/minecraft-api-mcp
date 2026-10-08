package ca.waltermiller.mcpapi.snapshot;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** World-facing snapshot helpers. Every method must run on the server thread. */
public final class RegionSnapshot {
    /** Restores block updates to listeners without dropping items from replaced blocks. */
    private static final int RESTORE_FLAGS = Block.NOTIFY_LISTENERS | Block.SKIP_DROPS;

    private RegionSnapshot() {}

    /** Blocks, air, and block entities in the inclusive bounds; entities are not captured. */
    public static NbtCompound capture(ServerWorld world, AreaBounds bounds) {
        StructureTemplate template = new StructureTemplate();
        template.saveFromWorld(world, min(bounds), size(bounds), false, List.of());
        return template.writeNbt(new NbtCompound());
    }

    /** Places a snapshot back at its minimum corner without rotation. */
    public static boolean restore(ServerWorld world, StructureTemplate snapshot, AreaBounds bounds) {
        BlockPos origin = min(bounds);
        return snapshot.place(world, origin, origin, new StructurePlacementData(), Random.create(), RESTORE_FLAGS);
    }

    /** Chunk coordinates in the bounds that are not loaded; never loads or generates chunks. */
    public static List<Map<String, Integer>> missingChunks(ServerWorld world, AreaBounds bounds) {
        List<Map<String, Integer>> missing = new ArrayList<>();
        for (int cx = bounds.min_x() >> 4; cx <= bounds.max_x() >> 4; cx++) {
            for (int cz = bounds.min_z() >> 4; cz <= bounds.max_z() >> 4; cz++) {
                if (world.getChunkManager().getWorldChunk(cx, cz) == null) missing.add(Map.of("x", cx, "z", cz));
            }
        }
        return missing;
    }

    /**
     * Compares the template's first palette, as placed at {@code pos}, with the world. Mirrors
     * StructureTemplate.place's position transform; processors and random palettes are ignored.
     */
    public static OverwriteTally.Report scan(ServerWorld world, NbtCompound templateNbt, BlockPos pos,
                                             StructurePlacementData placement) {
        NbtList palette = templateNbt.getList("palettes")
            .map(palettes -> palettes.getListOrEmpty(0))
            .orElseGet(() -> templateNbt.getListOrEmpty("palette"));
        List<String> names = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) names.add(palette.getCompoundOrEmpty(i).getString("Name", "minecraft:air"));

        OverwriteTally tally = new OverwriteTally();
        templateNbt.getListOrEmpty("blocks").streamCompounds().forEach(entry -> {
            int index = entry.getInt("state", 0);
            String incoming = index >= 0 && index < names.size() ? names.get(index) : "minecraft:air";
            if ("minecraft:structure_void".equals(incoming)) return;
            NbtList local = entry.getListOrEmpty("pos");
            BlockPos target = StructureTemplate.transform(placement,
                new BlockPos(local.getInt(0, 0), local.getInt(1, 0), local.getInt(2, 0))).add(pos);
            BlockState existing = world.getBlockState(target);
            tally.record(Registries.BLOCK.getId(existing.getBlock()).toString(), categorize(world, target, existing),
                incoming, OverwriteTally.isAirId(incoming));
        });
        return tally.report();
    }

    static OverwriteTally.Category categorize(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isAir()) return OverwriteTally.Category.AIR;
        if (state.hasBlockEntity()) return OverwriteTally.Category.BLOCK_ENTITIES;
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) return OverwriteTally.Category.LOGS_LEAVES;
        if (state.getBlock() instanceof FluidBlock) return OverwriteTally.Category.FLUIDS;
        Block block = state.getBlock();
        if (state.getCollisionShape(world, pos).isEmpty()
                && (block instanceof PlantBlock || block instanceof SugarCaneBlock || block instanceof VineBlock
                    || state.isReplaceable())) {
            return OverwriteTally.Category.VEGETATION;
        }
        if (state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER) || state.isIn(BlockTags.TERRACOTTA)
                || state.isIn(BlockTags.SNOW) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY)) {
            return OverwriteTally.Category.TERRAIN;
        }
        return OverwriteTally.Category.OTHER;
    }

    private static BlockPos min(AreaBounds b) {
        return new BlockPos(b.min_x(), b.min_y(), b.min_z());
    }

    private static Vec3i size(AreaBounds b) {
        return new Vec3i(b.max_x() - b.min_x() + 1, b.max_y() - b.min_y() + 1, b.max_z() - b.min_z() + 1);
    }
}
