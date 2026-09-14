package music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One bot-wide presence rotation. All presence attempts, including controls, share the same pacing. */
public final class StatusRotation implements AutoCloseable {
    public static final long MIN_INTERVAL_MS = 30_000;
    public static final long MAX_INTERVAL_MS = 3_600_000;
    public static final long DEFAULT_INTERVAL_MS = 120_000;
    private static final Logger LOG = LoggerFactory.getLogger(StatusRotation.class);

    interface Cancellation { void cancel(); }
    interface Scheduler extends AutoCloseable {
        Cancellation schedule(Runnable task, long delayNanos);
        @Override void close();
    }

    public record Snapshot(boolean running, List<String> texts, long intervalMs) {
        public Snapshot { texts = List.copyOf(texts); }
    }

    private final Scheduler scheduler;
    private final LongSupplier nanoTime;
    private Cancellation pending;
    private long revision;
    private boolean closed;
    private boolean running;
    private List<String> texts = List.of();
    private long intervalMs = DEFAULT_INTERVAL_MS;
    private int nextIndex;
    private Consumer<String> update;
    private boolean attempted;
    private long lastAttemptNanos;

    public StatusRotation() { this(newScheduler(), System::nanoTime); }

    StatusRotation(Scheduler scheduler, LongSupplier nanoTime) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    private static Scheduler newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "bot-status-rotation");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return new Scheduler() {
            @Override public Cancellation schedule(Runnable task, long delayNanos) {
                var future = executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
            @Override public void close() { executor.shutdownNow(); }
        };
    }

    public static List<String> parseTexts(String raw) {
        if (raw == null) throw new UserError("status.texts.required");
        if (raw.isBlank()) throw new UserError("status.texts.required");
        return validatedTexts(List.of(raw.split("\\||\\R", -1)));
    }

    private static List<String> validatedTexts(List<String> values) {
        if (values == null || values.isEmpty()) throw new UserError("status.texts.required");
        var validated = new ArrayList<String>(values.size());
        for (String value : values) {
            if (value == null) throw new UserError("status.texts.required");
            String text = value.strip();
            if (text.isBlank()) continue;
            if (text.matches("(?s).*\\R.*")) throw new UserError("status.texts.invalid");
            if (text.length() > 128)
                throw new UserError("status.texts.too_long", validated.size() + 1, text.length(), 128);
            validated.add(text);
        }
        if (validated.isEmpty()) throw new UserError("status.texts.required");
        if (validated.size() > 10) throw new UserError("status.texts.too_many", validated.size(), 10);
        return List.copyOf(validated);
    }

    public synchronized void start(List<String> values, long intervalMs, Consumer<String> update) {
        ensureOpen();
        var validated = validatedTexts(values);
        if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS)
            throw new UserError("status.interval", MIN_INTERVAL_MS, MAX_INTERVAL_MS);
        Objects.requireNonNull(update);
        cancelPending();
        this.texts = validated;
        this.intervalMs = intervalMs;
        this.update = update;
        this.running = true;
        this.nextIndex = 0;
        emitOrSchedule(revision);
    }

    public synchronized void stop(Consumer<String> update) {
        ensureOpen();
        Objects.requireNonNull(update);
        cancelPending();
        running = false;
        texts = List.of();
        intervalMs = DEFAULT_INTERVAL_MS;
        this.update = update;
        emitOrSchedule(revision);
    }

    public synchronized Snapshot snapshot() { return new Snapshot(running, texts, intervalMs); }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Status rotation is closed");
    }

    private void cancelPending() {
        revision++;
        if (pending != null) { pending.cancel(); pending = null; }
    }

    private long remainingDelay(long delayMs) {
        if (!attempted) return 0;
        return Math.max(0, TimeUnit.MILLISECONDS.toNanos(delayMs) - (nanoTime.getAsLong() - lastAttemptNanos));
    }

    /** Caller holds the monitor, so a superseded callback can never emit after a newer control. */
    private void emitOrSchedule(long expectedRevision) {
        if (closed || revision != expectedRevision) return;
        long delay = remainingDelay(MIN_INTERVAL_MS);
        if (delay > 0) { schedule(expectedRevision, delay); return; }
        String text = running ? texts.get(nextIndex) : null;
        if (running) nextIndex = (nextIndex + 1) % texts.size();
        attempted = true;
        lastAttemptNanos = nanoTime.getAsLong();
        try { update.accept(text); }
        catch (RuntimeException ex) { LOG.warn("Bot status update failed ({})", ex.getClass().getSimpleName()); }
        // A consumer may reenter start/stop/close; do not restore its superseded schedule.
        if (closed || revision != expectedRevision) return;
        if (running && texts.size() > 1) schedule(expectedRevision, remainingDelay(intervalMs));
        else if (!running) update = null;
    }

    private void schedule(long expectedRevision, long delayNanos) {
        pending = scheduler.schedule(() -> {
            synchronized (StatusRotation.this) {
                if (closed || revision != expectedRevision) return;
                pending = null;
                emitOrSchedule(expectedRevision);
            }
        }, delayNanos);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        texts = List.of();
        intervalMs = DEFAULT_INTERVAL_MS;
        update = null;
        cancelPending();
        scheduler.close();
    }
}
