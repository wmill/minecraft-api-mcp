package com.example.buildtask.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundingBoxTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromBlockSetRequestUsesStartCoordinatesAndNonNullBlocks() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 10);
        taskData.put("start_y", 20);
        taskData.put("start_z", 30);
        taskData.set("blocks", buildBlocks3d());

        BoundingBox box = BoundingBox.fromBlockSetRequest(taskData);

        assertThat(box).isNotNull();
        assertThat(box.getMinX()).isEqualTo(10);
        assertThat(box.getMinY()).isEqualTo(20);
        assertThat(box.getMinZ()).isEqualTo(30);
        assertThat(box.getMaxX()).isEqualTo(11);
        assertThat(box.getMaxY()).isEqualTo(21);
        assertThat(box.getMaxZ()).isEqualTo(31);
    }

    @Test
    void fromBlockSetRequestReturnsNullWhenAllBlocksAreNull() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 5);
        taskData.put("start_y", 6);
        taskData.put("start_z", 7);
        taskData.set("blocks", buildAllNullBlocks3d());

        BoundingBox box = BoundingBox.fromBlockSetRequest(taskData);

        assertThat(box).isNull();
    }

    @Test
    void fromFillBoxRequestUsesCorners() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("x1", 12);
        taskData.put("y1", 5);
        taskData.put("z1", -2);
        taskData.put("x2", 8);
        taskData.put("y2", 9);
        taskData.put("z2", 3);

        BoundingBox box = BoundingBox.fromFillBoxRequest(taskData);

        assertThat(box).isNotNull();
        assertThat(box.getMinX()).isEqualTo(8);
        assertThat(box.getMinY()).isEqualTo(5);
        assertThat(box.getMinZ()).isEqualTo(-2);
        assertThat(box.getMaxX()).isEqualTo(12);
        assertThat(box.getMaxY()).isEqualTo(9);
        assertThat(box.getMaxZ()).isEqualTo(3);
    }

    @Test
    void fromTaskDataPrefabDoorUsesFacingAndWidth() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 10);
        taskData.put("start_y", 64);
        taskData.put("start_z", 30);
        taskData.put("facing", "north");
        taskData.put("width", 3);

        BoundingBox box = BoundingBox.fromTaskData(TaskType.PREFAB_DOOR, taskData);

        assertThat(box).isNotNull();
        assertThat(box.getMinX()).isEqualTo(10);
        assertThat(box.getMaxX()).isEqualTo(12);
        assertThat(box.getMinY()).isEqualTo(64);
        assertThat(box.getMaxY()).isEqualTo(65);
        assertThat(box.getMinZ()).isEqualTo(30);
        assertThat(box.getMaxZ()).isEqualTo(30);
    }

    @Test
    void fromTaskDataPrefabStairsUsesStartAndEnd() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 0);
        taskData.put("start_y", 64);
        taskData.put("start_z", 0);
        taskData.put("end_x", 2);
        taskData.put("end_y", 66);
        taskData.put("end_z", -1);

        BoundingBox box = BoundingBox.fromTaskData(TaskType.PREFAB_STAIRS, taskData);

        assertThat(box).isNotNull();
        assertThat(box.getMinX()).isEqualTo(0);
        assertThat(box.getMaxX()).isEqualTo(2);
        assertThat(box.getMinY()).isEqualTo(64);
        assertThat(box.getMaxY()).isEqualTo(66);
        assertThat(box.getMinZ()).isEqualTo(-1);
        assertThat(box.getMaxZ()).isEqualTo(0);
    }

    @Test
    void fromTaskDataPrefabWindowUsesHeight() {
        ObjectNode taskData = mapper.createObjectNode();
        taskData.put("start_x", 5);
        taskData.put("start_y", 10);
        taskData.put("start_z", 7);
        taskData.put("end_x", 8);
        taskData.put("end_z", 7);
        taskData.put("height", 4);

        BoundingBox box = BoundingBox.fromTaskData(TaskType.PREFAB_WINDOW, taskData);

        assertThat(box).isNotNull();
        assertThat(box.getMinX()).isEqualTo(5);
        assertThat(box.getMaxX()).isEqualTo(8);
        assertThat(box.getMinY()).isEqualTo(10);
        assertThat(box.getMaxY()).isEqualTo(13);
        assertThat(box.getMinZ()).isEqualTo(7);
        assertThat(box.getMaxZ()).isEqualTo(7);
    }

    private ArrayNode buildBlocks3d() {
        ArrayNode blocks = mapper.createArrayNode();
        for (int x = 0; x < 2; x++) {
            ArrayNode blocksX = mapper.createArrayNode();
            for (int y = 0; y < 2; y++) {
                ArrayNode blocksY = mapper.createArrayNode();
                for (int z = 0; z < 2; z++) {
                    if ((x == 0 && y == 0 && z == 0) || (x == 1 && y == 1 && z == 1)) {
                        ObjectNode block = mapper.createObjectNode();
                        block.put("block_type", "minecraft:stone");
                        blocksY.add(block);
                    } else {
                        blocksY.addNull();
                    }
                }
                blocksX.add(blocksY);
            }
            blocks.add(blocksX);
        }
        return blocks;
    }

    private ArrayNode buildAllNullBlocks3d() {
        ArrayNode blocks = mapper.createArrayNode();
        for (int x = 0; x < 1; x++) {
            ArrayNode blocksX = mapper.createArrayNode();
            for (int y = 0; y < 1; y++) {
                ArrayNode blocksY = mapper.createArrayNode();
                blocksY.addNull();
                blocksX.add(blocksY);
            }
            blocks.add(blocksX);
        }
        return blocks;
    }
}
