package com.example.buildtask.service;

import com.example.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public TaskDataValidator() {
        this.objectMapper = new ObjectMapper();
    }

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
            default -> ValidationResult.failure("Unknown task type: " + taskType);
        };
    }

    /**
     * Validates BLOCK_SET task data against BlockSetRequest schema.
     */
    private ValidationResult validateBlockSetData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required fields
        if (!data.has("start_x") || !data.get("start_x").isInt()) {
            errors.add("start_x is required and must be an integer");
        }
        if (!data.has("start_y") || !data.get("start_y").isInt()) {
            errors.add("start_y is required and must be an integer");
        }
        if (!data.has("start_z") || !data.get("start_z").isInt()) {
            errors.add("start_z is required and must be an integer");
        }
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

        // Validate optional world field
        if (data.has("world") && (!data.get("world").isTextual() || data.get("world").asText().trim().isEmpty())) {
            errors.add("world must be a non-empty string if provided");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    /**
     * Validates BLOCK_FILL task data against FillBoxRequest schema.
     */
    private ValidationResult validateBlockFillData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required coordinate fields
        if (!data.has("x1") || !data.get("x1").isInt()) {
            errors.add("x1 is required and must be an integer");
        }
        if (!data.has("y1") || !data.get("y1").isInt()) {
            errors.add("y1 is required and must be an integer");
        }
        if (!data.has("z1") || !data.get("z1").isInt()) {
            errors.add("z1 is required and must be an integer");
        }
        if (!data.has("x2") || !data.get("x2").isInt()) {
            errors.add("x2 is required and must be an integer");
        }
        if (!data.has("y2") || !data.get("y2").isInt()) {
            errors.add("y2 is required and must be an integer");
        }
        if (!data.has("z2") || !data.get("z2").isInt()) {
            errors.add("z2 is required and must be an integer");
        }

        // Check required block_type field
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        } else {
            String block_type = data.get("block_type").asText();
            if (!isValidBlockIdentifier(block_type)) {
                errors.add("block_type must be a valid block identifier (e.g., 'minecraft:stone')");
            }
        }

        // Validate optional world field
        if (data.has("world") && (!data.get("world").isTextual() || data.get("world").asText().trim().isEmpty())) {
            errors.add("world must be a non-empty string if provided");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    /**
     * Validates PREFAB_DOOR task data against DoorRequest schema.
     */
    private ValidationResult validateDoorData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required coordinate fields
        if (!data.has("start_x") || !data.get("start_x").isInt()) {
            errors.add("start_x is required and must be an integer");
        }
        if (!data.has("start_y") || !data.get("start_y").isInt()) {
            errors.add("start_y is required and must be an integer");
        }
        if (!data.has("start_z") || !data.get("start_z").isInt()) {
            errors.add("start_z is required and must be an integer");
        }

        // Check required facing field
        if (!data.has("facing") || !data.get("facing").isTextual()) {
            errors.add("facing is required and must be a string");
        } else {
            String facing = data.get("facing").asText().toLowerCase();
            if (!List.of("north", "south", "east", "west").contains(facing)) {
                errors.add("facing must be one of: north, south, east, west");
            }
        }

        // Check required block_type field
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        } else {
            String block_type = data.get("block_type").asText();
            if (!isValidBlockIdentifier(block_type)) {
                errors.add("block_type must be a valid block identifier (e.g., 'minecraft:oak_door')");
            }
        }

        // Validate optional fields
        if (data.has("width") && (!data.get("width").isInt() || data.get("width").asInt() <= 0)) {
            errors.add("width must be a positive integer if provided");
        }
        if (data.has("hinge") && !List.of("left", "right").contains(data.get("hinge").asText().toLowerCase())) {
            errors.add("hinge must be 'left' or 'right' if provided");
        }
        if (data.has("open") && !data.get("open").isBoolean()) {
            errors.add("open must be a boolean if provided");
        }
        if (data.has("double_doors") && !data.get("double_doors").isBoolean()) {
            errors.add("double_doors must be a boolean if provided");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    /**
     * Validates PREFAB_STAIRS task data against StairRequest schema.
     */
    private ValidationResult validateStairsData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required coordinate fields
        String[] requiredCoords = {"start_x", "start_y", "start_z", "end_x", "end_y", "end_z"};
        for (String coord : requiredCoords) {
            if (!data.has(coord) || !data.get(coord).isInt()) {
                errors.add(coord + " is required and must be an integer");
            }
        }

        // Check required block_type field
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        }

        // Check required stair_type field
        if (!data.has("stair_type") || !data.get("stair_type").isTextual() || data.get("stair_type").asText().trim().isEmpty()) {
            errors.add("stair_type is required and must be a non-empty string");
        }

        // Check required staircase_direction field
        if (!data.has("staircase_direction") || !data.get("staircase_direction").isTextual()) {
            errors.add("staircase_direction is required and must be a string");
        } else {
            String direction = data.get("staircase_direction").asText().toLowerCase();
            if (!List.of("north", "south", "east", "west").contains(direction)) {
                errors.add("staircase_direction must be one of: north, south, east, west");
            }
        }

        // Validate optional fill_support field
        if (data.has("fill_support") && !data.get("fill_support").isBoolean()) {
            errors.add("fill_support must be a boolean if provided");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    /**
     * Validates PREFAB_WINDOW task data against WindowPaneRequest schema.
     */
    private ValidationResult validateWindowPaneData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required coordinate fields
        String[] requiredCoords = {"start_x", "start_y", "start_z", "end_x", "end_z"};
        for (String coord : requiredCoords) {
            if (!data.has(coord) || !data.get(coord).isInt()) {
                errors.add(coord + " is required and must be an integer");
            }
        }

        // Check required height field
        if (!data.has("height") || !data.get("height").isInt() || data.get("height").asInt() <= 0) {
            errors.add("height is required and must be a positive integer");
        }

        // Check required block_type field
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        }

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

        // Validate optional waterlogged field
        if (data.has("waterlogged") && !data.get("waterlogged").isBoolean()) {
            errors.add("waterlogged must be a boolean if provided");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    /**
     * Validates PREFAB_TORCH task data against TorchRequest schema.
     */
    private ValidationResult validateTorchData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required coordinate fields
        if (!data.has("x") || !data.get("x").isInt()) {
            errors.add("x is required and must be an integer");
        }
        if (!data.has("y") || !data.get("y").isInt()) {
            errors.add("y is required and must be an integer");
        }
        if (!data.has("z") || !data.get("z").isInt()) {
            errors.add("z is required and must be an integer");
        }

        // Check required block_type field
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        }

        // Validate optional facing field for wall torches
        if (data.has("facing")) {
            if (!data.get("facing").isTextual()) {
                errors.add("facing must be a string if provided");
            } else {
                String facing = data.get("facing").asText().toLowerCase();
                if (!List.of("north", "south", "east", "west").contains(facing)) {
                    errors.add("facing must be one of: north, south, east, west");
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
    }

    /**
     * Validates PREFAB_SIGN task data against SignRequest schema.
     */
    private ValidationResult validateSignData(JsonNode data) {
        List<String> errors = new ArrayList<>();

        // Check required coordinate fields
        if (!data.has("x") || !data.get("x").isInt()) {
            errors.add("x is required and must be an integer");
        }
        if (!data.has("y") || !data.get("y").isInt()) {
            errors.add("y is required and must be an integer");
        }
        if (!data.has("z") || !data.get("z").isInt()) {
            errors.add("z is required and must be an integer");
        }

        // Check required block_type field
        if (!data.has("block_type") || !data.get("block_type").isTextual() || data.get("block_type").asText().trim().isEmpty()) {
            errors.add("block_type is required and must be a non-empty string");
        }

        // Validate optional text fields
        if (data.has("front_lines")) {
            if (!data.get("front_lines").isArray()) {
                errors.add("front_lines must be an array if provided");
            } else if (data.get("front_lines").size() > 4) {
                errors.add("front_lines can have maximum 4 lines");
            }
        }
        if (data.has("back_lines")) {
            if (!data.get("back_lines").isArray()) {
                errors.add("back_lines must be an array if provided");
            } else if (data.get("back_lines").size() > 4) {
                errors.add("back_lines can have maximum 4 lines");
            }
        }

        // Validate optional facing field for wall signs
        if (data.has("facing")) {
            if (!data.get("facing").isTextual()) {
                errors.add("facing must be a string if provided");
            } else {
                String facing = data.get("facing").asText().toLowerCase();
                if (!List.of("north", "south", "east", "west").contains(facing)) {
                    errors.add("facing must be one of: north, south, east, west");
                }
            }
        }

        // Validate optional rotation field for standing signs
        if (data.has("rotation")) {
            if (!data.get("rotation").isInt()) {
                errors.add("rotation must be an integer if provided");
            } else {
                int rotation = data.get("rotation").asInt();
                if (rotation < 0 || rotation > 15) {
                    errors.add("rotation must be between 0 and 15");
                }
            }
        }

        // Validate optional glowing field
        if (data.has("glowing") && !data.get("glowing").isBoolean()) {
            errors.add("glowing must be a boolean if provided");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(String.join("; ", errors));
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