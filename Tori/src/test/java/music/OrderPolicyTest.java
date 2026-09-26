package music;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderPolicyTest {
    private static TicketOrderStore.Order order(long id,String status) {
        Instant now=Instant.parse("2026-09-25T12:00:00Z");
        return new TicketOrderStore.Order(id,"guild","customer","design","details",null,status,now,now,null,"channel","message");
    }
    @Test void completedAndCancelledOrdersLeaveQueueAndPositionsRecompute() {
        var active=List.of(order(11,"PROCESSING"),order(14,"NOTED"),order(15,"NOTED"));
        assertEquals(1,OrderPolicy.position(11,active)); assertEquals(2,OrderPolicy.position(14,active)); assertEquals(3,OrderPolicy.position(15,active));
        var afterFinish=List.of(active.get(1),active.get(2));
        assertEquals(0,OrderPolicy.position(11,afterFinish)); assertEquals(1,OrderPolicy.position(14,afterFinish));
        assertFalse(OrderPolicy.active("DONE")); assertFalse(OrderPolicy.active("CANCELLED"));
    }
    @Test void processingPolicyAllowsOneOrManyBasedOnGuildMode() {
        var active=List.of(order(1,"PROCESSING"),order(2,"NOTED"));
        assertFalse(OrderPolicy.mayProcess("SEQUENTIAL",active,2));
        assertTrue(OrderPolicy.mayProcess("SEQUENTIAL",active,1));
        assertTrue(OrderPolicy.mayProcess("PARALLEL",active,2));
    }
    @Test void terminalOrdersCannotBeReopenedAndOnlySupportedStatesAreAccepted() {
        assertFalse(OrderPolicy.validTransition("DONE","NOTED"));
        assertFalse(OrderPolicy.validTransition("CANCELLED","PROCESSING"));
        assertFalse(OrderPolicy.validTransition("NOTED","UNKNOWN"));
        assertFalse(OrderPolicy.validTransition("NOTED","NOTED"));
        assertTrue(OrderPolicy.validTransition("PROCESSING","DONE"));
        assertTrue(OrderPolicy.validTransition("NOTED","CANCELLED"));
    }
}
