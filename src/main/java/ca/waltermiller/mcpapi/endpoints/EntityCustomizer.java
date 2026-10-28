package ca.waltermiller.mcpapi.endpoints;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerDataContainer;
import net.minecraft.village.VillagerProfession;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the {@code afterConsumer} for {@code EntityType.create}: applies {@link SpawnOptions} to a
 * freshly created entity. It runs after {@code MobEntity.initialize}, so it overrides random
 * variants and gear. Options that do not apply to the entity are recorded in {@code ignored}.
 */
final class EntityCustomizer {
    /** Variant-like components, tried in order; the first one the entity exposes takes {@code variant}. */
    private static final List<ComponentType<?>> VARIANT_COMPONENTS = List.of(
        DataComponentTypes.CAT_VARIANT,
        DataComponentTypes.WOLF_VARIANT,
        DataComponentTypes.FROG_VARIANT,
        DataComponentTypes.PIG_VARIANT,
        DataComponentTypes.COW_VARIANT,
        DataComponentTypes.CHICKEN_VARIANT,
        DataComponentTypes.FOX_VARIANT,
        DataComponentTypes.PARROT_VARIANT,
        DataComponentTypes.RABBIT_VARIANT,
        DataComponentTypes.AXOLOTL_VARIANT,
        DataComponentTypes.LLAMA_VARIANT,
        DataComponentTypes.HORSE_VARIANT,
        DataComponentTypes.MOOSHROOM_VARIANT,
        DataComponentTypes.SALMON_SIZE,
        DataComponentTypes.TROPICAL_FISH_PATTERN,
        DataComponentTypes.VILLAGER_VARIANT);

    private static final List<ComponentType<?>> COLOR_COMPONENTS = List.of(
        DataComponentTypes.SHEEP_COLOR,
        DataComponentTypes.WOLF_COLLAR,
        DataComponentTypes.CAT_COLLAR,
        DataComponentTypes.TROPICAL_FISH_BASE_COLOR);

    private EntityCustomizer() {
    }

    /**
     * Checks every id that lives in a static registry (entity types, items, effects, professions)
     * so bad input fails with 400 before anything is spawned.
     */
    static void checkIds(String type, SpawnOptions options) {
        entityId(type);
        if (options == null) {
            return;
        }
        if (options.equipment != null) {
            options.equipment.values().forEach(EntityCustomizer::item);
        }
        if (options.effects != null) {
            options.effects.forEach(effect -> effect(effect.id));
        }
        if (options.profession != null) {
            profession(options.profession);
        }
        if (options.passengers != null) {
            options.passengers.forEach(p -> checkIds(p.type, p.options));
        }
    }

