package music;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only against an explicitly opted-in, dedicated tori_test database and random test accounts. */
class PostgresCurrencyActivityLiveTest {
    @Test void activitiesTransfersWagersAndEquipmentAreAtomicAndPersistent() throws Exception {
        assumeTrue("YES".equals(System.getenv("TORI_POSTGRES_LIVE_TEST")));
        String url = System.getenv("TORI_TEST_DATABASE_URL");
        String user = System.getenv("TORI_TEST_DATABASE_USER");
        String password = System.getenv("TORI_TEST_DATABASE_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://[^?]+/tori_test(?:\\?.*)?")
            || user == null || password == null)
            throw new IllegalStateException("Live economy activity test requires a dedicated tori_test database.");

        String sender = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        String recipient = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        boolean ownsAccounts = false;
        try {
            try (var database = new PostgresDatabase(url, user, password)) {
                assertNoAccount(database, sender);
                assertNoAccount(database, recipient);
                ownsAccounts = true;
                var store = new PostgresCurrencyStore(database);
                assertTrue(store.changeBalance(sender, 120));
                assertTrue(store.transfer(sender, recipient, 30));
                assertEquals(90, store.balance(sender));
                assertEquals(30, store.balance(recipient));
                assertFalse(store.transfer(sender, sender, 1));
                assertFalse(store.transfer(sender, recipient, 10_000));
                assertEquals(90, store.balance(sender));

                long now = System.currentTimeMillis();
                assertEquals(0, store.beg(sender, now, 20));
                assertEquals(59_999, store.beg(sender, now + 1, 30));
                long afterBeg = store.balance(sender);
                assertEquals(110, afterBeg);

                var work = store.work(sender, now, "fish");
                assertEquals("fish", work.jobId());
                assertTrue(work.amount() >= 35 && work.amount() <= 100);
                assertTrue(store.work(sender, now + 1, "fish").remaining() > 0);
                long afterWork = store.balance(sender);
                assertEquals(afterBeg + work.amount(), afterWork);

                assertTrue(store.settleWager(sender, 20, 40));
                assertEquals(afterWork + 20, store.balance(sender));
                assertFalse(store.settleWager(sender, 20, 100), "payout is capped at the catalog's 3x gross return");
                assertFalse(store.settleWager(sender, Long.MAX_VALUE, 0));
                assertEquals(afterWork + 20, store.balance(sender));

                assertFalse(store.changeBalance(sender, Long.MAX_VALUE), "balance overflow must be rejected");
                assertFalse(store.changeBalance(sender, -Long.MAX_VALUE), "negative balances must be rejected");
                assertEquals(afterWork + 20, store.balance(sender));

                var grant = store.grantItem(sender, "fishing_rod", 1);
                assertNotNull(grant);
                assertTrue(store.equip(sender, grant.item().id()));
                assertTrue(store.unequip(sender, "rod"));
                assertFalse(store.unequip(sender, "rod"));
                assertFalse(store.unequip(sender, "unknown"));

                var ranks = store.leaderboard(20);
                assertTrue(ranks.size() <= 20);
                for (int index = 1; index < ranks.size(); index++)
                    assertTrue(ranks.get(index - 1).balance() >= ranks.get(index).balance());
            }

            try (var database = new PostgresDatabase(url, user, password)) {
                var reopened = new PostgresCurrencyStore(database);
                assertEquals(30, reopened.balance(recipient));
                assertEquals(1, reopened.inventory(sender).stream().filter(item -> item.id().equals("fishing_rod"))
                    .findFirst().orElseThrow().quantity());
                assertTrue(reopened.equipment(sender).isEmpty());
            }
        } finally {
            if (ownsAccounts) cleanup(url, user, password, List.of(sender, recipient));
        }
    }

    private static void assertNoAccount(PostgresDatabase database, String userId) throws Exception {
        try (var connection = database.connection(); var query = connection.prepareStatement(
            "SELECT 1 FROM economy_accounts WHERE user_id=?")) {
            query.setString(1, userId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) throw new IllegalStateException("Random test account collision; refusing to touch existing data.");
            }
        }
    }

    private static void cleanup(String url, String user, String password, List<String> userIds) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("economy_equipment", "economy_tool_wear", "economy_inventory", "economy_accounts")) {
                    for (String userId : userIds) try (var delete = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE user_id=?")) {
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
