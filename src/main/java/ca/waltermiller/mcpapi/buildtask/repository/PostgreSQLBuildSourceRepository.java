package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.buildtask.model.BuildSource;
import ca.waltermiller.mcpapi.database.DatabaseConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PostgreSQLBuildSourceRepository implements BuildSourceRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLBuildSourceRepository.class);
    private static final String SUMMARY_COLUMNS = "build_id, kind, artifact_id, schematic_id, entry, y_offset, created_at";

    private final DatabaseConfig databaseConfig;
    private Connection transactionConnection;

    public PostgreSQLBuildSourceRepository(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    PostgreSQLBuildSourceRepository(DatabaseConfig config, Connection connection) {
        this(config);
        this.transactionConnection = connection;
    }

    private Connection connection() throws SQLException {
        return transactionConnection == null ? databaseConfig.getConnection() : BuildTransaction.lease(transactionConnection);
    }

    @Override
    public void create(BuildSource source) throws SQLException {
        String sql = """
            INSERT INTO build_sources (build_id, kind, artifact_id, schematic_id, source, entry, props, root_size,
                                       y_offset, target, details)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb)
            """;
        try (Connection conn = connection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, source.buildId());
            stmt.setString(2, source.kind());
            stmt.setString(3, source.artifactId());
            stmt.setString(4, source.schematicId());
            stmt.setString(5, source.source());
            stmt.setString(6, source.entry());
            stmt.setString(7, json(source.props()));
            stmt.setString(8, json(source.rootSize()));
            if (source.yOffset() == null) stmt.setNull(9, Types.INTEGER); else stmt.setInt(9, source.yOffset());
            stmt.setString(10, json(source.target()));
            stmt.setString(11, json(source.details()));
            stmt.executeUpdate();
        }
    }

    @Override
    public Optional<BuildSource> findByBuildId(UUID buildId) throws SQLException {
        String sql = "SELECT " + SUMMARY_COLUMNS + ", source, props::text AS props, root_size::text AS root_size, "
            + "target::text AS target, details::text AS details FROM build_sources WHERE build_id = ?";
        try (Connection conn = connection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, buildId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new BuildSource((UUID) rs.getObject("build_id"), rs.getString("kind"),
                    rs.getString("artifact_id"), rs.getString("schematic_id"), rs.getString("source"),
                    rs.getString("entry"), parse(rs.getString("props")), parse(rs.getString("root_size")),
                    yOffset(rs), parse(rs.getString("target")), parse(rs.getString("details")), created(rs)));
            }
        }
    }

    @Override
    public Map<UUID, BuildSource> findSummaries(Collection<UUID> buildIds) throws SQLException {
        Map<UUID, BuildSource> found = new LinkedHashMap<>();
        if (buildIds.isEmpty()) return found;
        String sql = "SELECT " + SUMMARY_COLUMNS + ", source IS NOT NULL AS has_source FROM build_sources WHERE build_id = ANY (?)";
        try (Connection conn = connection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("uuid", buildIds.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("build_id");
                    // A non-null marker keeps has_source accurate in the summary's map view.
                    found.put(id, new BuildSource(id, rs.getString("kind"), rs.getString("artifact_id"),
                        rs.getString("schematic_id"), rs.getBoolean("has_source") ? "" : null, rs.getString("entry"),
                        null, null, yOffset(rs), null, null, created(rs)));
                }
            }
        }
        return found;
    }

    private static Integer yOffset(ResultSet rs) throws SQLException {
        int value = rs.getInt("y_offset");
        return rs.wasNull() ? null : value;
    }

    private static java.time.Instant created(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return created == null ? null : created.toInstant();
    }

    private static String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private static JsonNode parse(String json) {
        return JdbcSupport.parseJsonOrNull(json, LOGGER, "build_sources");
    }
}
