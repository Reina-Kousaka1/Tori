package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {
    @TempDir Path directory;

    @Test void ownerIdLoadsFromEnvAndBlankDisablesRestart() throws Exception {
        Files.writeString(directory.resolve(".env"), "BOT_OWNER_ID=123456789012345678\n");
        assertEquals(123456789012345678L, BotConfig.load(directory).ownerId());
        Files.writeString(directory.resolve(".env"), "BOT_OWNER_ID=\n");
        assertEquals(0, BotConfig.load(directory).ownerId());
    }

    @Test void malformedOwnerIdsAreRejectedWithoutEchoingValues() throws Exception {
        for (String value : new String[] {"not-an-id", "123", "-123456789012345678", "99999999999999999999"}) {
            Files.writeString(directory.resolve(".env"), "BOT_OWNER_ID=" + value + "\n");
            var error = assertThrows(IllegalArgumentException.class, () -> BotConfig.load(directory).ownerId());
            assertFalse(error.getMessage().contains(value));
        }
    }

    @Test void loadsLocalValuesIncludingQuotesCommentsAndEquals() throws Exception {
        Files.writeString(directory.resolve(".env"), """
            # Local settings
            BOT_CONFIG_TEST_TOKEN="example.token=value"
            BOT_CONFIG_TEST_PASSWORD="space # and = characters"
            BOT_CONFIG_TEST_OPTION=enabled # comment
            """);
        var config = BotConfig.load(directory);
        assertEquals("example.token=value", config.required("BOT_CONFIG_TEST_TOKEN"));
        assertEquals("space # and = characters", config.required("BOT_CONFIG_TEST_PASSWORD"));
        assertEquals("enabled", config.get("BOT_CONFIG_TEST_OPTION"));
    }

    @Test void missingFileAllowsEnvironmentOnlyStartupAndDefaults() {
        var config = BotConfig.load(directory);
        assertNull(config.get("BOT_CONFIG_TEST_MISSING"));
        assertEquals("fallback", config.get("BOT_CONFIG_TEST_MISSING", "fallback"));
        var error = assertThrows(IllegalArgumentException.class,
            () -> config.required("BOT_CONFIG_TEST_MISSING"));
        assertTrue(error.getMessage().contains(".env"));
        assertTrue(error.getMessage().contains("BOT_CONFIG_TEST_MISSING"));
    }

    @Test void processEnvironmentOverridesFile() throws Exception {
        String key = System.getenv().keySet().stream().filter(name -> name.equalsIgnoreCase("PATH"))
            .findFirst().orElseThrow();
        Files.writeString(directory.resolve(".env"), key + "=file-value-that-must-not-win\n");
        assertEquals(System.getenv(key), BotConfig.load(directory).required(key));
    }

    @Test void blankRequiredValuesAreRejected() throws Exception {
        Files.writeString(directory.resolve(".env"), "BOT_CONFIG_TEST_EMPTY=\nBOT_CONFIG_TEST_BLANK=\"   \"\n");
        var config = BotConfig.load(directory);
        assertThrows(IllegalArgumentException.class, () -> config.required("BOT_CONFIG_TEST_EMPTY"));
        assertThrows(IllegalArgumentException.class, () -> config.required("BOT_CONFIG_TEST_BLANK"));
    }

    @Test void malformedFileDoesNotExposeCredentialsInException() throws Exception {
        Files.writeString(directory.resolve(".env"), "malformed secret-value-without-equals\n");
        var error = assertThrows(IllegalArgumentException.class, () -> BotConfig.load(directory));
        assertFalse(error.getMessage().contains("secret-value"));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }
}
