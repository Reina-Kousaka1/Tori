package music;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

/** Persistence contract shared by the bot runtime and focused store tests. */
interface BotStore extends AutoCloseable {
    record Stats(long starts, String lastRestart) {}
    Stats stats(String botId, String guildId, String guildName, String channelId, String channelName) throws SQLException;
    void lifecycle(String sessionId, String eventType, String reason, Instant startedAt) throws SQLException;
    boolean save(WebhookModLogger.Entry entry, boolean webhookEnabled) throws SQLException;
    void update(WebhookModLogger.Entry entry, String status, Integer httpStatus, String error, boolean attempt) throws SQLException;
    Map<String, String> prefixes() throws SQLException;
    void setPrefix(String guildId, String prefix) throws SQLException;
    default Map<String, Language> languages() throws SQLException { return Map.of(); }
    default void setLanguage(String guildId, Language language) throws SQLException {
        throw new SQLException("Language persistence is unavailable.");
    }
    @Override default void close() {}
}
