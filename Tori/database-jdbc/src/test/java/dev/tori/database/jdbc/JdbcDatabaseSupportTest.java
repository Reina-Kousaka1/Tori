package dev.tori.database.jdbc;

import dev.tori.database.api.DatabaseException;
import dev.tori.database.api.SqlCommand;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class JdbcDatabaseSupportTest {
    @Test void preparedParametersAreBoundAndTransactionCommitsOffCallerThread() {
        var fake = new FakeDataSource();
        String caller = Thread.currentThread().getName();
        try (var database = new TestDatabase(fake)) {
            int updated = database.transaction(tx -> {
                assertNotEquals(caller, Thread.currentThread().getName());
                assertTrue(Thread.currentThread().getName().startsWith("database-test-sql-"));
                return tx.execute(new SqlCommand("UPDATE accounts SET balance = ? WHERE user_id = ?",
                    Arrays.asList(125L, "user-7")));
            }).toCompletableFuture().join();

            assertEquals(1, updated);
            assertEquals(1, fake.commits.get());
            assertEquals(0, fake.rollbacks.get());
            assertEquals(Map.of(1, 125L, 2, "user-7"), fake.parameters);
        }
    }

    @Test void callbackFailureRollsBackAndDoesNotLeakSensitiveExceptionMessage() {
        var fake = new FakeDataSource();
        try (var database = new TestDatabase(fake)) {
            var stage = database.transaction(tx -> {
                tx.execute(new SqlCommand("UPDATE accounts SET balance = balance + ?", java.util.List.of(1L)));
                throw new IllegalStateException("private value must not reach user message");
            });

            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> stage.toCompletableFuture().join());
            assertInstanceOf(DatabaseException.class, failure.getCause());
            assertFalse(failure.getCause().getMessage().contains("private value"));
            assertEquals(0, fake.commits.get());
            assertEquals(1, fake.rollbacks.get());
        }
    }

    @Test void operationsAfterCloseFailWithSanitizedProviderError() {
        var database = new TestDatabase(new FakeDataSource());
        database.close();
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
            () -> database.execute(new SqlCommand("DELETE FROM accounts")).toCompletableFuture().join());
        assertInstanceOf(DatabaseException.class, failure.getCause());
        assertTrue(database.isClosed());
    }

    private static final class TestDatabase extends JdbcDatabaseSupport {
        private TestDatabase(DataSource source) { super("test-sql", source, () -> {}); }
    }

    private static final class FakeDataSource implements DataSource {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final Map<Integer, Object> parameters = new ConcurrentHashMap<>();

        @Override public Connection getConnection() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "setAutoCommit", "close" -> null;
                case "isClosed" -> false;
                case "isValid" -> true;
                case "commit" -> { commits.incrementAndGet(); yield null; }
                case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                case "prepareStatement" -> preparedStatement();
                case "unwrap" -> throw new SQLException("not a wrapper");
                case "isWrapperFor" -> false;
                case "toString" -> "FakeConnection";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(Connection.class, handler);
        }

        private PreparedStatement preparedStatement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setObject" -> { parameters.put((Integer) args[0], args[1]); yield null; }
                case "setNull" -> { parameters.put((Integer) args[0], 0); yield null; }
                case "executeUpdate" -> 1;
                case "close" -> null;
                case "toString" -> "FakePreparedStatement";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(PreparedStatement.class, handler);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }

        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
