package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.buildtask.model.BuildSource;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Provenance of recorded placements (one row per build). */
public interface BuildSourceRepository {
    void create(BuildSource source) throws SQLException;

    Optional<BuildSource> findByBuildId(UUID buildId) throws SQLException;

    /** Summaries (no source text) for the given builds; builds without provenance are absent. */
    Map<UUID, BuildSource> findSummaries(Collection<UUID> buildIds) throws SQLException;
}
