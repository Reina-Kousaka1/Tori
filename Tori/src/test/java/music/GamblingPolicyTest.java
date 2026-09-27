package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GamblingPolicyTest {
    @Test void gambleAndSlotsKeepTheirExistingWinThresholdsAndGrossPayouts() {
        assertEquals(300, GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, 100, 0));
        assertEquals(300, GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, 100, 7));
        assertEquals(200, GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, 100, 8));
        assertEquals(200, GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, 100, 44));
        assertEquals(0, GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, 100, 45));
        assertEquals(300, GamblingPolicy.grossWinnings(GamblingPolicy.Game.SLOTS, 100, 0));
        assertEquals(200, GamblingPolicy.grossWinnings(GamblingPolicy.Game.SLOTS, 100, 24));
        assertEquals(0, GamblingPolicy.grossWinnings(GamblingPolicy.Game.SLOTS, 100, 25));
    }

    @Test void rejectsInvalidInputsAndUnrepresentablePayouts() {
        assertThrows(IllegalArgumentException.class, () -> GamblingPolicy.grossWinnings(null, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GamblingPolicy.grossWinnings(GamblingPolicy.Game.SLOTS, 10, 100));
        assertThrows(ArithmeticException.class,
            () -> GamblingPolicy.grossWinnings(GamblingPolicy.Game.GAMBLE, Long.MAX_VALUE / 2, 0));
    }
}
