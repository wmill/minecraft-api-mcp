package ca.waltermiller.mcpapi.buildtask.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDataTranslatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void translateBlockSetShiftsStartOnlyAndLeavesBlocksUntouched() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 10);
        taskData.put("start_y", 20);
        taskData.put("start_z", 30);
        ArrayNode blocks = mapper.createArrayNode();
        blocks.addArray().addArray().add("minecraft:stone");
        taskData.set("blocks", blocks);

        JsonNode translated = TaskDataTranslator.translate(TaskType.BLOCK_SET, taskData, 1, 2, 3);

        assertThat(translated.get("start_x").asInt()).isEqualTo(11);
        assertThat(translated.get("start_y").asInt()).isEqualTo(22);
        assertThat(translated.get("start_z").asInt()).isEqualTo(33);
        assertThat(translated.get("blocks")).isEqualTo(blocks);
    }

    @Test
    void translateBlockFillShiftsBothCorners() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("x1", 0).put("y1", 64).put("z1", 0);
        taskData.put("x2", 2).put("y2", 64).put("z2", 2);

        JsonNode translated = TaskDataTranslator.translate(TaskType.BLOCK_FILL, taskData, 5, -1, 10);

        assertThat(translated.get("x1").asInt()).isEqualTo(5);
        assertThat(translated.get("y1").asInt()).isEqualTo(63);
        assertThat(translated.get("z1").asInt()).isEqualTo(10);
        assertThat(translated.get("x2").asInt()).isEqualTo(7);
        assertThat(translated.get("y2").asInt()).isEqualTo(63);
        assertThat(translated.get("z2").asInt()).isEqualTo(12);
    }

    @Test
    void translatePrefabDoorShiftsStartAndLeavesFacingWidth() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 10).put("start_y", 64).put("start_z", 30);
        taskData.put("facing", "north").put("width", 3);

        JsonNode translated = TaskDataTranslator.translate(TaskType.PREFAB_DOOR, taskData, 1, 0, -1);

        assertThat(translated.get("start_x").asInt()).isEqualTo(11);
        assertThat(translated.get("start_y").asInt()).isEqualTo(64);
        assertThat(translated.get("start_z").asInt()).isEqualTo(29);
        assertThat(translated.get("facing").asText()).isEqualTo("north");
        assertThat(translated.get("width").asInt()).isEqualTo(3);
    }

    @Test
    void translatePrefabStairsShiftsStartAndEnd() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 0).put("start_y", 64).put("start_z", 0);
        taskData.put("end_x", 2).put("end_y", 66).put("end_z", -1);

        JsonNode translated = TaskDataTranslator.translate(TaskType.PREFAB_STAIRS, taskData, 10, 0, 0);

        assertThat(translated.get("start_x").asInt()).isEqualTo(10);
        assertThat(translated.get("end_x").asInt()).isEqualTo(12);
        assertThat(translated.get("end_y").asInt()).isEqualTo(66);
        assertThat(translated.get("end_z").asInt()).isEqualTo(-1);
    }

    @Test
    void translatePrefabWindowShiftsStartAndEndButNotHeight() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 5).put("start_y", 10).put("start_z", 7);
        taskData.put("end_x", 8).put("end_z", 7);
        taskData.put("height", 4);

        JsonNode translated = TaskDataTranslator.translate(TaskType.PREFAB_WINDOW, taskData, 1, 1, 1);

        assertThat(translated.get("start_x").asInt()).isEqualTo(6);
        assertThat(translated.get("start_y").asInt()).isEqualTo(11);
        assertThat(translated.get("end_x").asInt()).isEqualTo(9);
        assertThat(translated.get("end_z").asInt()).isEqualTo(8);
        assertThat(translated.get("height").asInt()).isEqualTo(4);
        assertThat(translated.has("end_y")).isFalse();
    }

    @Test
    void translatePrefabLadderShiftsPositionButNotHeight() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("x", 1).put("y", 64).put("z", 2).put("height", 5);

        JsonNode translated = TaskDataTranslator.translate(TaskType.PREFAB_LADDER, taskData, -1, 5, 0);

        assertThat(translated.get("x").asInt()).isEqualTo(0);
        assertThat(translated.get("y").asInt()).isEqualTo(69);
        assertThat(translated.get("z").asInt()).isEqualTo(2);
        assertThat(translated.get("height").asInt()).isEqualTo(5);
    }

    @Test
    void translatePrefabTorchShiftsPosition() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("x", 1).put("y", 64).put("z", 2);

        JsonNode translated = TaskDataTranslator.translate(TaskType.PREFAB_TORCH, taskData, 1, 1, 1);

        assertThat(translated.get("x").asInt()).isEqualTo(2);
        assertThat(translated.get("y").asInt()).isEqualTo(65);
        assertThat(translated.get("z").asInt()).isEqualTo(3);
    }

    @Test
    void translateRailSegmentShiftsEveryPathPoint() {
        ObjectNode taskData = mapper.createObjectNode();
        ArrayNode path = mapper.createArrayNode();
        path.addObject().put("x", 10).put("y", 64).put("z", 20);
        path.addObject().put("x", 12).put("y", 65).put("z", 23);
        taskData.set("path", path);

        JsonNode translated = TaskDataTranslator.translate(TaskType.RAIL_SURFACE_SEGMENT, taskData, 5, 0, -5);

        ArrayNode translatedPath = (ArrayNode) translated.get("path");
        assertThat(translatedPath.get(0).get("x").asInt()).isEqualTo(15);
        assertThat(translatedPath.get(0).get("y").asInt()).isEqualTo(64);
        assertThat(translatedPath.get(0).get("z").asInt()).isEqualTo(15);
        assertThat(translatedPath.get(1).get("x").asInt()).isEqualTo(17);
        assertThat(translatedPath.get(1).get("z").asInt()).isEqualTo(18);
    }

    @Test
    void translateNbtStructureShiftsPositionButNotSize() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("x", 0).put("y", 64).put("z", 0);
        taskData.put("size_x", 3).put("size_y", 4).put("size_z", 5);
        taskData.put("filename", "house.nbt").put("rotation", "NONE");

        JsonNode translated = TaskDataTranslator.translate(TaskType.NBT_STRUCTURE, taskData, 1, 2, 3);

        assertThat(translated.get("x").asInt()).isEqualTo(1);
        assertThat(translated.get("y").asInt()).isEqualTo(66);
        assertThat(translated.get("z").asInt()).isEqualTo(3);
        assertThat(translated.get("size_x").asInt()).isEqualTo(3);
        assertThat(translated.get("filename").asText()).isEqualTo("house.nbt");
    }

    @Test
    void translateThrowsWhenRequiredFieldMissing() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("x1", 0).put("y1", 64).put("z1", 0);
        // missing x2/y2/z2

        assertThatThrownBy(() -> TaskDataTranslator.translate(TaskType.BLOCK_FILL, taskData, 1, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
