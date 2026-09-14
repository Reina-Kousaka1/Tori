package music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Optional, asynchronous modlog delivery. No bot token is sent to the webhook. */
public final class WebhookModLogger implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookModLogger.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PATH = Pattern.compile("/api(?:/v10)?/webhooks/[0-9]{17,20}/[A-Za-z0-9_-]{20,}");
    private final URI endpoint;
    private final Transport transport;
    private final Sleeper sleeper;
    private final ModLogStore store;
    private HttpClient ownedClient;
    private Instant pauseUntil = Instant.MIN; // Accessed only by the delivery worker.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "modlog-webhook");
            thread.setDaemon(true);
            return thread;
        });

    public record Entry(String caseId, String action, String guild, String guildId,
                        String channelId, String moderator, String moderatorId,
                        String target, String reason, String result, Instant timestamp, Language language) {
        public Entry(String caseId, String action, String guild, String guildId,
                     String channelId, String moderator, String moderatorId,
                     String target, String reason, String result, Instant timestamp) {
            this(caseId, action, guild, guildId, channelId, moderator, moderatorId, target, reason, result, timestamp, Language.DE);
        }
    }
    record Response(int status, String body) {}
    @FunctionalInterface interface Transport { Response send(URI uri, String payload) throws Exception; }
    @FunctionalInterface interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }

    public static WebhookModLogger fromConfig(BotConfig config) {
        return fromConfig(config, ModLogStore.fromConfig(config));
    }

    static WebhookModLogger fromConfig(BotConfig config, ModLogStore store) {
        validateEndpoint(config.get("MODLOG_WEBHOOK_URL"));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var logger = new WebhookModLogger(config.get("MODLOG_WEBHOOK_URL"), (uri, payload) -> {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }, Thread::sleep, store);
        logger.ownedClient = client;
        return logger;
    }

    WebhookModLogger(String url, Transport transport, Sleeper sleeper) {
        this(url, transport, sleeper, null);
    }

    WebhookModLogger(String url, Transport transport, Sleeper sleeper, ModLogStore store) {
        this.endpoint = validateEndpoint(url);
        this.transport = transport;
        this.sleeper = sleeper;
        this.store = store;
    }

    static URI validateEndpoint(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equals(uri.getScheme()) || !"discord.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || !PATH.matcher(uri.getRawPath()).matches()
                || (uri.getRawQuery() != null && !uri.getRawQuery().matches("thread_id=[0-9]{17,20}")))
                throw new IllegalArgumentException();
            return URI.create(uri + (uri.getRawQuery() == null ? "?" : "&") + "wait=true");
        } catch (IllegalArgumentException ex) {
            // Never include the URL or parsing exception: it contains the webhook credential.
            throw new IllegalArgumentException("MODLOG_WEBHOOK_URL: vollständigen https://discord.com/api/webhooks/ID/TOKEN-Link verwenden (optional ?thread_id=ID).");
        }
    }

    public void publish(Entry entry) {
        if (store != null) {
            try {
                if (!store.save(entry, endpoint != null)) return;
            } catch (Exception ex) {
                LOG.warn("Moderation database write failed; webhook delivery will still be attempted.");
            }
        }
        if (endpoint == null) return;
        try { worker.execute(new Delivery(entry)); }
        catch (RejectedExecutionException ex) { notSent(entry); }
    }

    private final class Delivery implements Runnable {
        private final Entry entry;
        Delivery(Entry entry) { this.entry = entry; }
        @Override public void run() { deliver(entry); }
    }

    private void record(Entry entry, String status, Integer http, String error, boolean attempt) {
        if (store == null) return;
        try { store.update(entry, status, http, error, attempt); }
        catch (Exception ex) { LOG.warn("Moderation database delivery-state update failed."); }
    }

    private void notSent(Entry entry) {
        record(entry, "NOT_SENT", null, "QUEUE_CLOSED_OR_FULL", false);
        LOG.warn("Modlog delivery queue closed or full; database case retained.");
    }

    void deliver(Entry entry) {
        if (endpoint == null) return;
        try {
            long remaining = pauseUntil.isAfter(Instant.now()) ? Duration.between(Instant.now(), pauseUntil).toMillis() : 0;
            if (remaining > 60_000) { failed(entry, "Webhook-Ratelimit weiterhin aktiv"); return; }
            if (remaining > 0) sleeper.sleep(remaining);
            String payload = payload(entry);
            for (int attempt = 0; attempt < 3; attempt++) {
                record(entry, "SENDING", null, null, true);
                Response response = transport.send(endpoint, payload);
                if (response.status() >= 200 && response.status() < 300) {
                    record(entry, "DELIVERED", response.status(), null, false);
                    return;
                }
                record(entry, "FAILED", response.status(), "HTTP " + response.status(), false);
                if (response.status() == 429) {
                    double seconds = JSON.readTree(response.body()).path("retry_after").asDouble(-1);
                    boolean validDelay = Double.isFinite(seconds) && seconds >= 0 && seconds <= 86400;
                    long delay = validDelay ? Math.max(100, (long) Math.ceil(seconds * 1000)) : 60_000;
                    pauseUntil = Instant.now().plusMillis(delay);
                    if (validDelay && delay <= 60_000 && attempt < 2) {
                        sleeper.sleep(delay);
                        continue;
                    }
                }
                failed(entry, "HTTP " + response.status());
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failed(entry, "Versand unterbrochen");
        } catch (Exception ex) {
            // Do not log response bodies, URLs, reasons or exception messages.
            failed(entry, "Transport- oder Formatfehler");
        }
    }

    static String payload(Entry entry) throws Exception {
        Language language = entry.language();
        var fields = List.of(
            field(Messages.text(language, "log.server"), cut(entry.guild(), 120) + " (" + entry.guildId() + ")"),
            field(Messages.text(language, "log.channel"), entry.channelId()),
            field(Messages.text(language, "log.moderator"), cut(entry.moderator(), 120) + " (" + entry.moderatorId() + ")"),
            field(Messages.text(language, "log.target"), entry.target()),
            field(Messages.text(language, "log.reason"), cut(entry.reason(), 450)),
            field(Messages.text(language, "log.result"), entry.result())
        );
        var embed = Map.of("title", Messages.text(language, "log.title", cut(entry.action(), 32)), "color", ToriEmbeds.Category.SUCCESS.color(),
            "timestamp", entry.timestamp().toString(), "fields", fields,
            "footer", Map.of("text", ToriEmbeds.footer(language, Messages.text(language, "log.case", cut(entry.caseId(), 32)))));
        return JSON.writeValueAsString(Map.of("allowed_mentions", Map.of("parse", List.of()), "embeds", List.of(embed)));
    }

    private static Map<String, Object> field(String name, String value) {
        return Map.of("name", name, "value", cut(value, 900), "inline", false);
    }
    private static String cut(String value, int max) {
        if (value == null || value.isBlank()) return "—";
        return value.substring(0, Math.min(value.length(), max));
    }
    private void failed(Entry entry, String status) {
        Integer http = status.startsWith("HTTP ") ? Integer.valueOf(status.substring(5)) : null;
        record(entry, "FAILED", http, status, false);
        LOG.warn("Modlog für Fall {} nicht zugestellt: {}. Moderationsaktion bleibt unverändert.", entry.caseId(), status);
    }
    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                var pending = worker.shutdownNow();
                pending.forEach(task -> { if (task instanceof Delivery delivery) notSent(delivery.entry); });
                int discarded = pending.size();
                LOG.warn("Modlog-Versand beendet; {} wartende Einträge verworfen.", discarded);
            }
        } catch (InterruptedException ex) {
            worker.shutdownNow().forEach(task -> { if (task instanceof Delivery delivery) notSent(delivery.entry); });
            Thread.currentThread().interrupt();
        } finally {
            if (ownedClient != null) ownedClient.shutdownNow();
        }
    }
}
