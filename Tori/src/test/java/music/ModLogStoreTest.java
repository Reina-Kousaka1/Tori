package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModLogStoreTest {
    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/this_is_a_fake_webhook_token_for_tests";
    @TempDir Path directory;
    private Path database() { return directory.resolve("nested/moderation.db"); }
    private static WebhookModLogger.Entry entry() {
        return new WebhookModLogger.Entry("123", "ban", "Server", "10", "20", "Mod", "30", "User 40",
            "Spam '); DROP TABLE moderation_cases; -- 🎵", "Banned", Instant.parse("2026-09-10T12:00:00Z"), Language.EN);
    }
    private Map<String, Object> row() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM moderation_cases")) {
            assertTrue(rows.next());
            var result = new HashMap<String, Object>();
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                result.put(rows.getMetaData().getColumnName(i), rows.getObject(i));
            assertFalse(rows.next(), "One case must not be duplicated");
            return result;
        }
    }
    @Test void disabledWebhookStillSavesCompleteCaseAcrossReopen() throws Exception {
        var store = new ModLogStore(database());
        try (var logger = new WebhookModLogger("", (uri, body) -> { fail("No webhook configured"); return null; }, ms -> {}, store)) {
            logger.publish(entry());
        }
        new ModLogStore(database());
        var row = row();
        assertEquals("DISABLED", row.get("webhook_status"));
        assertEquals(0, row.get("webhook_attempts"));
        assertEquals(entry().reason(), row.get("reason"));
        assertEquals("2026-09-10T12:00:00Z", row.get("occurred_at"));
        assertEquals("en", row.get("language"));
        assertEquals("30", row.get("moderator_id"));
        assertEquals("Banned", row.get("result"));
        assertFalse(row.toString().contains(URL));
    }
    @Test void savesBeforeNetworkAndDeduplicatesPublishAcrossRestart() throws Exception {
        var store = new ModLogStore(database());
        var requests = new AtomicInteger();
        try (var logger = new WebhookModLogger(URL, (uri, body) -> {
            assertEquals("SENDING", row().get("webhook_status"));
            requests.incrementAndGet();
            return new WebhookModLogger.Response(200, "{}");
        }, ms -> {}, store)) {
            logger.publish(entry());
            logger.publish(entry());
        }
        try (var logger = new WebhookModLogger(URL, (uri, body) -> {
            fail("Persisted case must not be sent twice"); return null;
        }, ms -> {}, new ModLogStore(database()))) { logger.publish(entry()); }
        assertEquals(1, requests.get());
        assertEquals("DELIVERED", row().get("webhook_status"));
        assertEquals(1, row().get("webhook_attempts"));
        assertEquals(200, row().get("webhook_http_status"));
    }
    @Test void retriesAndFinalHttpFailureAreRecordedWithoutResponseBody() throws Exception {
        var requests = new AtomicInteger();
        try (var logger = new WebhookModLogger(URL, (uri, body) -> requests.incrementAndGet() == 1
                ? new WebhookModLogger.Response(429, "{\"retry_after\":0.1}")
                : new WebhookModLogger.Response(404, "SECRET RESPONSE"), ms -> {}, new ModLogStore(database()))) {
            logger.publish(entry());
        }
        assertEquals("FAILED", row().get("webhook_status"));
        assertEquals(2, row().get("webhook_attempts"));
        assertEquals(404, row().get("webhook_http_status"));
        assertFalse(row().toString().contains("SECRET"));
    }
    @Test void transportFailurePreservesCaseWithoutExceptionSecrets() throws Exception {
        try (var logger = new WebhookModLogger(URL, (uri, body) -> { throw new Exception("SECRET"); },
                ms -> {}, new ModLogStore(database()))) { logger.publish(entry()); }
        assertEquals("FAILED", row().get("webhook_status"));
        assertEquals(entry().reason(), row().get("reason"));
        assertFalse(row().toString().contains("SECRET"));
    }
    @Test void closedQueueRetainsCaseAsNotSent() throws Exception {
        var logger = new WebhookModLogger(URL, (uri, body) -> { fail("Closed"); return null; }, ms -> {}, new ModLogStore(database()));
        logger.close();
        logger.publish(entry());
        assertEquals("NOT_SENT", row().get("webhook_status"));
        assertEquals(0, row().get("webhook_attempts"));
    }
    @Test void parallelWritersPreserveAllDistinctCases() throws Exception {
        var store = new ModLogStore(database());
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                String id = Integer.toString(i);
                tasks.add(workers.submit(() -> store.save(new WebhookModLogger.Entry(id, "purge", "Guild", "1", "2", "Mod", "3",
                    "channel", "reason", "done", Instant.now()), false)));
            }
            for (var result : tasks) assertTrue(result.get());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM moderation_cases")) {
            assertTrue(rows.next()); assertEquals(24, rows.getInt(1));
        }
    }
    @Test void configuredDatabasePathIsUsedAndInvalidPathErrorIsScrubbed() throws Exception {
        var env = directory.resolve(".env");
        Files.writeString(env, "MODLOG_DB_PATH=" + database().toString().replace('\\', '/') + "\n");
        ModLogStore.fromConfig(BotConfig.load(directory));
        assertTrue(Files.isRegularFile(database()));
        Path blocker = directory.resolve("SECRET");
        Files.writeString(blocker, "not a directory");
        Files.writeString(env, "MODLOG_DB_PATH=" + blocker.resolve("db.sqlite").toString().replace('\\', '/') + "\n");
        var error = assertThrows(IllegalArgumentException.class, () -> ModLogStore.fromConfig(BotConfig.load(directory)));
        assertFalse(error.getMessage().contains("SECRET"));
        assertNull(error.getCause());
    }
}
