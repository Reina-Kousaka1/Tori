package dev.tori.database.ferretdb;

/** FerretDB speaks the MongoDB wire protocol and is configured independently from JDBC providers. */
public final class FerretDbConfig {
    private final String connectionString;
    private final String database;
    private final int maxPoolSize;

    public FerretDbConfig(String connectionString, String database) { this(connectionString, database, 8); }

    public FerretDbConfig(String connectionString, String database, int maxPoolSize) {
        if (connectionString == null || !(connectionString.startsWith("mongodb://")
            || connectionString.startsWith("mongodb+srv://")))
            throw new IllegalArgumentException("A MongoDB-compatible connection string is required");
        if (database == null || !database.matches("[A-Za-z0-9_-]{1,63}"))
            throw new IllegalArgumentException("Database name is invalid");
        if (maxPoolSize < 1 || maxPoolSize > 64) throw new IllegalArgumentException("maxPoolSize must be between 1 and 64");
        this.connectionString = connectionString;
        this.database = database;
        this.maxPoolSize = maxPoolSize;
    }

    public String connectionString() { return connectionString; }
    public String database() { return database; }
    public int maxPoolSize() { return maxPoolSize; }

    @Override public String toString() {
        return "FerretDbConfig[connectionString=<redacted>, database=" + database + ", maxPoolSize=" + maxPoolSize + "]";
    }
}
