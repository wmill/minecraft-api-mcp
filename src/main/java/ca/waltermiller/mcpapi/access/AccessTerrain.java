package ca.waltermiller.mcpapi.access;

/** World reads for the access search. Implementations never load or generate chunks. */
public interface AccessTerrain {
    int bottomY();
    int topYInclusive();
    /** Chunk coordinates. */
    boolean chunkLoaded(int chunkX, int chunkZ);
    /** Only called for columns in loaded chunks. */
    AccessCell cell(int x, int y, int z);
    /** First Y above the highest motion-blocking block in the column; cells at or above it are open to the sky. */
    int skyY(int x, int z);
}
