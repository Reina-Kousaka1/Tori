package music;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyCooldownTest {
    private static final long DAY = 24L * 60 * 60 * 1_000;

    @Test void claimUsesElapsedTwentyFourHoursRatherThanCalendarMidnight() {
        long claim = Instant.parse("2026-09-28T11:21:00Z").toEpochMilli();
        long justAfterMidnight = Instant.parse("2026-09-29T00:01:00Z").toEpochMilli();

        assertEquals(11 * 60 * 60_000L + 20 * 60_000L,
            DailyCooldown.remainingMillis(justAfterMidnight, claim));
    }

    @Test void cooldownIsBlockedUntilTheExactTwentyFourHourBoundary() {
        long claim = Instant.parse("2026-03-28T11:21:00Z").toEpochMilli();

        assertEquals(1, DailyCooldown.remainingMillis(claim + DAY - 1, claim));
        assertEquals(0, DailyCooldown.remainingMillis(claim + DAY, claim));
        assertEquals(0, DailyCooldown.remainingMillis(claim + DAY + 1, claim));
    }

    @Test void cooldownUsesAbsoluteTimeAcrossDaylightSavingTransitions() {
        long claim = Instant.parse("2026-03-28T11:21:00Z").toEpochMilli();
        long sameInstantNextDay = Instant.parse("2026-03-29T11:21:00Z").toEpochMilli();

        assertEquals(0, DailyCooldown.remainingMillis(sameInstantNextDay, claim));
    }

    @Test void cooldownDoesNotDependOnTheDefaultTimezone() {
        long claim = Instant.parse("2026-09-28T11:21:00Z").toEpochMilli();
        long nextClaim = Instant.parse("2026-09-29T11:21:00Z").toEpochMilli();

        assertEquals(0, DailyCooldown.remainingMillis(nextClaim, claim));
    }

    @Test void absentLegacyClaimTimestampHasNoCooldown() {
        assertEquals(0, DailyCooldown.remainingMillis(100, 0));
    }
}
