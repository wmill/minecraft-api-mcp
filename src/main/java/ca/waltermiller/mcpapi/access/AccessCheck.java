package ca.waltermiller.mcpapi.access;

import ca.waltermiller.mcpapi.access.AccessRequest.Profile;
import ca.waltermiller.mcpapi.access.AccessRequest.Target;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Flood;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs an access check against a terrain. Server thread only when the terrain reads a live world. */
public final class AccessCheck {
    static final int MAX_ROUTE_POINTS = 200;
    static final int MAX_MISSING_CHUNKS = 32;
    public static final String PLAYER_LIMITATIONS = "Estimate of player movement: walking, 1-block jumps, stairs, "
        + "slabs, drops of up to 3 blocks (deeper only into water), ladders/vines/scaffolding, swimming, and the "
        + "doors/trapdoors/gates the doors policy allows. Not modelled: sprint-jumps, parkour, sneaking under "
        + "1.5-high gaps, boats, elytra, ender pearls, pistons and redstone. 'unreachable' means no route inside "
        + "the search box with this model; widen margin or max_nodes if the search hit its limits.";
    public static final String VILLAGER_LIMITATIONS = "Vanilla mob pathfinding with a villager-sized probe: "
        + "ladders, vines and scaffolding are not used, drops are limited by mob rules, iron doors never open. "
        + "Use the player profile to check access for players.";

    private AccessCheck() {}

    public static Map<String, Object> run(String world, AccessRequest req, AccessTerrain terrain, MobProbe probe) {
        if (req.map() && req.mapRegion() == null) throw new AccessException("invalid_request", "Map region was not resolved");
        Box requested = req.searchBox();
        Box box = new Box(requested.minX(), Math.max(requested.minY(), terrain.bottomY()), requested.minZ(),
            requested.maxX(), Math.min(requested.maxY(), terrain.topYInclusive()), requested.maxZ());
        if (box.minY() > box.maxY()) throw new AccessException("invalid_request", "Positions are outside the world height");
        requireLoaded(req, terrain);
        var search = new PlayerAccessSearch(terrain, req.doors(), box);

        var response = new LinkedHashMap<String, Object>();
        response.put("success", true);
        response.put("world", world);
        response.put("profile", req.profile().name().toLowerCase(Locale.ROOT));
        response.put("doors", req.doors().name().toLowerCase(Locale.ROOT));

        Pos start = search.resolve(req.start());
        var startJson = new LinkedHashMap<String, Object>();
        startJson.put("requested", req.start());
        startJson.put("valid", start != null);
        if (start != null) {
            startJson.put("position", start);
            if (!start.equals(req.start())) startJson.put("adjusted", true);
        } else {
            startJson.put("reason", "not_standable");
            Pos suggestion = search.suggest(req.start());
            if (suggestion != null) startJson.put("suggestion", suggestion);
        }
        response.put("start", startJson);

        Flood flood = null;
        var targets = new ArrayList<Map<String, Object>>();
        if (start == null) {
            for (Target t : req.targets()) targets.add(status(t, "unknown", "invalid_start"));
        } else if (req.profile() == Profile.PLAYER) {
            Map<Target, Pos> resolved = new LinkedHashMap<>();
            Map<Target, Pos> vias = new LinkedHashMap<>();
            Set<Long> stopAt = new HashSet<>();
            for (Target t : req.targets()) {
                Pos p = search.resolve(t.pos());
                if (p != null) { resolved.put(t, p); stopAt.add(p.pack()); }
                if (t.via() != null) {
                    Pos v = search.resolve(t.via());
                    if (v != null) { vias.put(t, v); stopAt.add(v.pack()); }
                }
            }
            flood = search.flood(start, req.map() ? null : stopAt, req.maxNodes());
            for (Target t : req.targets()) targets.add(playerTarget(search, req, flood, t, resolved.get(t), vias.get(t)));
        } else {
            double startFeet = search.stand(start.x(), start.y(), start.z()).feet();
            for (Target t : req.targets()) targets.add(villagerTarget(search, req, probe, start, startFeet, t));
        }
        response.put("targets", targets);

        if (req.map()) {
            if (flood != null) response.put("map", ReachabilityMap.compute(search, terrain, req.mapRegion(), flood));
            else response.put("map", Map.of("status", "unknown", "reason", "invalid_start"));
        }
        var searchJson = new LinkedHashMap<String, Object>();
        searchJson.put("box", box.json());
        searchJson.put("max_nodes", req.maxNodes());
        if (flood != null) {
            searchJson.put("expanded_nodes", flood.expanded());
            searchJson.put("reached_positions", flood.parent().size());
            searchJson.put("node_limit_hit", flood.nodeLimit());
            searchJson.put("touched_box_edge", flood.touchedBoundary());
            searchJson.put("touched_unloaded_chunks", flood.touchedUnloaded());
        }
        response.put("search", searchJson);
        response.put("limitations", req.profile() == Profile.PLAYER ? PLAYER_LIMITATIONS : VILLAGER_LIMITATIONS);
        return response;
    }

