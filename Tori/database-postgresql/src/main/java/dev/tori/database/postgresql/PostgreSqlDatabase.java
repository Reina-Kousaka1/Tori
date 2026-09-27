package dev.tori.database.postgresql;

import dev.tori.database.jdbc.JdbcDatabaseSupport;

/** PostgreSQL adapter using the shared HikariCP, prepared SQL, transaction, and Flyway support. */
public final class PostgreSqlDatabase extends JdbcDatabaseSupport {
    private PostgreSqlDatabase(PostgreSqlConfig config) {
        super("postgresql", createPool("postgresql", config.jdbcUrl(), config.username(),
            config.password(), config.maxPoolSize()));
    }

    public static PostgreSqlDatabase connect(PostgreSqlConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        return new PostgreSqlDatabase(config);
    }
}
