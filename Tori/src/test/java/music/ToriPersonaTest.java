package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToriPersonaTest {
    @Test void pickMeCopyIsLimitedToPlayfulContextsAndDoesNotChangeFacts() {
        var persona = new ToriPersona(new ToriPersonaConfig(true, 100));
        String shop = "Fishing Rod - 65 credits";
        assertEquals(shop + "\n\nObviously I have the cutest taste and the cleanest serve form. Can we take a second to acknowledge how talented I am?",
            persona.decorate(shop, ToriPersona.Context.SHOP_BROWSE, Language.EN, 0));
        assertEquals("Transaction failed.", persona.decorate("Transaction failed.", ToriPersona.Context.TRANSACTION, Language.EN, 0));
        assertEquals("Permission denied.", persona.decorate("Permission denied.", ToriPersona.Context.PERMISSION, Language.EN, 0));
    }

    @Test void disabledAndZeroIntensityUsePlainFallbackAndStatusCombinesThemes() {
        var persona = new ToriPersona(new ToriPersonaConfig(false, 100));
        assertEquals("Shop", persona.decorate("Shop", ToriPersona.Context.SHOP_BROWSE, Language.DE, 0));
        assertEquals(java.util.List.of("besties", "training"), persona.statuses());
        assertTrue(persona.statusActivity("training", 1, 2).getName().contains("training"));
        var themed = new ToriPersona(new ToriPersonaConfig(true, 100));
        assertTrue(themed.statuses().containsAll(java.util.List.of("ballet", "volleyball")));
        assertTrue(themed.statusActivity("training", 1, 2).getName().contains("Ballet"));
        assertTrue(themed.statusActivity("training", 1, 2).getName().contains("volleyball"));
        assertEquals(100, ToriPersonaConfig.parseIntensity("101"));
        assertEquals(0, ToriPersonaConfig.parseIntensity("-1"));
        assertEquals(72, ToriPersonaConfig.parseIntensity("bad"));
    }
}
