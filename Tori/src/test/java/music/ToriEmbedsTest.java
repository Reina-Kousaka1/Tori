package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToriEmbedsTest {
    @Test void domainAndSemanticColorsAreDistinct() {
        for (var category : new ToriEmbeds.Category[] {ToriEmbeds.Category.GENERAL,
                ToriEmbeds.Category.ECONOMY, ToriEmbeds.Category.SHOP,
                ToriEmbeds.Category.PROFILE, ToriEmbeds.Category.MARRIAGE})
            assertEquals(ToriEmbeds.SOFT_LAVENDER, category.color());
        assertEquals(ToriEmbeds.BALLET_PINK, ToriEmbeds.Category.BALLET.color());
        assertEquals(ToriEmbeds.VOLLEYBALL_NAVY, ToriEmbeds.Category.VOLLEYBALL.color());
        assertEquals(ToriEmbeds.VOLLEYBALL_GOLD, ToriEmbeds.Category.VOLLEYBALL.accentColor());
        assertEquals(ToriEmbeds.CHEER_NAVY, ToriEmbeds.Category.CHEER.color());
        assertEquals(ToriEmbeds.CHEER_DUSTY_LAVENDER, ToriEmbeds.Category.CHEER.accentColor());
        assertNotEquals(ToriEmbeds.VOLLEYBALL_GOLD, ToriEmbeds.Category.GENERAL.color());
        assertNotEquals(ToriEmbeds.CHEER_DUSTY_LAVENDER, ToriEmbeds.Category.SHOP.color());
        assertEquals(0x57A773, ToriEmbeds.Category.SUCCESS.color());
        assertEquals(0xE6AD2E, ToriEmbeds.Category.WARNING.color());
        assertEquals(0xD9534F, ToriEmbeds.Category.ERROR.color());
    }
    @Test void localizedFooterKeepsAttributionAndBuildersStayIndependent() {
        for (var language : Language.values()) {
            var embed = ToriEmbeds.create(ToriEmbeds.Category.GENERAL, language).setTitle("Song").build();
            assertEquals("Tori", embed.getAuthor().getName());
            assertEquals(Messages.text(language, "stats.love"), embed.getFooter().getText());
            assertEquals("LRCLIB · " + Messages.text(language, "stats.love"), ToriEmbeds.footer(language, "LRCLIB"));
            assertNull(embed.getTimestamp());
            assertNull(ToriEmbeds.create(ToriEmbeds.Category.SHOP, language).build().getTitle());
            assertEquals(ToriEmbeds.SOFT_LAVENDER, GeneralBot.helpEmbed(language).getColorRaw());
            for (var category : new ToriEmbeds.Category[] {ToriEmbeds.Category.BALLET,
                    ToriEmbeds.Category.VOLLEYBALL, ToriEmbeds.Category.CHEER}) {
                var themed = ToriEmbeds.create(category, language).build();
                assertEquals(category.header(), themed.getAuthor().getName());
                assertNull(themed.getImage());
            }
        }
    }
    @Test void textEmbedsRespectDiscordDescriptionLimitAndSurrogates() {
        var body = "a".repeat(4094) + "🌸" + "b".repeat(100);
        var embed = ToriEmbeds.text(ToriEmbeds.Category.GENERAL, Language.EN, "Title", body).build();
        assertTrue(embed.getDescription().length() <= 4096);
        assertTrue(embed.getDescription().endsWith("…"));
        assertFalse(Character.isHighSurrogate(embed.getDescription().charAt(embed.getDescription().length() - 2)));
    }
}
