package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    @Test
    void balanceReadsMatchingElixirResponseWithoutComputingStateInJava() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/economy/v1/execute", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                assertEquals("wallet.balance", request.path("operation").asText());
                assertEquals("123456789012345678", request.path("context").path("actor_user_id").asText());
                assertEquals("456789012345678901", request.path("context").path("target_user_id").asText());
                assertFalse(request.has("idempotency_key"));
                byte[] response = JSON.writeValueAsBytes(Map.of(
                    "request_id", request.path("request_id").asText(), "status", "ok",
                    "result", Map.of("type", "wallet_balance", "user_id", "456789012345678901", "balance", "4821")));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var client = new EconomyV2Client(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), SECRET);
            assertEquals(4821, client.balance("123456789012345678", "234567890123456789",
                "345678901234567890", "456789012345678901"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inventoryCatalogAndLeaderboardParseNeutralStructuredResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/economy/v1/execute", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String operation = request.path("operation").asText();
                Map<String, Object> result = switch (operation) {
                    case "inventory.list" -> Map.of("type", "inventory", "user_id", "456789012345678901",
                        "items", java.util.List.of(Map.of("item_id", "fish", "quantity", "3")));
                    case "shop.catalog" -> Map.of("type", "shop_catalog",
                        "products", java.util.List.of(Map.of("product_id", "rod", "name", "Fishing Rod",
                            "description", "Basic tool", "category", "tools", "current_price", "65",
                            "effective_price", "52", "stock", "-1", "available", true, "rarity", "common")),
                        "sales", java.util.List.of(Map.of("product_id", "rod", "category", "",
                            "discount_percent", 20, "ends_at_epoch", "2000000000")));
                    case "wallet.leaderboard" -> Map.of("type", "leaderboard", "entries",
                        java.util.List.of(Map.of("user_id", "456789012345678901", "balance", "4821")));
                    default -> throw new AssertionError("Unexpected operation " + operation);
                };
                byte[] response = JSON.writeValueAsBytes(Map.of("request_id", request.path("request_id").asText(),
                    "status", "ok", "result", result));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var client = new EconomyV2Client(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), SECRET);
            var inventory = client.inventory("123456789012345678", "234567890123456789",
                "345678901234567890", "456789012345678901");
            assertEquals(new EconomyV2Client.InventoryItem("fish", 3), inventory.items().getFirst());
            var catalog = client.shopCatalog("123456789012345678", "234567890123456789",
                "345678901234567890", "tools");
            assertEquals(52, catalog.products().getFirst().effectivePrice());
            assertEquals(-1, catalog.products().getFirst().stock());
            assertEquals(20, catalog.sales().getFirst().discountPercent());
            assertEquals(2_000_000_000L, catalog.sales().getFirst().endsAtEpoch());
            var leaderboard = client.leaderboard("123456789012345678", "234567890123456789",
                "345678901234567890", 10);
            assertEquals(new EconomyV2Client.LeaderboardEntry("456789012345678901", 4821), leaderboard.getFirst());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableServiceAndMalformedResponseFailClosed() throws Exception {
        int unusedPort;
        try (var socket = new ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        var unavailable = new EconomyV2Client(HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + unusedPort), SECRET, Duration.ofMillis(200));
        assertThrows(java.io.IOException.class, () -> unavailable.balance("123", "234", "345", "456"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/economy/v1/execute", exchange -> {
            try (exchange) {
                byte[] response = "{\"request_id\":\"wrong\",\"status\":\"ok\",\"result\":{\"type\":\"wallet_balance\",\"user_id\":\"456\",\"balance\":\"10\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var client = new EconomyV2Client(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), SECRET);
            assertThrows(java.io.IOException.class, () -> client.balance("123", "234", "345", "456"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void slowElixirServiceTimesOut() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/economy/v1/execute", exchange -> {
            try (exchange) {
                try { Thread.sleep(350); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try {
            var client = new EconomyV2Client(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), SECRET, Duration.ofMillis(80));
            assertThrows(HttpTimeoutException.class, () -> client.balance("123", "234", "345", "456"));
        } finally {
            server.stop(0);
        }
    }
}
