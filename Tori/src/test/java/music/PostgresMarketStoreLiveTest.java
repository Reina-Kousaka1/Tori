package music;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises market transactions only in an explicitly opted-in, dedicated tori_test database. */
class PostgresMarketStoreLiveTest {
    @Test void quotePurchaseIdempotencyStockAndPriceHistorySurviveRestart() throws Exception {
        assumeTrue("YES".equals(System.getenv("TORI_POSTGRES_LIVE_TEST")));
        String url = System.getenv("TORI_TEST_DATABASE_URL");
        String user = System.getenv("TORI_TEST_DATABASE_USER");
        String password = System.getenv("TORI_TEST_DATABASE_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://[^?]+/tori_test(?:\\?.*)?")
            || user == null || password == null)
            throw new IllegalStateException("Live market test requires credentials for a dedicated tori_test database.");

        String userId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        String guildId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        String productId = "live_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String firstInteraction = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        String secondInteraction = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        boolean ownsAccount = false;
        boolean ownsProduct = false;
        Instant now = Instant.now();
        try {
            try (var database = new PostgresDatabase(url, user, password)) {
                assertNoAccount(database, userId);
                assertNoProduct(database, productId);
                ownsAccount = true;
                var currency = new PostgresCurrencyStore(database);
                assertEquals(0, currency.daily(userId, System.currentTimeMillis()));

                var market = new PostgresMarketStore(database, () -> 0.9);
                var product = new DeseModels.Product(productId, "Isolated market test item", "Temporary test fixture",
                    "live_test", 20, 20, 14, 26, 0.05, 2, now, now, now.plus(Duration.ofHours(1)),
                    true, "common", List.of("test"));
                assertTrue(market.createProduct(product));
                ownsProduct = true;
                assertEquals(20, market.quote(guildId, userId, productId, now));
                var readOnly = new PostgresMarketStore(database, () -> 0.9, MarketMode.READ_ONLY);
                assertEquals(20, readOnly.product(productId, now.plus(Duration.ofHours(2))).currentPrice(),
                    "browsing must not evolve prices in read-only mode");
                assertEquals(20, readOnly.quote(guildId, userId, productId, now.plusSeconds(1)));
                assertTrue(readOnly.activeSales(now).isEmpty());
                assertThrows(IllegalStateException.class,
                    () -> readOnly.buy(guildId, userId, firstInteraction, productId, 1, now));
                assertThrows(IllegalStateException.class, () -> readOnly.setStock(productId, 0, now));
                assertThrows(IllegalStateException.class, () -> readOnly.createProduct(product));
                assertThrows(IllegalStateException.class, () -> readOnly.seedCatalog(now));
                assertThrows(IllegalStateException.class, () -> readOnly.evolveDueProducts(now));
                assertThrows(IllegalStateException.class,
                    () -> readOnly.maybeCreateRandomSale(now, List.of(), List.of(productId)));
                assertEquals(2, market.product(productId, now).stock());
                assertEquals(150, currency.balance(userId));
            }

            try (var database = new PostgresDatabase(url, user, password)) {
                var market = new PostgresMarketStore(database, () -> 0.9);
                var currency = new PostgresCurrencyStore(database);
                var purchase = market.buy(guildId, userId, firstInteraction, productId, 1, now.plusSeconds(30));
                assertEquals(DeseModels.PurchaseState.PURCHASED, purchase.state());
                assertEquals(20, purchase.total());
                assertEquals(130, currency.balance(userId));

                assertEquals(DeseModels.PurchaseState.DUPLICATE,
                    market.buy(guildId, userId, firstInteraction, productId, 1, now.plusSeconds(31)).state());
                assertEquals(130, currency.balance(userId), "retrying the same Discord interaction must not charge twice");
                assertEquals(1, currency.inventory(userId).stream().filter(item -> item.id().equals(productId))
                    .findFirst().orElseThrow().quantity());

                assertEquals(20, market.quote(guildId, userId, productId, now.plusSeconds(32)));
                assertEquals(DeseModels.PurchaseState.OUT_OF_STOCK,
                    market.buy(guildId, userId, secondInteraction, productId, 2, now.plusSeconds(33)).state());
                assertEquals(130, currency.balance(userId), "a rejected stock purchase must roll back wallet changes");

                var repriced = market.product(productId, now.plus(Duration.ofHours(2)));
                assertNotNull(repriced);
                assertEquals(21, repriced.currentPrice());
                assertEquals(1, market.history(productId, 10).size());
            }

            try (var database = new PostgresDatabase(url, user, password)) {
                var currency = new PostgresCurrencyStore(database);
                var market = new PostgresMarketStore(database, () -> 0.9);
                assertEquals(130, currency.balance(userId));
                assertEquals(21, market.product(productId, now.plus(Duration.ofHours(2))).currentPrice());
                assertEquals(1, market.history(productId, 10).size());
                try (var connection = database.connection(); var query = connection.prepareStatement(
                    "SELECT count(*) FROM economy_market_inventory WHERE user_id=? AND product_id=?")) {
                    query.setString(1, userId);
                    query.setString(2, productId);
                    try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
                }
            }
        } finally {
            if (ownsAccount || ownsProduct) cleanup(url, user, password, userId, productId, ownsAccount, ownsProduct);
        }
    }

    private static void assertNoAccount(PostgresDatabase database, String userId) throws Exception {
        try (var connection = database.connection(); var query = connection.prepareStatement(
            "SELECT 1 FROM economy_accounts WHERE user_id=?")) {
            query.setString(1, userId);
            try (var rows = query.executeQuery()) { if (rows.next()) throw new IllegalStateException("Random test account collision; refusing to touch existing data."); }
        }
    }

    private static void assertNoProduct(PostgresDatabase database, String productId) throws Exception {
        try (var connection = database.connection(); var query = connection.prepareStatement(
            "SELECT 1 FROM economy_market_products WHERE product_id=?")) {
            query.setString(1, productId);
            try (var rows = query.executeQuery()) { if (rows.next()) throw new IllegalStateException("Random test product collision; refusing to touch existing data."); }
        }
    }

    private static void cleanup(String url, String user, String password, String userId, String productId,
                                boolean ownsAccount, boolean ownsProduct) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                if (ownsProduct) {
                    delete(connection, "DELETE FROM economy_market_inventory WHERE user_id=? OR product_id=?", userId, productId);
                    delete(connection, "DELETE FROM economy_market_transactions WHERE user_id=? OR product_id=?", userId, productId);
                    delete(connection, "DELETE FROM economy_market_quotes WHERE user_id=? OR product_id=?", userId, productId);
                    delete(connection, "DELETE FROM economy_market_price_history WHERE product_id=?", productId, null);
                    delete(connection, "DELETE FROM economy_market_sales WHERE product_id=?", productId, null);
                    delete(connection, "DELETE FROM economy_market_products WHERE product_id=?", productId, null);
                }
                if (ownsAccount) {
                    delete(connection, "DELETE FROM economy_inventory WHERE user_id=?", userId, null);
                    delete(connection, "DELETE FROM economy_equipment WHERE user_id=?", userId, null);
                    delete(connection, "DELETE FROM economy_tool_wear WHERE user_id=?", userId, null);
                    delete(connection, "DELETE FROM economy_accounts WHERE user_id=?", userId, null);
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private static void delete(java.sql.Connection connection, String sqlText, String first, String second) throws Exception {
        try (var statement = connection.prepareStatement(sqlText)) {
            statement.setString(1, first);
            if (sqlText.chars().filter(value -> value == '?').count() == 2) statement.setString(2, second);
            statement.executeUpdate();
        }
    }
}
