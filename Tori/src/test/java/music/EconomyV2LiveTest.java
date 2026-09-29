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
        var client = new EconomyV2Client(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build(), URI.create(url), secret);
        try {
            seed(databaseUrl, databaseUser, databasePassword, actor, user, rankingUser, productId);
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

            assumeTrue("YES".equals(System.getenv("TORI_ECONOMY_TEST_WRITE_ENABLED")),
                "Set TORI_ECONOMY_TEST_WRITE_ENABLED=YES only when the live API is configured for tori_test writes");
            String dailyInteraction = snowflake();
            var daily = client.dailyClaim(dailyInteraction, actor, "234567890123456789",
                "345678901234567890", null);
            assertTrue(daily.succeeded(), "daily failed with " + daily.errorCode());
            assertEquals(150, daily.credits());
            assertEquals(150, daily.balance());
            assertEquals(daily, client.dailyClaim(dailyInteraction, actor, "234567890123456789",
                "345678901234567890", null), "lost-response retry must replay the stored daily result");
            var cooldown = client.dailyClaim(snowflake(), actor, "234567890123456789",
                "345678901234567890", null);
            assertEquals("COOLDOWN_ACTIVE", cooldown.errorCode());

            String transferInteraction = snowflake();
            var transfer = client.transfer(transferInteraction, actor, "234567890123456789",
                "345678901234567890", user, 25);
            assertTrue(transfer.succeeded(), "transfer failed with " + transfer.errorCode());
            assertEquals(125, transfer.balance());
            assertEquals(transfer, client.transfer(transferInteraction, actor, "234567890123456789",
                "345678901234567890", user, 25), "lost-response retry must replay the stored transfer result");
            assertEquals(125, client.balance(actor, "234567890123456789", "345678901234567890", actor));
            assertEquals(4846, client.balance(actor, "234567890123456789", "345678901234567890", user));
            assertEquals("INSUFFICIENT_FUNDS", client.transfer(snowflake(), actor, "234567890123456789",
                "345678901234567890", user, 10_000).errorCode());
            assertEquals("INVALID_TARGET", client.transfer(snowflake(), actor, "234567890123456789",
                "345678901234567890", actor, 1).errorCode());
            assertEquals("INVALID_AMOUNT", client.transfer(snowflake(), actor, "234567890123456789",
                "345678901234567890", user, 0).errorCode());
        } finally {
            cleanup(databaseUrl, databaseUser, databasePassword, actor, user, rankingUser, productId);
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
            try (var check = connection.createStatement(); var rows = check.executeQuery(
                    "SELECT to_regclass('economy_v2_requests'), to_regclass('economy_v2_ledger_entries')")) {
                if (!rows.next() || rows.getString(1) == null || rows.getString(2) == null)
                    throw new IllegalStateException("The isolated tori_test database must have the draft V5 economy tables applied");
            }
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

    private static void cleanup(String url, String user, String password, String actor, String owner,
                                String rankingUser, String product)
            throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            try (var check = connection.createStatement(); var rows = check.executeQuery(
                    "SELECT to_regclass('economy_v2_requests'), to_regclass('economy_v2_ledger_entries')")) {
                if (!rows.next() || rows.getString(1) == null || rows.getString(2) == null) return;
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_v2_ledger_entries WHERE user_id IN (?,?,?) OR counterparty_user_id IN (?,?,?)")) {
                for (int index = 1; index <= 3; index++) {
                    String id = index == 1 ? actor : index == 2 ? owner : rankingUser;
                    delete.setString(index, id);
                    delete.setString(index + 3, id);
                }
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_v2_requests WHERE actor_user_id IN (?,?,?)")) {
                delete.setString(1, actor);
                delete.setString(2, owner);
                delete.setString(3, rankingUser);
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_inventory WHERE user_id=?")) {
                delete.setString(1, owner);
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_accounts WHERE user_id=?")) {
                delete.setString(1, owner);
                delete.executeUpdate();
                delete.setString(1, rankingUser);
                delete.executeUpdate();
                delete.setString(1, actor);
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM economy_market_products WHERE product_id=?")) {
                delete.setString(1, product);
                delete.executeUpdate();
            }
        }
    }
}
