package ca.waltermiller.mcpapi.terrain;

/**
 * One column as smoothing sees it.
 *
 * @param ground  standing Y: first air above the ground, below any vegetation
 * @param surface first air above everything, vegetation included ({@code >= ground})
 * @param top     id of the block at {@code ground - 1}, re-placed on the new surface
 * @param fill    id used to build the column up
 */
public record GroundColumn(Kind kind, int ground, int surface, String top, String fill) {
    public enum Kind {
        /** Natural ground; smoothed, vegetation cleared. */
        GROUND,
        /** Water, lava or ice; never reshaped, vegetation above it cleared. */
        FLUID,
        /** Buildings, paving, farmland and paths; never touched. */
        BUILT,
        /** Context column in an unloaded chunk; height unknown. */
        UNLOADED
    }

    public static final GroundColumn UNLOADED_COLUMN = new GroundColumn(Kind.UNLOADED, 0, 0, null, null);

    public boolean masked() { return kind != Kind.GROUND; }

    /** Whether vegetation above the ground is removed. */
    public boolean clearsVegetation() { return kind == Kind.GROUND || kind == Kind.FLUID; }
}