    private static Map<String, Object> playerTarget(PlayerAccessSearch search, AccessRequest req, Flood flood,
                                                    Target t, Pos target, Pos via) {
        if (target == null) return invalid(search, t, t.pos(), "invalid_target");
        if (t.via() != null && via == null) return viaProblem(search, t, target);
        var out = base(t, target);
        if (via == null) {
            if (flood.reached(target)) return reachable(out, flood.route(target), req, null);
            return unreached(out, flood, target, null);
        }
        if (!flood.reached(via)) return unreached(out, flood, via, "via_unreachable");
        List<Pos> first = flood.route(via);
        Flood second = via.equals(target) ? flood : search.flood(via, Set.of(target.pack()), req.maxNodes());
        if (!second.reached(target)) return unreached(out, second, target, "target_unreachable_from_via");
        List<Pos> route = new ArrayList<>(first);
        List<Pos> rest = via.equals(target) ? List.of() : second.route(target);
        if (!rest.isEmpty()) route.addAll(rest.subList(1, rest.size()));
        return reachable(out, route, req, via);
    }

    private static Map<String, Object> villagerTarget(PlayerAccessSearch search, AccessRequest req, MobProbe probe,
                                                      Pos start, double startFeet, Target t) {
        Pos target = search.resolve(t.pos());
        if (target == null) return invalid(search, t, t.pos(), "invalid_target");
        Pos via = null;
        if (t.via() != null) {
            via = search.resolve(t.via());
            if (via == null) return viaProblem(search, t, target);
        }
        var out = base(t, target);
        List<Pos> route = new ArrayList<>();
        Pos from = start;
        double fromFeet = startFeet;
        for (Pos leg : via == null ? List.of(target) : List.of(via, target)) {
            if (leg.equals(from)) continue;
            var result = probe.find(from, fromFeet, leg, req.maxNodes());
            if (result == null || !result.reached()) {
                out.put("status", "unreachable");
                out.put("reason", leg.equals(via) && !via.equals(target) ? "via_unreachable" : "no_route_within_limits");
                if (result != null && result.end() != null) {
                    out.put("nearest_reached", Map.of("position", result.end(), "distance", round(result.end().distance(leg))));
                }
                return out;
            }
            if (!route.isEmpty() && !result.nodes().isEmpty()) route.addAll(result.nodes().subList(1, result.nodes().size()));
            else route.addAll(result.nodes());
            from = leg;
            fromFeet = search.stand(leg.x(), leg.y(), leg.z()).feet();
        }
        return reachable(out, route, req, via);
    }

