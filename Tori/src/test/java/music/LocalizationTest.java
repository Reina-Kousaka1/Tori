package music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {
    @ParameterizedTest @EnumSource(Language.class)
    void everyLanguageHasAllKeysAndMatchingPlaceholders(Language language) {
        assertEquals(Messages.keys(Language.EN), Messages.keys(language));
        for (String key : Messages.keys(Language.EN)) {
            long expected = placeholders(Messages.template(Language.EN, key));
            assertEquals(expected, placeholders(Messages.template(language, key)), key);
            Object[] args = new Object[(int) expected]; Arrays.fill(args, "7");
            assertFalse(Messages.text(language, key, args).isBlank(), key);
        }
    }
    private static long placeholders(String template) {
        return java.util.regex.Pattern.compile("(?<!%)%s").matcher(template).results().count();
    }
    @ParameterizedTest @EnumSource(Language.class)
    void helpContainsEveryCommandAndFitsOneMessage(Language language) {
        var commands = new ArrayList<>(MusicBot.commands());
        commands.addAll(ModerationBot.commands()); commands.addAll(GeneralBot.commands());
        String help = GeneralBot.help(language);
        assertTrue(help.length() <= 1950);
        for (var command : commands) assertTrue(help.contains("/" + command.getName()), command.getName());
        assertFalse(help.contains(language.label));
        var embed = GeneralBot.helpEmbed(language);
        assertEquals(4, embed.getFields().size());
        for (var field : embed.getFields()) {
            assertFalse(field.isInline());
            assertTrue(field.getValue().length() <= 1024);
        }
        assertTrue(embed.getFields().get(2).getValue().contains("/avatar user_id:"));
        assertTrue(embed.getFields().get(3).getValue().contains("/status action:stop"));
    }
    @Test void allCommandAndOptionDescriptionsAreLocalized() throws Exception {
        var commands = new ArrayList<>(MusicBot.commands());
        commands.addAll(ModerationBot.commands()); commands.addAll(GeneralBot.commands());
        for (var command : commands) {
            var json = new ObjectMapper().readTree(command.toData().toString());
            for (var language : Language.values()) {
                String code = language == Language.EN ? "en-US" : language.code;
                assertEquals(Messages.text(language, "cmd." + command.getName()), json.path("description_localizations").path(code).asText());
                for (var option : json.path("options"))
                    assertEquals(Messages.text(language, "opt." + option.path("name").asText()), option.path("description_localizations").path(code).asText());
            }
        }
    }
    @ParameterizedTest @EnumSource(Language.class)
    void webhooksUseTheCapturedGuildLanguage(Language language) throws Exception {
        var entry = new WebhookModLogger.Entry("1", "ban", "Guild", "2", "3", "Mod", "4", "User 5",
            Messages.text(language, "mod.no.reason"), Messages.text(language, "mod.done", "ban", "5"), Instant.now(), language);
        var json = new ObjectMapper().readTree(WebhookModLogger.payload(entry));
        var embed = json.path("embeds").get(0);
        assertEquals(Messages.text(language, "log.reason"), embed.path("fields").get(4).path("name").asText());
        assertEquals(Messages.text(language, "mod.no.reason"), embed.path("fields").get(4).path("value").asText());
        assertEquals(ToriEmbeds.footer(language, Messages.text(language, "log.case", "1")), embed.path("footer").path("text").asText());
    }
    @Test void validationErrorsTranslateWithoutChangingPolicy() {
        var error = assertThrows(UserError.class, () -> MusicInput.resolve("spotify:track:bad", false));
        assertEquals("Invalid Spotify URI.", error.localized(Language.EN));
        assertEquals("Ongeldige Spotify-URI.", error.localized(Language.NL));
        var policyError = assertThrows(UserError.class, () -> ModerationPolicy.target(1, 2, 3, true, false, true, true, false));
        assertEquals("The server owner is protected.", policyError.localized(Language.EN));
    }
    @Test void localeFallbackAndPercentFormattingWork() {
        assertEquals(Language.EN, Language.discordLocale("en-GB", Language.DE));
        assertEquals(Language.NL, Language.discordLocale("nl", Language.DE));
        assertEquals(Language.DE, Language.discordLocale("zz", Language.DE));
        assertEquals("Volume: 80 %", Messages.text(Language.NL, "music.volume", 80));
    }
}
