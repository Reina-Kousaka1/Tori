package music;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class OrderEmbedThemeTest {
    private static TicketOrderStore.Order order(String status) {
        var now = Instant.parse("2026-09-28T12:00:00Z");
        return new TicketOrderStore.Order(42, "123", "456", "Banner", "A custom banner",
            null, status, now, now, null, null, null);
    }

    @Test void statusColorAndMobileFieldLayoutRemainDistinct() {
        assertEquals(ToriEmbeds.NAVY, TicketOrderBot.orderEmbed(order("NOTED"), 2).getColorRaw());
        assertEquals(ToriEmbeds.WARM_GOLD, TicketOrderBot.orderEmbed(order("PROCESSING"), 2).getColorRaw());
        assertEquals(ToriEmbeds.Category.SUCCESS.color(), TicketOrderBot.orderEmbed(order("DONE"), 0).getColorRaw());
        assertEquals(ToriEmbeds.Category.ERROR.color(), TicketOrderBot.orderEmbed(order("CANCELLED"), 0).getColorRaw());
        var embed = TicketOrderBot.orderEmbed(order("NOTED"), 2);
        assertFalse(embed.getFields().getFirst().isInline());
        assertEquals("#2", embed.getFields().stream().filter(field -> field.getName().equals("Queue Position"))
            .findFirst().orElseThrow().getValue());
        assertEquals(10, embed.getFields().size());
    }
}
