package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiningFishingPolicyTest {
    @Test void preservesTierDependentFishingAndMiningDropThresholds() {
        assertEquals("fish", MiningFishingPolicy.drop("fish", 1, 99));
        assertEquals("shark", MiningFishingPolicy.drop("fish", 5, 0));
        assertEquals("sparkle_fragment", MiningFishingPolicy.drop("mine", 8, 0));
        assertEquals("mine_crate", MiningFishingPolicy.drop("mine", 1, 8));
        assertEquals("rock", MiningFishingPolicy.drop("mine", 1, 99));
    }

    @Test void boundsToolTierAndRejectsInvalidInputs() {
        assertEquals(1, MiningFishingPolicy.tier(40));
        assertEquals(8, MiningFishingPolicy.tier(9999));
        assertThrows(IllegalArgumentException.class, () -> MiningFishingPolicy.drop("fish", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> MiningFishingPolicy.drop("unknown", 1, 1));
    }
}
