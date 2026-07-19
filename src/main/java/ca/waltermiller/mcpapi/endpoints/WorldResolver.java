package ca.waltermiller.mcpapi.endpoints;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Resolves world identifier strings (e.g. "minecraft:the_nether") to registry keys.
 */
public final class WorldResolver {

    private WorldResolver() {
    }

    /**
     * Returns the registry key for the given world identifier, {@link World#OVERWORLD}
     * when the identifier is null or blank, or null when the identifier is malformed.
     */
    public static RegistryKey<World> resolveWorldKey(String world) {
        if (world == null || world.isBlank()) {
            return World.OVERWORLD;
        }
        Identifier id = Identifier.tryParse(world);
        return id != null ? RegistryKey.of(RegistryKeys.WORLD, id) : null;
    }
}
