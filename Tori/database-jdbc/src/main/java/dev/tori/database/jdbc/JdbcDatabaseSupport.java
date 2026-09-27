package dev.tori.database.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.tori.database.api.DatabaseCapability;
import dev.tori.database.api.DatabaseException;
import dev.tori.database.api.DatabaseHealth;
import dev.tori.database.api.SqlCommand;
import dev.tori.database.api.SqlDatabase;
import dev.tori.database.api.SqlRow;
import dev.tori.database.api.SqlTransaction;
import dev.tori.database.api.SqlTransactionWork;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded async/JDBC implementation for relational provider modules. */
public abstract class JdbcDatabaseSupport implements SqlDatabase {
    private static final Set<DatabaseCapability> CAPABILITIES = Set.of(
        DatabaseCapability.ASYNC_OPERATIONS, DatabaseCapability.HEALTH_CHECK,
        DatabaseCapability.RELATIONAL_QUERIES, DatabaseCapability.TRANSACTIONS,
        DatabaseCapability.SCHEMA_MIGRATIONS);

    private final String providerId;
    private final DataSource dataSource;
    private final AutoCloseable poolOwner;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    protected JdbcDatabaseSupport(String providerId, HikariDataSource pool) { this(providerId, pool, pool); }

    /** Protected for provider tests with an isolated fake DataSource. */
    protected JdbcDatabaseSupport(String providerId, DataSource dataSource, AutoCloseable poolOwner) {
        this.providerId = Objects.requireNonNull(providerId);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.poolOwner = Objects.requireNonNull(poolOwner);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "database-" + providerId + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public static HikariDataSource createPool(String providerId, String jdbcUrl, String username,
                                               String password, int maxPoolSize) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (maxPoolSize < 1 || maxPoolSize > 64) throw new IllegalArgumentException("maxPoolSize must be between 1 and 64");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("tori-" + providerId);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(3_000);
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(true);
        config.addDataSourceProperty("tcpKeepAlive", "true");
        return new HikariDataSource(config);
    }

    @Override public final String providerId() { return providerId; }
    @Override public final Set<DatabaseCapability> capabilities() { return CAPABILITIES; }
    @Override public final boolean isClosed() { return closed.get(); }

    @Override public CompletionStage<DatabaseHealth> health() {
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        return submit("health", () -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean healthy = connection.isValid(3);
                Duration latency = elapsed(started);
                return healthy ? DatabaseHealth.available(providerId, checkedAt, latency)
                    : DatabaseHealth.unavailable(providerId, checkedAt, latency);
            } catch (SQLException ex) {
                return DatabaseHealth.unavailable(providerId, checkedAt, elapsed(started));
            }
        });
    }

    @Override public CompletionStage<List<SqlRow>> query(SqlCommand command) {
        Objects.requireNonNull(command, "command");
        return submit("query", () -> {
            try (Connection connection = dataSource.getConnection()) { return query(connection, command); }
            catch (SQLException ex) { throw failure("query", ex); }
        });
    }

    @Override public CompletionStage<Integer> execute(SqlCommand command) {
        Objects.requireNonNull(command, "command");
        return submit("execute", () -> {
            try (Connection connection = dataSource.getConnection()) { return execute(connection, command); }
            catch (SQLException ex) { throw failure("execute", ex); }
        });
    }

    @Override public <T> CompletionStage<T> transaction(SqlTransactionWork<T> work) {
        Objects.requireNonNull(work, "work");
        return submit("transaction", () -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.execute(new TransactionView(connection));
                    connection.commit();
                    return result;
                } catch (Exception | Error ex) {
                    try { connection.rollback(); } catch (SQLException rollbackFailure) { ex.addSuppressed(rollbackFailure); }
                    if (ex instanceof Error error) throw error;
                    if (ex instanceof DatabaseException databaseException) throw databaseException;
                    throw failure("transaction", ex);
                } finally {
                    try { connection.setAutoCommit(originalAutoCommit); }
                    catch (SQLException ignored) { /* Hikari discards a broken connection. */ }
                }
            } catch (SQLException ex) { throw failure("transaction", ex); }
        });
    }

    @Override public CompletionStage<Integer> migrate(String... locations) {
        String[] selected = locations == null || locations.length == 0
            ? new String[] {"classpath:db/migration"} : locations.clone();
        for (String location : selected)
            if (location == null || location.isBlank()) throw new IllegalArgumentException("Migration locations cannot be blank");
        return submit("migrate", () -> Flyway.configure().dataSource(dataSource)
            .locations(selected).load().migrate().migrationsExecuted);
    }

    private <T> CompletionStage<T> submit(String operation, CheckedSupplier<T> supplier) {
        if (closed.get()) return CompletableFuture.failedFuture(new DatabaseException(providerId, operation));
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return supplier.get(); }
                catch (DatabaseException ex) { throw ex; }
                catch (Exception ex) { throw failure(operation, ex); }
            }, executor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new DatabaseException(providerId, operation, ex));
        }
    }

    private List<SqlRow> query(Connection connection, SqlCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(command.text())) {
            bind(statement, command);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                int columns = metadata.getColumnCount();
                var rows = new ArrayList<SqlRow>();
                while (result.next()) {
                    var values = new LinkedHashMap<String, Object>();
                    for (int index = 1; index <= columns; index++)
                        values.put(metadata.getColumnLabel(index), result.getObject(index));
                    rows.add(new SqlRow(values));
                }
                return List.copyOf(rows);
            }
        }
    }

    private int execute(Connection connection, SqlCommand command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(command.text())) {
            bind(statement, command);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, SqlCommand command) throws SQLException {
        List<Object> parameters = command.parameters();
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value == null) statement.setNull(index + 1, Types.NULL);
            else if (value instanceof Instant instant) statement.setTimestamp(index + 1, Timestamp.from(instant));
            else statement.setObject(index + 1, value);
        }
    }

    private static Duration elapsed(long started) { return Duration.ofNanos(Math.max(0, System.nanoTime() - started)); }
    private DatabaseException failure(String operation, Throwable cause) { return new DatabaseException(providerId, operation, cause); }

    private final class TransactionView implements SqlTransaction {
        private final Connection connection;
        private TransactionView(Connection connection) { this.connection = connection; }
        @Override public List<SqlRow> query(SqlCommand command) {
            try { return JdbcDatabaseSupport.this.query(connection, Objects.requireNonNull(command)); }
            catch (SQLException ex) { throw failure("transaction-query", ex); }
        }
        @Override public int execute(SqlCommand command) {
            try { return JdbcDatabaseSupport.this.execute(connection, Objects.requireNonNull(command)); }
            catch (SQLException ex) { throw failure("transaction-execute", ex); }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try { poolOwner.close(); }
        catch (Exception ex) { throw new DatabaseException(providerId, "close", ex); }
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
