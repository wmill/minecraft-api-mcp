package com.example.endpoints;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import io.javalin.Javalin;

/**
 * Test class to verify that the refactored endpoints provide access to their core functionality
 */
public class EndpointRefactoringTest {

    @Mock
    private Javalin mockApp;
    
    @Mock
    private MinecraftServer mockServer;
    
    @Mock
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testBlocksEndpointProvidesCore() {
        // Test that BlocksEndpoint provides access to its core functionality
        BlocksEndpoint endpoint = new BlocksEndpoint(mockApp, mockServer, mockLogger);
        
        assertNotNull(endpoint.getCore(), "BlocksEndpoint should provide access to its core");
        assertTrue(endpoint.getCore() instanceof BlocksEndpointCore, "Core should be instance of BlocksEndpointCore");
    }

    @Test
    void testPrefabEndpointProvidesCore() {
        // Test that PrefabEndpoint provides access to its core functionality
        PrefabEndpoint endpoint = new PrefabEndpoint(mockApp, mockServer, mockLogger);
        
        assertNotNull(endpoint.getCore(), "PrefabEndpoint should provide access to its core");
        assertTrue(endpoint.getCore() instanceof PrefabEndpointCore, "Core should be instance of PrefabEndpointCore");
    }

    @Test
    void testCoreInstancesAreIndependent() {
        // Test that different endpoint instances have independent core instances
        BlocksEndpoint endpoint1 = new BlocksEndpoint(mockApp, mockServer, mockLogger);
        BlocksEndpoint endpoint2 = new BlocksEndpoint(mockApp, mockServer, mockLogger);
        
        assertNotSame(endpoint1.getCore(), endpoint2.getCore(), 
                     "Different endpoint instances should have independent core instances");
    }
}