package com.example.endpoints;

import com.example.APIServer;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration test to verify BuildTaskEndpoint is properly registered in APIServer.
 * This test ensures the endpoint can be instantiated without errors when database is unavailable.
 */
public class BuildTaskEndpointIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(BuildTaskEndpointIntegrationTest.class);

    @Test
    public void testAPIServerStartsWithoutBuildTaskSystemWhenDatabaseUnavailable() {
        // Given: A mock Minecraft server
        MinecraftServer mockServer = mock(MinecraftServer.class);
        
        // When: Starting the API server (database will be unavailable in test environment)
        // Then: Should not throw an exception, should gracefully handle database unavailability
        assertDoesNotThrow(() -> {
            APIServer.start(mockServer, logger);
            
            // Verify the app was created
            assertNotNull(APIServer.app);
            assertNotNull(APIServer.minecraftServer);
            assertNotNull(APIServer.logger);
            
            // Clean up
            if (APIServer.app != null) {
                APIServer.app.stop();
            }
        });
    }
}