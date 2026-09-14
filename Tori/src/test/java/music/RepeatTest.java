package music;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RepeatTest {
    @Test void naturalEndRepeatsButSkipAndFailureAdvance() {
        var queue = new TrackQueue<String>();
        queue.add(List.of("one", "two"));
        queue.advance(); queue.repeat(true);
        assertEquals("one", queue.advance(true));
        assertEquals(List.of("two"), queue.snapshot());
        assertEquals("two", queue.advance(false));
        queue.repeat(false);
        assertNull(queue.advance(true));
    }
    @Test void stopResetsRepeat() {
        var queue = new TrackQueue<String>();
        queue.repeat(true); queue.clear();
        assertFalse(queue.repeating());
        assertNull(queue.advance(true));
    }
}
