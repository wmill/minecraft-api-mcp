package com.example.buildtask.service;

import com.example.buildtask.model.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskDataValidator.
 * Tests validation logic for all task types.
 */
class TaskDataValidatorTest {

    private TaskDataValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        validator = new TaskDataValidator();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testValidateNullTaskType() {
        JsonNode data = objectMapper.createObjectNode();
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(null, data);
        
        assertFalse(result.isValid());
        assertEquals("Task type cannot be null", result.getErrorMessage());
    }

    @Test
    void testValidateNullTaskData() {
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.BLOCK_SET, null);
        
        assertFalse(result.isValid());
        assertEquals("Task data cannot be null", result.getErrorMessage());
    }

    @Test
    void testValidBlockSetData() throws Exception {
        String validData = """
            {
                "start_x": 0,
                "start_y": 64,
                "start_z": 0,
                "blocks": [
                    [
                        [
                            {
                                "block_name": "minecraft:stone"
                            }
                        ]
                    ]
                ]
            }
            """;
        JsonNode data = objectMapper.readTree(validData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.BLOCK_SET, data);
        
        assertTrue(result.isValid());
        assertNull(result.getErrorMessage());
    }

    @Test
    void testInvalidBlockSetDataMissingCoordinates() throws Exception {
        String invalidData = """
            {
                "blocks": [
                    [
                        [
                            {
                                "block_name": "minecraft:stone"
                            }
                        ]
                    ]
                ]
            }
            """;
        JsonNode data = objectMapper.readTree(invalidData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.BLOCK_SET, data);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("start_x is required"));
        assertTrue(result.getErrorMessage().contains("start_y is required"));
        assertTrue(result.getErrorMessage().contains("start_z is required"));
    }

    @Test
    void testValidFillBoxData() throws Exception {
        String validData = """
            {
                "x1": 0,
                "y1": 64,
                "z1": 0,
                "x2": 10,
                "y2": 74,
                "z2": 10,
                "block_type": "minecraft:stone"
            }
            """;
        JsonNode data = objectMapper.readTree(validData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.BLOCK_FILL, data);
        
        assertTrue(result.isValid());
    }

    @Test
    void testInvalidFillBoxDataMissingBlockType() throws Exception {
        String invalidData = """
            {
                "x1": 0,
                "y1": 64,
                "z1": 0,
                "x2": 10,
                "y2": 74,
                "z2": 10
            }
            """;
        JsonNode data = objectMapper.readTree(invalidData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.BLOCK_FILL, data);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("block_type is required"));
    }

    @Test
    void testValidDoorData() throws Exception {
        String validData = """
            {
                "start_x": 0,
                "start_y": 64,
                "start_z": 0,
                "facing": "north",
                "block_type": "minecraft:oak_door"
            }
            """;
        JsonNode data = objectMapper.readTree(validData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.PREFAB_DOOR, data);
        
        assertTrue(result.isValid());
    }

    @Test
    void testInvalidDoorDataBadFacing() throws Exception {
        String invalidData = """
            {
                "start_x": 0,
                "start_y": 64,
                "start_z": 0,
                "facing": "invalid",
                "block_type": "minecraft:oak_door"
            }
            """;
        JsonNode data = objectMapper.readTree(invalidData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.PREFAB_DOOR, data);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("facing must be one of: north, south, east, west"));
    }

    @Test
    void testValidTorchData() throws Exception {
        String validData = """
            {
                "x": 0,
                "y": 64,
                "z": 0,
                "block_type": "minecraft:torch"
            }
            """;
        JsonNode data = objectMapper.readTree(validData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.PREFAB_TORCH, data);
        
        assertTrue(result.isValid());
    }

    @Test
    void testValidSignData() throws Exception {
        String validData = """
            {
                "x": 0,
                "y": 64,
                "z": 0,
                "block_type": "minecraft:oak_sign",
                "front_lines": ["Hello", "World"],
                "rotation": 8
            }
            """;
        JsonNode data = objectMapper.readTree(validData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.PREFAB_SIGN, data);
        
        assertTrue(result.isValid());
    }

    @Test
    void testInvalidSignDataTooManyLines() throws Exception {
        String invalidData = """
            {
                "x": 0,
                "y": 64,
                "z": 0,
                "block_type": "minecraft:oak_sign",
                "front_lines": ["Line1", "Line2", "Line3", "Line4", "Line5"]
            }
            """;
        JsonNode data = objectMapper.readTree(invalidData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.PREFAB_SIGN, data);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("front_lines can have maximum 4 lines"));
    }

    @Test
    void testInvalidBlockIdentifier() throws Exception {
        String invalidData = """
            {
                "x1": 0,
                "y1": 64,
                "z1": 0,
                "x2": 10,
                "y2": 74,
                "z2": 10,
                "block_type": "invalid_identifier"
            }
            """;
        JsonNode data = objectMapper.readTree(invalidData);
        
        TaskDataValidator.ValidationResult result = validator.validateTaskData(TaskType.BLOCK_FILL, data);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("block_type must be a valid block identifier"));
    }

    @Test
    void testUnknownTaskType() {
        JsonNode data = objectMapper.createObjectNode();
        
        // This will test the default case in the switch statement
        // We can't create an unknown enum value, but we can test with null which triggers the validation
        TaskDataValidator.ValidationResult result = validator.validateTaskData(null, data);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("Task type cannot be null"));
    }
}
