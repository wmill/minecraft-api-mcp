package ca.waltermiller.mcpapi.buildtask.service;

import ca.waltermiller.mcpapi.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates task data against endpoint schemas.
 * Provides comprehensive input validation with detailed error messages.
 * Requirements: 2.2
 */
public class TaskDataValidator {
    private static final Logger logger = LoggerFactory.getLogger(TaskDataValidator.class);

    /**
     * Validates task data for a specific task type.
     * Returns validation result with detailed error messages.
     */
    public ValidationResult validateTaskData(TaskType taskType, JsonNode taskData) {
        if (taskType == null) {
            return ValidationResult.failure("Task type cannot be null");
        }

        if (taskData == null) {
            return ValidationResult.failure("Task data cannot be null");
        }

        logger.debug("Validating task data for type: {}", taskType);

        return switch (taskType) {
            case BLOCK_SET -> validateBlockSetData(taskData);
            case BLOCK_FILL -> validateBlockFillData(taskData);
            case PREFAB_DOOR -> validateDoorData(taskData);
            case PREFAB_STAIRS -> validateStairsData(taskData);
            case PREFAB_WINDOW -> validateWindowPaneData(taskData);
            case PREFAB_TORCH -> validateTorchData(taskData);
            case PREFAB_SIGN -> validateSignData(taskData);
            case PREFAB_LADDER -> validateLadderData(taskData);
            case RAIL_SURFACE_SEGMENT, RAIL_BRIDGE_SEGMENT, RAIL_TUNNEL_SEGMENT -> validateRailSegmentData(taskData);
            case NBT_STRUCTURE -> ValidationResult.success();
            default -> ValidationResult.failure("Unknown task type: " + taskType);
        };
    }

