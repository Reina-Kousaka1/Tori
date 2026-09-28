package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EconomyV2ClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "test-secret-with-at-least-32-characters";
    private static final Map<String, String> CONTEXT = Map.of(
            "actor_user_id", "123456789012345678", "guild_id", "234567890123456789",
            "channel_id", "345678901234567890");

    @Test
    void retryUsesExactlyTheSamePayloadAndStringSnowflakes() throws Exception {
        var captured = new ArrayList<JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/economy/v1/execute", exchange -> {
            try (exchange) {
                assertEquals("Bearer " + SECRET, exchange.getRequestHeaders().getFirst("Authorization"));
                captured.add(JSON.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"request_id":"927dfac0-0fb1-40de-96d0-5bad7b88ce7c","status":"ok",
                         "result":{"type":"daily_claimed","credits_awarded":"150","balance":"920"}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var client = new EconomyV2Client(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), SECRET);
            var request = EconomyV2Client.Request.mutation("456789012345678901",
                    "daily.claim", CONTEXT, Map.of());
            assertEquals(200, client.execute(request).httpStatus());
            assertEquals(200, client.execute(request).httpStatus());
            assertEquals(2, captured.size());
            assertEquals(captured.get(0), captured.get(1));
            assertEquals("discord-interaction:456789012345678901",
                    captured.get(0).path("idempotency_key").asText());
            assertTrue(captured.get(0).path("context").path("actor_user_id").isTextual());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNumericDiscordContextBeforeNetworkCall() {
        assertThrows(IllegalArgumentException.class, () -> new EconomyV2Client.Request(
                "927dfac0-0fb1-40de-96d0-5bad7b88ce7c", "discord-interaction:123",
                "daily.claim", Map.of("actor_user_id", "nope",
                    "guild_id", "234567890123456789", "channel_id", "345678901234567890"), Map.of()));
    }
}
