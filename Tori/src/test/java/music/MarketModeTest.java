package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketModeTest {
    @Test void defaultsToLegacyAndRejectsUnimplementedV2() {
        assertEquals(MarketMode.LEGACY, MarketMode.parse(null));
        assertEquals(MarketMode.LEGACY, MarketMode.parse("legacy"));
        assertEquals(MarketMode.READ_ONLY, MarketMode.parse(" read_only "));
        assertThrows(IllegalArgumentException.class, () -> MarketMode.parse("V2"));
        assertThrows(IllegalArgumentException.class, () -> MarketMode.parse("invalid"));
    }

    @Test void onlyLegacyCanWrite() {
        assertDoesNotThrow(MarketMode.LEGACY::requireWritable);
        assertThrows(IllegalStateException.class, MarketMode.READ_ONLY::requireWritable);
        assertThrows(IllegalStateException.class, MarketMode.V2::requireWritable);
    }
}
