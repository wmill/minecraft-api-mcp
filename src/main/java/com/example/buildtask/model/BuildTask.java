package com.example.buildtask.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents an individual building task within a build.
 * Contains task data, coordinates, and execution status.
 * Requirements: 1.4, 2.4, 4.3
 */
public class BuildTask {
    private UUID id;
    private UUID buildId;
    private int taskOrder;
    private TaskType taskType;
    private JsonNode taskData;
    private TaskStatus status;
    private Instant executedAt;
    private String errorMessage;
    private BoundingBox coordinates;
    private String description;

    public BuildTask() {
        this.id = UUID.randomUUID();
        this.status = TaskStatus.QUEUED;
    }

    public BuildTask(UUID buildId, int taskOrder, TaskType taskType, JsonNode taskData, String description) {
        this();
        this.buildId = buildId;
        this.taskOrder = taskOrder;
        this.taskType = taskType;
        this.taskData = taskData;
        this.coordinates = BoundingBox.fromTaskData(taskType, taskData);
        this.description = description;
    }

    /**
     * Marks the task as completed successfully.
     */
    public void markCompleted() {
        this.status = TaskStatus.COMPLETED;
        this.executedAt = Instant.now();
        this.errorMessage = null;
    }

    /**
     * Marks the task as failed with an error message.
     */
    public void markFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.executedAt = Instant.now();
        this.errorMessage = errorMessage;
    }

    /**
     * Marks the task as currently executing.
     */
    public void markExecuting() {
        this.status = TaskStatus.EXECUTING;
    }

    /**
     * Updates the coordinates based on the current task data.
     */
    public void updateCoordinates() {
        this.coordinates = BoundingBox.fromTaskData(this.taskType, this.taskData);
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBuildId() {
        return buildId;
    }

    public void setBuildId(UUID buildId) {
        this.buildId = buildId;
    }

    public int getTaskOrder() {
        return taskOrder;
    }

    public void setTaskOrder(int taskOrder) {
        this.taskOrder = taskOrder;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
        // Update coordinates when task type changes
        if (this.taskData != null) {
            updateCoordinates();
        }
    }

    public JsonNode getTaskData() {
        return taskData;
    }

    public void setTaskData(JsonNode taskData) {
        this.taskData = taskData;
        // Update coordinates when task data changes
        if (this.taskType != null) {
            updateCoordinates();
        }
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public BoundingBox getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(BoundingBox coordinates) {
        this.coordinates = coordinates;
    }

    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BuildTask buildTask = (BuildTask) obj;
        return id != null && id.equals(buildTask.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "BuildTask{" +
                "id=" + id +
                ", buildId=" + buildId +
                ", taskOrder=" + taskOrder +
                ", taskType=" + taskType +
                ", status=" + status +
                ", executedAt=" + executedAt +
                ", errorMessage='" + errorMessage + '\'' +
                ", coordinates=" + coordinates +
                ", description='" + description + '\'' +
                '}';
    }
}