package com.example.buildtask.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a build - a collection of related building tasks with metadata and status tracking.
 * Requirements: 1.4, 2.4, 4.3
 */
public class Build {
    private UUID id;
    private String name;
    private String description;
    private BuildStatus status;
    private Instant createdAt;
    private Instant completedAt;
    private String world;

    public Build() {
        this.id = UUID.randomUUID();
        this.status = BuildStatus.CREATED;
        this.createdAt = Instant.now();
        this.world = "minecraft:overworld";
    }

    public Build(String name, String description) {
        this();
        this.name = name;
        this.description = description;
    }

    public Build(String name, String description, String world) {
        this(name, description);
        this.world = world;
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BuildStatus getStatus() {
        return status;
    }

    public void setStatus(BuildStatus status) {
        this.status = status;
        if (status == BuildStatus.COMPLETED && completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Build build = (Build) obj;
        return id != null && id.equals(build.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "Build{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                ", world='" + world + '\'' +
                '}';
    }
}