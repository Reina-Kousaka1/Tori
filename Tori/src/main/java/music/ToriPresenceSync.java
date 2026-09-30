package music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Last-known-good semantic context with a local fallback and an occasional recovery resync. */
final class ToriPresenceSync implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ToriPresenceSync.class);
    static final Duration RESYNC_INTERVAL = Duration.ofMinutes(5);

    interface Provider {
        ToriPresenceContext fetch() throws Exception;
        ToriPresenceContext update(String activity, String specialEvent, Long ttlSeconds) throws Exception;
    }

    private final Provider provider;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private volatile ToriPresenceContext cached = ToriPresenceContext.general();
    private volatile Consumer<ToriPresenceContext> listener = ignored -> {};
    private boolean started;
    private volatile boolean closed;

    ToriPresenceSync(ToriPresenceClient client) {
        this(client, Clock.systemUTC());
    }

    ToriPresenceSync(Provider provider, Clock clock) {
        this.provider = provider;
        this.clock = Objects.requireNonNull(clock);
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "tori-presence-context");
            thread.setDaemon(true);
            return thread;
        });
    }

    static ToriPresenceSync disabled() {
        return new ToriPresenceSync(null, Clock.systemUTC());
    }

    synchronized void start(Consumer<ToriPresenceContext> listener) {
        if (closed || provider == null || started) return;
        this.listener = Objects.requireNonNull(listener);
        started = true;
        executor.scheduleWithFixedDelay(this::pollSafely, 0, RESYNC_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    boolean available() { return provider != null && !closed; }

    boolean available() { return provider != null && !closed; }

    ToriPresenceContext current() {
        var value = cached;
        return value.expiredAt(clock.instant()) ? ToriPresenceContext.general() : value;
    }

    ToriPresenceContext setContext(String activity, String specialEvent, Long ttlSeconds) throws Exception {
        if (provider == null) throw new IllegalStateException("Tori presence provider is not configured");
        return accept(provider.update(activity, specialEvent, ttlSeconds));
    }

    /** Package-visible so cache recovery and expiry behavior can be verified without a live service. */
    void pollOnce() throws Exception {
        if (provider != null) accept(provider.fetch());
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception ex) {
            LOG.warn("Tori presence context refresh failed ({})", ex.getClass().getSimpleName());
        }
    }

    private ToriPresenceContext accept(ToriPresenceContext next) {
        Objects.requireNonNull(next);
        if (next.expiredAt(clock.instant()))
            throw new IllegalArgumentException("Presence provider returned an expired context");
        var previous = cached;
        if (next.revision() < previous.revision()
            && next.updatedAt() != null
            && (previous.updatedAt() == null || !next.updatedAt().isAfter(previous.updatedAt()))) {
            return current();
        }
        cached = next;
        if (!next.equals(previous)) listener.accept(next);
        return next;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        listener = ignored -> {};
    }
}
