package music;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StatusRotationTest {
    private static final long GAP = StatusRotation.MIN_INTERVAL_MS;
    private static final long INTERVAL = GAP + GAP / 2;
    @Test void rotatesInOrderAndWrapsAtTheConfiguredInterval() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            rotation.start(List.of("First", "Second", "Third"),  INTERVAL, sent::add);
            assertEquals(List.of("First"), sent);
            timer.advance(INTERVAL - 1);
            assertEquals(List.of("First"), sent);
            timer.advance(1);
            timer.advance(INTERVAL);
            timer.advance(INTERVAL);
            assertEquals(List.of("First", "Second", "Third", "First"), sent);
        }
    }

    @Test void replacementCancelsTheOldRotationAndStartsItsIntervalWhenSent() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            rotation.start(List.of("Old 1", "Old 2"), INTERVAL, sent::add);
            var staleTask = timer.tasks.peek();
            timer.advance(GAP / 2);
            rotation.start(List.of("New 1", "New 2"), 2 * GAP, sent::add);
            assertTrue(staleTask.cancelled);
            timer.advance(GAP / 2);
            assertEquals(List.of("Old 1", "New 1"), sent);
            staleTask.runnable.run(); // Already queued callbacks must also respect cancellation.
            timer.advance(2 * GAP - 1);
            assertEquals(List.of("Old 1", "New 1"), sent);
            timer.advance(1);
            assertEquals(List.of("Old 1", "New 1", "New 2"), sent);
        }
    }

    @Test void stopClearsPresenceAfterPacingAndSendsNoFurtherUpdates() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            rotation.start(List.of("A", "B"), INTERVAL, sent::add);
            var before = rotation.snapshot();
            timer.advance(3 * GAP / 10);
            rotation.stop(sent::add);
            assertFalse(rotation.snapshot().running());
            assertEquals(List.of(), rotation.snapshot().texts());
            assertEquals(List.of("A", "B"), before.texts());
            assertEquals(INTERVAL, before.intervalMs());
            assertTrue(before.running());
            assertThrows(UnsupportedOperationException.class, () -> before.texts().add("C"));
            timer.advance(7 * GAP / 10 - 1);
            assertEquals(List.of("A"), sent);
            timer.advance(1);
            assertEquals(Arrays.asList("A", null), sent);
            timer.advance(100 * GAP);
            assertEquals(Arrays.asList("A", null), sent);
        }
    }

    @Test void rapidControlsCoalesceAndAllUpdatesKeepTheMinimumGap() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            var times = new ArrayList<Long>();
            Consumer<String> update = text -> { sent.add(text); times.add(timer.now); };
            rotation.start(List.of("A1", "A2"), GAP, update);
            timer.advance(GAP / 10); rotation.stop(update);
            timer.advance(GAP / 10); rotation.start(List.of("B1", "B2"), GAP, update);
            timer.advance(GAP / 10); rotation.stop(update);
            timer.advance(GAP / 10); rotation.start(List.of("C1", "C2"), GAP, update);
            timer.advance(16 * GAP / 10);
            assertEquals(List.of("A1", "C1", "C2"), sent);
            timer.advance(GAP / 10); rotation.stop(update);
            timer.advance(GAP / 10); rotation.stop(update);
            timer.advance(GAP / 10); rotation.start(List.of("D1", "D2"), GAP, update);
            timer.advance(GAP / 10); rotation.stop(update);
            timer.advance(6 * GAP / 10);
            assertEquals(Arrays.asList("A1", "C1", "C2", null), sent);
            assertEquals(List.of(0L, nanos(GAP), nanos(2 * GAP), nanos(3 * GAP)), times);
            timer.advance(100 * GAP);
            assertEquals(4, sent.size());
        }
    }

    @Test void repeatedStopIsAlsoPacedWhenNoRotationWasRunning() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            rotation.stop(sent::add);
            timer.advance(GAP / 10); rotation.stop(sent::add);
            timer.advance(GAP / 10); rotation.stop(sent::add);
            timer.advance(8 * GAP / 10 - 1);
            assertEquals(1, sent.size());
            timer.advance(1);
            assertEquals(Arrays.asList(null, null), sent);
        }
    }

    @Test void closeCancelsPendingWorkWithoutClearingPresence() {
        var timer = new FakeScheduler();
        var rotation = timer.rotation();
        var sent = new ArrayList<String>();
        rotation.start(List.of("A", "B"), GAP, sent::add);
        var staleTask = timer.tasks.peek();
        rotation.close();
        rotation.close();
        assertTrue(timer.closed);
        assertTrue(staleTask.cancelled);
        staleTask.runnable.run();
        timer.advance(6 * GAP);
        assertEquals(List.of("A"), sent);
        assertFalse(rotation.snapshot().running());
        assertThrows(IllegalStateException.class, () -> rotation.start(List.of("C", "D"), GAP, sent::add));
        assertThrows(IllegalStateException.class, () -> rotation.stop(sent::add));
    }

    @Test void failedAttemptsArePacedAndDoNotKillFutureUpdates() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var attempted = new ArrayList<String>();
            Consumer<String> update = text -> {
                attempted.add(text);
                if (attempted.size() == 1) throw new IllegalStateException("Do not log this message");
            };
            assertDoesNotThrow(() -> rotation.start(List.of("A", "B"), GAP, update));
            timer.advance(GAP / 10);
            rotation.start(List.of("C", "D"), GAP, update);
            timer.advance(9 * GAP / 10 - 1);
            assertEquals(List.of("A"), attempted);
            timer.advance(1);
            timer.advance(GAP);
            assertEquals(List.of("A", "C", "D"), attempted);
        }
    }

    @Test void reentrantStopCannotRestoreTheOldSchedule() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            rotation.start(List.of("A", "B"), GAP, text -> {
                sent.add(text);
                rotation.stop(sent::add);
            });
            timer.advance(GAP);
            timer.advance(10 * GAP);
            assertEquals(Arrays.asList("A", null), sent);
        }
    }

    @Test void parsesTrimmedImmutableTextsAndAcceptsTheLimits() {
        assertEquals(List.of("A", "B"), StatusRotation.parseTexts(" A | B "));
        assertEquals(List.of("A", "B"), StatusRotation.parseTexts("\u2003A\u2003|\u2003B\u2003"));
        assertThrows(UnsupportedOperationException.class, () -> StatusRotation.parseTexts("A|B").add("C"));
        var ten = java.util.Collections.nCopies(10, "a".repeat(128));
        assertEquals(ten, StatusRotation.parseTexts(String.join("|", ten)));
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            rotation.start(ten, StatusRotation.MIN_INTERVAL_MS, text -> {});
            rotation.start(ten, StatusRotation.MAX_INTERVAL_MS, text -> {});
            assertEquals(StatusRotation.MAX_INTERVAL_MS, rotation.snapshot().intervalMs());
        }
    }

    @Test void acceptsOneStatusEmptySeparatorsAndLineSeparatedLists() {
        assertEquals(List.of("Paying w my bbfs!"), StatusRotation.parseTexts("Paying w my bbfs!"));
        assertEquals(List.of("A", "B"), StatusRotation.parseTexts("| A || \u2003 | B |"));
        assertEquals(List.of("A", "B", "C", "D"), StatusRotation.parseTexts("A\r\nB\n\n|C\u2028D\r"));
    }

    @Test void singleStatusStaysFixedWithoutRepeatedUpdatesAndCanBeReplaced() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            rotation.start(StatusRotation.parseTexts("Paying w my bbfs!"), INTERVAL, sent::add);
            timer.advance(6 * GAP);
            assertTrue(rotation.snapshot().running());
            assertEquals(List.of("Paying w my bbfs!"), sent);
            rotation.start(List.of("A", "B"), INTERVAL, sent::add);
            timer.advance(INTERVAL);
            assertEquals(List.of("Paying w my bbfs!", "A", "B"), sent);
        }
    }

    @Test void rejectsEmptyAndOversizedInputWithSpecificErrors() {
        for (String raw : Arrays.asList(null, "", "   ", "\n", "| |\r\n|"))
            assertEquals("status.texts.required", assertThrows(UserError.class, () -> StatusRotation.parseTexts(raw)).getMessage());
        var tooLong = assertThrows(UserError.class, () -> StatusRotation.parseTexts("A | |" + "b".repeat(129)));
        assertEquals("status.texts.too_long", tooLong.getMessage());
        assertEquals(Messages.text(Language.EN, "status.texts.too_long", 2, 129, 128), tooLong.localized(Language.EN));
        assertEquals("status.texts.too_many", assertThrows(UserError.class,
            () -> StatusRotation.parseTexts(String.join("|", java.util.Collections.nCopies(11, "A")))).getMessage());
    }

    @Test void invalidStartPreservesTheExistingRotationAndCopiesItsInput() {
        var timer = new FakeScheduler();
        try (var rotation = timer.rotation()) {
            var sent = new ArrayList<String>();
            var texts = new ArrayList<>(List.of("A", "B"));
            rotation.start(texts, GAP, sent::add);
            texts.set(1, "Changed");
            for (long interval : List.of(-1L, 0L, GAP - 1, 3_600_001L, Long.MAX_VALUE))
                assertEquals("status.interval", assertThrows(UserError.class,
                        () -> rotation.start(List.of("C", "D"), interval, sent::add)).getMessage());
            assertThrows(UserError.class, () -> rotation.start(null, GAP, sent::add));
            assertThrows(UserError.class, () -> rotation.start(List.of(), GAP, sent::add));
            assertThrows(UserError.class, () -> rotation.start(List.of(" "), GAP, sent::add));
            assertThrows(UserError.class, () -> rotation.start(Arrays.asList("C", null), GAP, sent::add));
            assertThrows(UserError.class, () -> rotation.start(List.of("C", "D\nE"), GAP, sent::add));
            assertThrows(NullPointerException.class, () -> rotation.start(List.of("C", "D"), GAP, null));
            timer.advance(GAP);
            assertEquals(List.of("A", "B"), sent);
        }
    }

    private static long nanos(long millis) { return TimeUnit.MILLISECONDS.toNanos(millis); }

    static final class FakeScheduler implements StatusRotation.Scheduler {
        private long now;
        private long sequence;
        private boolean closed;
        private final PriorityQueue<Task> tasks = new PriorityQueue<>();

        StatusRotation rotation() { return new StatusRotation(this, () -> now); }

        @Override public StatusRotation.Cancellation schedule(Runnable runnable, long delayNanos) {
            assertFalse(closed);
            assertTrue(delayNanos >= 0);
            var task = new Task(now + delayNanos, sequence++, runnable);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        void advance(long millis) {
            long target = now + nanos(millis);
            while (!tasks.isEmpty() && tasks.peek().due <= target) {
                var task = tasks.remove();
                now = task.due;
                if (!task.cancelled) task.runnable.run();
            }
            now = target;
        }

        @Override public void close() { closed = true; tasks.clear(); }

        private static final class Task implements Comparable<Task> {
            private final long due;
            private final long sequence;
            private final Runnable runnable;
            private boolean cancelled;
            Task(long due, long sequence, Runnable runnable) {
                this.due = due; this.sequence = sequence; this.runnable = runnable;
            }
            @Override public int compareTo(Task other) {
                int time = Long.compare(due, other.due);
                return time == 0 ? Long.compare(sequence, other.sequence) : time;
            }
        }
    }
}
