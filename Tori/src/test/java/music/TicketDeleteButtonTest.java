package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TicketDeleteButtonTest {
    @Test void onlyClosedTicketsCanBeDeleted() {
        assertTrue(TicketOrderBot.canDeleteTicket("CLOSED"));
        assertFalse(TicketOrderBot.canDeleteTicket("OPEN"));
        assertFalse(TicketOrderBot.canDeleteTicket("DELETED"));
        assertFalse(TicketOrderBot.canDeleteTicket(null));
    }
}
