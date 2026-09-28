package music;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToriShopUiTest {
    @Test void pageBoundsAndEmptyResultsAreStable() {
        assertEquals(new ToriShopUi.Page(0, 1, 0, 0), ToriShopUi.page(0, 99));
        assertEquals(new ToriShopUi.Page(0, 3, 0, 5), ToriShopUi.page(13, -5));
        assertEquals(new ToriShopUi.Page(2, 3, 10, 13), ToriShopUi.page(13, 99));
        assertEquals(429_496_730, ToriShopUi.page(Integer.MAX_VALUE, Integer.MAX_VALUE).count());
        assertThrows(IllegalArgumentException.class, () -> ToriShopUi.page(-1, 0));
        var empty = ToriShopUi.render(Language.EN, List.of(), ToriShopUi.page(0, 0), "all");
        assertEquals(1, empty.getFields().size());
        assertFalse(empty.getFields().getFirst().isInline());
    }

    @Test void controlsDisablePreviousAndNextAtEdges() {
        var first = ToriShopUi.controls("123456789012345678", "all", ToriShopUi.page(11, 0), Language.EN)
            .getFirst().getButtons();
        assertTrue(first.getFirst().isDisabled());
        assertFalse(first.getLast().isDisabled());
        assertTrue(first.get(1).isDisabled());
        var last = ToriShopUi.controls("123456789012345678", "all", ToriShopUi.page(11, 2), Language.EN)
            .getFirst().getButtons();
        assertFalse(last.getFirst().isDisabled());
        assertTrue(last.getLast().isDisabled());
        assertTrue(ToriShopUi.controls("123456789012345678", "x".repeat(64),
            ToriShopUi.page(50_000, 9_998), Language.EN).getFirst().getButtons().stream()
            .allMatch(button -> button.getCustomId().length() <= 100));
    }

    @Test void longNamesAndPricesFitEmbedFields() {
        var page = ToriShopUi.page(1, 0);
        var embed = ToriShopUi.render(Language.EN,
            List.of(new ToriShopUi.Entry("id", "N".repeat(400), "P".repeat(1500))), page, "all");
        assertTrue(embed.getFields().getFirst().getName().length() <= 256);
        assertTrue(embed.getFields().getFirst().getValue().length() <= 1024);
    }
}
