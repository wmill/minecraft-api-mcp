package ca.waltermiller.mcpapi.access;

import ca.waltermiller.mcpapi.access.AccessCell.Openable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PlayerAccessSearchTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode check(GridTerrain terrain, String body) throws Exception {
        var request = AccessRequest.parse(JSON.readTree(body));
        return JSON.valueToTree(AccessCheck.run("minecraft:overworld", request, terrain, null));
    }

    private static JsonNode target(GridTerrain terrain, String extra, int x, int y, int z) throws Exception {
        return check(terrain, "{\"start\":{\"x\":10,\"y\":64,\"z\":0},\"targets\":[{\"name\":\"t\",\"x\":" + x
            + ",\"y\":" + y + ",\"z\":" + z + "}]" + extra + "}").path("targets").get(0);
    }

    @Test void walksAcrossFlatGroundWithShortestRoute() throws Exception {
        var t = check(new GridTerrain(), "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":5,\"y\":64,\"z\":3}],\"include_route\":true}");
        var result = t.path("targets").get(0);
        assertThat(result.path("status").asText()).isEqualTo("reachable");
        assertThat(result.path("route_length").asInt()).isEqualTo(8);
        assertThat(result.path("route").get(0).path("x").asInt()).isZero();
        assertThat(t.path("start").path("valid").asBoolean()).isTrue();
    }

    @Test void adjustsAFloorBlockTargetToItsFeetPosition() throws Exception {
        var result = target(new GridTerrain(), "", 5, 63, 0);
        assertThat(result.path("status").asText()).isEqualTo("reachable");
        assertThat(result.path("position").path("y").asInt()).isEqualTo(64);
        assertThat(result.path("adjusted").asBoolean()).isTrue();
    }

    @Test void invalidTargetInsideSolidGetsSuggestion() throws Exception {
        var terrain = new GridTerrain().fill(5, 64, 0, 5, 70, 0, AccessCell.STONE);
        var result = target(terrain, "", 5, 66, 0);
        assertThat(result.path("status").asText()).isEqualTo("invalid_target");
        assertThat(result.has("suggestion")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"1,reachable", "2,unreachable"})
    void wallHeightLimitsJumping(int height, String status) throws Exception {
        var terrain = new GridTerrain();
        terrain.fill(-2, 64, -2, 2, 63 + height, 2, AccessCell.STONE).fill(-1, 64, -1, 1, 63 + height, 1, AccessCell.AIR);
        var result = target(terrain, ",\"margin\":4", 0, 64, 0);
        assertThat(result.path("status").asText()).isEqualTo(status);
        if (status.equals("unreachable")) {
            assertThat(result.path("reason").asText()).isEqualTo("no_route_within_search_box");
            assertThat(result.path("nearest_reached").path("distance").asDouble()).isGreaterThan(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "WOOD,false,wooden,reachable", "WOOD,false,none,unreachable",
        "IRON,false,wooden,unreachable", "IRON,false,all,reachable", "IRON,true,none,reachable"})
    void doorPolicy(Openable kind, boolean open, String doors, String status) throws Exception {
        var terrain = new GridTerrain().house(66, AccessCell.door(kind, open));
        var result = target(terrain, ",\"doors\":\"" + doors + "\"", 0, 64, 0);
        assertThat(result.path("status").asText()).isEqualTo(status);
    }

    @Test void sealedHouseIsReportedAsNoRouteWhenStartIsInside() throws Exception {
        var terrain = new GridTerrain().house(66, null);
        var t = check(terrain, "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":10,\"y\":64,\"z\":0}]}");
        assertThat(t.path("targets").get(0).path("reason").asText()).isEqualTo("no_route_exists");
        assertThat(t.path("search").path("touched_box_edge").asBoolean()).isFalse();
    }

    @Test void ladderReachesTheSecondFloor() throws Exception {
        String body = "{\"start\":{\"x\":10,\"y\":64,\"z\":0},\"targets\":[{\"name\":\"upstairs\",\"x\":1,\"y\":68,\"z\":1}],"
            + "\"map\":{\"x1\":-3,\"y1\":63,\"z1\":-3,\"x2\":3,\"y2\":73,\"z2\":3}}";
        var without = check(twoStorey(false), body);
        assertThat(without.path("targets").get(0).path("status").asText()).isEqualTo("unreachable");
        var map = without.path("map");
        var indoor = map.path("unreached_regions").get(0);
        assertThat(indoor.path("outdoor").asBoolean()).isFalse();
        assertThat(indoor.path("bounds").path("min_y").asInt()).isEqualTo(68);
        assertThat(level(map, 64).path("fraction").asDouble()).isEqualTo(1.0);
        assertThat(level(map, 68).path("reachable").asInt()).isZero();

        var with = check(twoStorey(true), body);
        assertThat(with.path("targets").get(0).path("status").asText()).isEqualTo("reachable");
        assertThat(level(with.path("map"), 68).path("fraction").asDouble()).isEqualTo(1.0);
        for (var region : with.path("map").path("unreached_regions")) assertThat(region.path("outdoor").asBoolean()).isTrue();
    }

    @Test void trapdoorOverLadderHoleIsPassableWhenWooden() throws Exception {
        var terrain = twoStorey(true);
        terrain.set(-2, 67, -2, new AccessCell(true, 1.0, false, false, false, false, false, Openable.WOOD, false));
        terrain.fill(-2, 64, -2, -2, 66, -2, AccessCell.LADDER);
        String body = "{\"start\":{\"x\":10,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":68,\"z\":1}]";
        assertThat(check(terrain, body + "}").path("targets").get(0).path("status").asText()).isEqualTo("reachable");
        assertThat(check(terrain, body + ",\"doors\":\"none\"}").path("targets").get(0).path("status").asText()).isEqualTo("unreachable");
    }

    @ParameterizedTest
    @CsvSource({"true,reachable", "false,unreachable"})
    void stairsClimbUnderALowCeilingWhereAJumpCannot(boolean stair, String status) throws Exception {
        var terrain = new GridTerrain();
        terrain.set(1, 64, 0, stair ? AccessCell.stairs() : AccessCell.STONE);
        terrain.set(0, 66, 0, AccessCell.STONE);
        var request = AccessRequest.parse(JSON.readTree(
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":65,\"z\":0}],\"margin\":0}"));
        JsonNode result = JSON.valueToTree(AccessCheck.run("w", request, terrain, null));
        assertThat(result.path("targets").get(0).path("status").asText()).isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({"3,false,reachable", "4,false,unreachable", "4,true,reachable"})
    void dropsAreLimitedUnlessLandingInWater(int drop, boolean water, String status) throws Exception {
        var terrain = new GridTerrain();
        terrain.fill(0, 64, 0, 0, 63 + drop, 0, AccessCell.STONE);
        if (water) terrain.set(1, 63, 0, AccessCell.WATER);
        int targetY = water ? 63 : 64;
        var request = AccessRequest.parse(JSON.readTree("{\"start\":{\"x\":0,\"y\":" + (64 + drop) + ",\"z\":0},"
            + "\"targets\":[{\"x\":1,\"y\":" + targetY + ",\"z\":0}],\"margin\":0}"));
        JsonNode result = JSON.valueToTree(AccessCheck.run("w", request, terrain, null));
        assertThat(result.path("targets").get(0).path("status").asText()).isEqualTo(status);
    }

    @Test void oneHighGapBlocksAndLavaIsAvoided() throws Exception {
        var gap = new GridTerrain().set(1, 65, 0, AccessCell.STONE);
        var lava = new GridTerrain().set(1, 64, 0, AccessCell.LAVA);
        for (var terrain : List.of(gap, lava)) {
            var request = AccessRequest.parse(JSON.readTree(
                "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":2,\"y\":64,\"z\":0}],\"margin\":0}"));
            // margin 0 makes the box a 3x1 corridor: the only way is through x=1.
            JsonNode result = JSON.valueToTree(AccessCheck.run("w", request, terrain, null));
            assertThat(result.path("targets").get(0).path("status").asText()).isEqualTo("unreachable");
        }
    }

    @Test void viaRequiresTheRouteToUseThatDoorway() throws Exception {
        var terrain = new GridTerrain().house(66, AccessCell.door(Openable.WOOD, false));
        terrain.set(-3, 64, 0, AccessCell.door(Openable.IRON, false)).set(-3, 65, 0, AccessCell.door(Openable.IRON, false));
        String front = "{\"start\":{\"x\":10,\"y\":64,\"z\":0},\"targets\":[{\"x\":0,\"y\":64,\"z\":0,\"via\":{\"x\":3,\"y\":64,\"z\":0}}],\"include_route\":true}";
        String back = front.replace("\"x\":3,\"y\":64", "\"x\":-3,\"y\":64");
        var ok = check(terrain, front).path("targets").get(0);
        assertThat(ok.path("status").asText()).isEqualTo("reachable");
        assertThat(ok.path("via_crossed").asBoolean()).isTrue();
        var blocked = check(terrain, back).path("targets").get(0);
        assertThat(blocked.path("status").asText()).isEqualTo("unreachable");
        assertThat(blocked.path("reason").asText()).isEqualTo("via_door_cannot_be_opened");
    }

    @Test void nodeLimitAndUnloadedChunksAreUnknown() throws Exception {
        var limited = target(new GridTerrain(), ",\"max_nodes\":5", 0, 64, 9);
        assertThat(limited.path("status").asText()).isEqualTo("unknown");
        assertThat(limited.path("reason").asText()).isEqualTo("node_limit");

        var terrain = new GridTerrain();
        terrain.loaded = (cx, cz) -> cx != 1;
        var request = AccessRequest.parse(JSON.readTree(
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":40,\"y\":64,\"z\":0}],\"margin\":2}"));
        JsonNode result = JSON.valueToTree(AccessCheck.run("w", request, terrain, null));
        assertThat(result.path("targets").get(0).path("status").asText()).isEqualTo("unknown");
        assertThat(result.path("targets").get(0).path("reason").asText()).isEqualTo("unloaded_chunks");

        var missing = AccessRequest.parse(JSON.readTree(
            "{\"start\":{\"x\":20,\"y\":64,\"z\":0},\"targets\":[{\"x\":0,\"y\":64,\"z\":0}]}"));
        assertThatThrownBy(() -> AccessCheck.run("w", missing, terrain, null))
            .isInstanceOfSatisfying(AccessException.class, e -> assertThat(e.code()).isEqualTo("chunks_not_loaded"));
    }

    @Test void invalidStartMarksTargetsUnknown() throws Exception {
        var terrain = new GridTerrain().fill(0, 64, 0, 0, 70, 0, AccessCell.STONE);
        var t = check(terrain, "{\"start\":{\"x\":0,\"y\":66,\"z\":0},\"targets\":[{\"x\":4,\"y\":64,\"z\":0}]}");
        assertThat(t.path("start").path("valid").asBoolean()).isFalse();
        assertThat(t.path("start").has("suggestion")).isTrue();
        assertThat(t.path("targets").get(0).path("reason").asText()).isEqualTo("invalid_start");
    }

    @Test void routeIsCollapsedToTurns() {
        var route = List.of(new PlayerAccessSearch.Pos(0, 64, 0), new PlayerAccessSearch.Pos(1, 64, 0),
            new PlayerAccessSearch.Pos(2, 64, 0), new PlayerAccessSearch.Pos(2, 64, 1), new PlayerAccessSearch.Pos(2, 64, 2));
        assertThat(AccessCheck.turns(route)).extracting(PlayerAccessSearch.Pos::x).containsExactly(0, 2, 2);
    }

    @Test void packRoundTripsNegativeCoordinates() {
        for (int[] p : new int[][] {{-29_999_999, -64, 29_999_999}, {0, 0, 0}, {-1, 319, -1}, {12345, -2048, -6789}}) {
            long packed = PlayerAccessSearch.pack(p[0], p[1], p[2]);
            assertThat(PlayerAccessSearch.Pos.unpack(packed)).isEqualTo(new PlayerAccessSearch.Pos(p[0], p[1], p[2]));
        }
    }

    @Test void requestValidation() {
        for (String body : new String[] {
            "[]", "{}", "{\"start\":{\"x\":0,\"y\":64,\"z\":0}}",
            "{\"start\":{\"x\":0.5,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":64,\"z\":0}]}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":64,\"z\":0}],\"profile\":\"cat\"}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":64,\"z\":0}],\"doors\":\"glass\"}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":1,\"y\":64,\"z\":0}],\"max_nodes\":0}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"name\":\"a\",\"x\":1,\"y\":64,\"z\":0},{\"name\":\"a\",\"x\":2,\"y\":64,\"z\":0}]}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"map\":{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":300,\"y2\":10,\"z2\":0}}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"map\":{\"build_id\":\"nope\"}}",
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"map\":{\"x1\":0,\"y1\":60,\"z1\":0,\"x2\":5,\"y2\":70,\"z2\":5},\"profile\":\"villager\"}",
        }) {
            assertThatThrownBy(() -> AccessRequest.parse(JSON.readTree(body))).as(body).isInstanceOf(AccessException.class);
        }
        assertThatThrownBy(() -> AccessRequest.parse(JSON.readTree(
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"targets\":[{\"x\":600,\"y\":64,\"z\":0}]}")).searchBox())
            .isInstanceOfSatisfying(AccessException.class, e -> assertThat(e.code()).isEqualTo("search_box_too_large"));
    }

    @Test void villagerProfileUsesTheProbe() throws Exception {
        var request = AccessRequest.parse(JSON.readTree(
            "{\"start\":{\"x\":0,\"y\":64,\"z\":0},\"profile\":\"villager\",\"targets\":[{\"name\":\"a\",\"x\":3,\"y\":64,\"z\":0},{\"name\":\"b\",\"x\":0,\"y\":64,\"z\":3}]}"));
        MobProbe probe = (start, feet, target, max) -> target.x() == 3
            ? new MobProbe.Result(true, target, List.of(start, target))
            : new MobProbe.Result(false, new PlayerAccessSearch.Pos(0, 64, 1), List.of(start));
        JsonNode result = JSON.valueToTree(AccessCheck.run("w", request, new GridTerrain(), probe));
        assertThat(result.path("targets").get(0).path("status").asText()).isEqualTo("reachable");
        assertThat(result.path("targets").get(1).path("status").asText()).isEqualTo("unreachable");
        assertThat(result.path("targets").get(1).path("nearest_reached").path("distance").asDouble()).isEqualTo(2.0);
        assertThat(result.path("limitations").asText()).contains("ladders");
    }

    private static GridTerrain twoStorey(boolean ladder) {
        var terrain = new GridTerrain().house(70, AccessCell.door(Openable.WOOD, false));
        terrain.fill(-2, 67, -2, 2, 67, 2, AccessCell.STONE);
        if (ladder) terrain.fill(-2, 64, -2, -2, 67, -2, AccessCell.LADDER);
        return terrain;
    }

    private static JsonNode level(JsonNode map, int y) {
        for (var level : map.path("floors")) if (level.path("y").asInt() == y) return level;
        throw new AssertionError("No level " + y + " in " + map);
    }

}
