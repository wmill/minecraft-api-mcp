package ca.waltermiller.mcpapi.access;

/**
 * What the access search needs to know about one block.
 *
 * @param solid     the block has a collision shape
 * @param top       height of the collision shape's top within the block (0 when not solid; fences and walls are 1.5)
 * @param climbable ladders, vines, scaffolding and the like: no collision for the search, and you can hang in them
 * @param water     the block holds water (source or flowing)
 * @param lava      the block holds lava
 * @param hazard    entering or standing on it hurts (fire, magma, cactus, berry bushes, powder snow, ...)
 * @param stair     a stair block, which can be walked up without a jump
 * @param openable  door, trapdoor or fence gate kind
 * @param open      the openable is currently open
 */
public record AccessCell(boolean solid, double top, boolean climbable, boolean water, boolean lava, boolean hazard,
                         boolean stair, Openable openable, boolean open) {
    /** Doors, trapdoors and fence gates: wooden ones open by hand, iron ones need redstone. */
    public enum Openable { NONE, WOOD, IRON }

    public static final AccessCell AIR = new AccessCell(false, 0, false, false, false, false, false, Openable.NONE, false);
    public static final AccessCell STONE = solid(1.0);
    public static final AccessCell WATER = new AccessCell(false, 0, false, true, false, false, false, Openable.NONE, false);
    public static final AccessCell LAVA = new AccessCell(false, 0, false, false, true, false, false, Openable.NONE, false);
    public static final AccessCell LADDER = new AccessCell(false, 0, true, false, false, false, false, Openable.NONE, false);

    public static AccessCell solid(double top) {
        return new AccessCell(true, top, false, false, false, false, false, Openable.NONE, false);
    }

    public static AccessCell stairs() {
        return new AccessCell(true, 1.0, false, false, false, false, true, Openable.NONE, false);
    }

    public static AccessCell door(Openable kind, boolean open) {
        return new AccessCell(true, 1.0, false, false, false, false, false, kind, open);
    }

    public static AccessCell hazardous(boolean solid, double top) {
        return new AccessCell(solid, top, false, false, false, true, false, Openable.NONE, false);
    }
}
