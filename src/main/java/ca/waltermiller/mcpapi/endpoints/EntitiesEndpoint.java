package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.operation.OperationHttp;
import ca.waltermiller.mcpapi.operation.OperationRegistry;

import io.javalin.Javalin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;


public class EntitiesEndpoint extends APIEndpoint {
    private static final int TIMEOUT_SECONDS = 5;

    public EntitiesEndpoint(Javalin app, MinecraftServer server, Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        app.get("/api/world/entities", ctx -> {
            var entityInfos = Registries.ENTITY_TYPE.stream()
                    .map(type -> new EntityInfo(
                            Registries.ENTITY_TYPE.getId(type).toString(),
                            type.getUntranslatedName()))
                    .toList();
            ctx.json(entityInfos);
        });
        app.post("/api/world/entities/spawn", ctx -> {
            EntitySpawnRequest req = ctx.bodyAsClass(EntitySpawnRequest.class);

            int total;
            try {
                total = EntitySpawnSpec.validate(req);
                EntityCustomizer.checkIds(req.type, req.options);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
                return;
            }

            RegistryKey<World> worldKey = WorldResolver.resolveWorldKey(req.world);
            ServerWorld world = worldKey != null ? server.getWorld(worldKey) : null;
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + req.world));
                return;
            }

            EntityType<?> entityType = Registries.ENTITY_TYPE.get(EntityCustomizer.entityId(req.type));
            int count = req.count != null ? req.count : 1;
            LOGGER.info("Spawning {} x {} ({} entities incl. passengers) at ({}, {}, {}) in world {}",
                    count, req.type, total, req.x, req.y, req.z, worldKey.getValue());

            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            OperationHttp.dispatch(server::execute, () -> {
                List<Entity> spawned = new ArrayList<>();
                try {
                    Set<String> ignored = new LinkedHashSet<>();
                    List<double[]> offsets = EntitySpawnSpec.scatterOffsets(count, EntitySpawnSpec.spread(req), new Random());
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (double[] offset : offsets) {
                        double x = req.x + 0.5 + offset[0];
                        double z = req.z + 0.5 + offset[1];
                        Entity root = createWithPassengers(world, entityType, req.options, x, req.y, z, ignored);
                        if (!world.spawnNewEntityAndPassengers(root)) {
                            throw new IllegalStateException("Failed to spawn " + req.type + " in world");
                        }
                        spawned.add(root);
                        results.add(describe(root));
                    }
                    Entity first = spawned.getFirst();
                    LOGGER.info("Spawned {} entities, first {} with UUID {}", spawned.size(), req.type, first.getUuid());
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("type", req.type);
                    response.put("uuid", first.getUuid().toString());
                    response.put("position", position(first));
                    response.put("entities", results);
                    response.put("ignored", List.copyOf(ignored));
                    future.complete(response);
                } catch (Exception e) {
                    spawned.forEach(root -> root.streamSelfAndPassengers().forEach(Entity::discard));
                    future.completeExceptionally(e);
                }
            }, future);

            respond(ctx, future, TIMEOUT_SECONDS, "entity spawn",
                result -> !result.containsKey("error"),
                result -> (String) result.get("error"),
                result -> result);
        });
    }

    private static Entity createWithPassengers(ServerWorld world, EntityType<?> type, SpawnOptions options,
                                               double x, double y, double z, Set<String> ignored) {
        Entity entity = create(world, type, options, BlockPos.ofFloored(x, y, z), ignored);
        if (entity == null) {
            throw new IllegalStateException("Failed to create entity: " + Registries.ENTITY_TYPE.getId(type));
        }
        entity.setPosition(x, y, z);
        if (options != null && options.passengers != null) {
            for (PassengerSpec spec : options.passengers) {
                EntityType<?> passengerType = Registries.ENTITY_TYPE.get(EntityCustomizer.entityId(spec.type));
                Entity passenger = createWithPassengers(world, passengerType, spec.options, x, y, z, ignored);
                if (!passenger.startRiding(entity, true)) {
                    ignored.add("passenger " + spec.type + " (could not ride " + Registries.ENTITY_TYPE.getId(type) + ")");
                }
            }
        }
        return entity;
    }

    private static <T extends Entity> T create(ServerWorld world, EntityType<T> type, SpawnOptions options,
                                               BlockPos pos, Set<String> ignored) {
        return type.create(world, EntityCustomizer.of(options, world, ignored), pos, SpawnReason.COMMAND, false, false);
    }

    private static Map<String, Object> describe(Entity entity) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
        info.put("uuid", entity.getUuid().toString());
        info.put("position", position(entity));
        if (entity.hasPassengers()) {
            info.put("passengers", entity.getPassengerList().stream().map(EntitiesEndpoint::describe).toList());
        }
        return info;
    }

    private static Map<String, Object> position(Entity entity) {
        return Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ());
    }

}

record EntityInfo(String id, String display_name) {
}
