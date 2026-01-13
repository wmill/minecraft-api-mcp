package com.example.util;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Example tests showing best practices for testing Minecraft mod logic.
 *
 * This demonstrates:
 * - Testing pure logic that uses Minecraft types but doesn't require server/world
 * - Testing coordinate calculations
 * - Testing validation logic
 * - Parameterized tests for multiple scenarios
 */
class CoordinateUtilsTest {

    /**
     * Test BlockPos coordinate calculations - no mocking needed!
     */
    @Test
    void testBlockPosOffsets() {
        BlockPos origin = new BlockPos(100, 64, 200);

        // Test cardinal directions
        assertThat(origin.north().getZ()).isEqualTo(199);  // -Z
        assertThat(origin.south().getZ()).isEqualTo(201);  // +Z
        assertThat(origin.east().getX()).isEqualTo(101);   // +X
        assertThat(origin.west().getX()).isEqualTo(99);    // -X
        assertThat(origin.up().getY()).isEqualTo(65);      // +Y
        assertThat(origin.down().getY()).isEqualTo(63);    // -Y
    }

    /**
     * Test directional offsets with distances
     */
    @ParameterizedTest
    @CsvSource({
        "NORTH, 5, 100, 64, 195",
        "SOUTH, 5, 100, 64, 205",
        "EAST, 5, 105, 64, 200",
        "WEST, 5, 95, 64, 200",
    })
    void testDirectionalOffset(Direction direction, int distance, int expectedX, int expectedY, int expectedZ) {
        BlockPos origin = new BlockPos(100, 64, 200);
        BlockPos result = origin.offset(direction, distance);

        assertThat(result.getX()).isEqualTo(expectedX);
        assertThat(result.getY()).isEqualTo(expectedY);
        assertThat(result.getZ()).isEqualTo(expectedZ);
    }

    /**
     * Test Y-coordinate validation (Minecraft 1.18+ world height)
     */
    @ParameterizedTest
    @CsvSource({
        "-64, true",   // Min height
        "0, true",     // Below sea level
        "63, true",    // Sea level
        "320, true",   // Max height
        "-65, false",  // Too low
        "321, false"   // Too high
    })
    void testYCoordinateValidation(int y, boolean expected) {
        boolean isValid = isValidY(y);
        assertThat(isValid).isEqualTo(expected);
    }

    /**
     * Test Direction enum operations
     */
    @Test
    void testDirectionRotations() {
        // Test clockwise rotation
        assertThat(Direction.NORTH.rotateYClockwise()).isEqualTo(Direction.EAST);
        assertThat(Direction.EAST.rotateYClockwise()).isEqualTo(Direction.SOUTH);
        assertThat(Direction.SOUTH.rotateYClockwise()).isEqualTo(Direction.WEST);
        assertThat(Direction.WEST.rotateYClockwise()).isEqualTo(Direction.NORTH);

        // Test counter-clockwise rotation
        assertThat(Direction.NORTH.rotateYCounterclockwise()).isEqualTo(Direction.WEST);

        // Test opposite
        assertThat(Direction.NORTH.getOpposite()).isEqualTo(Direction.SOUTH);
        assertThat(Direction.UP.getOpposite()).isEqualTo(Direction.DOWN);
    }

    /**
     * Test Direction axis checks
     */
    @Test
    void testDirectionAxisChecks() {
        // Horizontal directions
        assertThat(Direction.NORTH.getAxis().isHorizontal()).isTrue();
        assertThat(Direction.SOUTH.getAxis().isHorizontal()).isTrue();
        assertThat(Direction.EAST.getAxis().isHorizontal()).isTrue();
        assertThat(Direction.WEST.getAxis().isHorizontal()).isTrue();

        // Vertical directions
        assertThat(Direction.UP.getAxis().isVertical()).isTrue();
        assertThat(Direction.DOWN.getAxis().isVertical()).isTrue();
        assertThat(Direction.NORTH.getAxis().isVertical()).isFalse();
    }

    /**
     * Test Identifier parsing and validation
     */
    @Test
    void testIdentifierParsing() {
        // Valid identifiers - this is what we actually need to test
        Identifier stone = Identifier.tryParse("minecraft:stone");
        assertThat(stone).isNotNull();
        assertThat(stone.getNamespace()).isEqualTo("minecraft");
        assertThat(stone.getPath()).isEqualTo("stone");

        Identifier oakDoor = Identifier.tryParse("minecraft:oak_door");
        assertThat(oakDoor).isNotNull();
        assertThat(oakDoor.getNamespace()).isEqualTo("minecraft");
        assertThat(oakDoor.getPath()).isEqualTo("oak_door");

        // Valid with custom namespace
        Identifier custom = Identifier.tryParse("mymod:custom_block");
        assertThat(custom).isNotNull();
        assertThat(custom.getNamespace()).isEqualTo("mymod");
        assertThat(custom.getPath()).isEqualTo("custom_block");

        // Obviously invalid identifier
        assertThat(Identifier.tryParse("invalid::::id")).isNull();
    }

    /**
     * Test rotation clamping logic (for signs, etc.)
     */
    @ParameterizedTest
    @CsvSource({
        "0, 0",      // Min valid
        "15, 15",    // Max valid
        "-1, 0",     // Below min, clamped
        "16, 15",    // Above max, clamped
        "100, 15",   // Way above, clamped
        "-100, 0"    // Way below, clamped
    })
    void testRotationClamping(int input, int expected) {
        int result = clampRotation(input);
        assertThat(result).isEqualTo(expected);
    }

    /**
     * Test distance calculations
     */
    @Test
    void testDistanceCalculations() {
        BlockPos pos1 = new BlockPos(0, 0, 0);
        BlockPos pos2 = new BlockPos(3, 4, 0);

        // Manhattan distance
        int manhattanDistance = Math.abs(pos2.getX() - pos1.getX()) +
                                Math.abs(pos2.getY() - pos1.getY()) +
                                Math.abs(pos2.getZ() - pos1.getZ());
        assertThat(manhattanDistance).isEqualTo(7);

        // Squared distance
        double sqrDist = pos1.getSquaredDistance(pos2);
        assertThat(sqrDist).isEqualTo(25.0);
    }

    /**
     * Test bounding box calculations
     */
    @Test
    void testBoundingBox() {
        BlockPos corner1 = new BlockPos(100, 64, 200);
        BlockPos corner2 = new BlockPos(110, 70, 210);

        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        assertThat(minX).isEqualTo(100);
        assertThat(maxX).isEqualTo(110);
        assertThat(minY).isEqualTo(64);
        assertThat(maxY).isEqualTo(70);
        assertThat(minZ).isEqualTo(200);
        assertThat(maxZ).isEqualTo(210);

        // Volume calculation
        int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        assertThat(volume).isEqualTo(11 * 7 * 11); // 847 blocks
    }

    /**
     * Test string parsing for directions
     */
    @ParameterizedTest
    @CsvSource({
        "north, NORTH",
        "NORTH, NORTH",
        "North, NORTH",
        "south, SOUTH",
        "east, EAST",
        "west, WEST"
    })
    void testDirectionFromString(String input, Direction expected) {
        Direction result = parseDirection(input);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void testInvalidDirectionFromString() {
        assertThatThrownBy(() -> parseDirection("invalid"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parseDirection("up"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // Helper methods that would typically be in your actual code

    private boolean isValidY(int y) {
        return y >= -64 && y <= 320;
    }

    private int clampRotation(int rotation) {
        return Math.max(0, Math.min(15, rotation));
    }

    private Direction parseDirection(String str) {
        return switch (str.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException("Invalid direction: " + str);
        };
    }
}
