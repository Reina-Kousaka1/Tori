package music;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

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
        String databaseUrl = System.getenv("TORI_TEST_DATABASE_URL");
        String databaseUser = System.getenv("TORI_TEST_DATABASE_USER");
        String databasePassword = System.getenv("TORI_TEST_DATABASE_PASSWORD");
        assumeTrue(databaseUrl != null && databaseUrl.matches("jdbc:postgresql://[^?]+/tori_test(?:\\?.*)?")
            && databaseUser != null && databasePassword != null,
            "Live economy reads require an explicitly named tori_test PostgreSQL database");
        String actor = snowflake();
        String user = snowflake();
        String rankingUser = snowflake();
        String productId = "api_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        seed(databaseUrl, databaseUser, databasePassword, actor, user, rankingUser, productId);
        var client = new EconomyV2Client(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build(), URI.create(url), secret);
        try {
            assertEquals(4821L, client.balance(actor, "234567890123456789",
                "345678901234567890", user));
            var inventory = client.inventory(actor, "234567890123456789", "345678901234567890", user);
            assertEquals(user, inventory.userId());
            assertEquals(1, inventory.items().size());
            assertEquals(new EconomyV2Client.InventoryItem("fish", 3), inventory.items().getFirst());
            var shop = client.shopCatalog(actor, "234567890123456789", "345678901234567890", "api_test");
            var product = shop.products().stream().filter(item -> item.id().equals(productId)).findFirst().orElseThrow();
            assertEquals(314L, product.effectivePrice());
            assertEquals(-1L, product.stock());
            assertTrue(product.available());
            var leaderboard = client.leaderboard(actor, "234567890123456789", "345678901234567890", 100);
            assertTrue(leaderboard.stream().anyMatch(entry -> entry.userId().equals(user) && entry.balance() == 4821L));
        } finally {
            cleanup(databaseUrl, databaseUser, databasePassword, user, rankingUser, productId);
        }
    }

    private static String snowflake() {
        return Long.toString(8_000_000_000_000_000_000L +
            Math.floorMod(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 100_000_000_000_000_000L));
    }

    private static void seed(String url, String user, String password, String actor, String target,
                             String rankingUser, String product)
            throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            try (var insert = connection.prepareStatement("INSERT INTO economy_accounts(user_id,balance) VALUES (?,0),(?,4821),(?,9223372036854775807)")) {
                insert.setString(1, actor);
                insert.setString(2, target);
                insert.setString(3, rankingUser);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO economy_inventory(user_id,item_id,quantity) VALUES (?, 'fish', 3)")) {
                insert.setString(1, target);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO economy_market_products(product_id,name,description,category,current_price,base_price,
                        minimum_price,maximum_price,volatility,stock,available,rarity,tags,created_at,updated_at,next_price_at)
                    VALUES (?, 'API test product', 'Read path fixture', 'api_test', 314, 314, 100, 500,
                        0, -1, TRUE, 'test', ARRAY[]::TEXT[], now(), now(), now() + interval '1 day')
                    """)) {
                insert.setString(1, product);
                insert.executeUpdate();
            }
        }
    }

    private static void cleanup(String url, String user, String password, String owner,
                                String rankingUser, String product)
            throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            try (var delete = connection.prepareStatement("DELETE FROM economy_inventory WHERE user_id=?")) {
                delete.setString(1, owner);
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_accounts WHERE user_id=?")) {
                delete.setString(1, owner);
                delete.executeUpdate();
                delete.setString(1, rankingUser);
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_market_products WHERE product_id=?")) {
                delete.setString(1, product);
                delete.executeUpdate();
            }
        }
    }
}
