package com.example.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for database functionality.
 * These tests require a running PostgreSQL instance and are only enabled when DB_TEST=true.
 */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class DatabaseIntegrationTest {
    
    private DatabaseManager databaseManager;
    
    @BeforeEach
    void setUp() throws SQLException {
        // Reset singleton to ensure clean state for each test
        DatabaseManager.resetInstance();
        databaseManager = DatabaseManager.getInstance();
        databaseManager.initialize();
    }
    
    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        // Reset singleton after each test
        DatabaseManager.resetInstance();
    }
    
    @Test
    void testDatabaseConnection() throws SQLException {
        DatabaseConfig config = databaseManager.getDatabaseConfig();
        
        try (Connection connection = config.getConnection()) {
            assertThat(connection).isNotNull();
            assertThat(connection.isValid(5)).isTrue();
        }
    }
    
    @Test
    void testSchemaInitialization() throws SQLException {
        DatabaseSchema schema = databaseManager.getDatabaseSchema();
        
        // Schema should be initialized during manager initialization
        assertThat(schema.isSchemaInitialized()).isTrue();
    }
    
    @Test
    void testHealthCheck() {
        assertThat(databaseManager.isHealthy()).isTrue();
    }
}