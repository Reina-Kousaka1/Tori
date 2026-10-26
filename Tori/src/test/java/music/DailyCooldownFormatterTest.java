package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyCooldownFormatterTest {
    private static final long HOUR = 60L * 60 * 1_000;

    @Test void roundsAnyPartialMinuteUp() {
        assertEquals("1 min", DailyCooldownFormatter.format(Language.EN, 1));
        assertEquals("1 min", DailyCooldownFormatter.format(Language.EN, 60_000));
    }

    @Test void formatsTheTwentyFourHourDailyWindowAsHours() {
        assertEquals("24 hr", DailyCooldownFormatter.format(Language.EN, 24 * HOUR));
        assertEquals("24 Std.", DailyCooldownFormatter.format(Language.DE, 24 * HOUR));
        assertEquals("24 uur", DailyCooldownFormatter.format(Language.NL, 24 * HOUR));
    }

    @Test void keepsMinutesWhenTheRemainingTimeIsNotAnExactHour() {
        assertEquals("23 hr 59 min", DailyCooldownFormatter.format(Language.EN, 23 * HOUR + 59 * 60_000L));
    }
}
