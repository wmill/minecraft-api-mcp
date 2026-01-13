package com.example.buildtask.model;

/**
 * Represents the status of a build throughout its lifecycle.
 */
public enum BuildStatus {
    CREATED,      // Build has been created but no tasks executed
    IN_PROGRESS,  // Build execution has started
    COMPLETED,    // All tasks have been executed successfully
    FAILED        // Build execution failed
}