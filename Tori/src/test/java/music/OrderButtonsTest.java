package music;

import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderButtonsTest {
    @Test void orderPostHasExactlyThreeVisibleStatusButtons() {
        var rows=TicketOrderBot.orderButtons(1042,false);
        assertEquals(1,rows.size());
        List<Button> buttons=rows.getFirst().getComponents().stream().map(component->(Button)component).toList();
        assertEquals(3,buttons.size());
        assertEquals(List.of("♡ noted","💌 processing","❕ done"),buttons.stream().map(Button::getLabel).toList());
        assertTrue(buttons.stream().noneMatch(Button::isDisabled));
    }
    @Test void terminalOrderKeepsTheThreeButtonsVisibleButDisabled() {
        var buttons=TicketOrderBot.orderButtons(1042,true).getFirst().getComponents().stream().map(component->(Button)component).toList();
        assertEquals(3,buttons.size());
        assertTrue(buttons.stream().allMatch(button->button.isDisabled()));
    }
}
