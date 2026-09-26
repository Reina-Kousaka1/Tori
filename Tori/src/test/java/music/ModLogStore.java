package music;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;

/** Local moderation audit database. Webhook credentials and response bodies are never stored. */
final class ModLogStore implements BotStore {
    private final String jdbcUrl;

    static ModLogStore fromConfig(BotConfig config) {
        String configured = config.get("MODLOG_DB_PATH", "").strip();
        try {
            Path path = configured.isEmpty()
                ? Path.of(config.get("BOT_DATA_DIR", "data"), "moderation.db") : Path.of(configured);
            return new ModLogStore(path);
        } catch (Exception ex) {
            // Paths or JDBC exceptions may contain private configuration; do not propagate them.
            throw new IllegalArgumentException("Cannot initialize moderation database. Check MODLOG_DB_PATH and directory write permissions.");
        }
    }

    ModLogStore(Path path) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        jdbcUrl = "jdbc:sqlite:" + absolute;
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS bot_stats_context (
                    bot_id TEXT NOT NULL, guild_id TEXT NOT NULL, channel_id TEXT NOT NULL,
                    guild_name TEXT NOT NULL, channel_name TEXT NOT NULL, updated_at TEXT NOT NULL,
                    PRIMARY KEY (bot_id, guild_id, channel_id)
                )
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS moderation_cases (
                    guild_id TEXT NOT NULL,
                    case_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    guild_name TEXT,
                    channel_id TEXT,
                    moderator_id TEXT,
                    moderator_name TEXT,
                    target TEXT,
                    reason TEXT,
                    result TEXT,
                    occurred_at TEXT NOT NULL,
                    language TEXT NOT NULL,
                    webhook_status TEXT NOT NULL,
                    webhook_attempts INTEGER NOT NULL DEFAULT 0,
                    webhook_http_status INTEGER,
                    webhook_error TEXT,
                    webhook_updated_at TEXT NOT NULL,
                    PRIMARY KEY (guild_id, case_id)
                )
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS moderation_cases_time ON moderation_cases(guild_id, occurred_at)");
            sql.execute("""
                CREATE TABLE IF NOT EXISTS bot_events (
                    session_id TEXT NOT NULL,
                    event_type TEXT NOT NULL CHECK (event_type IN ('BOT_STARTED', 'BOT_STOPPED')),
                    reason TEXT,
                    occurred_at TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    PRIMARY KEY (session_id, event_type)
                )
                """);
        }
    }

    public BotStore.Stats stats(String botId, String guildId, String guildName, String channelId, String channelName) throws SQLException {
        try (var connection = connect()) {
            try (var sql = connection.prepareStatement("""
                INSERT INTO bot_stats_context VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(bot_id, guild_id, channel_id) DO UPDATE SET
                    guild_name=excluded.guild_name, channel_name=excluded.channel_name, updated_at=excluded.updated_at
                """)) {
                sql.setString(1, botId); sql.setString(2, guildId); sql.setString(3, channelId);
                sql.setString(4, guildName); sql.setString(5, channelName); sql.setString(6, Instant.now().toString());
                sql.executeUpdate();
            }
            try (var sql = connection.createStatement(); var result = sql.executeQuery("""
                SELECT COUNT(CASE WHEN event_type='BOT_STARTED' THEN 1 END),
                       MAX(CASE WHEN event_type='BOT_STOPPED' AND reason='RESTART' THEN occurred_at END)
                FROM bot_events
                """)) {
                result.next();
                return new BotStore.Stats(result.getLong(1), result.getString(2));
            }
        }
    }

    Connection connect() throws SQLException {
        var properties = new java.util.Properties();
        properties.setProperty("busy_timeout", "3000");
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    public void lifecycle(String sessionId, String eventType, String reason, Instant startedAt) throws SQLException {
        if (!java.util.Set.of("BOT_STARTED", "BOT_STOPPED").contains(eventType))
            throw new IllegalArgumentException("Unsupported bot lifecycle event");
        try (var connection = connect(); var sql = connection.prepareStatement("""
                INSERT INTO bot_events(session_id, event_type, reason, occurred_at, started_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT(session_id, event_type) DO NOTHING
                """)) {
            sql.setString(1, sessionId);
            sql.setString(2, eventType);
            sql.setString(3, reason);
            sql.setString(4, Instant.now().toString());
            sql.setString(5, startedAt.toString());
            sql.executeUpdate();
        }
    }

    public boolean save(WebhookModLogger.Entry entry, boolean webhookEnabled) throws SQLException {
        try (var connection = connect(); var sql = connection.prepareStatement("""
                INSERT INTO moderation_cases
                (guild_id, case_id, action, guild_name, channel_id, moderator_id, moderator_name,
                 target, reason, result, occurred_at, language, webhook_status, webhook_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guild_id, case_id) DO NOTHING
                """)) {
            String[] values = {entry.guildId(), entry.caseId(), entry.action(), entry.guild(), entry.channelId(),
                entry.moderatorId(), entry.moderator(), entry.target(), entry.reason(), entry.result(),
                entry.timestamp().toString(), entry.language().code, webhookEnabled ? "PENDING" : "DISABLED", Instant.now().toString()};
            for (int i = 0; i < values.length; i++) sql.setString(i + 1, values[i]);
            return sql.executeUpdate() == 1;
        }
    }

    public void update(WebhookModLogger.Entry entry, String status, Integer httpStatus, String error, boolean attempt) throws SQLException {
        try (var connection = connect(); var sql = connection.prepareStatement("""
                UPDATE moderation_cases SET webhook_status = ?, webhook_http_status = ?, webhook_error = ?,
                    webhook_attempts = webhook_attempts + ?, webhook_updated_at = ?
                WHERE guild_id = ? AND case_id = ?
                """)) {
            sql.setString(1, status);
            if (httpStatus == null) sql.setNull(2, Types.INTEGER); else sql.setInt(2, httpStatus);
            sql.setString(3, error);
            sql.setInt(4, attempt ? 1 : 0);
            sql.setString(5, Instant.now().toString());
            sql.setString(6, entry.guildId());
            sql.setString(7, entry.caseId());
            sql.executeUpdate();
        }
    }

    public java.util.Map<String, String> prefixes() throws SQLException {
        var loaded = new java.util.HashMap<String, String>();
        try (var connection = connect(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS guild_prefixes (guild_id TEXT PRIMARY KEY, prefix TEXT NOT NULL)");
            try (var rows = sql.executeQuery("SELECT guild_id, prefix FROM guild_prefixes")) {
                while (rows.next()) loaded.put(rows.getString(1), rows.getString(2));
            }
        }
        return loaded;
    }

    public void setPrefix(String guildId, String prefix) throws SQLException {
        try (var connection = connect(); var sql = connection.prepareStatement(
            "INSERT INTO guild_prefixes VALUES (?, ?) ON CONFLICT(guild_id) DO UPDATE SET prefix=excluded.prefix")) {
            sql.setString(1, guildId); sql.setString(2, prefix); sql.executeUpdate();
        }
    }
}
