package music;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresCurrencyStoreTest {
    @Test void defaultRandomGeneratorUsesProviderIndependentJdkRandom() {
        var random = PostgresCurrencyStore.defaultRandomGenerator();

        assertEquals(Random.class, random.getClass());
        assertEquals("java.base", random.getClass().getModule().getName());
    }
}
