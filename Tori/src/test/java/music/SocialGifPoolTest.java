package music;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SocialGifPoolTest {
    @Test
    void missingConfigurationLeavesTextOnlyPresentation() {
        assertTrue(SocialGifPool.fromJson(null).select("hug", "interaction").isEmpty());
        assertTrue(SocialGifPool.fromJson(" ").select("hug", "interaction").isEmpty());
    }

    @Test
    void configuredPoolSelectsDeterministicallyForAnInteraction() {
        var pool = SocialGifPool.fromJson("""
            {"hug":["https://media.example.test/hug-a.gif","https://media.example.test/hug-b.gif"]}
            """);
        Optional<URI> first = pool.select("hug", "987654321012345678");
        assertTrue(first.isPresent());
        assertEquals(first, pool.select("hug", "987654321012345678"));
        assertTrue(first.orElseThrow().toString().endsWith(".gif"));
        assertTrue(pool.select("kiss", "987654321012345678").isEmpty());
    }

    @Test
    void rejectsNonHttpsOrUnknownActionUrlsWithoutEchoingConfiguration() {
        String configuredUrl = "http://media.example.test/private.gif";
        var error = assertThrows(IllegalArgumentException.class,
            () -> SocialGifPool.fromJson("{\"hug\":[\"" + configuredUrl + "\"]}"));
        assertFalse(error.getMessage().contains(configuredUrl));
        assertThrows(IllegalArgumentException.class,
            () -> SocialGifPool.fromJson("{\"unknown\":[\"https://media.example.test/a.gif\"]}"));
    }
}
