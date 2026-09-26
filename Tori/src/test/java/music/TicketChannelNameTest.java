package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketChannelNameTest {
    @Test void channelNameUsesZeroPaddedPersistentTicketId() {
        assertEquals("ticket-001",TicketOrderBot.ticketChannelName(1));
        assertEquals("ticket-002",TicketOrderBot.ticketChannelName(2));
        assertEquals("ticket-999",TicketOrderBot.ticketChannelName(999));
        assertEquals("ticket-1000",TicketOrderBot.ticketChannelName(1000));
    }
}
