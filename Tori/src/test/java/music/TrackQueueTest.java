package music;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrackQueueTest {
    @Test void preservesOrderAndResetsCurrent() {
        var queue = new TrackQueue<String>();
        queue.add(List.of("a", "b"));
        assertEquals("a", queue.advance());
        assertEquals(List.of("b"), queue.snapshot());
        assertEquals("b", queue.advance());
        assertNull(queue.advance());
        assertNull(queue.current());
    }
    @Test void rejectsOversizedPlaylistsAtomically() {
        var queue = new TrackQueue<String>(2);
        queue.add(List.of("a"));
        assertThrows(IllegalArgumentException.class, () -> queue.add(List.of("b", "c")));
        assertEquals(List.of("a"), queue.snapshot());
    }
    @Test void stopRemovesCurrentAndPending() {
        var queue = new TrackQueue<String>();
        queue.add(List.of("a", "b")); queue.advance(); queue.clear();
        assertNull(queue.current()); assertNull(queue.advance());
    }
}
