package com.example.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Database configuration and connection management using HikariCP connection pooling.
 * Supports both environment variable configuration (for Docker) and default values for development.
 */
public class DatabaseConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseConfig.class);
    
    private static DatabaseConfig instance;
    private HikariDataSource dataSource;
    
    // Default configuration values
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "5432";
    private static final String DEFAULT_DATABASE = "minecraft_builds";
    private static final String DEFAULT_USER = "minecraft";
    private static final String DEFAULT_PASSWORD = "minecraft_password";
    
    private DatabaseConfig() {
        // Lazy initialization - don't initialize data source in constructor
    }
    
    public static synchronized DatabaseConfig getInstance() {
        if (instance == null) {
            instance = new DatabaseConfig();
        }
        return instance;
    }
    
    /**
     * Reset the singleton instance (for testing purposes only).
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
    
    private void initializeDataSource() {
        try {
            HikariConfig config = new HikariConfig();
            
            // Read configuration from environment variables or use defaults
            String host = getEnvOrDefault("DB_HOST", DEFAULT_HOST);
            String port = getEnvOrDefault("DB_PORT", DEFAULT_PORT);
            String database = getEnvOrDefault("DB_NAME", DEFAULT_DATABASE);
            String user = getEnvOrDefault("DB_USER", DEFAULT_USER);
            String password = getEnvOrDefault("DB_PASSWORD", DEFAULT_PASSWORD);
            
            String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
            
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user);
            config.setPassword(password);
            config.setDriverClassName("org.postgresql.Driver");
            
            // Connection pool settings
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(30000); // 30 seconds
            config.setIdleTimeout(600000); // 10 minutes
            config.setMaxLifetime(1800000); // 30 minutes
            config.setLeakDetectionThreshold(60000); // 1 minute
            
            // Connection validation
            config.setConnectionTestQuery("SELECT 1");
            config.setValidationTimeout(5000); // 5 seconds
            
            // Pool name for monitoring
            config.setPoolName("MinecraftBuildsPool");
            
            this.dataSource = new HikariDataSource(config);
            
            LOGGER.info("Database connection pool initialized successfully");
            LOGGER.info("Database URL: {}", jdbcUrl.replaceAll("://[^@]*@", "://***:***@"));
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize database connection pool", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    private String getEnvOrDefault(String envVar, String defaultValue) {
        String value = System.getenv(envVar);
        return value != null ? value : defaultValue;
    }
    
    public DataSource getDataSource() {
        if (dataSource == null) {
            synchronized (this) {
                if (dataSource == null) {
                    initializeDataSource();
                }
            }
        }
        return dataSource;
    }
    
    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }
    
    public void testConnection() throws SQLException {
        try (Connection connection = getConnection()) {
            if (connection.isValid(5)) {
                LOGGER.info("Database connection test successful");
            } else {
                throw new SQLException("Database connection validation failed");
            }
        }
    }
    
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Database connection pool shut down");
        }
        // Reset the dataSource to null so it can be reinitialized
        dataSource = null;
    }
    
    // Health check method for monitoring
    public boolean isHealthy() {
        try {
            testConnection();
            return true;
        } catch (SQLException | RuntimeException e) {
            LOGGER.warn("Database health check failed", e);
            return false;
        }
    }
}