package music;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Updates.*;

/** Main Tori's MongoDB persistence. Identifiers and timestamps retain their legacy string format. */
final class MongoBotStore implements BotStore {
    private final MongoClient client;
    private final MongoDatabase db;

    static MongoBotStore fromConfig(BotConfig config) {
        String uri = config.required("MONGODB_URI");
        String database = config.get("MONGODB_DATABASE", "tori_main");
        if (!database.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("MONGODB_DATABASE must be a simple database name.");
        try {
            return new MongoBotStore(uri, database);
        } catch (RuntimeException ex) {
            // MongoDB URI may include credentials; never expose it through an exception.
            throw new IllegalArgumentException("Cannot connect to MongoDB. Check MONGODB_URI and database availability.");
        }
    }

    MongoBotStore(String uri, String database) {
        client = MongoClients.create(uri);
        try {
            db = client.getDatabase(database);
            db.runCommand(new Document("ping", 1));
            unique("bot_stats_context", "bot_id", "guild_id", "channel_id");
            unique("bot_events", "session_id", "event_type");
            unique("moderation_cases", "guild_id", "case_id");
            unique("guild_prefixes", "guild_id");
            unique("guild_languages", "guild_id");
            db.getCollection("moderation_cases").createIndex(
                new Document("guild_id", 1).append("occurred_at", 1),
                new IndexOptions().name("guild_time_v1"));
        } catch (RuntimeException ex) {
            client.close();
            throw ex;
        }
    }

    private void unique(String collection, String... fields) {
        var keys = new Document();
        for (String field : fields) keys.append(field, 1);
        db.getCollection(collection).createIndex(keys, new IndexOptions().unique(true).name("natural_key_v1"));
    }

    private static SQLException unavailable(MongoException ex) {
        // Do not retain driver messages; they can contain credentials or connection details.
        return new SQLException("MongoDB operation failed.");
    }

    @Override public BotStore.Stats stats(String botId, String guildId, String guildName,
                                               String channelId, String channelName) throws SQLException {
        try {
            db.getCollection("bot_stats_context").updateOne(
                and(eq("bot_id", botId), eq("guild_id", guildId), eq("channel_id", channelId)),
                combine(set("guild_name", guildName), set("channel_name", channelName),
                    set("updated_at", Instant.now().toString()),
                    setOnInsert("bot_id", botId), setOnInsert("guild_id", guildId),
                    setOnInsert("channel_id", channelId), setOnInsert("schema_version", 1)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
            long starts = db.getCollection("bot_events").countDocuments(eq("event_type", "BOT_STARTED"));
            Document restart = db.getCollection("bot_events")
                .find(and(eq("event_type", "BOT_STOPPED"), eq("reason", "RESTART")))
                .sort(descending("occurred_at")).first();
            return new BotStore.Stats(starts, restart == null ? null : restart.getString("occurred_at"));
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    @Override public void lifecycle(String sessionId, String eventType, String reason, Instant startedAt) throws SQLException {
        if (!Set.of("BOT_STARTED", "BOT_STOPPED").contains(eventType))
            throw new IllegalArgumentException("Unsupported bot lifecycle event");
        try {
            db.getCollection("bot_events").updateOne(
                and(eq("session_id", sessionId), eq("event_type", eventType)),
                setOnInsert(new Document("session_id", sessionId).append("event_type", eventType)
                    .append("reason", reason).append("occurred_at", Instant.now().toString())
                    .append("started_at", startedAt.toString()).append("schema_version", 1)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    @Override public boolean save(WebhookModLogger.Entry entry, boolean webhookEnabled) throws SQLException {
        var document = new Document("guild_id", entry.guildId()).append("case_id", entry.caseId())
            .append("action", entry.action()).append("guild_name", entry.guild())
            .append("channel_id", entry.channelId()).append("moderator_id", entry.moderatorId())
            .append("moderator_name", entry.moderator()).append("target", entry.target())
            .append("reason", entry.reason()).append("result", entry.result())
            .append("occurred_at", entry.timestamp().toString()).append("language", entry.language().code)
            .append("webhook_status", webhookEnabled ? "PENDING" : "DISABLED")
            .append("webhook_attempts", 0).append("webhook_http_status", null)
            .append("webhook_error", null).append("webhook_updated_at", Instant.now().toString())
            .append("schema_version", 1);
        try {
            db.getCollection("moderation_cases").insertOne(document);
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getCode() == 11000) return false;
            throw unavailable(ex);
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    @Override public void update(WebhookModLogger.Entry entry, String status, Integer httpStatus,
                                 String error, boolean attempt) throws SQLException {
        try {
            db.getCollection("moderation_cases").updateOne(
                and(eq("guild_id", entry.guildId()), eq("case_id", entry.caseId())),
                combine(set("webhook_status", status), set("webhook_http_status", httpStatus),
                    set("webhook_error", error), set("webhook_updated_at", Instant.now().toString()),
                    inc("webhook_attempts", attempt ? 1 : 0)));
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    @Override public Map<String, String> prefixes() throws SQLException {
        var loaded = new HashMap<String, String>();
        try {
            for (Document row : db.getCollection("guild_prefixes").find())
                loaded.put(row.getString("guild_id"), row.getString("prefix"));
            return loaded;
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    @Override public void setPrefix(String guildId, String prefix) throws SQLException {
        try {
            db.getCollection("guild_prefixes").updateOne(eq("guild_id", guildId),
                combine(set("prefix", prefix), setOnInsert("guild_id", guildId),
                    setOnInsert("schema_version", 1)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    Map<String, Language> languages() throws SQLException {
        var loaded = new HashMap<String, Language>();
        try {
            for (Document row : db.getCollection("guild_languages").find())
                loaded.put(row.getString("guild_id"), Language.parse(row.getString("language")));
            return loaded;
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    void setLanguage(String guildId, Language language) throws SQLException {
        try {
            db.getCollection("guild_languages").updateOne(eq("guild_id", guildId),
                combine(set("language", language.code), setOnInsert("guild_id", guildId),
                    setOnInsert("schema_version", 1)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (MongoException ex) { throw unavailable(ex); }
    }

    @Override public void close() { client.close(); }
}
