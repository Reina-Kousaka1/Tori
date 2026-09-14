package music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Separate bounded worker stripes for music and moderation; never block JDA's gateway. */
public abstract class CommandListener extends ListenerAdapter implements AutoCloseable {
    private final Set<String> names;
    protected final LanguageStore languages;
    private final ExecutorService[] workers = new ExecutorService[8];
    private final Semaphore[] commandSlots = new Semaphore[8];
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    protected CommandListener(Set<String> names, LanguageStore languages) {
        this.names = names;
        this.languages = languages;
        for (int i = 0; i < workers.length; i++) {
            workers[i] = Executors.newSingleThreadExecutor();
            commandSlots[i] = new Semaphore(32);
        }
    }
    protected void serial(long guild, Runnable task) {
        if (closed.get()) return;
        try { workers[(Long.hashCode(guild) & Integer.MAX_VALUE) % workers.length].execute(() -> {
            try { task.run(); }
            catch (Exception ex) { LoggerFactory.getLogger(getClass()).warn("Aktion fehlgeschlagen ({})", ex.getClass().getSimpleName()); }
        }); } catch (RejectedExecutionException ex) {
            if (!closed.get()) throw ex;
        }
    }
    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (closed.get()) return;
        if (!names.contains(event.getName())) return;
        if (event.isAcknowledged()) return;
        if (event.getGuild() == null) {
            event.reply(Messages.text(language(event), "server.only")).setEphemeral(true)
                .queue(null, failure -> interactionFailure(event, "acknowledgement", failure));
            return;
        }
        // Acknowledge before consulting settings or submitting any command work.
        event.deferReply(false).queue(hook -> {
            Language initialLanguage = language(event);
            var slots = commandSlots[(Long.hashCode(event.getGuild().getIdLong()) & Integer.MAX_VALUE) % workers.length];
            if (!slots.tryAcquire()) {
                hook.editOriginal(Messages.text(initialLanguage, "busy"))
                    .queue(null, failure -> interactionFailure(event, "response", failure));
                return;
            }
            try {
                serial(event.getGuild().getIdLong(), () -> {
                    try {
                        Language currentLanguage = language(event);
                        String message = null;
                        MessageEmbed embed = null;
                        Runnable onDelivered = () -> {};
                        try {
                            embed = handleEmbed(event, currentLanguage);
                            if (embed == null) message = handle(event, currentLanguage);
                            onDelivered = afterReply(event);
                        }
                        catch (UserError ex) { message = ex.localized(currentLanguage); }
                        catch (IllegalArgumentException ex) { message = Messages.text(currentLanguage, "error.input"); }
                        catch (ErrorResponseException ex) { message = Messages.text(currentLanguage, "error.discord", ex.getErrorCode()); }
                        catch (Exception ex) {
                            // Include source locations without exception messages, which may contain credentials.
                            LoggerFactory.getLogger(getClass()).warn("Command {} failed ({}) at {}", event.getName(),
                                ex.getClass().getSimpleName(), java.util.Arrays.toString(ex.getStackTrace()));
                            message = Messages.text(currentLanguage, "error.generic");
                        }
                        Runnable delivered = onDelivered;
                        var reply = embed == null ? hook.editOriginal(clip(message, 1950)) : hook.editOriginalEmbeds(embed);
                        reply.setAllowedMentions(List.of()).queue(ignored -> delivered.run(),
                            failure -> interactionFailure(event, "response", failure));
                    } finally { slots.release(); }
                });
            } catch (RejectedExecutionException ex) {
                slots.release();
                hook.editOriginal(Messages.text(initialLanguage, "shutting.down"))
                    .queue(null, failure -> interactionFailure(event, "response", failure));
            }
        }, failure -> interactionFailure(event, "acknowledgement", failure));
    }
    private void interactionFailure(SlashCommandInteractionEvent event, String action, Throwable failure) {
        var log = LoggerFactory.getLogger(getClass());
        if (failure instanceof ErrorResponseException error) {
            int code = error.getErrorCode();
            if (code == 10062 || code == 40060) {
                log.warn("Command /{} {} failed (Discord {}): interaction expired or was acknowledged elsewhere. "
                    + "Check response latency and other running bot instances.", event.getName(), action, code);
            } else {
                log.warn("Command /{} {} failed (Discord {}).", event.getName(), action, code);
            }
        } else {
            // Exception messages and HTTP details can contain interaction tokens.
            log.warn("Command /{} {} failed ({}).", event.getName(), action, failure.getClass().getSimpleName());
        }
    }
    private Language language(SlashCommandInteractionEvent event) {
        return event.getGuild() != null ? languages.get(event.getGuild().getId())
            : Language.discordLocale(event.getUserLocale().getLocale(), languages.defaultLanguage());
    }
    protected String handle(SlashCommandInteractionEvent event, Language language) { return handle(new CommandContext(event), language); }
    protected String handle(CommandContext event, Language language) { throw new UserError("error.unknown"); }
    protected MessageEmbed handleEmbed(SlashCommandInteractionEvent event, Language language) { return handleEmbed(new CommandContext(event), language); }
    protected MessageEmbed handleEmbed(CommandContext event, Language language) { return null; }
    /** Runs only after a successful command's response reaches Discord. */
    protected Runnable afterReply(SlashCommandInteractionEvent event) { return afterReply(new CommandContext(event)); }
    protected Runnable afterReply(CommandContext event) { return () -> {}; }
    boolean accepts(String name) { return names.contains(name); }
    void prefix(net.dv8tion.jda.api.events.message.MessageReceivedEvent message, String name, java.util.Map<String, String> options) {
        if (closed.get()) return;
        long guild = message.getGuild().getIdLong();
        var slots = commandSlots[(Long.hashCode(guild) & Integer.MAX_VALUE) % workers.length];
        if (!slots.tryAcquire()) return;
        try {
            serial(guild, () -> {
                try {
                    Language language = languages.get(message.getGuild().getId());
                    var context = new CommandContext(message, name, options);
                    MessageEmbed embed = null;
                    String text = null;
                    Runnable delivered = () -> {};
                    try {
                        embed = handleEmbed(context, language);
                        if (embed == null) text = handle(context, language);
                        delivered = afterReply(context);
                    } catch (UserError ex) { text = ex.localized(language); }
                    catch (IllegalArgumentException ex) { text = Messages.text(language, "error.input"); }
                    catch (Exception ex) {
                        LoggerFactory.getLogger(getClass()).warn("Prefix command {} failed ({})", name, ex.getClass().getSimpleName());
                        text = Messages.text(language, "error.generic");
                    }
                    var reply = embed == null ? message.getChannel().sendMessage(clip(text, 1950)) : message.getChannel().sendMessageEmbeds(embed);
                    Runnable callback = delivered;
                    reply.setAllowedMentions(List.of()).queue(ignored -> callback.run(),
                        ex -> LoggerFactory.getLogger(getClass()).warn("Prefix response failed ({})", ex.getClass().getSimpleName()));
                } finally { slots.release(); }
            });
        } catch (RejectedExecutionException ex) { slots.release(); }
    }
    protected static void require(boolean condition, String key, Object... args) { if (!condition) throw new UserError(key, args); }
    protected static String clip(String value, int max) { return value == null ? "—" : value.substring(0, Math.min(max, value.length())); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (var worker : workers) worker.shutdown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            for (var worker : workers) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (!worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) worker.shutdownNow();
            }
            long interruptedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (var worker : workers) {
                long remaining = Math.max(0, interruptedDeadline - System.nanoTime());
                if (!worker.awaitTermination(remaining, TimeUnit.NANOSECONDS))
                    LoggerFactory.getLogger(getClass()).warn("Command worker did not terminate within the shutdown deadline.");
            }
        } catch (InterruptedException ex) {
            for (var worker : workers) worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
