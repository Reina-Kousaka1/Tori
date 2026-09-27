package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShopCatalogTest {
    @Test void publicCatalogContainsTheCurrentMiningAndFishingGearAndNoLegacyBrandItems() {
        assertEquals(65, ShopCatalog.find("fishing_rod").price());
        assertEquals("pickaxe", ShopCatalog.find("brom_pickaxe").toolSlot());
        assertEquals(15, ShopCatalog.find("fishing_bait").price());
        assertNotNull(ShopCatalog.find("hellfire_pickaxe"));
        assertNotNull(ShopCatalog.find("shark"));
        assertTrue(ShopCatalog.market().stream().noneMatch(item -> item.name().toLowerCase().contains("cheer")
            || item.name().toLowerCase().contains("sephora")));
    }

    @Test void existingRecipeRequirementsArePreserved() {
        assertEquals(2, ShopCatalog.recipe("comet_rod").ingredients().get("wood"));
        assertEquals(20, ShopCatalog.recipe("hellfire_pickaxe").ingredients().get("diamond"));
    }
}
