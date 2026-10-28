package ca.waltermiller.mcpapi.endpoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Request DTOs for {@code POST /api/world/entities/spawn} plus the pure validation and scatter
 * logic, kept free of Minecraft classes so it can be unit tested.
 */
final class EntitySpawnSpec {
    static final int MAX_COUNT = 32;
    static final double MAX_SPREAD = 16.0;
    static final double DEFAULT_SPREAD = 2.0;
    static final int MAX_PASSENGER_DEPTH = 8;
    static final int MAX_TOTAL_ENTITIES = 64;
    static final double MAX_HEALTH = 1024.0;
    static final int MAX_EFFECT_SECONDS = 3600;
    static final int MAX_EFFECT_AMPLIFIER = 9;
    static final int MAX_EFFECTS = 16;
    static final int MAX_FIRE_SECONDS = 600;
    static final int MAX_SIZE = 16;
    static final int MAX_NAME_LENGTH = 64;
    static final double MAX_VELOCITY = 10.0;
    static final Set<String> EQUIPMENT_SLOTS = Set.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle");

    private EntitySpawnSpec() {
    }

    /**
     * Validates the request and returns the total number of entities it would create
     * (roots times the size of each rider stack).
     *
     * @throws IllegalArgumentException with an "Invalid ..." message on bad input
     */
    static int validate(EntitySpawnRequest req) {
        if (req.type == null || req.type.isBlank()) {
            throw new IllegalArgumentException("Invalid entity type: type is required");
        }
        int count = req.count != null ? req.count : 1;
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("Invalid count " + count + ": must be 1-" + MAX_COUNT);
        }
        double spread = spread(req);
        if (!(spread >= 0 && spread <= MAX_SPREAD)) {
            throw new IllegalArgumentException("Invalid spread " + spread + ": must be 0-" + MAX_SPREAD);
        }
        int perRoot = validateTree(req.type, req.options, 1);
        int total = count * perRoot;
        if (total > MAX_TOTAL_ENTITIES) {
            throw new IllegalArgumentException("Invalid request: would spawn " + total
                + " entities (max " + MAX_TOTAL_ENTITIES + ")");
        }
        return total;
    }

    static double spread(EntitySpawnRequest req) {
        return req.spread != null ? req.spread : DEFAULT_SPREAD;
    }

    private static int validateTree(String type, SpawnOptions options, int depth) {
        if (depth > MAX_PASSENGER_DEPTH) {
            throw new IllegalArgumentException("Invalid passengers: stack deeper than " + MAX_PASSENGER_DEPTH);
        }
        if (options == null) {
            return 1;
        }
        validateOptions(type, options);
        int total = 1;
        if (options.passengers != null) {
            for (PassengerSpec passenger : options.passengers) {
                if (passenger == null || passenger.type == null || passenger.type.isBlank()) {
                    throw new IllegalArgumentException("Invalid passenger: type is required");
                }
                total += validateTree(passenger.type, passenger.options, depth + 1);
            }
        }
        return total;
    }

    private static void validateOptions(String type, SpawnOptions o) {
        if (o.custom_name != null && o.custom_name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid custom_name: longer than " + MAX_NAME_LENGTH + " characters");
        }
        if (o.fire_seconds != null && (o.fire_seconds < 0 || o.fire_seconds > MAX_FIRE_SECONDS)) {
            throw new IllegalArgumentException("Invalid fire_seconds " + o.fire_seconds + ": must be 0-" + MAX_FIRE_SECONDS);
        }
        if (o.pitch != null && (o.pitch < -90 || o.pitch > 90)) {
            throw new IllegalArgumentException("Invalid pitch " + o.pitch + ": must be -90 to 90");
        }
        if (o.velocity != null) {
            for (double v : new double[]{o.velocity.x, o.velocity.y, o.velocity.z}) {
                if (!(Math.abs(v) <= MAX_VELOCITY)) {
                    throw new IllegalArgumentException("Invalid velocity: each component must be within +/-" + MAX_VELOCITY);
                }
            }
        }
        if (o.max_health != null && !(o.max_health >= 1 && o.max_health <= MAX_HEALTH)) {
            throw new IllegalArgumentException("Invalid max_health " + o.max_health + ": must be 1-" + MAX_HEALTH);
        }
        if (o.health != null && !(o.health >= 1 && o.health <= MAX_HEALTH)) {
            throw new IllegalArgumentException("Invalid health " + o.health + ": must be 1-" + MAX_HEALTH);
        }
        if (o.equipment != null) {
            for (Map.Entry<String, String> slot : o.equipment.entrySet()) {
                if (!EQUIPMENT_SLOTS.contains(slot.getKey())) {
                    throw new IllegalArgumentException("Invalid equipment slot '" + slot.getKey()
                        + "': expected one of " + EQUIPMENT_SLOTS.stream().sorted().toList());
                }
                if (slot.getValue() == null || slot.getValue().isBlank()) {
                    throw new IllegalArgumentException("Invalid item for slot " + slot.getKey() + ": item id is required");
                }
            }
        }
        if (o.effects != null) {
            if (o.effects.size() > MAX_EFFECTS) {
                throw new IllegalArgumentException("Invalid effects: at most " + MAX_EFFECTS);
            }
            for (EffectSpec effect : o.effects) {
                if (effect == null || effect.id == null || effect.id.isBlank()) {
                    throw new IllegalArgumentException("Invalid effect: id is required");
                }
                int seconds = effect.duration_seconds != null ? effect.duration_seconds : EffectSpec.DEFAULT_SECONDS;
                if (seconds < 1 || seconds > MAX_EFFECT_SECONDS) {
                    throw new IllegalArgumentException("Invalid duration_seconds " + seconds + " for effect "
                        + effect.id + ": must be 1-" + MAX_EFFECT_SECONDS);
                }
                int amplifier = effect.amplifier != null ? effect.amplifier : 0;
                if (amplifier < 0 || amplifier > MAX_EFFECT_AMPLIFIER) {
                    throw new IllegalArgumentException("Invalid amplifier " + amplifier + " for effect "
                        + effect.id + ": must be 0-" + MAX_EFFECT_AMPLIFIER);
                }
            }
        }
        if (o.size != null && (o.size < 1 || o.size > MAX_SIZE)) {
            throw new IllegalArgumentException("Invalid size " + o.size + ": must be 1-" + MAX_SIZE);
        }
        if (o.villager_level != null && (o.villager_level < 1 || o.villager_level > 5)) {
            throw new IllegalArgumentException("Invalid villager_level " + o.villager_level + ": must be 1-5");
        }
    }

    /**
     * Horizontal offsets for {@code count} entities: the first stays on the requested spot,
     * the rest are scattered uniformly in a disc of radius {@code spread}.
     */
    static List<double[]> scatterOffsets(int count, double spread, Random random) {
        List<double[]> offsets = new ArrayList<>(count);
        offsets.add(new double[]{0, 0});
        for (int i = 1; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = spread * Math.sqrt(random.nextDouble());
            offsets.add(new double[]{Math.cos(angle) * radius, Math.sin(angle) * radius});
        }
        return offsets;
    }
}

class EntitySpawnRequest {
    public String type;
    public String world; // optional; default to "minecraft:overworld"
    public double x;
    public double y;
    public double z;
    public Integer count;
    public Double spread;
    public SpawnOptions options;
}

class SpawnOptions {
    public String custom_name;
    public Boolean name_visible;
    public Boolean glowing;
    public Boolean silent;
    public Boolean no_gravity;
    public Boolean invulnerable;
    public Boolean no_ai;
    public Boolean persistent;
    public Boolean baby;
    public Integer fire_seconds;
    public Float yaw;
    public Float pitch;
    public Vec velocity;
    public Double max_health;
    public Double health;
    public Map<String, String> equipment;
    public Boolean drop_equipment;
    public List<EffectSpec> effects;
    public String variant;
    public String color;
    public String profession;
    public Integer villager_level;
    public Integer size;
    public Boolean charged;
    public String tamed_by;
    public List<PassengerSpec> passengers;
}

class PassengerSpec {
    public String type;
    public SpawnOptions options;
}

class EffectSpec {
    static final int DEFAULT_SECONDS = 30;

    public String id;
    public Integer duration_seconds;
    public Integer amplifier;
    public Boolean particles;
}

class Vec {
    public double x;
    public double y;
    public double z;
}
