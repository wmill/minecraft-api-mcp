package ca.waltermiller.mcpapi.access;

import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Box;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Doors;
import ca.waltermiller.mcpapi.access.PlayerAccessSearch.Pos;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Validated body of {@code POST /api/world/access/check}. {@code mapRegion} is null when the map names a build. */
public record AccessRequest(String world, Pos start, List<Target> targets, boolean map, Box mapRegion, UUID mapBuildId,
                            Profile profile, Doors doors, int maxNodes, int margin, boolean includeRoute) {
    public enum Profile { PLAYER, VILLAGER }

    public record Target(String name, Pos pos, Pos via) {}

    public static final int MAX_TARGETS = 16;
    public static final int DEFAULT_MAX_NODES = 50_000;
    public static final int MAX_NODES = 200_000;
    public static final int DEFAULT_MARGIN = 16;
    public static final int MAX_MARGIN = 64;
    public static final long MAX_MAP_CELLS = 250_000;
    public static final int MAX_MAP_SPAN = 256;
    public static final int MAX_BOX_SPAN = 512;
    private static final int MAX_HORIZONTAL = 30_000_000;
    private static final int MAX_VERTICAL = 2047;

    public AccessRequest withMapRegion(Box region) {
        checkMapRegion(region);
        return new AccessRequest(world, start, targets, true, region, mapBuildId, profile, doors, maxNodes, margin, includeRoute);
    }

    public AccessRequest withWorld(String value) {
        return new AccessRequest(value, start, targets, map, mapRegion, mapBuildId, profile, doors, maxNodes, margin, includeRoute);
    }

    public static AccessRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid("Expected a JSON object");
        String world = null;
        if (body.hasNonNull("world")) {
            if (!body.get("world").isTextual()) throw invalid("world must be a string");
            world = body.get("world").asText();
        }
        Pos start = pos(body.get("start"), "start");
        List<Target> targets = new ArrayList<>();
        JsonNode list = body.get("targets");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) throw invalid("targets must be an array");
            if (list.size() > MAX_TARGETS) throw invalid("At most " + MAX_TARGETS + " targets");
            var names = new HashSet<String>();
            for (int i = 0; i < list.size(); i++) {
                JsonNode t = list.get(i);
                String field = "targets[" + i + "]";
                if (!t.isObject()) throw invalid(field + " must be an object");
                String name = "target_" + (i + 1);
                if (t.hasNonNull("name")) {
                    if (!t.get("name").isTextual() || t.get("name").asText().isBlank()) throw invalid(field + ".name must be a non-empty string");
                    name = t.get("name").asText().strip();
                    if (name.length() > 40) name = name.substring(0, 40);
                }
                if (!names.add(name)) throw invalid("Duplicate target name: " + name);
                Pos via = t.hasNonNull("via") ? pos(t.get("via"), field + ".via") : null;
                targets.add(new Target(name, pos(t, field), via));
            }
        }
        boolean map = false;
        Box region = null;
        UUID buildId = null;
        JsonNode m = body.get("map");
        if (m != null && !m.isNull() && !(m.isBoolean() && !m.asBoolean())) {
            if (!m.isObject()) throw invalid("map must be an object with build_id or x1,y1,z1,x2,y2,z2");
            map = true;
            if (m.hasNonNull("build_id")) {
                try { buildId = UUID.fromString(m.get("build_id").asText()); }
                catch (IllegalArgumentException e) { throw invalid("map.build_id must be a UUID"); }
            } else {
                int[] c = new int[6];
                String[] names = {"x1", "y1", "z1", "x2", "y2", "z2"};
                for (int i = 0; i < 6; i++) c[i] = coordinate(m.get(names[i]), "map." + names[i], names[i].startsWith("y"));
                region = Box.of(c[0], c[1], c[2], c[3], c[4], c[5]);
                checkMapRegion(region);
            }
        }
        if (targets.isEmpty() && !map) throw invalid("Give at least one target or a map region");

        Profile profile = Profile.PLAYER;
        if (body.hasNonNull("profile")) {
            String p = body.get("profile").asText("").toLowerCase(Locale.ROOT);
            profile = switch (p) {
                case "player" -> Profile.PLAYER;
                case "villager" -> Profile.VILLAGER;
                default -> throw invalid("profile must be \"player\" or \"villager\"");
            };
        }
        if (profile == Profile.VILLAGER && map) {
            throw invalid("map is only supported for the player profile");
        }
        Doors doors = Doors.WOODEN;
        if (body.hasNonNull("doors")) {
            doors = switch (body.get("doors").asText("").toLowerCase(Locale.ROOT)) {
                case "wooden" -> Doors.WOODEN;
                case "all" -> Doors.ALL;
                case "none" -> Doors.NONE;
                default -> throw invalid("doors must be \"wooden\", \"all\" or \"none\"");
            };
        }
        int maxNodes = intField(body, "max_nodes", DEFAULT_MAX_NODES, 1, MAX_NODES);
        int margin = intField(body, "margin", DEFAULT_MARGIN, 0, MAX_MARGIN);
        boolean includeRoute = false;
        if (body.hasNonNull("include_route")) {
            if (!body.get("include_route").isBoolean()) throw invalid("include_route must be a boolean");
            includeRoute = body.get("include_route").asBoolean();
        }
        return new AccessRequest(world, start, List.copyOf(targets), map, region, buildId, profile, doors, maxNodes, margin, includeRoute);
    }

    /** Union of the start, targets, vias and map region, padded by the margin, before clamping to world height. */
    public Box searchBox() {
        Box box = new Box(start.x(), start.y(), start.z(), start.x(), start.y(), start.z());
        for (Target t : targets) {
            box = box.union(point(t.pos()));
            if (t.via() != null) box = box.union(point(t.via()));
        }
        if (mapRegion != null) box = box.union(mapRegion);
        box = new Box(box.minX() - margin, box.minY() - margin, box.minZ() - margin,
            box.maxX() + margin, box.maxY() + margin, box.maxZ() + margin);
        if (box.maxX() - box.minX() + 1 > MAX_BOX_SPAN || box.maxZ() - box.minZ() + 1 > MAX_BOX_SPAN) {
            throw new AccessException("search_box_too_large",
                "Start, targets and map (plus margin) must fit in " + MAX_BOX_SPAN + "x" + MAX_BOX_SPAN + " columns");
        }
        return box;
    }

    private static Box point(Pos p) { return new Box(p.x(), p.y(), p.z(), p.x(), p.y(), p.z()); }

    private static void checkMapRegion(Box r) {
        if (r.maxX() - r.minX() + 1 > MAX_MAP_SPAN || r.maxZ() - r.minZ() + 1 > MAX_MAP_SPAN
                || r.maxY() - r.minY() + 1 > MAX_MAP_SPAN || r.volume() > MAX_MAP_CELLS) {
            throw new AccessException("map_too_large", "Map region must be at most " + MAX_MAP_SPAN
                + " per side and " + MAX_MAP_CELLS + " cells");
        }
    }

    private static Pos pos(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw invalid(field + " must be an object with x, y, z");
        return new Pos(coordinate(node.get("x"), field + ".x", false), coordinate(node.get("y"), field + ".y", true),
            coordinate(node.get("z"), field + ".z", false));
    }

    private static int coordinate(JsonNode node, String field, boolean vertical) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw invalid(field + " must be an integer");
        int v = node.intValue();
        int limit = vertical ? MAX_VERTICAL : MAX_HORIZONTAL;
        if (v < -limit || v > limit) throw invalid(field + " is out of range");
        return v;
    }

    private static int intField(JsonNode body, String name, int fallback, int min, int max) {
        if (!body.hasNonNull(name)) return fallback;
        JsonNode n = body.get(name);
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < min || n.intValue() > max) {
            throw invalid(name + " must be an integer from " + min + " to " + max);
        }
        return n.intValue();
    }

    private static AccessException invalid(String message) { return new AccessException("invalid_request", message); }
}
