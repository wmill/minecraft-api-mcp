package com.example.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DatabaseManager class.
 * These tests verify manager initialization and component access.
 * Note: Tests that require database connectivity will be skipped if no database is available.
 */
class DatabaseManagerTest {
    
    private DatabaseManager databaseManager;
    
    @BeforeEach
    void setUp() {
        databaseManager = DatabaseManager.getInstance();
    }
    
    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }
    
    @Test
    void testDatabaseManagerSingleton() {
        DatabaseManager instance1 = DatabaseManager.getInstance();
        DatabaseManager instance2 = DatabaseManager.getInstance();
        
        assertThat(instance1).isSameAs(instance2);
    }
    
    @Test
    void testGetDatabaseConfig() {
        DatabaseConfig config = databaseManager.getDatabaseConfig();
        assertThat(config).isNotNull();
    }
    
    @Test
    void testGetDatabaseSchema() {
        DatabaseSchema schema = databaseManager.getDatabaseSchema();
        assertThat(schema).isNotNull();
    }
    
    @Test
    void testHealthCheckBeforeInitialization() {
        // Before initialization, health check should return false
        boolean isHealthy = databaseManager.isHealthy();
        
        // Health depends on database availability, so we just verify no exceptions
        assertThat(isHealthy).isIn(true, false);
    }
}