    /**
     * Validates BLOCK_SET task data against BlockSetRequest schema.
     */
    private ValidationResult validateBlockSetData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "start_x", "start_y", "start_z");
        if (!data.has("blocks") || !data.get("blocks").isArray()) {
            errors.add("blocks is required and must be a 3D array");
        } else {
            // Validate blocks array structure
            JsonNode blocks = data.get("blocks");
            if (blocks.size() == 0) {
                errors.add("blocks array cannot be empty");
            } else {
                validateBlocksArray(blocks, errors);
            }
        }

        optionalWorld(data, errors);

        return result(errors);
    }

    /**
     * Validates BLOCK_FILL task data against FillBoxRequest schema.
     */
    private ValidationResult validateBlockFillData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "x1", "y1", "z1", "x2", "y2", "z2");
        requireBlockType(data, errors, "minecraft:stone");
        optionalWorld(data, errors);

        return result(errors);
    }

    /**
     * Validates PREFAB_DOOR task data against DoorRequest schema.
     */
    private ValidationResult validateDoorData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "start_x", "start_y", "start_z");
        requireDirection(data, errors, "facing");
        requireBlockType(data, errors, "minecraft:oak_door");

        // Validate optional fields
        if (data.has("width") && (!data.get("width").isInt() || data.get("width").asInt() <= 0)) {
            errors.add("width must be a positive integer if provided");
        }
        if (data.has("hinge") && !List.of("left", "right").contains(data.get("hinge").asText().toLowerCase())) {
            errors.add("hinge must be 'left' or 'right' if provided");
        }
        optionalBoolean(data, errors, "open");
        optionalBoolean(data, errors, "double_doors");

        return result(errors);
    }

    /**
     * Validates PREFAB_STAIRS task data against StairRequest schema.
     */
    private ValidationResult validateStairsData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "start_x", "start_y", "start_z", "end_x", "end_y", "end_z");
        requireNonEmptyString(data, errors, "block_type");
        requireNonEmptyString(data, errors, "stair_type");
        requireDirection(data, errors, "staircase_direction");
        optionalBoolean(data, errors, "fill_support");

        return result(errors);
    }

    /**
     * Validates PREFAB_WINDOW task data against WindowPaneRequest schema.
     */
    private ValidationResult validateWindowPaneData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "start_x", "start_y", "start_z", "end_x", "end_z");
        requirePositiveInt(data, errors, "height");
        requireNonEmptyString(data, errors, "block_type");

        // Validate wall alignment (must be north-south or east-west)
        if (data.has("start_x") && data.has("start_z") && data.has("end_x") && data.has("end_z")) {
            int start_x = data.get("start_x").asInt();
            int start_z = data.get("start_z").asInt();
            int end_x = data.get("end_x").asInt();
            int end_z = data.get("end_z").asInt();

            boolean isEastWest = start_z == end_z;
            boolean isNorthSouth = start_x == end_x;

            if (!isEastWest && !isNorthSouth) {
                errors.add("Window pane wall must be aligned north-south or east-west");
            }
        }

        optionalBoolean(data, errors, "waterlogged");

        return result(errors);
    }

    /**
     * Validates PREFAB_TORCH task data against TorchRequest schema.
     */
    private ValidationResult validateTorchData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "x", "y", "z");
        requireNonEmptyString(data, errors, "block_type");
        optionalDirection(data, errors, "facing");

        return result(errors);
    }

    /**
     * Validates PREFAB_SIGN task data against SignRequest schema.
     */
    private ValidationResult validateSignData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "x", "y", "z");
        requireNonEmptyString(data, errors, "block_type");
        optionalSignLines(data, errors, "front_lines");
        optionalSignLines(data, errors, "back_lines");
        optionalDirection(data, errors, "facing");

        // Validate optional rotation field for standing signs
        if (data.has("rotation")) {
            if (!data.get("rotation").isInt()) {
                errors.add("rotation must be an integer if provided");
            } else {
                int rotation = data.get("rotation").asInt();
                if (rotation < MIN_SIGN_ROTATION || rotation > MAX_SIGN_ROTATION) {
                    errors.add("rotation must be between " + MIN_SIGN_ROTATION + " and " + MAX_SIGN_ROTATION);
                }
            }
        }

        optionalBoolean(data, errors, "glowing");

        return result(errors);
    }

    /**
     * Validates PREFAB_LADDER task data against LadderRequest schema.
     */
    private ValidationResult validateLadderData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        requireInts(data, errors, "x", "y", "z");
        requirePositiveInt(data, errors, "height");
        requireBlockType(data, errors, "minecraft:ladder");
        optionalDirection(data, errors, "facing");
        optionalWorld(data, errors);

        return result(errors);
    }

    private ValidationResult validateRailSegmentData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        if (!data.has("path") || !data.get("path").isArray() || data.get("path").size() < 2) {
            errors.add("path is required and must contain at least 2 points");
        } else {
            for (int i = 0; i < data.get("path").size(); i++) {
                JsonNode point = data.get("path").get(i);
                if (!point.isObject()
                    || !point.has("x") || !point.get("x").isInt()
                    || !point.has("y") || !point.get("y").isInt()
                    || !point.has("z") || !point.get("z").isInt()) {
                    errors.add("path[" + i + "] must contain integer x, y, z fields");
                }
            }
        }

        String[] blockFields = {"rail_bed_block", "support_block", "power_block"};
        for (String field : blockFields) {
            if (!data.has(field) || !data.get(field).isTextual() || !isValidBlockIdentifier(data.get(field).asText())) {
                errors.add(field + " is required and must be a valid block identifier");
            }
        }

        if (data.has("tunnel_lining_block")
            && !data.get("tunnel_lining_block").isNull()
            && (!data.get("tunnel_lining_block").isTextual() || !isValidBlockIdentifier(data.get("tunnel_lining_block").asText()))) {
            errors.add("tunnel_lining_block must be a valid block identifier if provided");
        }

        requirePositiveInt(data, errors, "powered_rail_interval");
        optionalWorld(data, errors);

        return result(errors);
    }

    private static final List<String> HORIZONTAL_DIRECTIONS = List.of("north", "south", "east", "west");
    private static final int MAX_SIGN_LINES = 4;
    private static final int MIN_SIGN_ROTATION = 0;
    private static final int MAX_SIGN_ROTATION = 15;

    private ValidationResult result(List<String> errors) {
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    private void requireInts(JsonNode data, List<String> errors, String... fields) {
        for (String field : fields) {
            if (!data.has(field) || !data.get(field).isInt()) {
                errors.add(field + " is required and must be an integer");
            }
        }
    }

    private void requirePositiveInt(JsonNode data, List<String> errors, String field) {
        if (!data.has(field) || !data.get(field).isInt() || data.get(field).asInt() <= 0) {
            errors.add(field + " is required and must be a positive integer");
        }
    }

    private void requireNonEmptyString(JsonNode data, List<String> errors, String field) {
        if (!data.has(field) || !data.get(field).isTextual() || data.get(field).asText().trim().isEmpty()) {
            errors.add(field + " is required and must be a non-empty string");
        }
    }

    /** Requires block_type to be present and a well-formed block identifier. */
    private void requireBlockType(JsonNode data, List<String> errors, String example) {
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        } else if (!isValidBlockIdentifier(data.get("block_type").asText())) {
            errors.add("block_type must be a valid block identifier (e.g., '" + example + "')");
        }
    }

    private void requireDirection(JsonNode data, List<String> errors, String field) {
        if (!data.has(field) || !data.get(field).isTextual()) {
            errors.add(field + " is required and must be a string");
        } else if (!HORIZONTAL_DIRECTIONS.contains(data.get(field).asText().toLowerCase())) {
            errors.add(field + " must be one of: north, south, east, west");
        }
    }

    private void optionalDirection(JsonNode data, List<String> errors, String field) {
        if (!data.has(field)) {
            return;
        }
        if (!data.get(field).isTextual()) {
            errors.add(field + " must be a string if provided");
        } else if (!HORIZONTAL_DIRECTIONS.contains(data.get(field).asText().toLowerCase())) {
            errors.add(field + " must be one of: north, south, east, west");
        }
    }

    private void optionalBoolean(JsonNode data, List<String> errors, String field) {
        if (data.has(field) && !data.get(field).isBoolean()) {
            errors.add(field + " must be a boolean if provided");
        }
    }

    private void optionalWorld(JsonNode data, List<String> errors) {
        if (data.has("world") && (!data.get("world").isTextual() || data.get("world").asText().trim().isEmpty())) {
            errors.add("world must be a non-empty string if provided");
        }
    }

    private void optionalSignLines(JsonNode data, List<String> errors, String field) {
        if (!data.has(field)) {
            return;
        }
        if (!data.get(field).isArray()) {
            errors.add(field + " must be an array if provided");
        } else if (data.get(field).size() > MAX_SIGN_LINES) {
            errors.add(field + " can have maximum " + MAX_SIGN_LINES + " lines");
        }
    }

    /**
     * Validates the structure of a 3D blocks array.
     */
    private void validateBlocksArray(JsonNode blocks, List<String> errors) {
        // Check if it's a proper 3D array structure
        for (int x = 0; x < blocks.size(); x++) {
            JsonNode xArray = blocks.get(x);
            if (!xArray.isArray()) {
                errors.add("blocks[" + x + "] must be an array (Y dimension)");
                continue;
            }
            
            for (int y = 0; y < xArray.size(); y++) {
                JsonNode yArray = xArray.get(y);
                if (!yArray.isArray()) {
                    errors.add("blocks[" + x + "][" + y + "] must be an array (Z dimension)");
                    continue;
                }
                
                for (int z = 0; z < yArray.size(); z++) {
                    JsonNode blockData = yArray.get(z);
                    // null is allowed (means no change), otherwise validate block data structure
                    if (!blockData.isNull() && !blockData.isObject()) {
                        errors.add("blocks[" + x + "][" + y + "][" + z + "] must be null or a block data object");
                    } else if (blockData.isObject()) {
                        validateBlockData(blockData, x, y, z, errors);
                    }
                }
            }
        }
    }

    /**
     * Validates individual block data structure.
     */
    private void validateBlockData(JsonNode blockData, int x, int y, int z, List<String> errors) {
        String position = "[" + x + "][" + y + "][" + z + "]";
        
        if (!blockData.has("block_name") || !blockData.get("block_name").isTextual()) {
            errors.add("blocks" + position + ".block_name is required and must be a string");
        } else {
            String block_name = blockData.get("block_name").asText();
            if (!isValidBlockIdentifier(block_name)) {
                errors.add("blocks" + position + ".block_name must be a valid block identifier");
            }
        }
        
        // Properties are optional but if present should be an object
        if (blockData.has("properties") && !blockData.get("properties").isObject()) {
            errors.add("blocks" + position + ".properties must be an object if provided");
        }
    }

    /**
     * Basic validation for block identifiers.
     */
    private boolean isValidBlockIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return false;
        }
        
        // Basic format check: should contain namespace:path
        return identifier.contains(":") && 
               identifier.split(":").length == 2 &&
               !identifier.startsWith(":") && 
               !identifier.endsWith(":");
    }

    /**
     * Result of task data validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return valid ? "ValidationResult{valid=true}" : 
                   "ValidationResult{valid=false, error='" + errorMessage + "'}";
        }
    }
}
