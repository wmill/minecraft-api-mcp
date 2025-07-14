package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;


public class EntitiesEndpoint extends APIEndpoint {
    public EntitiesEndpoint(Javalin app, MinecraftServer server, Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        // Define your endpoints here
        app.get("/api/world/entities", ctx -> {
            ctx.result("List of entities would be here");
            // map over Registries.ENTITY_TYPE and return a list of EntityInfo
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

            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;

            ServerWorld world = server.getWorld(worldKey);
        
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
        
            // Ensure this runs on the server thread and handle response properly
            server.execute(() -> {
                try {
                    BlockPos pos = new BlockPos((int) req.x, (int) req.y, (int) req.z);
                    Entity entity = entityType.create(world, null, pos, SpawnReason.COMMAND, false, false);
                    if (entity == null) {
                        ctx.status(500).json(Map.of("error", "Failed to create entity: " + req.type));
                        return;
                    }
            
                    entity.setPosition(req.x + 0.5, req.y, req.z + 0.5);
                    
                    if (world.spawnEntity(entity)) {
                        LOGGER.info("Spawned entity {} with UUID {} at position ({}, {}, {})",
                                req.type, entity.getUuid(), entity.getX(), entity.getY(), entity.getZ());
                        ctx.json(Map.of(
                            "success", true,
                            "type", req.type,
                            "uuid", entity.getUuid().toString(),
                            "position", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ())
                        ));

                    } else {
                        ctx.status(500).json(Map.of("error", "Failed to spawn entity in world"));
                    }
                } catch (Exception e) {
                    ctx.status(500).json(Map.of("error", "Exception during entity spawn: " + e.getMessage()));
                }
            });
        });
        
    }

}

/*
 * POST /api/entities/spawn
 * 
 * request body:
 * {
  "type": "minecraft:zombie",
  "location": {
    "x": 123,
    "y": 64,
    "z": 456
  },
  "world": "minecraft:overworld", // optional if default world
  "rotation": {
    "yaw": 0,
    "pitch": 0
  },
  "nbt": {
    "CustomName": "{\"text\":\"Bob\"}",
    "Health": 10
  }
}
 * 
 * response:
 * {
  "success": true,
  "entity_uuid": "b2d42952-f9fc-4297-832e-5d27d45be3b0",
  "type": "minecraft:zombie",
  "position": { "x": 123.5, "y": 64.0, "z": 456.5 }
}
 */

/*
Record payload json
[
        { "id": "minecraft:zombie", "display_name": "Zombie" },
        { "id": "minecraft:creeper", "display_name": "Creeper" },
        { "id": "minecraft:acacia_boat", "display_name": "Acacia Boat" },
        { "id": "yourmod:custom_wizard", "display_name": "Custom Wizard" }
        ]
*/

record EntityInfo(String id, String display_name) {
}

class EntitySpawnRequest {
    public String type;
    public String world; // optional; default to "minecraft:overworld"
    public double x;
    public double y;
    public double z;
}