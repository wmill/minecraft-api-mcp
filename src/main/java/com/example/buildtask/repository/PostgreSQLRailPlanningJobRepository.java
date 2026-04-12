package com.example.buildtask.repository;

import com.example.buildtask.model.RailPlanningJob;
import com.example.buildtask.model.RailPlanningStatus;
import com.example.database.DatabaseConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

public class PostgreSQLRailPlanningJobRepository implements RailPlanningJobRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLRailPlanningJobRepository.class);

    private final DatabaseConfig databaseConfig;
    private final ObjectMapper objectMapper;

    public PostgreSQLRailPlanningJobRepository(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public RailPlanningJob create(RailPlanningJob job) throws SQLException {
        String sql = """
            INSERT INTO rail_planning_jobs (
                id, build_id, status, phase, sampled_area_count, route_length,
                error_message, request_data, result_data, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """;
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, job.getId());
            stmt.setObject(2, job.getBuildId());
            stmt.setString(3, job.getStatus().name());
            stmt.setString(4, job.getPhase());
            stmt.setInt(5, job.getSampledAreaCount());
            stmt.setInt(6, job.getRouteLength());
            stmt.setString(7, job.getErrorMessage());
            stmt.setString(8, toJson(job.getRequestData()));
            stmt.setString(9, toJson(job.getResultData()));
            stmt.setTimestamp(10, Timestamp.from(job.getCreatedAt()));
            stmt.setTimestamp(11, Timestamp.from(job.getUpdatedAt()));
            stmt.executeUpdate();
            return job;
        }
    }

    @Override
    public RailPlanningJob update(RailPlanningJob job) throws SQLException {
        String sql = """
            UPDATE rail_planning_jobs
            SET status = ?, phase = ?, sampled_area_count = ?, route_length = ?,
                error_message = ?, request_data = ?::jsonb, result_data = ?::jsonb,
                updated_at = ?
            WHERE id = ?
            """;
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, job.getStatus().name());
            stmt.setString(2, job.getPhase());
            stmt.setInt(3, job.getSampledAreaCount());
            stmt.setInt(4, job.getRouteLength());
            stmt.setString(5, job.getErrorMessage());
            stmt.setString(6, toJson(job.getRequestData()));
            stmt.setString(7, toJson(job.getResultData()));
            stmt.setTimestamp(8, Timestamp.from(job.getUpdatedAt()));
            stmt.setObject(9, job.getId());
            if (stmt.executeUpdate() == 0) {
                throw new SQLException("Planning job not found: " + job.getId());
            }
            return job;
        }
    }

    @Override
    public Optional<RailPlanningJob> findById(UUID id) throws SQLException {
        String sql = """
            SELECT id, build_id, status, phase, sampled_area_count, route_length,
                   error_message, request_data, result_data, created_at, updated_at
            FROM rail_planning_jobs
            WHERE id = ?
            """;
        try (Connection conn = databaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                RailPlanningJob job = new RailPlanningJob();
                job.setId((UUID) rs.getObject("id"));
                job.setBuildId((UUID) rs.getObject("build_id"));
                job.setStatus(RailPlanningStatus.valueOf(rs.getString("status")));
                job.setPhase(rs.getString("phase"));
                job.setSampledAreaCount(rs.getInt("sampled_area_count"));
                job.setRouteLength(rs.getInt("route_length"));
                job.setErrorMessage(rs.getString("error_message"));
                job.setRequestData(parseJson(rs.getString("request_data")));
                job.setResultData(parseJson(rs.getString("result_data")));
                Timestamp createdAt = rs.getTimestamp("created_at");
                if (createdAt != null) {
                    job.setCreatedAt(createdAt.toInstant());
                }
                Timestamp updatedAt = rs.getTimestamp("updated_at");
                if (updatedAt != null) {
                    job.setUpdatedAt(updatedAt.toInstant());
                }
                return Optional.of(job);
            }
        }
    }

    private String toJson(JsonNode jsonNode) {
        return jsonNode == null ? null : jsonNode.toString();
    }

    private JsonNode parseJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse planning job JSON", e);
            return null;
        }
    }
}
