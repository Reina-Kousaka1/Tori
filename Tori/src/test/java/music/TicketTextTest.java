package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TicketTextTest {
    @Test void legacyConfigUsesExistingCopy() {
        var old = new TicketOrderStore.Config("guild", null, null, null, null, "SEQUENTIAL", null,
            null, null, null, null, null, null, null);
        var text = TicketText.from(old);
        assertEquals("🎫 TORI SUPPORT\n\nNeed assistance? Open a private ticket below.", text.panelMessage());
        assertEquals("Open Ticket", text.buttonLabel());
        assertEquals("Claim", text.claimLabel());
        assertEquals("Close", text.closeLabel());
        assertEquals("Delete", text.deleteLabel());
        assertEquals("Ticket #12 · SUPPORT · opened by <@123>\nUse `/ticket close` when your request is resolved.",
            text.welcomeMessage(12, "SUPPORT", "123"));
    }

    @Test void customCopyReplacesOnlySupportedPlaceholders() {
        var text = new TicketText("Help", "Ask here", "Create request", "Hi {user}, ticket #{ticket} is for {category}.",
            "Take it", "Finish", "Remove");
        assertEquals("Help\n\nAsk here", text.panelMessage());
        assertEquals("Hi <@123>, ticket #7 is for REPORTS.", text.welcomeMessage(7, "REPORTS", "123"));
    }

    @Test void rejectsBlankAndOversizedInputs() {
        assertThrows(IllegalArgumentException.class, () -> TicketText.validate("   ", "Panel text", 1500));
        assertThrows(IllegalArgumentException.class, () -> TicketText.validate("x".repeat(81), "Open button", 80));
        assertThrows(IllegalArgumentException.class, () -> TicketText.validate("Two\nlines", "Open button", 80));
        assertEquals("Hello", TicketText.validate(" Hello ", "Panel title", 100));
    }
}
