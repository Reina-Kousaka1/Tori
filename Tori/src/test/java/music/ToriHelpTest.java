package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToriHelpTest {
    @Test void everyRegisteredCommandHasOneCategory() {
        var entries = ToriHelp.entries();
        var names = ToriHelp.registeredNames();
        assertTrue(ToriHelp.unmappedNames().isEmpty());
        assertEquals(names.size(), entries.values().stream().mapToInt(java.util.List::size).sum());
        assertEquals(names, entries.values().stream().flatMap(java.util.List::stream)
            .collect(java.util.stream.Collectors.toSet()));
        assertTrue(entries.get(ToriHelp.Section.SOCIAL).isEmpty());
    }

    @Test void emptyCategoryAndAllTranslationsFitDiscord() {
        for (var language : Language.values()) {
            var overview = ToriHelp.overview(language);
            assertTrue(overview.getFields().size() <= 25);
            assertTrue(overview.getDescription().length() <= 4096);
            for (var section : ToriHelp.Section.values()) {
                var embed = ToriHelp.category(language, section);
                assertTrue(embed.getDescription().length() <= 4096);
                assertTrue(embed.getTitle().length() <= 256);
            }
            assertEquals(Messages.text(language, "help.v2.empty"),
                ToriHelp.category(language, ToriHelp.Section.SOCIAL).getDescription());
            assertEquals(1, ToriHelp.menu("123456789012345678", language).size());
        }
    }
}
