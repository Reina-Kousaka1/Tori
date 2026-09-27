package dev.tori.database.postgresql;

/** Credentials are supplied by the caller (typically environment or secret storage). */
public final class PostgreSqlConfig {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int maxPoolSize;

    public PostgreSqlConfig(String jdbcUrl, String username, String password) { this(jdbcUrl, username, password, 8); }

    public PostgreSqlConfig(String jdbcUrl, String username, String password, int maxPoolSize) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL JDBC URL must start with jdbc:postgresql://");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("PostgreSQL username is required");
        if (password == null) throw new IllegalArgumentException("PostgreSQL password is required");
        if (maxPoolSize < 1 || maxPoolSize > 64) throw new IllegalArgumentException("maxPoolSize must be between 1 and 64");
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maxPoolSize = maxPoolSize;
    }

    public String jdbcUrl() { return jdbcUrl; }
    public String username() { return username; }
    public String password() { return password; }
    public int maxPoolSize() { return maxPoolSize; }

    @Override public String toString() {
        return "PostgreSqlConfig[jdbcUrl=<configured>, username=<configured>, password=<redacted>, maxPoolSize="
            + maxPoolSize + "]";
    }
}