    private static Map<String, Object> base(Target t, Pos resolved) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", t.name());
        out.put("requested", t.pos());
        out.put("position", resolved);
        if (!resolved.equals(t.pos())) out.put("adjusted", true);
        if (t.via() != null) out.put("via", t.via());
        return out;
    }

    private static Map<String, Object> status(Target t, String status, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", t.name());
        out.put("requested", t.pos());
        out.put("status", status);
        out.put("reason", reason);
        return out;
    }

    private static Map<String, Object> invalid(PlayerAccessSearch search, Target t, Pos at, String reason) {
        var out = status(t, "invalid_target", reason);
        Pos suggestion = search.suggest(at);
        if (suggestion != null) out.put("suggestion", suggestion);
        return out;
    }

    private static Map<String, Object> viaProblem(PlayerAccessSearch search, Target t, Pos target) {
        if (search.closedDoor(t.via())) {
            var out = base(t, target);
            out.put("status", "unreachable");
            out.put("reason", "via_door_cannot_be_opened");
            return out;
        }
        return invalid(search, t, t.via(), "invalid_via");
    }

    private static Map<String, Object> reachable(Map<String, Object> out, List<Pos> route, AccessRequest req, Pos via) {
        out.put("status", "reachable");
        out.put("route_length", Math.max(0, route.size() - 1));
        if (via != null) out.put("via_crossed", true);
        if (req.includeRoute()) {
            List<Pos> points = turns(route);
            out.put("route_truncated", points.size() > MAX_ROUTE_POINTS);
            out.put("route", points.size() > MAX_ROUTE_POINTS ? points.subList(0, MAX_ROUTE_POINTS) : points);
        }
        return out;
    }

    private static Map<String, Object> unreached(Map<String, Object> out, Flood flood, Pos goal, String reason) {
        String status;
        String why;
        if (flood.nodeLimit()) { status = "unknown"; why = "node_limit"; }
        else if (flood.touchedUnloaded()) { status = "unknown"; why = "unloaded_chunks"; }
        else if (flood.touchedBoundary()) { status = "unreachable"; why = "no_route_within_search_box"; }
        else { status = "unreachable"; why = "no_route_exists"; }
        out.put("status", status);
        out.put("reason", reason == null ? why : reason);
        if (reason != null) out.put("search_result", why);
        Pos nearest = flood.nearest(goal);
        if (nearest != null) out.put("nearest_reached", Map.of("position", nearest, "distance", round(nearest.distance(goal))));
        return out;
    }

    /** First and last points plus every point where the direction of travel changes. */
    static List<Pos> turns(List<Pos> route) {
        if (route.size() <= 2) return route;
        var out = new ArrayList<Pos>();
        out.add(route.get(0));
        for (int i = 1; i < route.size() - 1; i++) {
            Pos a = route.get(i - 1), b = route.get(i), c = route.get(i + 1);
            boolean same = b.x() - a.x() == c.x() - b.x() && b.y() - a.y() == c.y() - b.y() && b.z() - a.z() == c.z() - b.z();
            if (!same) out.add(b);
        }
        out.add(route.get(route.size() - 1));
        return out;
    }

    private static void requireLoaded(AccessRequest req, AccessTerrain terrain) {
        Set<Long> needed = new LinkedHashSet<>();
        needed.add(chunk(req.start()));
        for (Target t : req.targets()) {
            needed.add(chunk(t.pos()));
            if (t.via() != null) needed.add(chunk(t.via()));
        }
        if (req.mapRegion() != null) {
            Box r = req.mapRegion();
            for (int cx = r.minX() >> 4; cx <= r.maxX() >> 4; cx++)
                for (int cz = r.minZ() >> 4; cz <= r.maxZ() >> 4; cz++) needed.add(PlayerAccessSearch.pack(cx, 0, cz));
        }
        List<Map<String, Integer>> missing = new ArrayList<>();
        int total = 0;
        for (long c : needed) {
            int cx = PlayerAccessSearch.unpackX(c), cz = PlayerAccessSearch.unpackZ(c);
            if (!terrain.chunkLoaded(cx, cz)) {
                total++;
                if (missing.size() < MAX_MISSING_CHUNKS) missing.add(Map.of("chunk_x", cx, "chunk_z", cz));
            }
        }
        if (total > 0) {
            throw new AccessException("chunks_not_loaded", total + " chunk(s) holding the start, targets or map region "
                + "are not loaded; move a player nearby and retry", missing);
        }
    }

    private static long chunk(Pos p) { return PlayerAccessSearch.pack(p.x() >> 4, 0, p.z() >> 4); }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
