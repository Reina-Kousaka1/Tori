package music;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class SnipeCacheTest {
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-10T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void deletionIsIsolatedByChannelAndGuildAndUsesLatestEdit() {
        var cache = new SnipeCache();
        cache.remember(1, 10, 20, 30, "original");
        cache.remember(1, 10, 20, 30, "edited");
        assertNull(cache.last(10, 20));
        cache.delete(1, 10, 20);
        assertEquals("edited", cache.last(10, 20).content());
        assertEquals(30, cache.last(10, 20).authorId());
        assertNull(cache.last(11, 20));
        assertNull(cache.last(10, 21));
        cache.delete(999, 10, 20);
        assertNull(cache.last(10, 20), "An unknown latest deletion must not expose an older message");
    }
    @Test void receivedAndDeletedEntriesExpireAndRestartClearsCache() {
        var clock = new MutableClock();
        var cache = new SnipeCache(clock, 10);
        cache.remember(1, 10, 20, 30, "old");
        clock.now = clock.now.plus(Duration.ofHours(1));
        cache.delete(1, 10, 20);
        assertNull(cache.last(10, 20));
        cache.remember(2, 10, 20, 30, "new");
        cache.delete(2, 10, 20);
        assertNotNull(cache.last(10, 20));
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertNull(cache.last(10, 20));
        cache.remember(3, 10, 20, 30, "clear");
        cache.delete(3, 10, 20);
        cache.clear();
        assertNull(cache.last(10, 20));
    }
    @Test void storageIsBoundedAndContentFitsPublicReply() {
        var cache = new SnipeCache(Clock.systemUTC(), 1);
        cache.remember(1, 10, 20, 30, "first");
        cache.remember(2, 10, 21, 30, "x".repeat(4000));
        cache.delete(1, 10, 20);
        assertNull(cache.last(10, 20));
        cache.delete(2, 10, 21);
        var result = cache.last(10, 21);
        assertEquals(1700, result.content().length());
        for (var language : Language.values()) assertTrue(Messages.text(language, "snipe.result",
            result.authorId(), result.deletedAt().getEpochSecond(), result.content()).length() <= 1950);
        cache.remember(3, 10, 22, 30, "third");
        cache.delete(3, 10, 22);
        assertNull(cache.last(10, 21));
        assertNotNull(cache.last(10, 22));
    }
}