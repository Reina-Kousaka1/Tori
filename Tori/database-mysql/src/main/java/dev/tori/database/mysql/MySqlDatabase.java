package dev.tori.database.mysql;

import dev.tori.database.jdbc.JdbcDatabaseSupport;

/** MySQL adapter; relational semantics are shared, not replicated with another provider. */
public final class MySqlDatabase extends JdbcDatabaseSupport {
    private MySqlDatabase(MySqlConfig config) {
        super("mysql", createPool("mysql", config.jdbcUrl(), config.username(),
            config.password(), config.maxPoolSize()));
    }

    public static MySqlDatabase connect(MySqlConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        return new MySqlDatabase(config);
    }
}
