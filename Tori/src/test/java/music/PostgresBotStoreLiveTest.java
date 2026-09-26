package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in PostgreSQL connectivity check; requires a database explicitly named tori_test. */
class PostgresBotStoreLiveTest {
    @Test void connectsToDedicatedPostgresTestDatabase() throws Exception {
        assumeTrue("YES".equals(System.getenv("TORI_POSTGRES_LIVE_TEST")));
        String url = System.getenv("TORI_TEST_DATABASE_URL");
        String user = System.getenv("TORI_TEST_DATABASE_USER");
        String password = System.getenv("TORI_TEST_DATABASE_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://[^?]+/tori_test(?:\\?.*)?")
            || user == null || password == null)
            throw new IllegalStateException("Live PostgreSQL test requires credentials for a dedicated tori_test database.");
        try (var database = new PostgresDatabase(url, user, password)) {
            var store = new PostgresBotStore(database);
            assertNotNull(store.prefixes());
            assertNotNull(store.languages());
        }
    }
}
