package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToriEmbedsTest {
    @Test void mainBrandAndSemanticColorsAreDistinct() {
        assertEquals(0x1D1D23, ToriEmbeds.NAVY);
        assertEquals(0x2C303C, ToriEmbeds.SECONDARY_DARK);
        assertEquals(0xD79A19, ToriEmbeds.Category.MUSIC.color());
        assertEquals(0x1D1D23, ToriEmbeds.Category.DEFAULT.color());
        assertEquals(0xD79A19, ToriEmbeds.Category.INFO.color());
        assertEquals(0x1D1D23, ToriEmbeds.Category.STATS.color());
        assertEquals(0x57A773, ToriEmbeds.Category.SUCCESS.color());
        assertEquals(0xE6AD2E, ToriEmbeds.Category.WARNING.color());
        assertEquals(0xD9534F, ToriEmbeds.Category.ERROR.color());
    }
    @Test void localizedFooterKeepsAttributionAndBuildersStayIndependent() {
        for (var language : Language.values()) {
            var embed = ToriEmbeds.create(ToriEmbeds.Category.MUSIC, language).setTitle("Song").build();
            assertEquals("Tori", embed.getAuthor().getName());
            assertEquals(Messages.text(language, "stats.love"), embed.getFooter().getText());
            assertEquals("LRCLIB · " + Messages.text(language, "stats.love"), ToriEmbeds.footer(language, "LRCLIB"));
            assertNull(embed.getTimestamp());
            assertNull(ToriEmbeds.create(ToriEmbeds.Category.INFO, language).build().getTitle());
            assertEquals(ToriEmbeds.GOLD, GeneralBot.helpEmbed(language).getColorRaw());
        }
    }
    @Test void textEmbedsRespectDiscordDescriptionLimitAndSurrogates() {
        var body = "a".repeat(4094) + "🌸" + "b".repeat(100);
        var embed = ToriEmbeds.text(ToriEmbeds.Category.DEFAULT, Language.EN, "Title", body).build();
        assertTrue(embed.getDescription().length() <= 4096);
        assertTrue(embed.getDescription().endsWith("…"));
        assertFalse(Character.isHighSurrogate(embed.getDescription().charAt(embed.getDescription().length() - 2)));
    }
}
