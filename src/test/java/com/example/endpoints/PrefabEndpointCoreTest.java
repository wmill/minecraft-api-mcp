package com.example.endpoints;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Test class for PrefabEndpointCore to verify core functionality works without HTTP context
 */
public class PrefabEndpointCoreTest {

    @Mock
    private MinecraftServer mockServer;
    
    @Mock
    private Logger mockLogger;
    
    private PrefabEndpointCore core;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        core = new PrefabEndpointCore(mockServer, mockLogger);
    }

    @Test
    void testCoreCanBeInstantiated() {
        // Test that the core can be instantiated without issues
        assertNotNull(core, "PrefabEndpointCore should be instantiable");
    }
}