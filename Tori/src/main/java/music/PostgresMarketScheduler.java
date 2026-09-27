package music;

import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restart-safe market maintenance. Missed hourly intervals are collapsed into one timestamped move. */
final class PostgresMarketScheduler implements AutoCloseable {
    private final PostgresMarketStore store;
    private final Clock clock;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "tori-market-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    PostgresMarketScheduler(PostgresMarketStore store, Clock clock) throws CurrencyStoreException {
        this.store = store;
        this.clock = clock;
        store.seedCatalog(clock.instant());
        maintainPrices();
        executor.scheduleAtFixedRate(this::maintainPrices, 1, 1, TimeUnit.HOURS);
        executor.scheduleAtFixedRate(this::maybeStartSale, 30, 30, TimeUnit.MINUTES);
    }

    private void maintainPrices() {
        if (closed.get()) return;
        try { store.evolveDueProducts(clock.instant()); }
        catch (CurrencyStoreException ex) {
            LoggerFactory.getLogger(PostgresMarketScheduler.class).warn("Market price maintenance failed safely.");
        }
    }

    private void maybeStartSale() {
        if (closed.get()) return;
        try {
            var available = store.products("all", clock.instant()).stream()
                .filter(DeseModels.Product::available).toList();
            List<String> categories = available.stream().map(DeseModels.Product::category).distinct().toList();
            List<String> products = available.stream().map(DeseModels.Product::id).toList();
            store.maybeCreateRandomSale(clock.instant(), categories, products);
        } catch (CurrencyStoreException ex) {
            LoggerFactory.getLogger(PostgresMarketScheduler.class).warn("Market sale maintenance failed safely.");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
