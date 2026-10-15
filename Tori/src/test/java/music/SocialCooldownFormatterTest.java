package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocialCooldownFormatterTest {
    @Test
    void roundsUpAndFormatsTheRemainingTimeCompactly() {
        assertEquals("23h 42m", SocialCooldownFormatter.format((23 * 60L + 42) * 60_000));
        assertEquals("5h 08m", SocialCooldownFormatter.format((5 * 60L + 8) * 60_000));
        assertEquals("1h 02m", SocialCooldownFormatter.format((62L * 60_000)));
        assertEquals("47m", SocialCooldownFormatter.format(47 * 60_000L));
        assertEquals("3m", SocialCooldownFormatter.format(2 * 60_000L + 1));
        assertEquals("1m", SocialCooldownFormatter.format(1));
    }
}
