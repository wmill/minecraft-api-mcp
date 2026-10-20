package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-only placeholders: the authoritative placement and its snapshots are never delayed. */
public final class StructureReveal {
    private static final int MAX_BLOCKS = 8192;
    private static final long MAX_VOLUME = 131072;
    private static final int DURATION = 40;
    private static final List<Reveal> ACTIVE = new ArrayList<>();

    private StructureReveal() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var iterator = ACTIVE.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().tick()) iterator.remove();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.clear());
    }

    /** Call on the server thread, after a successful NBT placement. */
    public static void start(ServerWorld world, AreaBounds bounds) {
        // Finish earlier overlays before overlapping placements can mask each other.
        ACTIVE.removeIf(reveal -> {
            if (reveal.world == world) {
                reveal.finish();
                return true;
            }
            return false;
        });
        long width = (long) bounds.max_x() - bounds.min_x() + 1;
        long height = (long) bounds.max_y() - bounds.min_y() + 1;
        long depth = (long) bounds.max_z() - bounds.min_z() + 1;
        if (width > MAX_VOLUME || height > MAX_VOLUME || depth > MAX_VOLUME
            || width * height > MAX_VOLUME || width * height * depth > MAX_VOLUME) return;
        List<ServerPlayerEntity> viewers = world.getPlayers(player ->
            player.squaredDistanceTo(bounds.min_x() + width / 2.0, bounds.min_y() + height / 2.0,
                bounds.min_z() + depth / 2.0) <= 96 * 96);
        if (viewers.isEmpty()) return;
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(bounds.min_x(), bounds.min_y(), bounds.min_z(),
            bounds.max_x(), bounds.max_y(), bounds.max_z())) {
            if (!world.isChunkLoaded(pos)) return;
            if (!world.getBlockState(pos).isAir()) {
                blocks.add(pos.toImmutable());
                if (blocks.size() > MAX_BLOCKS) return;
            }
        }
        blocks.sort(Comparator.comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        if (!blocks.isEmpty()) ACTIVE.add(new Reveal(world, List.copyOf(viewers), blocks));
    }

    static int revealedCount(int elapsed, int total) {
        return (int) ((long) Math.clamp(elapsed, 0, DURATION) * total / DURATION);
    }

    private static final class Reveal {
        final ServerWorld world;
        final List<ServerPlayerEntity> viewers;
        final List<BlockPos> blocks;
        int elapsed = -2; // Let placement/chunk packets settle before sending the overlay.
        int revealed;

        Reveal(ServerWorld world, List<ServerPlayerEntity> viewers, List<BlockPos> blocks) {
            this.world = world;
            this.viewers = viewers;
            this.blocks = blocks;
        }

        boolean tick() {
            if (++elapsed < 0) return false;
            if (elapsed == 0) {
                for (BlockPos pos : blocks) {
                    var packet = new BlockUpdateS2CPacket(pos, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
                    for (var viewer : viewers) {
                        if (viewer.getWorld() == world && !viewer.isDisconnected()) viewer.networkHandler.sendPacket(packet);
                    }
                }
            }
            restoreUntil(revealedCount(elapsed, blocks.size()));
            return elapsed >= DURATION;
        }

        void finish() {
            restoreUntil(blocks.size());
        }

        void restoreUntil(int count) {
            while (revealed < count) {
                BlockPos pos = blocks.get(revealed++);
                // Never reload a chunk for a cosmetic effect. Unloaded chunks are resent on return.
                if (!world.isChunkLoaded(pos)) continue;
                var packet = new BlockUpdateS2CPacket(pos, world.getBlockState(pos));
                var entity = world.getBlockEntity(pos);
                var entityPacket = entity == null ? null : entity.toUpdatePacket();
                for (var viewer : viewers) {
                    if (viewer.getWorld() != world || viewer.isDisconnected()) continue;
                    viewer.networkHandler.sendPacket(packet);
                    if (entityPacket != null) viewer.networkHandler.sendPacket(entityPacket);
                }
            }
        }
    }
}
