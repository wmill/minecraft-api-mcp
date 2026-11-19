package ca.waltermiller.mcpapi.access;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Pos;

import java.util.List;

/** Vanilla mob pathfinding from one feet position to another. */
public interface MobProbe {
    /** @param end last node of the (possibly partial) path; null when no path could be started */
    record Result(boolean reached, Pos end, List<Pos> nodes) {}

    Result find(Pos start, double startFeet, Pos target, int maxNodes);
}
