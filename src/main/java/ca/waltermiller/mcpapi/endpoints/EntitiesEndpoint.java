package ca.waltermiller.mcpapi.endpoints;

import io.javalin.Javalin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.Map;


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
        
            Identifier entityId = Identifier.tryParse(req.type);
            if (entityId == null) {
                ctx.status(400).json(Map.of("error", "Invalid entity type format: " + req.type));
                return;
            }

            RegistryKey<World> worldKey = WorldResolver.resolveWorldKey(req.world);

            ServerWorld world = worldKey != null ? server.getWorld(worldKey) : null;
        
            EntityType<?> entityType = Registries.ENTITY_TYPE.get(entityId);
        
            if (entityType == null) {
                ctx.status(400).json(Map.of("error", "Unknown entity type: " + req.type));
                return;
            }
        
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }

            LOGGER.info("Spawning an entity of type {} at position ({}, {}, {}) in world {}",
                    req.type, req.x, req.y, req.z, worldKey.getValue());
        
            // Create a future to handle the async response
            java.util.concurrent.CompletableFuture<Map<String, Object>> future = new java.util.concurrent.CompletableFuture<>();
            
            // Ensure this runs on the server thread
            server.execute(() -> {
                try {
                    BlockPos pos = new BlockPos((int) req.x, (int) req.y, (int) req.z);
                    Entity entity = entityType.create(world, null, pos, SpawnReason.COMMAND, false, false);
                    if (entity == null) {
                        future.complete(Map.of("error", "Failed to create entity: " + req.type));
                        return;
                    }
            
                    entity.setPosition(req.x + 0.5, req.y, req.z + 0.5);
                    
                    if (world.spawnEntity(entity)) {
                        LOGGER.info("Spawned entity {} with UUID {} at position ({}, {}, {})",
                                req.type, entity.getUuid(), entity.getX(), entity.getY(), entity.getZ());
                        future.complete(Map.of(
                            "success", true,
                            "type", req.type,
                            "uuid", entity.getUuid().toString(),
                            "position", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ())
                        ));
                    } else {
                        future.complete(Map.of("error", "Failed to spawn entity in world"));
                    }
                } catch (Exception e) {
                    future.complete(Map.of("error", "Exception during entity spawn: " + e.getMessage()));
                }
            });
            
            respond(ctx, future, TIMEOUT_SECONDS, "entity spawn",
                result -> !result.containsKey("error"),
                result -> (String) result.get("error"),
                result -> result);
        });
        
    }

}

record EntityInfo(String id, String display_name) {
}

class EntitySpawnRequest {
    public String type;
    public String world; // optional; default to "minecraft:overworld"
    public double x;
    public double y;
    public double z;
}