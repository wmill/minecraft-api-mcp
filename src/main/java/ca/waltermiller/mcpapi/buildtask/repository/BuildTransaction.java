package ca.waltermiller.mcpapi.buildtask.repository;

import ca.waltermiller.mcpapi.database.DatabaseConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** A build row lock and both repositories sharing one transaction. */
public final class BuildTransaction {
    private BuildTransaction() {}

    @FunctionalInterface
    public interface Work<T> {
        T run(BuildRepository builds, TaskRepository tasks) throws SQLException;
    }

    public static <T> T run(DatabaseConfig config, UUID id, Work<T> work) throws SQLException {
        try (Connection connection = config.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("SELECT id FROM builds WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new IllegalArgumentException("Build not found: " + id);
                    }
                }
                T result = work.run(new PostgreSQLBuildRepository(config, connection),
                    new PostgreSQLTaskRepository(config, connection));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    /** Repositories may close their lease; only the transaction owns the connection. */
    static Connection lease(Connection connection) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) return null;
                try { return method.invoke(connection, args); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
    }
}
