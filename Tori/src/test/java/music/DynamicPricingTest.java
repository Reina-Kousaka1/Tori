package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicPricingTest {
    @Test void movementUsesEachProductPriceAndRemainsInsideItsBounds() {
        var rules = new DynamicPricing.Rules(500, 350, 650, 0.05);
        assertTrue(DynamicPricing.next(500, rules, () -> 0.99) > 500);
        assertTrue(DynamicPricing.next(500, rules, () -> 0.01) < 500);
        assertEquals(650, DynamicPricing.next(650, rules, () -> 0.999999));
        assertEquals(350, DynamicPricing.next(350, rules, () -> 0.0));
    }

    @Test void rejectsInvalidRulesAndRandomSamples() {
        assertThrows(IllegalArgumentException.class, () -> new DynamicPricing.Rules(0, 1, 2, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new DynamicPricing.Rules(10, 1, 9, 0.1));
        var rules = new DynamicPricing.Rules(10, 5, 15, 0.05);
        assertThrows(IllegalArgumentException.class, () -> DynamicPricing.next(10, rules, () -> 1.0));
        assertThrows(IllegalArgumentException.class, () -> DynamicPricing.next(10, rules, () -> Double.NaN));
    }
}
