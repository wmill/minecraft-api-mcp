package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.buildtask.Json;
import ca.waltermiller.mcpapi.buildtask.model.BoundingBox;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Small shared helpers for the hand-rolled JDBC repositories.
 */
final class JdbcSupport {

    private JdbcSupport() {
    }

    /**
     * Binds the six parameters of the standard bounding-box intersection clause
     * (min_x <= ? AND max_x >= ? AND min_y <= ? AND max_y >= ? AND min_z <= ? AND max_z >= ?)
     * starting at firstIndex.
     */
    static void bindBoxIntersection(PreparedStatement stmt, int firstIndex, BoundingBox box) throws SQLException {
        stmt.setInt(firstIndex, box.getMaxX());
        stmt.setInt(firstIndex + 1, box.getMinX());
        stmt.setInt(firstIndex + 2, box.getMaxY());
        stmt.setInt(firstIndex + 3, box.getMinY());
        stmt.setInt(firstIndex + 4, box.getMaxZ());
        stmt.setInt(firstIndex + 5, box.getMinZ());
    }

    /**
     * Parses a JSON column value leniently: returns null (and logs a warning) on bad input
     * instead of failing the whole row.
     */
    static JsonNode parseJsonOrNull(String json, Logger logger, String context) {
        if (json == null) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception e) {
            logger.warn("Failed to parse JSON ({}): {}", context, e.getMessage());
            return null;
        }
    }
}
