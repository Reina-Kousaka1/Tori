package music;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Shared bounded PostgreSQL pool and startup schema migrations. */
final class PostgresDatabase implements AutoCloseable {
    private final HikariDataSource pool;

    static PostgresDatabase fromConfig(BotConfig config) {
        String url = config.get("TORI_DATABASE_URL", "jdbc:postgresql://localhost:5432/tori_main").strip();
        String user = config.get("TORI_DATABASE_USER", "tori").strip();
        String password = config.required("TORI_DATABASE_PASSWORD");
        if (!url.startsWith("jdbc:postgresql://") || user.isEmpty())
            throw new IllegalArgumentException("TORI_DATABASE_URL or TORI_DATABASE_USER is invalid.");
        return new PostgresDatabase(url, user, password);
    }

    PostgresDatabase(String url, String user, String password) {
        var settings = new HikariConfig();
        settings.setJdbcUrl(url);
        settings.setUsername(user);
        settings.setPassword(password);
        settings.setPoolName("tori-postgres");
        settings.setMaximumPoolSize(8);
        settings.setMinimumIdle(0);
        settings.setConnectionTimeout(10_000);
        settings.setValidationTimeout(3_000);
        settings.setInitializationFailTimeout(10_000);
        HikariDataSource created = null;
        try {
            created = new HikariDataSource(settings);
            try (Connection connection = created.getConnection()) {
                if (!connection.isValid(3)) throw new SQLException("PostgreSQL health check failed.");
            }
            Flyway.configure().dataSource(created).locations("classpath:db/migration").load().migrate();
            pool = created;
            LoggerFactory.getLogger(PostgresDatabase.class).info("PostgreSQL connected; schema migrations are current.");
        } catch (Exception ex) {
            if (created != null) created.close();
            // Driver/Flyway exceptions can include connection details; never expose them.
            LoggerFactory.getLogger(PostgresDatabase.class).error("PostgreSQL startup failed ({})", ex.getClass().getSimpleName());
            throw new IllegalArgumentException("PostgreSQL is unavailable or its schema migration failed. Check the database service and configured credentials.");
        }
    }

    DataSource dataSource() { return pool; }
    Connection connection() throws SQLException { return pool.getConnection(); }
    @Override public void close() { pool.close(); }
}
