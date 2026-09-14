package music;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class StatsStoreTest {
    @TempDir Path dir;
    @Test void persistsContextUpdatesAndLifecycleAcrossReopen() throws Exception {
        var path = dir.resolve("bot.db");
        var store = new ModLogStore(path);
        store.lifecycle("session1", "BOT_STARTED", null, Instant.now());
        store.lifecycle("session1", "BOT_STOPPED", "RESTART", Instant.now());
        store.stats("bot", "guild", "Old", "channel", "old");
        var reopened = new ModLogStore(path);
        var stats = reopened.stats("bot", "guild", "New's server", "channel", "new");
        assertEquals(1, stats.starts());
        assertNotNull(stats.lastRestart());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var sql = connection.createStatement(); var result = sql.executeQuery("SELECT * FROM bot_stats_context")) {
            assertTrue(result.next());
            assertEquals("New's server", result.getString("guild_name"));
            assertEquals("new", result.getString("channel_name"));
            assertFalse(result.next());
        }
    }
    @Test void freshDatabaseHasNoRestart() throws Exception {
        var stats = new ModLogStore(dir.resolve("new.db")).stats("b", "g", "G", "c", "C");
        assertEquals(0, stats.starts());
        assertNull(stats.lastRestart());
    }
}
