package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketOrderStoreNumericTest {
    @Test void ticketAndOrderIdsRemainLongPrecision() {
        long id = 9_007_199_254_741_093L;
        var order = new TicketOrderStore.Order(id, "guild", "customer", "product", "description", null,
            "NOTED", java.time.Instant.EPOCH, java.time.Instant.EPOCH, null, null, null);
        assertEquals(id, order.id());
    }
}
