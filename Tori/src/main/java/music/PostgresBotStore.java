package music;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Main Tori persistence for lifecycle, guild settings and moderation audit data. */
final class PostgresBotStore implements BotStore {
    private final PostgresDatabase database;

    static PostgresBotStore fromConfig(BotConfig config) {
        return new PostgresBotStore(PostgresDatabase.fromConfig(config));
    }
    PostgresBotStore(PostgresDatabase database) { this.database = database; }
    PostgresDatabase database() { return database; }

    private static void setInstant(java.sql.PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setObject(index, value == null ? null : value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }
    private static void setNullable(java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    @Override public BotStore.Stats stats(String botId, String guildId, String guildName,
                                           String channelId, String channelName) throws SQLException {
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                try (var sql = connection.prepareStatement("""
                    INSERT INTO bot_stats_context(bot_id,guild_id,channel_id,guild_name,channel_name,updated_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT(bot_id,guild_id,channel_id) DO UPDATE SET
                    guild_name=excluded.guild_name,channel_name=excluded.channel_name,updated_at=excluded.updated_at
                    """)) {
                    sql.setString(1, botId); sql.setString(2, guildId); sql.setString(3, channelId);
                    sql.setString(4, guildName); sql.setString(5, channelName);
                    setInstant(sql, 6, Instant.now()); sql.executeUpdate();
                }
                BotStore.Stats result;
                try (var sql = connection.prepareStatement("""
                    SELECT COUNT(*) FILTER (WHERE event_type='BOT_STARTED'),
                           MAX(occurred_at) FILTER (WHERE event_type='BOT_STOPPED' AND reason='RESTART')
                    FROM bot_events
                    """); var rows = sql.executeQuery()) {
                    rows.next();
                    OffsetDateTime restart = rows.getObject(2, OffsetDateTime.class);
                    result = new BotStore.Stats(rows.getLong(1), restart == null ? null : restart.toInstant().toString());
                }
                connection.commit();
                return result;
            } catch (SQLException ex) { connection.rollback(); throw ex; }
        }
    }

    @Override public void lifecycle(String sessionId, String eventType, String reason, Instant startedAt) throws SQLException {
        if (!Set.of("BOT_STARTED", "BOT_STOPPED").contains(eventType))
            throw new IllegalArgumentException("Unsupported bot lifecycle event");
        try (var connection = database.connection(); var sql = connection.prepareStatement("""
            INSERT INTO bot_events(session_id,event_type,reason,occurred_at,started_at)
            VALUES (?,?,?,?,?) ON CONFLICT(session_id,event_type) DO NOTHING
            """)) {
            sql.setString(1, sessionId); sql.setString(2, eventType); setNullable(sql, 3, reason);
            setInstant(sql, 4, Instant.now()); setInstant(sql, 5, startedAt); sql.executeUpdate();
        }
    }

    @Override public boolean save(WebhookModLogger.Entry entry, boolean webhookEnabled) throws SQLException {
        try (var connection = database.connection(); var sql = connection.prepareStatement("""
            INSERT INTO moderation_cases(guild_id,case_id,action,guild_name,channel_id,moderator_id,moderator_name,
              target,reason,result,occurred_at,language,webhook_status,webhook_attempts,webhook_updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,?) ON CONFLICT(guild_id,case_id) DO NOTHING
            """)) {
            sql.setString(1, entry.guildId()); sql.setString(2, entry.caseId()); sql.setString(3, entry.action());
            setNullable(sql, 4, entry.guild()); setNullable(sql, 5, entry.channelId());
            setNullable(sql, 6, entry.moderatorId()); setNullable(sql, 7, entry.moderator());
            setNullable(sql, 8, entry.target()); setNullable(sql, 9, entry.reason()); setNullable(sql, 10, entry.result());
            setInstant(sql, 11, entry.timestamp()); sql.setString(12, entry.language().code);
            sql.setString(13, webhookEnabled ? "PENDING" : "DISABLED"); setInstant(sql, 14, Instant.now());
            return sql.executeUpdate() == 1;
        }
    }

    @Override public void update(WebhookModLogger.Entry entry, String status, Integer httpStatus,
                                 String error, boolean attempt) throws SQLException {
        try (var connection = database.connection(); var sql = connection.prepareStatement("""
            UPDATE moderation_cases SET webhook_status=?,webhook_http_status=?,webhook_error=?,
              webhook_attempts=webhook_attempts+?,webhook_updated_at=? WHERE guild_id=? AND case_id=?
            """)) {
            sql.setString(1, status);
            if (httpStatus == null) sql.setNull(2, Types.INTEGER); else sql.setInt(2, httpStatus);
            setNullable(sql, 3, error); sql.setInt(4, attempt ? 1 : 0); setInstant(sql, 5, Instant.now());
            sql.setString(6, entry.guildId()); sql.setString(7, entry.caseId()); sql.executeUpdate();
        }
    }

    @Override public Map<String, String> prefixes() throws SQLException {
        var loaded = new HashMap<String, String>();
        try (var connection = database.connection(); var sql = connection.prepareStatement("SELECT guild_id,prefix FROM guild_prefixes");
             var rows = sql.executeQuery()) {
            while (rows.next()) loaded.put(rows.getString(1), rows.getString(2));
        }
        return loaded;
    }

    @Override public void setPrefix(String guildId, String prefix) throws SQLException {
        try (var connection = database.connection(); var sql = connection.prepareStatement("""
            INSERT INTO guild_prefixes(guild_id,prefix) VALUES (?,?)
            ON CONFLICT(guild_id) DO UPDATE SET prefix=excluded.prefix
            """)) {
            sql.setString(1, guildId); sql.setString(2, prefix); sql.executeUpdate();
        }
    }

    @Override public Map<String, Language> languages() throws SQLException {
        var loaded = new HashMap<String, Language>();
        try (var connection = database.connection(); var sql = connection.prepareStatement("SELECT guild_id,language FROM guild_languages");
             var rows = sql.executeQuery()) {
            while (rows.next()) loaded.put(rows.getString(1), Language.parse(rows.getString(2)));
        }
        return loaded;
    }

    @Override public void setLanguage(String guildId, Language language) throws SQLException {
        try (var connection = database.connection(); var sql = connection.prepareStatement("""
            INSERT INTO guild_languages(guild_id,language) VALUES (?,?)
            ON CONFLICT(guild_id) DO UPDATE SET language=excluded.language
            """)) {
            sql.setString(1, guildId); sql.setString(2, language.code); sql.executeUpdate();
        }
    }

    @Override public void close() { database.close(); }
}
