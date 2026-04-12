package com.example.buildtask.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public class RailPlanningJob {
    private UUID id;
    private UUID buildId;
    private RailPlanningStatus status;
    private String phase;
    private int sampledAreaCount;
    private int routeLength;
    private String errorMessage;
    private JsonNode requestData;
    private JsonNode resultData;
    private Instant createdAt;
    private Instant updatedAt;

    public RailPlanningJob() {
        this.id = UUID.randomUUID();
        this.status = RailPlanningStatus.PLANNING;
        this.phase = "queued";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

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

    public RailPlanningStatus getStatus() {
        return status;
    }

    public void setStatus(RailPlanningStatus status) {
        this.status = status;
        touch();
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
        touch();
    }

    public int getSampledAreaCount() {
        return sampledAreaCount;
    }

    public void setSampledAreaCount(int sampledAreaCount) {
        this.sampledAreaCount = sampledAreaCount;
        touch();
    }

    public int getRouteLength() {
        return routeLength;
    }

    public void setRouteLength(int routeLength) {
        this.routeLength = routeLength;
        touch();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        touch();
    }

    public JsonNode getRequestData() {
        return requestData;
    }

    public void setRequestData(JsonNode requestData) {
        this.requestData = requestData;
        touch();
    }

    public JsonNode getResultData() {
        return resultData;
    }

    public void setResultData(JsonNode resultData) {
        this.resultData = resultData;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
