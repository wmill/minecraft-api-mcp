package ca.waltermiller.mcpapi.buildtask.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility class for shifting task_data coordinates by a fixed offset.
 * Used to move an unexecuted build's tasks in 3D space without re-specifying them.
 */
public final class TaskDataTranslator {

    private TaskDataTranslator() {
    }

    /**
     * Returns a new JsonNode with this task's absolute-position fields shifted by (dx, dy, dz).
     * Relative fields (block arrays, sizes, heights) are left untouched.
     */
    public static JsonNode translate(TaskType taskType, JsonNode taskData, int dx, int dy, int dz) {
        if (taskData == null) {
            throw new IllegalArgumentException("Task data cannot be null");
        }

        ObjectNode copy = taskData.deepCopy();

        switch (taskType) {
            case BLOCK_SET:
            case PREFAB_DOOR:
                return shiftPoint(copy, "start_x", "start_y", "start_z", dx, dy, dz);

            case BLOCK_FILL:
                shiftPoint(copy, "x1", "y1", "z1", dx, dy, dz);
                return shiftPoint(copy, "x2", "y2", "z2", dx, dy, dz);

            case PREFAB_STAIRS:
                shiftPoint(copy, "start_x", "start_y", "start_z", dx, dy, dz);
                return shiftPoint(copy, "end_x", "end_y", "end_z", dx, dy, dz);

            case PREFAB_WINDOW:
                shiftPoint(copy, "start_x", "start_y", "start_z", dx, dy, dz);
                requireFields(copy, "end_x", "end_z");
                shift(copy, "end_x", dx);
                shift(copy, "end_z", dz);
                return copy;

            case PREFAB_LADDER:
            case PREFAB_TORCH:
            case PREFAB_SIGN:
                return shiftPoint(copy, "x", "y", "z", dx, dy, dz);

            case RAIL_SURFACE_SEGMENT:
            case RAIL_BRIDGE_SEGMENT:
            case RAIL_TUNNEL_SEGMENT:
                return translateRailSegment(copy, dx, dy, dz);

            case NBT_STRUCTURE:
                // Unreachable via BuildService.translateBuild today: recordNbtPlacement always
                // marks the build COMPLETED. Kept for switch-exhaustiveness with BoundingBox.fromTaskData.
                return shiftPoint(copy, "x", "y", "z", dx, dy, dz);

            default:
                throw new IllegalArgumentException("Unsupported task type for translation: " + taskType);
        }
    }

    private static ObjectNode shiftPoint(ObjectNode node, String xField, String yField, String zField,
                                         int dx, int dy, int dz) {
        requireFields(node, xField, yField, zField);
        shift(node, xField, dx);
        shift(node, yField, dy);
        shift(node, zField, dz);
        return node;
    }

    private static JsonNode translateRailSegment(ObjectNode copy, int dx, int dy, int dz) {
        if (!copy.has("path") || !copy.get("path").isArray()) {
            throw new IllegalArgumentException("Rail segment task data is missing a 'path' array");
        }

        ArrayNode path = (ArrayNode) copy.get("path");
        ArrayNode translatedPath = copy.arrayNode();
        for (JsonNode point : path) {
            if (!point.has("x") || !point.has("y") || !point.has("z")) {
                throw new IllegalArgumentException("Rail segment path point is missing x/y/z");
            }
            ObjectNode pointCopy = point.deepCopy();
            shift(pointCopy, "x", dx);
            shift(pointCopy, "y", dy);
            shift(pointCopy, "z", dz);
            translatedPath.add(pointCopy);
        }
        copy.set("path", translatedPath);
        return copy;
    }

    private static void requireFields(ObjectNode node, String... fields) {
        for (String field : fields) {
            if (!node.has(field)) {
                throw new IllegalArgumentException("Task data is missing required field: " + field);
            }
        }
    }

    private static void shift(ObjectNode node, String field, int delta) {
        node.put(field, node.get(field).asInt() + delta);
    }
}
