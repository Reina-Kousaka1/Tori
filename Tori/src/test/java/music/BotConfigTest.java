package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {
    @TempDir Path directory;

    @Test void rawDiscordTokenProvidesBotIdAndMalformedValueIsNotEchoed() {
        assertEquals(123456789012345678L,
            BotConfig.botIdFromToken("MTIzNDU2Nzg5MDEyMzQ1Njc4.timestamp.signature"));

        String secret = "malformed-token-secret";
        var error = assertThrows(BotConfig.ConfigurationException.class, () -> BotConfig.botIdFromToken(secret));
        assertTrue(error.getMessage().contains("DISCORD_TOKEN"));
        assertFalse(error.getMessage().contains(secret));
    }

    @Test void defaultLanguageValidationHasAnActionableSafeMessage() {
        var error = assertThrows(BotConfig.ConfigurationException.class,
            () -> BotConfig.validateDefaultLanguage("secret-language-value"));
        assertTrue(error.getMessage().contains("BOT_DEFAULT_LANGUAGE"));
        assertTrue(error.getMessage().contains("de, en, nl"));
        assertFalse(error.getMessage().contains("secret-language-value"));
    }

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

    @Test void economyV2BalanceIsOptInAndRestrictedToLocalHttp() throws Exception {
        Files.writeString(directory.resolve(".env"), ""
            + "TORI_ECONOMY_BALANCE_SOURCE=LEGACY\n");
        assertNull(BotConfig.load(directory).economyV2BalanceClient());

        Files.writeString(directory.resolve(".env"), ""
            + "TORI_ECONOMY_BALANCE_SOURCE=ELIXIR\n"
            + "TORI_ECONOMY_URL=http://127.0.0.1:4001\n"
            + "TORI_ECONOMY_API_SECRET=local-test-secret-with-at-least-32-characters\n");
        assertNotNull(BotConfig.load(directory).economyV2BalanceClient());

        Files.writeString(directory.resolve(".env"), ""
            + "TORI_ECONOMY_BALANCE_SOURCE=ELIXIR\n"
            + "TORI_ECONOMY_URL=https://example.invalid\n"
            + "TORI_ECONOMY_API_SECRET=local-test-secret-with-at-least-32-characters\n");
        assertThrows(BotConfig.ConfigurationException.class,
            () -> BotConfig.load(directory).economyV2BalanceClient());
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

    @Test void startupDiagnosticIncludesFullCauseAndFramesButRedactsConfiguredSecrets() throws Exception {
        String secret = "very-secret-token-value";
        Files.writeString(directory.resolve(".env"), "DISCORD_TOKEN=" + secret + "\n");
        var cause = new IllegalArgumentException("Invalid credential " + secret);
        var failure = new IllegalStateException("Startup failed", cause);

        String trace = Main.diagnosticStackTrace(failure, BotConfig.load(directory));

        assertTrue(trace.contains("IllegalStateException: Startup failed"));
        assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException: Invalid credential [REDACTED:DISCORD_TOKEN]"));
        assertTrue(trace.contains("at music.BotConfigTest."));
        assertFalse(trace.contains(secret));
    }

    @Test void unexpectedCurrencyStoreFailureLogsSanitizedMessageAndFullCauseChain() throws Exception {
        String password = "test-only-postgres-password";
        String token = "test-only-discord-token";
        Files.writeString(directory.resolve(".env"), "TORI_DATABASE_PASSWORD=" + password
            + "\nDISCORD_TOKEN=" + token + "\n");
        var root = new java.sql.SQLException("Connection rejected " + password);
        var intermediate = new IllegalStateException("Market seed failed " + token, root);
        var failure = new CurrencyStoreException(intermediate);

        String diagnostic = Main.unexpectedStartupDiagnostic(failure, BotConfig.load(directory));

        assertTrue(diagnostic.contains("Bot startup or execution failed (CurrencyStoreException)"));
        assertTrue(diagnostic.contains("music.CurrencyStoreException: Economy storage operation failed."));
        assertTrue(diagnostic.contains("Caused by: java.lang.IllegalStateException: Market seed failed [REDACTED:DISCORD_TOKEN]"));
        assertTrue(diagnostic.contains("Caused by: java.sql.SQLException: Connection rejected [REDACTED:TORI_DATABASE_PASSWORD]"));
        assertTrue(diagnostic.contains("at music.BotConfigTest."));
        assertFalse(diagnostic.contains(password));
        assertFalse(diagnostic.contains(token));
        assertEquals("Economy storage operation failed.", failure.getMessage());
    }
}
