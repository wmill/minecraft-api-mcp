package ca.waltermiller.mcpapi.access;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Doors;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Pos;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Vanilla land pathfinding with a villager that is never added to the world. Bypasses EntityNavigation
 * (which needs the mob on the ground in the world and moves the target) and calls the A* navigator directly,
 * over a ChunkCache of already-loaded chunks. Server thread only.
 */
public final class VanillaAccessProbe implements MobProbe {
    private final ServerWorld world;
    private final Box box;
    private final Doors doors;

    public VanillaAccessProbe(ServerWorld world, Box box, Doors doors) {
        this.world = world;
        this.box = box;
        this.doors = doors;
    }

    @Override
    public Result find(Pos start, double startFeet, Pos target, int maxNodes) {
        var mob = EntityType.VILLAGER.create(world, SpawnReason.COMMAND);
        if (mob == null) return null;
        try {
            mob.refreshPositionAndAngles(start.x() + 0.5, startFeet, start.z() + 0.5, 0, 0);
            mob.setOnGround(true);
            var maker = new LandPathNodeMaker();
            maker.setCanEnterOpenDoors(true);
            maker.setCanOpenDoors(doors != Doors.NONE);
            maker.setCanSwim(true);
            var navigator = new PathNodeNavigator(maker, maxNodes);
            var cache = new ChunkCache(world, new BlockPos(box.minX(), box.minY(), box.minZ()),
                new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
            double dx = box.maxX() - box.minX(), dy = box.maxY() - box.minY(), dz = box.maxZ() - box.minZ();
            float followRange = (float) Math.max(64, 4 * Math.sqrt(dx * dx + dy * dy + dz * dz));
            BlockPos goal = new BlockPos(target.x(), target.y(), target.z());
            Path path = navigator.findPathToAny(cache, mob, Set.of(goal), followRange, 0, 1.0f);
            if (path == null || path.getLength() == 0) return new Result(false, null, List.of());
            List<Pos> nodes = new ArrayList<>(path.getLength());
            for (int i = 0; i < path.getLength(); i++) {
                BlockPos p = path.getNodePos(i);
                nodes.add(new Pos(p.getX(), p.getY(), p.getZ()));
            }
            Pos end = nodes.get(nodes.size() - 1);
            return new Result(path.reachesTarget() && end.equals(target), end, nodes);
        } finally {
            mob.discard();
        }
    }
}
