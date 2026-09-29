package music;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real Java HTTP -> Elixir -> isolated PostgreSQL contract check. */
class EconomyV2LiveTest {
    @Test void javaReadsTheBalanceOwnedByTheIsolatedDatabase() throws Exception {
        String url = System.getenv("TORI_ECONOMY_TEST_API_URL");
        assumeTrue(url != null, "Set TORI_ECONOMY_TEST_API_URL for an isolated live test");
        assertTrue(url.matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+"));
        String secret = System.getenv("TORI_ECONOMY_TEST_API_SECRET");
        assertNotNull(secret);
        var client = new EconomyV2Client(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build(), URI.create(url), secret);
        assertEquals(4821L, client.balance("123456789012345678", "234567890123456789",
            "345678901234567890", "456789012345678901"));
    }
}
