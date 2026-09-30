package music;

import dev.arbjerg.lavalink.client.*;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private final Instant startedAt;
    private final String sessionId = UUID.randomUUID().toString();
    private final BotStore store;
    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private final CountDownLatch shutdownFinished = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    private boolean started;
    private String stopReason = "SHUTDOWN";
    private volatile int exitCode;
    private LavalinkClient client;
    private MusicBot music;
    private ModerationBot moderation;
    private GeneralBot general;
    private TicketOrderBot ticketOrders;
    private PostgresMarketScheduler marketScheduler;
    private WebhookModLogger modlog;
    private JDA jda;

    Main(Instant startedAt, BotStore store) {
        this.startedAt = startedAt;
        this.store = store;
    }

    public static void main(String[] args) {
        int result = 0;
        boolean again;
        do {
            Main bot = null;
            BotConfig config = null;
            Thread shutdownHook = null;
            again = false;
            try {
                config = BotConfig.load();
                if (args.length == 1 && args[0].equals("--register-commands")) {
                    CommandRegistration.register(config.discordToken(), config.get("DISCORD_GUILD_ID"));
                    return;
                }
                config.validateForStartup();
                bot = new Main(Instant.now(), PostgresBotStore.fromConfig(config));
                Main running = bot;
                shutdownHook = new Thread(running::close, "bot-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                bot.run(config);
                again = "RESTART".equals(bot.stopReason)
                    && !Boolean.parseBoolean(config.get("BOT_RESTART_EXTERNAL", "false"));
            } catch (Exception ex) {
                logStartupFailure(ex, config);
                result = 1;
            } finally {
                if (bot != null) {
                    bot.close();
                    result = Math.max(result, bot.exitCode);
                }
                if (shutdownHook != null) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                    catch (IllegalStateException ignored) { again = false; }
                }
            }
            // Recreate all services only after the previous session has closed successfully.
            again = again && result == 0 && !Thread.currentThread().isInterrupted();
        } while (again);
        System.exit(result);
    }

    private static void logStartupFailure(Exception ex, BotConfig config) {
        if (ex instanceof BotConfig.ConfigurationException configurationError) {
            LOG.error("Bot startup configuration failed: {}", configurationError.getMessage());
        } else if (ex instanceof CommandRegistration.RegistrationException) {
            LOG.error("{}", ex.getMessage());
        } else {
            LOG.error("{}", unexpectedStartupDiagnostic(ex, config));
        }
    }

    static String unexpectedStartupDiagnostic(Exception failure, BotConfig config) {
        return "Bot startup or execution failed (" + failure.getClass().getSimpleName()
            + "); full sanitized stacktrace follows:\n" + diagnosticStackTrace(failure, config);
    }

    static String diagnosticStackTrace(Throwable failure, BotConfig config) {
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        String rendered = trace.toString();
        return config == null ? rendered : config.redactSensitive(rendered);
    }

    private void run(BotConfig config) throws Exception {
        initialize(config);
        jda.awaitReady();
        CommandRegistration.register(jda, config.get("DISCORD_GUILD_ID"));
        client.addNode(new NodeOptions.Builder().setName("music")
            .setServerUri(config.get("LAVALINK_URI", "ws://localhost:2334"))
            .setPassword(config.required("LAVALINK_PASSWORD")).build());
        recordStarted();
        System.out.println("Java Music- und Moderationsbot ist bereit.");
        stopRequested.await();
    }

    private synchronized void initialize(BotConfig config) throws Exception {
        if (closing.get()) throw new IllegalStateException("Startup interrupted by shutdown");
        String token = config.discordToken();
        long ownerId = config.ownerId();
        config.required("LAVALINK_PASSWORD");
        long botId = config.discordBotId();
        LanguageStore languages = store instanceof PostgresBotStore
            ? LanguageStore.fromStore(config, store) : LanguageStore.fromConfig(config);
        client = new LavalinkClient(botId);
        music = new MusicBot(client, languages, new YouTubeSearch(config.get("YOUTUBE_API_KEY")), YtDlp.fromConfig(config));
        modlog = WebhookModLogger.fromConfig(config, store);
        moderation = new ModerationBot(modlog, languages);
        var prefixes = new PrefixSettings(store);
        general = new GeneralBot(languages, new StatusRotation(), ownerId, this::requestRestart, startedAt)
            .withStats(store, config.get("BOT_CREATOR", "")).withPrefixes(prefixes)
            .withPersona(new ToriPersona(ToriPersonaConfig.load(config)))
            .withPresenceSync(new ToriPresenceSync(config.toriPresenceClient()))
            .withShutdown(this::requestShutdown);
        if (store instanceof PostgresBotStore postgres) {
            general.withCurrency(new PostgresCurrencyStore(postgres.database()));
            EconomyRouting economyRouting = config.economyRouting();
            general.withEconomyRouting(economyRouting);
            EconomyV2Client economyClient = config.economyV2Client(economyRouting);
            if (economyClient != null) general.withEconomyV2Balance(economyClient);
            MarketMode marketMode = MarketMode.parse(config.get("TORI_MARKET_MODE", "LEGACY"));
            var market = new PostgresMarketStore(postgres.database(),
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(), marketMode);
            if (marketMode == MarketMode.LEGACY)
                marketScheduler = new PostgresMarketScheduler(market, Clock.systemUTC());
            general.withMarket(market);
            ticketOrders = new TicketOrderBot(languages, new TicketOrderStore(postgres.database()));
        }
        var listeners = new java.util.ArrayList<Object>();
        listeners.addAll(java.util.List.of(music, moderation, moderation.snipes, general));
        if (ticketOrders != null) listeners.add(ticketOrders);
        listeners.add(new PrefixCommands(prefixes, music, moderation, general));
        jda = JDABuilder.createDefault(token)
            .setAutoReconnect(true)
            .enableIntents(GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
            .enableCache(CacheFlag.VOICE_STATE)
            .setVoiceDispatchInterceptor(new JDAVoiceUpdateListener(client))
            .addEventListeners(listeners.toArray()).build();
    }

    synchronized void recordStarted() throws java.sql.SQLException {
        if (closing.get()) return;
        store.lifecycle(sessionId, "BOT_STARTED", null, startedAt);
        started = true;
    }

    /** Called only after the owner's restart reply has reached Discord. */
    synchronized void requestRestart() {
        if (!started || closing.get() || stopRequested.getCount() == 0) return;
        try {
            store.lifecycle(sessionId, "BOT_STOPPED", "RESTART", startedAt);
        } catch (java.sql.SQLException ex) {
            LoggerFactory.getLogger(Main.class).error("Restart cancelled: BOT_STOPPED could not be persisted.");
            return;
        }
        stopReason = "RESTART";
        stopRequested.countDown();
    }

    /** Signal the main thread after the owner's reply, so it performs normal cleanup. */
    synchronized void requestShutdown() {
        if (!started || closing.get() || stopRequested.getCount() == 0) return;
        stopReason = "SHUTDOWN";
        stopRequested.countDown();
    }

    @Override public void close() {
        boolean ownsShutdown;
        synchronized (this) {
            ownsShutdown = closing.compareAndSet(false, true);
            if (ownsShutdown && started) {
                try { store.lifecycle(sessionId, "BOT_STOPPED", stopReason, startedAt); }
                catch (java.sql.SQLException ex) { exitCode = 1; LoggerFactory.getLogger(Main.class).error("BOT_STOPPED could not be persisted."); }
            }
        }
        if (!ownsShutdown) {
            try { shutdownFinished.await(70, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return;
        }
        try {
            if (marketScheduler != null) clean(marketScheduler::close);
            // Cancel the normal rotation without sending a shutdown/restart presence.
            if (general != null) clean(general::close);
            if (ticketOrders != null) clean(ticketOrders::close);
            if (music != null) clean(() -> { if (jda == null) music.close(); else music.close(jda); });
            if (moderation != null) clean(moderation::close);
            else if (modlog != null) clean(modlog::close);
            if (client != null) clean(client::close);
            if (jda != null) clean(() -> {
                jda.shutdown();
                if (!jda.awaitShutdown(Duration.ofSeconds(15))) {
                    jda.shutdownNow();
                    if (!jda.awaitShutdown(Duration.ofSeconds(5))) throw new IllegalStateException("JDA shutdown timed out");
                }
            });
        } finally {
            clean(store::close);
            shutdownFinished.countDown();
        }
    }

    @FunctionalInterface private interface Cleanup { void run() throws Exception; }
    private void clean(Cleanup cleanup) {
        try { cleanup.run(); }
        catch (Exception ex) {
            if (ex instanceof CommandRegistration.RegistrationException)
                LoggerFactory.getLogger(Main.class).error("{}", ex.getMessage());
            exitCode = 1;
            // Continue with the remaining services even if one close operation fails.
            LoggerFactory.getLogger(Main.class).warn("Bot shutdown step failed ({})", ex.getClass().getSimpleName());
        }
    }
}
