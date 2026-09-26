package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in check of the actual Main Tori MongoDB connection; never changes user data. */
class MongoBotStoreLiveTest {
    @Test void connectsToConfiguredDatabaseAndReadsGuildPrefixes() throws Exception {
        assumeTrue("YES".equals(System.getenv("TORI_MONGO_LIVE_TEST")));
        try (var store = MongoBotStore.fromConfig(BotConfig.load())) {
            assertNotNull(store.prefixes());
        }
    }
}
