package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToriEmbedsTest {
    @Test void mainBrandAndSemanticColorsAreDistinct() {
        assertEquals(0xE69293, ToriEmbeds.Category.MUSIC.color());
        assertEquals(0xEBC597, ToriEmbeds.Category.INFO.color());
        assertEquals(0xA87AA2, ToriEmbeds.Category.STATS.color());
        assertEquals(0x57A773, ToriEmbeds.Category.SUCCESS.color());
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
            assertEquals(0xEBC597, GeneralBot.helpEmbed(language).getColorRaw());
        }
    }
}
