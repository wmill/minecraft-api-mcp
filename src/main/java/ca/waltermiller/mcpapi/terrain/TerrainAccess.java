package ca.waltermiller.mcpapi.terrain;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import net.minecraft.nbt.NbtCompound;

/** The world as terrain smoothing uses it. Every method runs on the server thread. */
public interface TerrainAccess {
    /** Never loads or generates chunks. */
    boolean chunkLoaded(int chunkX, int chunkZ);

    GroundColumn sample(int x, int z);

    /** Removes one vegetation block, notifying neighbours so detached leaves can decay. */
    void clearVegetation(int x, int y, int z);

    /** Sets a terrain block without neighbour updates; a null id sets air. */
    void setTerrain(int x, int y, int z, String blockId);

    /** Undo snapshot of the bounds, or null when unavailable. */
    NbtCompound capture(AreaBounds bounds);

    /** Visual feedback after a successful write. */
    default void placed(AreaBounds bounds) {}
}
