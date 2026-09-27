package music;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in persistence check limited to a dedicated tori_test database and a fresh random guild key. */
class PostgresOrderFastpassLiveTest {
    @Test void paymentMethodAndFastpassPersistAndPrioritizeWithoutChangingOrderStatus() throws Exception {
        assumeTrue("YES".equals(System.getenv("TORI_POSTGRES_LIVE_TEST")));
        String url = System.getenv("TORI_TEST_DATABASE_URL");
        String user = System.getenv("TORI_TEST_DATABASE_USER");
        String password = System.getenv("TORI_TEST_DATABASE_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://[^?]+/tori_test(?:\\?.*)?")
            || user == null || password == null)
            throw new IllegalStateException("Live order test requires a dedicated tori_test database.");

        String guildId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        boolean ownsGuild = false;
        try {
            try (var database = new PostgresDatabase(url, user, password)) {
                assertNoGuildData(database, guildId);
                ownsGuild = true;
                var store = new TicketOrderStore(database);
                var regular = store.createOrder(guildId, "customer-1", "Design", "Regular request", null, "PayPal", false);
                var priority = store.createOrder(guildId, "customer-2", "Repair", "Fast request", null, "Cash App", true);

                assertEquals(List.of(priority.id(), regular.id()), store.orders(guildId, true).stream()
                    .map(TicketOrderStore.Order::id).toList());
                var persisted = store.order(guildId, priority.id());
                assertEquals("Cash App", persisted.paymentMethod());
                assertTrue(persisted.fastpass());
                assertEquals("NOTED", persisted.status(), "Fastpass must not silently start an order.");
            }
            try (var database = new PostgresDatabase(url, user, password)) {
                var store = new TicketOrderStore(database);
                var orders = store.orders(guildId, true);
                assertEquals("Cash App", orders.getFirst().paymentMethod());
                assertTrue(orders.getFirst().fastpass());
            }
        } finally {
            if (ownsGuild) cleanup(url, user, password, guildId);
        }
    }

    private static void assertNoGuildData(PostgresDatabase database, String guildId) throws Exception {
        try (var connection = database.connection(); var query = connection.prepareStatement("""
            SELECT EXISTS(SELECT 1 FROM guild_orders WHERE guild_id=?)
                OR EXISTS(SELECT 1 FROM guild_ticket_order_counters WHERE guild_id=?)
                OR EXISTS(SELECT 1 FROM guild_ticket_order_config WHERE guild_id=?)
            """)) {
            query.setString(1, guildId);
            query.setString(2, guildId);
            query.setString(3, guildId);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                if (rows.getBoolean(1)) throw new IllegalStateException("Random test guild collision; refusing to touch existing data.");
            }
        }
    }

    private static void cleanup(String url, String user, String password, String guildId) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("guild_order_status_events", "guild_order_assignment_events", "guild_orders",
                    "guild_ticket_order_counters")) {
                    try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE guild_id=?")) {
                        delete.setString(1, guildId);
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
