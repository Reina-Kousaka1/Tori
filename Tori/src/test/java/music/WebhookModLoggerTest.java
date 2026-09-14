package music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebhookModLoggerTest {
    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/this_is_a_fake_webhook_token_for_tests";
    private static WebhookModLogger.Entry entry() {
        return new WebhookModLogger.Entry("123", "timeout", "Testserver", "10", "20", "Moderator", "30", "Nutzer 40; 10 Minuten",
            "Spam @everyone <@123>", "Erfolgreich", Instant.parse("2026-09-08T12:00:00Z"));
    }
    @Test void payloadContainsContextAndDisablesMentions() throws Exception {
        var json = new ObjectMapper().readTree(WebhookModLogger.payload(entry()));
        assertTrue(json.path("allowed_mentions").path("parse").isEmpty());
        var embed = json.path("embeds").get(0);
        assertEquals("Moderation /timeout", embed.path("title").asText());
        assertEquals("2026-09-08T12:00:00Z", embed.path("timestamp").asText());
        assertEquals(ToriEmbeds.footer(Language.DE, "Fall-ID: 123"), embed.path("footer").path("text").asText());
        assertEquals(6, embed.path("fields").size());
        assertTrue(embed.toString().contains("Spam @everyone"));
        assertTrue(embed.toString().contains("Nutzer 40; 10 Minuten"));
    }
    @Test void capsEmbedFieldAndTotalLengths() throws Exception {
        String longText = "x".repeat(10000);
        var entry = new WebhookModLogger.Entry(longText, longText, longText, longText, longText, longText,
            longText, longText, longText, longText, Instant.now());
        var embed = new ObjectMapper().readTree(WebhookModLogger.payload(entry)).path("embeds").get(0);
        int total = embed.path("title").asText().length() + embed.path("footer").path("text").asText().length();
        for (var field : embed.path("fields")) {
            assertTrue(field.path("value").asText().length() <= 1024);
            total += field.path("name").asText().length() + field.path("value").asText().length();
        }
        assertTrue(total <= 6000);
    }
    @Test void endpointRequestsDeliveryConfirmationAndSupportsThreads() {
        assertEquals(URL + "?wait=true", WebhookModLogger.validateEndpoint(URL).toString());
        assertEquals(URL + "?thread_id=123456789012345678&wait=true",
            WebhookModLogger.validateEndpoint(URL + "?thread_id=123456789012345678").toString());
    }
    @ParameterizedTest @ValueSource(strings = {"http://discord.com/api/webhooks/a/b", "https://evil.test/api/webhooks/a/b",
        "https://discord.com.evil.test/api/webhooks/a/b", "https://discord.com/api/webhooks/a/b", "not a url"})
    void rejectsInvalidUrlsWithoutLeakingSecrets(String url) {
        var ex = assertThrows(IllegalArgumentException.class, () -> WebhookModLogger.validateEndpoint(url + "SECRET"));
        assertFalse(ex.getMessage().contains("SECRET"));
        assertNull(ex.getCause());
    }
    @Test void disabledLoggerSendsNothing() {
        try (var logger = new WebhookModLogger("", (uri, body) -> { fail("Must not send"); return null; }, ms -> {})) {
            logger.publish(entry()); logger.deliver(entry());
        }
    }
    @Test void rateLimitRetriesAfterRequestedDelay() {
        var attempts = new AtomicInteger();
        List<Long> waits = new ArrayList<>();
        try (var logger = new WebhookModLogger(URL, (uri, body) -> attempts.incrementAndGet() == 1
                ? new WebhookModLogger.Response(429, "{\"retry_after\":0.25}") : new WebhookModLogger.Response(200, "{}"), waits::add)) {
            logger.deliver(entry());
        }
        assertEquals(2, attempts.get());
        assertEquals(List.of(250L), waits);
    }
    @Test void retryCountIsBounded() {
        var attempts = new AtomicInteger();
        try (var logger = new WebhookModLogger(URL, (uri, body) -> {
            attempts.incrementAndGet(); return new WebhookModLogger.Response(429, "{\"retry_after\":0.1}");
        }, ms -> {})) { logger.deliver(entry()); }
        assertEquals(3, attempts.get());
    }
    @Test void longRateLimitAlsoPausesSubsequentEntries() {
        var attempts = new AtomicInteger();
        try (var logger = new WebhookModLogger(URL, (uri, body) -> {
            attempts.incrementAndGet(); return new WebhookModLogger.Response(429, "{\"retry_after\":120}");
        }, ms -> fail("Long delays must not hold the worker"))) {
            logger.deliver(entry()); logger.deliver(entry());
        }
        assertEquals(1, attempts.get());
    }
    @Test void doesNotRetryPermanentOrAmbiguousFailures() {
        for (int status : new int[]{401, 404, 500}) {
            var attempts = new AtomicInteger();
            try (var logger = new WebhookModLogger(URL, (uri, body) -> {
                attempts.incrementAndGet(); return new WebhookModLogger.Response(status, "secret response");
            }, ms -> fail("Must not retry"))) { assertDoesNotThrow(() -> logger.deliver(entry())); }
            assertEquals(1, attempts.get());
        }
    }
    @Test void transportFailureDoesNotEscape() {
        try (var logger = new WebhookModLogger(URL, (uri, body) -> { throw new RuntimeException("secret URL"); }, ms -> {})) {
            assertDoesNotThrow(() -> logger.deliver(entry()));
        }
    }
    @Test void publishDoesNotWaitForTransport() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var logger = new WebhookModLogger(URL, (uri, body) -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return new WebhookModLogger.Response(200, "{}");
        }, ms -> {})) {
            try {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> logger.publish(entry()));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }
}
