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
    void helpOverviewIsCompactAndEachCategoryContainsItsRegisteredCommands(Language language) {
        var commands = new ArrayList<>(MusicBot.commands());
        commands.addAll(ModerationBot.commands()); commands.addAll(GeneralBot.commands()); commands.addAll(TicketOrderBot.commands());
        String help = GeneralBot.help(language);
        assertTrue(help.length() <= 1950);
        assertTrue(help.contains(Messages.text(language, "help.v2.select")) || help.contains(Messages.text(language, "help.v2.intro")));
        for (var command : commands) {
            boolean found = false;
            for (var section : ToriHelp.Section.values())
                found |= ToriHelp.category(language, section).getDescription().contains("/" + command.getName() + "`");
            assertTrue(found, command.getName());
        }
        assertFalse(help.contains(language.label));
        var embed = GeneralBot.helpEmbed(language);
        assertEquals(ToriHelp.Section.values().length, embed.getFields().size());
        for (var field : embed.getFields()) {
            assertFalse(field.isInline());
            assertTrue(field.getValue().length() <= 1024);
        }
    }
    @Test void allCommandAndOptionDescriptionsAreLocalized() throws Exception {
        var commands = new ArrayList<>(MusicBot.commands());
        commands.addAll(ModerationBot.commands()); commands.addAll(GeneralBot.commands()); commands.addAll(TicketOrderBot.commands());
        for (var command : commands) {
            var json = new ObjectMapper().readTree(command.toData().toString());
            for (var language : Language.values()) {
                String code = language == Language.EN ? "en-US" : language.code;
                assertEquals(Messages.text(language, "cmd." + command.getName()), json.path("description_localizations").path(code).asText());
                for (var option : json.path("options")) {
                    String name = option.path("name").asText();
                    String key = command.getName().equals("market") && option.path("type").asInt() == 1
                        ? "market.subcommand." + name : LocalizedCommands.optionKey(command.getName(), name);
                    assertEquals(Messages.text(language, key), option.path("description_localizations").path(code).asText(), command.getName()+"/"+name+"/"+code);
                }
                for (var subcommand : json.path("options")) for (var nested : subcommand.path("options"))
                    assertEquals(Messages.text(language,LocalizedCommands.optionKey(command.getName(), nested.path("name").asText())),nested.path("description_localizations").path(code).asText(),command.getName()+"/"+subcommand.path("name").asText()+"/"+nested.path("name").asText()+"/"+code);
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
