package music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TicketOrderErrorHandlingTest {
    @Test void ticketCommandOutsideTicketChannelReturnsFriendlyMessageWithoutThrowing() {
        assertEquals("This command must be used inside a ticket channel.", TicketOrderBot.ticketContextError(null));
    }

    @Test void permissionFailureReturnsItsConcreteUserFacingMessage() {
        String permissionMessage="This action requires Manage Server, Administrator, or the configured order staff role.";

        assertEquals(permissionMessage,TicketOrderBot.actionErrorMessage(new IllegalArgumentException(permissionMessage)));
    }

    @Test void unexpectedFailureReturnsGenericMessage() {
        assertEquals("The action could not be completed. If this persists, contact a server administrator.",
            TicketOrderBot.actionErrorMessage(new IllegalStateException("internal details")));
    }
}
