package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentMethodPolicyTest {
    @Test void keepsArbitraryMethodLabelsWithoutTreatingThemAsPayments() {
        assertEquals("Cash App", PaymentMethodPolicy.normalize("  Cash   App  "));
        assertEquals("Robux", PaymentMethodPolicy.normalize("Robux"));
        assertEquals("PayPal", PaymentMethodPolicy.normalize("PayPal"));
        assertEquals("", PaymentMethodPolicy.normalize(null));
        assertEquals("", PaymentMethodPolicy.normalize("  \n "));
    }

    @Test void rejectsMethodLabelsLongerThanTheDiscordOptionLimit() {
        assertThrows(IllegalArgumentException.class,
            () -> PaymentMethodPolicy.normalize("x".repeat(PaymentMethodPolicy.MAX_LENGTH + 1)));
    }
}
