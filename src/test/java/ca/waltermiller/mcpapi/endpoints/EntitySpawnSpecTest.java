package ca.waltermiller.mcpapi.endpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class EntitySpawnSpecTest {
    private static EntitySpawnRequest request(String type) {
        EntitySpawnRequest req = new EntitySpawnRequest();
        req.type = type;
        req.x = 10;
        req.y = 64;
        req.z = -5;
        return req;
    }

    private static PassengerSpec passenger(String type, SpawnOptions options) {
        PassengerSpec spec = new PassengerSpec();
        spec.type = type;
        spec.options = options;
        return spec;
    }

    @Test
    void plainRequestStillValidates() {
        assertThat(EntitySpawnSpec.validate(request("minecraft:zombie"))).isEqualTo(1);
    }

    @Test
    void legacyJsonBindsWithoutOptions() throws Exception {
        EntitySpawnRequest req = new ObjectMapper().readValue(
            "{\"type\":\"minecraft:cow\",\"x\":1,\"y\":2,\"z\":3}", EntitySpawnRequest.class);
        assertThat(req.options).isNull();
        assertThat(req.count).isNull();
        assertThat(EntitySpawnSpec.validate(req)).isEqualTo(1);
    }

    @Test
    void nestedOptionsBindFromJson() throws Exception {
        EntitySpawnRequest req = new ObjectMapper().readValue("""
            {"type":"minecraft:spider","x":0,"y":64,"z":0,"count":3,
             "options":{"glowing":true,"equipment":{"head":"minecraft:diamond_helmet"},
               "effects":[{"id":"minecraft:speed","amplifier":1}],
               "passengers":[{"type":"minecraft:skeleton","options":{"custom_name":"Rider"}}]}}
            """, EntitySpawnRequest.class);
        assertThat(req.options.passengers.getFirst().options.custom_name).isEqualTo("Rider");
        assertThat(EntitySpawnSpec.validate(req)).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, EntitySpawnSpec.MAX_COUNT + 1})
    void rejectsCountOutOfRange(int count) {
        EntitySpawnRequest req = request("minecraft:pig");
        req.count = count;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid count");
    }

    @Test
    void rejectsSpreadOutOfRange() {
        EntitySpawnRequest req = request("minecraft:pig");
        req.spread = 100.0;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid spread");
    }

    @Test
    void countsWholeRiderStack() {
        SpawnOptions top = new SpawnOptions();
        SpawnOptions middle = new SpawnOptions();
        middle.passengers = List.of(passenger("minecraft:chicken", top));
        SpawnOptions root = new SpawnOptions();
        root.passengers = List.of(passenger("minecraft:chicken", middle), passenger("minecraft:bat", null));
        EntitySpawnRequest req = request("minecraft:chicken");
        req.options = root;
        req.count = 4;
        assertThat(EntitySpawnSpec.validate(req)).isEqualTo(16);
    }

    @Test
    void rejectsTooManyEntitiesInTotal() {
        SpawnOptions root = new SpawnOptions();
        root.passengers = List.of(passenger("minecraft:chicken", null), passenger("minecraft:chicken", null));
        EntitySpawnRequest req = request("minecraft:chicken");
        req.options = root;
        req.count = EntitySpawnSpec.MAX_COUNT;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req))
            .hasMessageStartingWith("Invalid request: would spawn 96 entities");
    }

    @Test
    void rejectsStacksDeeperThanLimit() {
        SpawnOptions options = null;
        for (int i = 0; i < EntitySpawnSpec.MAX_PASSENGER_DEPTH; i++) {
            SpawnOptions next = new SpawnOptions();
            next.passengers = List.of(passenger("minecraft:chicken", options));
            options = next;
        }
        EntitySpawnRequest req = request("minecraft:chicken");
        req.options = options;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid passengers");
    }

    @Test
    void acceptsStackAtDepthLimit() {
        SpawnOptions options = null;
        for (int i = 0; i < EntitySpawnSpec.MAX_PASSENGER_DEPTH - 1; i++) {
            SpawnOptions next = new SpawnOptions();
            next.passengers = List.of(passenger("minecraft:chicken", options));
            options = next;
        }
        EntitySpawnRequest req = request("minecraft:chicken");
        req.options = options;
        assertThat(EntitySpawnSpec.validate(req)).isEqualTo(EntitySpawnSpec.MAX_PASSENGER_DEPTH);
    }

    @Test
    void rejectsPassengerWithoutType() {
        SpawnOptions root = new SpawnOptions();
        root.passengers = List.of(passenger(" ", null));
        EntitySpawnRequest req = request("minecraft:horse");
        req.options = root;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid passenger");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle"})
    void acceptsKnownEquipmentSlots(String slot) {
        SpawnOptions options = new SpawnOptions();
        options.equipment = Map.of(slot, "minecraft:stick");
        EntitySpawnRequest req = request("minecraft:zombie");
        req.options = options;
        assertThatCode(() -> EntitySpawnSpec.validate(req)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownEquipmentSlot() {
        SpawnOptions options = new SpawnOptions();
        options.equipment = Map.of("hat", "minecraft:carved_pumpkin");
        EntitySpawnRequest req = request("minecraft:zombie");
        req.options = options;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid equipment slot 'hat'");
    }

    @Test
    void rejectsEffectOutOfRange() {
        EffectSpec effect = new EffectSpec();
        effect.id = "minecraft:speed";
        effect.amplifier = 50;
        SpawnOptions options = new SpawnOptions();
        options.effects = List.of(effect);
        EntitySpawnRequest req = request("minecraft:zombie");
        req.options = options;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid amplifier 50");
    }

    @Test
    void rejectsBadScalars() {
        SpawnOptions options = new SpawnOptions();
        EntitySpawnRequest req = request("minecraft:slime");
        req.options = options;

        options.size = 40;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid size");
        options.size = null;

        options.max_health = 0.0;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid max_health");
        options.max_health = null;

        options.villager_level = 6;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid villager_level");
        options.villager_level = null;

        options.velocity = new Vec();
        options.velocity.y = 50;
        assertThatThrownBy(() -> EntitySpawnSpec.validate(req)).hasMessageStartingWith("Invalid velocity");
    }

    @Test
    void scatterKeepsFirstEntityOnTargetAndOthersWithinSpread() {
        List<double[]> offsets = EntitySpawnSpec.scatterOffsets(20, 3.0, new Random(42));
        assertThat(offsets).hasSize(20);
        assertThat(offsets.getFirst()).containsExactly(0.0, 0.0);
        List<Double> distances = new ArrayList<>();
        for (double[] o : offsets) {
            distances.add(Math.hypot(o[0], o[1]));
        }
        assertThat(distances).allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(3.0));
        assertThat(distances.subList(1, distances.size())).anySatisfy(d -> assertThat(d).isGreaterThan(0.5));
    }

    @Test
    void zeroSpreadStacksEveryoneOnTarget() {
        assertThat(EntitySpawnSpec.scatterOffsets(5, 0.0, new Random(1)))
            .allSatisfy(o -> assertThat(Math.hypot(o[0], o[1])).isZero());
    }
}
