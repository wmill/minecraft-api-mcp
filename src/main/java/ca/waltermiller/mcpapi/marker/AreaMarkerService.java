package ca.waltermiller.mcpapi.marker;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.decoration.Brightness;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread only. Labels exist solely on clients and are never spawned or saved in a world. */
public final class AreaMarkerService {
    private final TemporaryMarkers<Marker> markers = new TemporaryMarkers<>(Clock.systemUTC(), Marker::clear);

    public Map<String, Object> create(ServerWorld world, MarkerSpec spec, String player) {
        if (spec.bounds().min_y() < world.getBottomY() || spec.bounds().max_y() >= world.getTopYInclusive()) {
            throw new IllegalArgumentException("Marker bounds must leave room for the outline inside the world's height limits");
        }
        var entry = markers.add(spec.durationSeconds(), () -> new Marker(world, spec, player));
        return Map.of("success", true, "marker_id", entry.id(), "world", world.getRegistryKey().getValue().toString(),
            "bounds", spec.bounds(), "label", spec.label(), "color", spec.color(),
            "duration_seconds", spec.durationSeconds(), "expires_at", entry.expires().toString());
    }

    public Map<String, Object> remove(String id) {
        return Map.of("success", true, "marker_id", id, "removed", markers.remove(id));
    }

    public void tick(MinecraftServer server) {
        markers.expire();
        if (server.getTicks() % 10 == 0) {
            for (var entry : markers.entries()) entry.value().show(server.getTicks() % 40 == 0);
        }
    }

    public void clear() {
        markers.clear();
    }

    private static final class Marker {
        final ServerWorld world;
        final MarkerSpec spec;
        final String player;
        final DisplayEntity.TextDisplayEntity label;
        final Map<UUID, ServerPlayerEntity> viewers = new HashMap<>();
        final java.util.List<MarkerSpec.Point> points;
        final DustParticleEffect dust;

        Marker(ServerWorld world, MarkerSpec spec, String player) {
            this.world = world;
            this.spec = spec;
            this.player = player;
            points = spec.points(world.getTopYInclusive());
            dust = new DustParticleEffect(MarkerSpec.COLORS.get(spec.color()), 1.5f);
            label = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
            label.setPosition(spec.centerX(), Math.min(world.getTopYInclusive(), spec.bounds().max_y() + 2.0), spec.centerZ());
            label.setText(Text.literal(spec.label()).withColor(MarkerSpec.COLORS.get(spec.color())));
            label.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
            label.setBrightness(new Brightness(15, 15));
            label.setViewRange(2);
            label.setDisplayFlags((byte) (DisplayEntity.TextDisplayEntity.SHADOW_FLAG | DisplayEntity.TextDisplayEntity.SEE_THROUGH_FLAG));
        }

        void show(boolean refresh) {
            viewers.values().removeIf(viewer -> {
                if (!visible(viewer)) { hide(viewer); return true; }
                return false;
            });
            for (var viewer : world.getPlayers(this::visible)) {
                if (!spec.label().isEmpty() && (refresh || viewers.get(viewer.getUuid()) != viewer)) {
                    // Refresh also repairs labels cleared by respawn/dimension packets.
                    hide(viewer);
                    viewer.networkHandler.sendPacket(new EntitySpawnS2CPacket(label.getId(), label.getUuid(),
                        label.getX(), label.getY(), label.getZ(), 0, 0, EntityType.TEXT_DISPLAY, 0, Vec3d.ZERO, 0));
                    viewer.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(label.getId(), label.getDataTracker().getChangedEntries()));
                }
                viewers.put(viewer.getUuid(), viewer);
                for (var point : points) world.spawnParticles(viewer, dust, true, false,
                    point.x(), point.y(), point.z(), 1, 0, 0, 0, 0);
            }
        }

        boolean visible(ServerPlayerEntity viewer) {
            return !viewer.isDisconnected() && viewer.getWorld() == world
                && (player == null || viewer.getName().getString().equalsIgnoreCase(player))
                && viewer.squaredDistanceTo(spec.centerX(), ((double) spec.bounds().min_y() + spec.bounds().max_y()) / 2, spec.centerZ()) <= 128 * 128;
        }

        void hide(ServerPlayerEntity viewer) {
            if (!viewer.isDisconnected()) viewer.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(label.getId()));
        }

        void clear() { viewers.values().forEach(this::hide); viewers.clear(); }
    }
}
