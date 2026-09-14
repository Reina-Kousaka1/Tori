package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MainLifecycleTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("moderation.db"); }
    private List<Map<String, String>> events() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM bot_events ORDER BY rowid")) {
            var result = new ArrayList<Map<String, String>>();
            while (rows.next()) {
                var event = new HashMap<String, String>();
                for (String key : List.of("session_id", "event_type", "reason", "occurred_at", "started_at"))
                    event.put(key, rows.getString(key));
                result.add(event);
            }
            return result;
        }
    }

    @Test void restartIsPersistedBeforeClosingAndNotDuplicatedByShutdown() throws Exception {
        var startedAt = Instant.parse("2026-09-11T12:00:00Z");
        var bot = new Main(startedAt, new ModLogStore(database()));
        bot.recordStarted();
        bot.requestRestart();
        var beforeClose = events();
        assertEquals(List.of("BOT_STARTED", "BOT_STOPPED"), beforeClose.stream().map(e -> e.get("event_type")).toList());
        assertEquals("RESTART", beforeClose.get(1).get("reason"));
        assertEquals(startedAt.toString(), beforeClose.get(0).get("started_at"));
        bot.requestRestart();
        bot.close();
        bot.close();
        assertEquals(beforeClose, events());
    }

    @Test void newProcessSessionHasNewStartTimeAndKeepsPreviousEvents() throws Exception {
        var firstStart = Instant.parse("2026-09-11T12:00:00Z");
        try (var first = new Main(firstStart, new ModLogStore(database()))) {
            first.recordStarted(); first.requestRestart();
        }
        var secondStart = firstStart.plusSeconds(120);
        try (var second = new Main(secondStart, new ModLogStore(database()))) {
            second.recordStarted();
            assertEquals(3, events().size());
            assertNotEquals(events().get(0).get("session_id"), events().get(2).get("session_id"));
            assertEquals(secondStart.toString(), events().get(2).get("started_at"));
        }
        assertEquals("SHUTDOWN", events().get(3).get("reason"));
    }

    @Test void failedStartupDoesNotProduceSuccessfulStartOrRestartEvents() throws Exception {
        try (var bot = new Main(Instant.now(), new ModLogStore(database()))) { bot.requestRestart(); }
        assertTrue(events().isEmpty());
    }

    @Test void schemaExtensionPreservesExistingModerationCases() throws Exception {
        var store = new ModLogStore(database());
        var entry = new WebhookModLogger.Entry("123", "ban", "guild", "456", "789", "mod", "42",
            "target", "reason", "result", Instant.now());
        assertTrue(store.save(entry, false));
        try (var bot = new Main(Instant.now(), new ModLogStore(database()))) { bot.recordStarted(); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT case_id, reason FROM moderation_cases")) {
            assertTrue(rows.next()); assertEquals("123", rows.getString(1)); assertEquals("reason", rows.getString(2));
            assertFalse(rows.next());
        }
    }
}
