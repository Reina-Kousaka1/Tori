package music;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToriPresenceSyncTest {
    @Test void missingProviderUsesTheLocalGeneralFallback() {
        try (var sync = ToriPresenceSync.disabled()) {
            assertFalse(sync.available());
            assertEquals(ToriPresenceContext.general(), sync.current());
        }
    }

    @Test void keepsTheLastValidContextWhenTheElixirProviderIsUnavailable() throws Exception {
        var source = new FakeProvider();
        try (var sync = new ToriPresenceSync(source, Clock.systemUTC())) {
            sync.pollOnce();
            var ballet = sync.current();
            assertEquals(ToriPresenceContext.Activity.BALLET, ballet.activity());
            source.failure.set(true);
            assertThrows(Exception.class, sync::pollOnce);
            assertEquals(ballet, sync.current());
        }
    }

    @Test void expiredCachedContextSafelyFallsBackToGeneral() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-09-30T12:00:00Z"));
        var source = new FakeProvider();
        source.value.set(new ToriPresenceContext(ToriPresenceContext.Activity.CHEER,
            ToriPresenceContext.Mood.EXCITED, ToriPresenceContext.Season.AUTUMN,
            null, 0.5, 2, clock.instant(), clock.instant().plusSeconds(60)));
        try (var sync = new ToriPresenceSync(source, clock)) {
            sync.pollOnce();
            assertEquals(ToriPresenceContext.Activity.CHEER, sync.current().activity());
            clock.advanceSeconds(60);
            assertEquals(ToriPresenceContext.general(), sync.current());
        }
    }

    private static final class FakeProvider implements ToriPresenceSync.Provider {
        private final AtomicReference<ToriPresenceContext> value = new AtomicReference<>(
            new ToriPresenceContext(ToriPresenceContext.Activity.BALLET,
                ToriPresenceContext.Mood.FOCUSED, ToriPresenceContext.Season.AUTUMN,
                null, 0.7, 1, Instant.now(), null));
        private final AtomicReference<Boolean> failure = new AtomicReference<>(false);

        @Override public ToriPresenceContext fetch() throws Exception {
            if (failure.get()) throw new java.io.IOException("service unavailable");
            return value.get();
        }

        @Override public ToriPresenceContext update(String activity, String specialEvent, Long ttlSeconds) {
            return value.get();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        void advanceSeconds(long seconds) { instant.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
