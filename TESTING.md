# Testing Guide for Minecraft Fabric Mods

## Overview

This project uses **JUnit 5** with **Mockito** for testing. However, testing Minecraft mods requires a special approach because many Minecraft classes cannot be easily mocked.

## Test Setup

Dependencies added to `build.gradle`:
- `junit-jupiter` - JUnit 5 test framework
- `mockito-core` - Mocking framework
- `mockito-junit-jupiter` - Mockito integration with JUnit 5
- `assertj-core` - Fluent assertions library
- `byte-buddy` - Required for Java 23 compatibility

## Testing Strategies

### ✅ RECOMMENDED: Test Pure Logic

**What to test:**
- Coordinate calculations using `BlockPos`
- Direction parsing and rotations
- Identifier parsing validation
- Validation logic (coordinates, rotation clamping, etc.)
- Distance and bounding box calculations

**Example:**
```java
@Test
void testBlockPosOffsets() {
    BlockPos origin = new BlockPos(100, 64, 200);

    assertThat(origin.north().getZ()).isEqualTo(199);  // -Z
    assertThat(origin.south().getZ()).isEqualTo(201);  // +Z
    assertThat(origin.east().getX()).isEqualTo(101);   // +X
}
```

These tests use Minecraft types (`BlockPos`, `Direction`, `Identifier`) but don't require a running Minecraft server or complex mocking.

### ⚠️ AVOID: Mocking Heavy Minecraft Classes

**Don't try to mock:**
- `ServerWorld`
- `MinecraftServer`
- `WorldChunk`
- `BlockEntity` (complex ones)

These classes have complex initialization that fails during mocking. You'll see errors like:
```
Cannot instrument class net.minecraft.server.world.ServerWorld
```

### 🎯 BEST PRACTICE: Extract Testable Logic

Instead of testing endpoint code directly, extract the logic into testable helper methods:

**Bad (hard to test):**
```java
app.post("/api/endpoint", ctx -> {
    // Complex logic mixed with Minecraft/web code
    int x = req.x;
    if (x < -64 || x > 320) { // validation
        ctx.status(400);
        return;
    }
    // more logic...
});
```

**Good (easy to test):**
```java
// In your endpoint class
private boolean isValidY(int y) {
    return y >= -64 && y <= 320;
}

// In your test
@Test
void testYCoordinateValidation() {
    assertThat(isValidY(63)).isTrue();
    assertThat(isValidY(-65)).isFalse();
}
```

### Alternative: Integration Tests

For testing actual endpoint behavior, consider:
1. **Manual testing** with curl commands
2. **Docker-based integration tests** that spin up a real Minecraft server
3. **Postman/Newman** for API endpoint testing

## Running Tests

```bash
# Run all tests
./gradlew test

# Run tests and generate report
./gradlew test --rerun-tasks

# View test report
open build/reports/tests/test/index.html
```

## Example Test Patterns

### Parameterized Tests

Test multiple scenarios efficiently:

```java
@ParameterizedTest
@CsvSource({
    "north, NORTH",
    "south, SOUTH",
    "east, EAST",
    "west, WEST"
})
void testDirectionParsing(String input, Direction expected) {
    Direction result = parseDirection(input);
    assertThat(result).isEqualTo(expected);
}
```

### Testing Validation Logic

```java
@ParameterizedTest
@CsvSource({
    "-64, true",   // Min height
    "320, true",   // Max height
    "-65, false",  // Too low
    "321, false"   // Too high
})
void testYCoordinateValidation(int y, boolean expected) {
    assertThat(isValidY(y)).isEqualTo(expected);
}
```

### Testing Calculations

```java
@Test
void testBoundingBox() {
    BlockPos corner1 = new BlockPos(100, 64, 200);
    BlockPos corner2 = new BlockPos(110, 70, 210);

    int volume = (110 - 100 + 1) * (70 - 64 + 1) * (210 - 200 + 1);
    assertThat(volume).isEqualTo(847);
}
```

## Tips for Refactoring

When refactoring your code with tests:

1. **Start by extracting helper methods** for validation and calculations
2. **Write tests for these helpers** before refactoring
3. **Run tests frequently** during refactoring
4. **Keep business logic separate** from Minecraft/Javalin code

Example refactoring workflow:
```bash
# 1. Extract helper method
# 2. Write test for helper
./gradlew test

# 3. Refactor main code to use helper
./gradlew test

# 4. Repeat for each piece of logic
```

## Common Gotchas

1. **Java 23 Compatibility**: We use ByteBuddy experimental mode for Java 23 support
2. **Null Handling**: `Identifier.tryParse(null)` may throw NPE in some versions
3. **Empty Strings**: `Identifier.tryParse("")` may return valid identifiers in some versions
4. **Static Initialization**: Some Minecraft classes fail during static initialization

## Further Reading

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [Fabric Testing](https://fabricmc.net/wiki/tutorial:testing)

## Summary

**✅ DO:**
- Test pure logic and calculations
- Use Minecraft types that don't require server context
- Extract testable methods from your endpoints
- Use parameterized tests for multiple scenarios

**❌ DON'T:**
- Try to mock `ServerWorld`, `MinecraftServer`, etc.
- Test endpoint routing directly (test the logic instead)
- Expect full integration testing without a real server
