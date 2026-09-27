package music;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Destructive operations are confined to one random account in an explicitly named tori_test database. */
class PostgresCurrencyStoreLiveTest {
    @Test void economyTransactionsAndStateSurviveAStoreRestart() throws Exception {
        assumeTrue("YES".equals(System.getenv("TORI_POSTGRES_LIVE_TEST")));
        String url = System.getenv("TORI_TEST_DATABASE_URL");
        String user = System.getenv("TORI_TEST_DATABASE_USER");
        String password = System.getenv("TORI_TEST_DATABASE_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://[^?]+/tori_test(?:\\?.*)?")
            || user == null || password == null)
            throw new IllegalStateException("Live economy test requires credentials for a dedicated tori_test database.");

        String userId = Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        boolean ownsAccount = false;
        try {
            long gatheredCredits;
            try (var database = new PostgresDatabase(url, user, password)) {
                try (var connection = database.connection(); var query = connection.prepareStatement(
                    "SELECT 1 FROM economy_accounts WHERE user_id=?")) {
                    query.setString(1, userId);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) throw new IllegalStateException("Random test account collision; refusing to touch existing data.");
                    }
                }
                ownsAccount = true;
                var store = new PostgresCurrencyStore(database, new Random(7));
                assertEquals(0, store.balance(userId));
                assertEquals(0, store.daily(userId, 1_000_000L));
                assertTrue(store.daily(userId, 1_000_001L) > 0);

                var purchase = store.buy(userId, "fishing_bait", 2);
                assertNotNull(purchase);
                assertEquals(30, purchase.total());
                assertEquals(120, purchase.balance());
                assertNull(store.buy(userId, "crown", 1), "an unaffordable purchase must not debit or add inventory");
                assertEquals(2, store.inventory(userId).stream().filter(item -> item.id().equals("fishing_bait"))
                    .findFirst().orElseThrow().quantity());

                var sale = store.sell(userId, "fishing_bait", 1);
                assertNotNull(sale);
                assertEquals(7, sale.total());
                assertEquals(127, sale.balance());
                var rod = store.buy(userId, "fishing_rod", 1);
                assertNotNull(rod);
                assertEquals(62, rod.balance());
                assertTrue(store.equip(userId, "fishing_rod"));

                long now = 2_000_000L;
                var firstCatch = store.gather(userId, "fish", now);
                assertNotNull(firstCatch);
                assertTrue(firstCatch.credits() > 0);
                assertEquals(39, firstCatch.remainingDurability());
                assertTrue(store.gather(userId, "fish", now + 1).cooldownRemaining() > 0);
                gatheredCredits = firstCatch.credits();
                CurrencyStore.Gather lastCatch = firstCatch;
                for (int use = 1; use < 40; use++) {
                    lastCatch = store.gather(userId, "fish", now + use * 60_000L);
                    assertNotNull(lastCatch);
                    gatheredCredits += lastCatch.credits();
                }
                assertTrue(lastCatch.broke());
                assertTrue(store.equipment(userId).stream().noneMatch(tool -> tool.itemId().equals("fishing_rod")));
                assertNotNull(store.sell(userId, "fishing_rod", 1), "a broken, unequipped tool can be sold");
                assertEquals(62 + gatheredCredits + 32, store.balance(userId));
            }

            try (var reopened = new PostgresDatabase(url, user, password)) {
                var store = new PostgresCurrencyStore(reopened);
                assertEquals(62 + gatheredCredits + 32, store.balance(userId));
                assertFalse(store.inventory(userId).stream().anyMatch(item -> item.id().equals("fishing_rod")));
                assertEquals(1, store.inventory(userId).stream().filter(item -> item.id().equals("fishing_bait"))
                    .findFirst().orElseThrow().quantity());
                try (var connection = reopened.connection(); var query = connection.prepareStatement(
                    "SELECT count(*) FROM economy_tool_wear WHERE user_id=? AND item_id='fishing_rod'")) {
                    query.setString(1, userId);
                    try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
                }
            }
        } finally {
            if (ownsAccount) cleanup(url, user, password, userId);
        }
    }

    private static void cleanup(String url, String user, String password, String userId) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                for (String table : new String[] {"economy_equipment", "economy_tool_wear", "economy_inventory", "economy_accounts"}) {
                    try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE user_id=?")) {
                        delete.setString(1, userId);
                        delete.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        }
    }
}