    static Identifier entityId(String type) {
        Identifier id = Identifier.tryParse(type);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
            throw new IllegalArgumentException("Invalid entity type: " + type);
        }
        return id;
    }

    static <T extends Entity> Consumer<T> of(SpawnOptions options, ServerWorld world, Set<String> ignored) {
        if (options == null) {
            return entity -> {
            };
        }
        return entity -> apply(entity, options, world, ignored);
    }

    private static void apply(Entity entity, SpawnOptions o, ServerWorld world, Set<String> ignored) {
        String typeId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        Ignored skip = option -> ignored.add(option + " (" + typeId + ")");
        MobEntity mob = entity instanceof MobEntity m ? m : null;
        LivingEntity living = entity instanceof LivingEntity l ? l : null;

        if (o.custom_name != null) {
            entity.setCustomName(Text.literal(o.custom_name));
            entity.setCustomNameVisible(o.name_visible == null || o.name_visible);
        } else if (o.name_visible != null) {
            entity.setCustomNameVisible(o.name_visible);
        }
        if (o.glowing != null) {
            entity.setGlowing(o.glowing);
        }
        if (o.silent != null) {
            entity.setSilent(o.silent);
        }
        if (o.no_gravity != null) {
            entity.setNoGravity(o.no_gravity);
        }
        if (o.invulnerable != null) {
            entity.setInvulnerable(o.invulnerable);
        }
        if (o.fire_seconds != null && o.fire_seconds > 0) {
            entity.setOnFireForTicks(o.fire_seconds * 20);
        }
        if (o.yaw != null) {
            entity.setYaw(o.yaw);
            entity.setHeadYaw(o.yaw);
            if (living != null) {
                living.setBodyYaw(o.yaw);
            }
        }
        if (o.pitch != null) {
            entity.setPitch(o.pitch);
        }
        if (o.velocity != null) {
            entity.setVelocity(o.velocity.x, o.velocity.y, o.velocity.z);
        }

        if (o.no_ai != null) {
            if (mob != null) mob.setAiDisabled(o.no_ai); else skip.add("no_ai");
        }
        if (Boolean.TRUE.equals(o.persistent)) {
            if (mob != null) mob.setPersistent(); else skip.add("persistent");
        }
        if (o.baby != null) {
            if (mob != null) mob.setBaby(o.baby);
            if (mob == null || mob.isBaby() != o.baby) skip.add("baby");
        }

        if (o.max_health != null || o.health != null) {
            if (living != null) {
                if (o.max_health != null) {
                    EntityAttributeInstance maxHealth = living.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                    if (maxHealth != null) maxHealth.setBaseValue(o.max_health); else skip.add("max_health");
                }
                living.setHealth((float) (o.health != null ? o.health : living.getMaxHealth()));
            } else {
                skip.add("health");
            }
        }
        if (o.equipment != null) {
            applyEquipment(o, living, mob, skip);
        }
        if (o.effects != null) {
            if (living != null) {
                for (EffectSpec effect : o.effects) {
                    int seconds = effect.duration_seconds != null ? effect.duration_seconds : EffectSpec.DEFAULT_SECONDS;
                    int amplifier = effect.amplifier != null ? effect.amplifier : 0;
                    boolean particles = effect.particles == null || effect.particles;
                    living.addStatusEffect(new StatusEffectInstance(effect(effect.id), seconds * 20, amplifier, false, particles));
                }
            } else {
                skip.add("effects");
            }
        }

        if (o.variant != null && !setFirstComponent(entity, VARIANT_COMPONENTS, o.variant, world, "variant", typeId)) {
            skip.add("variant");
        }
        if (o.color != null && !setFirstComponent(entity, COLOR_COMPONENTS, o.color, world, "color", typeId)) {
            skip.add("color");
        }
        if (o.profession != null || o.villager_level != null) {
            if (entity instanceof VillagerDataContainer villager) {
                VillagerData data = villager.getVillagerData();
                if (o.profession != null) data = data.withProfession(profession(o.profession));
                if (o.villager_level != null) data = data.withLevel(o.villager_level);
                villager.setVillagerData(data);
            } else {
                skip.add("profession");
            }
        }
        if (o.size != null) {
            if (entity instanceof SlimeEntity slime) slime.setSize(o.size, true); else skip.add("size");
        }
        if (o.charged != null) {
            if (entity instanceof CreeperEntity creeper) {
                creeper.getDataTracker().set(CreeperEntity.CHARGED, o.charged);
            } else {
                skip.add("charged");
            }
        }
        if (o.tamed_by != null) {
            applyTamedBy(entity, o.tamed_by, world, skip);
        }
    }

    private static void applyEquipment(SpawnOptions o, LivingEntity living, MobEntity mob, Ignored skip) {
        if (living == null) {
            skip.add("equipment");
            return;
        }
        for (Map.Entry<String, String> entry : o.equipment.entrySet()) {
            EquipmentSlot slot = EquipmentSlot.byName(entry.getKey());
            if (!living.canUseSlot(slot)) {
                skip.add("equipment." + entry.getKey());
                continue;
            }
            living.equipStack(slot, new ItemStack(item(entry.getValue())));
            if (mob != null) {
                mob.setEquipmentDropChance(slot, Boolean.TRUE.equals(o.drop_equipment) ? 1.0f : 0.0f);
            }
        }
    }

    private static void applyTamedBy(Entity entity, String playerName, ServerWorld world, Ignored skip) {
        if (!(entity instanceof TameableEntity) && !(entity instanceof AbstractHorseEntity)) {
            skip.add("tamed_by");
            return;
        }
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerName);
        if (player == null) {
            throw new IllegalArgumentException("Invalid tamed_by: player '" + playerName + "' is not online");
        }
        if (entity instanceof TameableEntity tameable) {
            tameable.setTamedBy(player);
        } else {
            ((AbstractHorseEntity) entity).bondWithPlayer(player);
        }
    }

    private static boolean setFirstComponent(Entity entity, List<ComponentType<?>> candidates, String raw,
                                             ServerWorld world, String option, String typeId) {
        RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, world.getRegistryManager());
        for (ComponentType<?> type : candidates) {
            if (entity.get(type) != null) {
                setComponent(entity, type, raw, ops, option, typeId);
                return true;
            }
        }
        return false;
    }

    private static <T> void setComponent(Entity entity, ComponentType<T> type, String raw,
                                         RegistryOps<JsonElement> ops, String option, String typeId) {
        T value = type.getCodecOrThrow().parse(ops, new JsonPrimitive(raw))
            .getOrThrow(message -> new IllegalArgumentException(
                "Invalid " + option + " '" + raw + "' for " + typeId + ": " + message));
        entity.setComponent(type, value);
    }

    private static Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || !Registries.ITEM.containsId(identifier)) {
            throw new IllegalArgumentException("Invalid item: " + id);
        }
        return Registries.ITEM.get(identifier);
    }

    private static RegistryEntry<StatusEffect> effect(String id) {
        return Optional.ofNullable(Identifier.tryParse(id))
            .flatMap(Registries.STATUS_EFFECT::getEntry)
            .<RegistryEntry<StatusEffect>>map(entry -> entry)
            .orElseThrow(() -> new IllegalArgumentException("Invalid effect: " + id));
    }

    private static RegistryEntry<VillagerProfession> profession(String id) {
        return Optional.ofNullable(Identifier.tryParse(id))
            .flatMap(Registries.VILLAGER_PROFESSION::getEntry)
            .<RegistryEntry<VillagerProfession>>map(entry -> entry)
            .orElseThrow(() -> new IllegalArgumentException("Invalid profession: " + id));
    }

    @FunctionalInterface
    private interface Ignored {
        void add(String option);
    }
}
