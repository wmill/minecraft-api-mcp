package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.buildtask.model.BuildStatus;
import ca.waltermiller.mcpapi.buildtask.model.TaskStatus;
import ca.waltermiller.mcpapi.database.DatabaseConfig;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Execution only lives in the server process, so a build still IN_PROGRESS (or a task still
 * EXECUTING) at startup was interrupted by a crash or a shutdown that outlived its deadline.
 * Without this sweep such builds would reject every queue edit, execute, and replay forever.
 */
public final class InterruptedExecutions {
    public static final String TASK_ERROR = "Interrupted by server shutdown; inspect the region before executing again";

    private InterruptedExecutions() {}

    public record Recovered(int builds, int tasks) {}

    /** Marks interrupted builds and tasks FAILED; call before any execution can start. */
    public static Recovered fail(DatabaseConfig config) throws SQLException {
        try (Connection connection = config.getConnection()) {
            connection.setAutoCommit(false);
            try (var tasks = connection.prepareStatement("UPDATE build_tasks SET status = ?, error_message = ? WHERE status = ?");
                 var builds = connection.prepareStatement("UPDATE builds SET status = ? WHERE status = ?")) {
                tasks.setString(1, TaskStatus.FAILED.name());
                tasks.setString(2, TASK_ERROR);
                tasks.setString(3, TaskStatus.EXECUTING.name());
                int failedTasks = tasks.executeUpdate();
                builds.setString(1, BuildStatus.FAILED.name());
                builds.setString(2, BuildStatus.IN_PROGRESS.name());
                int failedBuilds = builds.executeUpdate();
                connection.commit();
                return new Recovered(failedBuilds, failedTasks);
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
