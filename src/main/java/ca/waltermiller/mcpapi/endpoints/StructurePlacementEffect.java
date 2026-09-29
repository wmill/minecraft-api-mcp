package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/** A bounded, single construction burst. Call on the server thread after placement. */
final class StructurePlacementEffect {
    private static final int MAX_POINTS = 32;
    private static final double EDGE_OFFSET = 0.15;

    private StructurePlacementEffect() {}

    record Point(double x, double y, double z) {}

    static void emit(ServerWorld world, AreaBounds bounds) {
        for (Point point : particlePoints(bounds)) {
            world.spawnParticles(ParticleTypes.SMOKE, point.x(), point.y(), point.z(),
                3, 0.1, 0.1, 0.1, 0.01);
        }
        world.playSound(null, ((double) bounds.min_x() + bounds.max_x() + 1) / 2,
            bounds.min_y(), ((double) bounds.min_z() + bounds.max_z() + 1) / 2,
            SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.6f, 1.0f);
    }

    static List<Point> particlePoints(AreaBounds bounds) {
        // Bounds are inclusive block coordinates; expand the physical footprint slightly.
        double left = bounds.min_x() - EDGE_OFFSET;
        double north = bounds.min_z() - EDGE_OFFSET;
        double width = (double) bounds.max_x() + 1 + EDGE_OFFSET - left;
        double depth = (double) bounds.max_z() + 1 + EDGE_OFFSET - north;
        double perimeter = 2 * (width + depth);
        int count = (int) Math.min(MAX_POINTS, Math.ceil(perimeter / 2));
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double distance = (i + 0.5) * perimeter / count;
            double x;
            double z;
            if (distance < width) {
                x = left + distance;
                z = north;
            } else if (distance < width + depth) {
                x = left + width;
                z = north + distance - width;
            } else if (distance < 2 * width + depth) {
                x = left + 2 * width + depth - distance;
                z = north + depth;
            } else {
                x = left;
                z = north + perimeter - distance;
            }
            points.add(new Point(x, bounds.min_y() + 0.5, z));
        }
        return points;
    }
}
