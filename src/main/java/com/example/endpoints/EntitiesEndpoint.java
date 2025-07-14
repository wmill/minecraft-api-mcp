package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;

public class EntitiesEndpoint extends APIEndpoint {
    public EntitiesEndpoint(Javalin app, MinecraftServer server) {
        super(app, server);
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

            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;

            ServerWorld world = server.getWorld(worldKey);

            // Identifier worldId = req.world != null ? new Identifier(req.world) : World.OVERWORLD;
        
            EntityType<?> entityType = Registries.ENTITY_TYPE.get(entityId);
            // ServerWorld world = server.getWorld(worldId);
        
            if (entityType == null) {
                ctx.status(400).json(Map.of("error", "Unknown entity type: " + req.type));
                return;
            }
        
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }
        
            // Ensure this runs on the server thread
            server.execute(() -> {
                Entity entity = entityType.create(world);
                if (entity == null) {
                    ctx.status(500).json(Map.of("error", "Failed to create entity: " + req.type));
                    return;
                }
        
                entity.refreshPositionAndAngles(req.x + 0.5, req.y, req.z + 0.5, 0, 0);
                world.spawnEntity(entity);
        
                ctx.json(Map.of(
                    "success", true,
                    "type", req.type,
                    "uuid", entity.getUuid().toString(),
                    "position", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ())
                ));
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

public class EntitySpawnRequest {
    public String type;
    public String world; // optional; default to "minecraft:overworld"
    public double x;
    public double y;
    public double z;
}