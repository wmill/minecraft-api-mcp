package com.example.buildtask.model;

/**
 * Represents the execution status of a build task.
 */
public enum TaskStatus {
    QUEUED,     // Task is waiting to be executed
    EXECUTING,  // Task is currently being executed
    COMPLETED,  // Task has been executed successfully
    FAILED      // Task execution failed
}