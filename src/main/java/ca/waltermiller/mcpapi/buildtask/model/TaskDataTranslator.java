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
                requireFields(copy, "start_x", "start_y", "start_z");
                shift(copy, "start_x", dx);
                shift(copy, "start_y", dy);
                shift(copy, "start_z", dz);
                return copy;

            case BLOCK_FILL:
                requireFields(copy, "x1", "y1", "z1", "x2", "y2", "z2");
                shift(copy, "x1", dx);
                shift(copy, "y1", dy);
                shift(copy, "z1", dz);
                shift(copy, "x2", dx);
                shift(copy, "y2", dy);
                shift(copy, "z2", dz);
                return copy;

            case PREFAB_DOOR:
                requireFields(copy, "start_x", "start_y", "start_z");
                shift(copy, "start_x", dx);
                shift(copy, "start_y", dy);
                shift(copy, "start_z", dz);
                return copy;

            case PREFAB_STAIRS:
                requireFields(copy, "start_x", "start_y", "start_z", "end_x", "end_y", "end_z");
                shift(copy, "start_x", dx);
                shift(copy, "start_y", dy);
                shift(copy, "start_z", dz);
                shift(copy, "end_x", dx);
                shift(copy, "end_y", dy);
                shift(copy, "end_z", dz);
                return copy;

            case PREFAB_WINDOW:
                requireFields(copy, "start_x", "start_y", "start_z", "end_x", "end_z");
                shift(copy, "start_x", dx);
                shift(copy, "start_y", dy);
                shift(copy, "start_z", dz);
                shift(copy, "end_x", dx);
                shift(copy, "end_z", dz);
                return copy;

            case PREFAB_LADDER:
                requireFields(copy, "x", "y", "z");
                shift(copy, "x", dx);
                shift(copy, "y", dy);
                shift(copy, "z", dz);
                return copy;

            case PREFAB_TORCH:
            case PREFAB_SIGN:
                requireFields(copy, "x", "y", "z");
                shift(copy, "x", dx);
                shift(copy, "y", dy);
                shift(copy, "z", dz);
                return copy;

            case RAIL_SURFACE_SEGMENT:
            case RAIL_BRIDGE_SEGMENT:
            case RAIL_TUNNEL_SEGMENT:
                return translateRailSegment(copy, dx, dy, dz);

            case NBT_STRUCTURE:
                // Unreachable via BuildService.translateBuild today: recordNbtPlacement always
                // marks the build COMPLETED. Kept for switch-exhaustiveness with BoundingBox.fromTaskData.
                requireFields(copy, "x", "y", "z");
                shift(copy, "x", dx);
                shift(copy, "y", dy);
                shift(copy, "z", dz);
                return copy;

            default:
                throw new IllegalArgumentException("Unsupported task type for translation: " + taskType);
        }
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
