package music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Bounded shared workers with independent per-guild command ordering; never block JDA's gateway. */
public abstract class CommandListener extends ListenerAdapter implements AutoCloseable {
    private static final int WORKER_COUNT = 8;
    private static final int MAX_PENDING_COMMANDS_PER_GUILD = 32;
    private static final int MAX_PENDING_COMMANDS_TOTAL = WORKER_COUNT * MAX_PENDING_COMMANDS_PER_GUILD;

    private record QueuedWork(Runnable task, boolean command) {}
    private static final class GuildWorkQueue {
        final ArrayDeque<QueuedWork> tasks = new ArrayDeque<>();
        int pendingCommands;
        boolean running;
    }

    private final Set<String> names;
    protected final LanguageStore languages;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT);
    private final Object queueMonitor = new Object();
    private final Map<Long, GuildWorkQueue> guildQueues = new HashMap<>();
    private int pendingCommands;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    protected CommandListener(Set<String> names, LanguageStore languages) {
        this.names = names;
        this.languages = languages;
    }
    protected void serial(long guild, Runnable task) {
        enqueue(guild, task, false);
    }

    boolean serialCommand(long guild, Runnable task) {
        return enqueue(guild, task, true);
    }

    private boolean enqueue(long guild, Runnable task, boolean command) {
        synchronized (queueMonitor) {
            if (closed.get()) return false;
            if (command && pendingCommands >= MAX_PENDING_COMMANDS_TOTAL) return false;

            GuildWorkQueue queue = guildQueues.computeIfAbsent(guild, ignored -> new GuildWorkQueue());
            if (command && queue.pendingCommands >= MAX_PENDING_COMMANDS_PER_GUILD) return false;

            QueuedWork work = new QueuedWork(task, command);
            queue.tasks.addLast(work);
            if (command) {
                queue.pendingCommands++;
                pendingCommands++;
            }
            if (!queue.running) scheduleNext(guild, queue);
            return true;
        }
    }

    private void scheduleNext(long guild, GuildWorkQueue queue) {
        QueuedWork next = queue.tasks.peekFirst();
        if (next == null) {
            queue.running = false;
            guildQueues.remove(guild, queue);
            queueMonitor.notifyAll();
            return;
        }

        queue.running = true;
        try {
            workers.execute(() -> {
                try { next.task().run(); }
                catch (Exception ex) {
                    LoggerFactory.getLogger(getClass()).warn("Aktion fehlgeschlagen ({})", ex.getClass().getSimpleName());
                } finally {
                    finish(guild, queue, next);
                }
            });
        } catch (RejectedExecutionException ex) {
            queue.running = false;
            queue.tasks.removeFirst();
            releaseCommandSlot(queue, next);
            if (queue.tasks.isEmpty()) guildQueues.remove(guild, queue);
            queueMonitor.notifyAll();
            if (!closed.get()) throw ex;
        }
    }

    private void finish(long guild, GuildWorkQueue queue, QueuedWork completed) {
        synchronized (queueMonitor) {
            if (guildQueues.get(guild) != queue) return;
            queue.tasks.removeFirst();
            releaseCommandSlot(queue, completed);
            if (queue.tasks.isEmpty()) {
                queue.running = false;
                guildQueues.remove(guild, queue);
                queueMonitor.notifyAll();
            } else {
                scheduleNext(guild, queue);
            }
        }
    }

    private void releaseCommandSlot(GuildWorkQueue queue, QueuedWork work) {
        if (!work.command()) return;
        queue.pendingCommands--;
        pendingCommands--;
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
            try {
                boolean accepted = serialCommand(event.getGuild().getIdLong(), () -> {
                    try {
                        Language currentLanguage = language(event);
                        String message = null;
                        MessageEmbed embed = null;
                        List<ActionRow> components = List.of();
                        Runnable onDelivered = () -> {};
                        try {
                            var context = new CommandContext(event);
                            var view = handleView(context, currentLanguage);
                            if (view != null) {
                                embed = view.embed();
                                components = view.components();
                            } else {
                                embed = handleEmbed(event, currentLanguage);
                                if (embed == null) message = handle(event, currentLanguage);
                                components = replyComponents(context, currentLanguage);
                            }
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
                        if (!components.isEmpty()) reply.setComponents(components);
                        reply.setAllowedMentions(List.of()).queue(ignored -> delivered.run(),
                            failure -> interactionFailure(event, "response", failure));
                });
                if (!accepted) {
                    String message = Messages.text(initialLanguage, closed.get() ? "shutting.down" : "busy");
                    hook.editOriginal(message)
                        .queue(null, failure -> interactionFailure(event, "response", failure));
                }
            } catch (RejectedExecutionException ex) {
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
    protected record CommandView(MessageEmbed embed, List<ActionRow> components) {
        protected CommandView { components = List.copyOf(components); }
    }
    protected CommandView handleView(CommandContext event, Language language) { return null; }
    /** Optional controls for the initial response; defaults to none. */
    protected List<ActionRow> replyComponents(CommandContext event, Language language) { return List.of(); }
    /** Runs only after a successful command's response reaches Discord. */
    protected Runnable afterReply(SlashCommandInteractionEvent event) { return afterReply(new CommandContext(event)); }
    protected Runnable afterReply(CommandContext event) { return () -> {}; }
    boolean accepts(String name) { return names.contains(name); }
    void prefix(net.dv8tion.jda.api.events.message.MessageReceivedEvent message, String name, java.util.Map<String, String> options) {
        if (closed.get()) return;
        long guild = message.getGuild().getIdLong();
        try {
            serialCommand(guild, () -> {
                try {
                    Language language = languages.get(message.getGuild().getId());
                    var context = new CommandContext(message, name, options);
                    MessageEmbed embed = null;
                    String text = null;
                    List<ActionRow> components = List.of();
                    Runnable delivered = () -> {};
                    try {
                        var view = handleView(context, language);
                        if (view != null) {
                            embed = view.embed();
                            components = view.components();
                        } else {
                            embed = handleEmbed(context, language);
                            if (embed == null) text = handle(context, language);
                            components = replyComponents(context, language);
                        }
                        delivered = afterReply(context);
                    } catch (UserError ex) { text = ex.localized(language); }
                    catch (IllegalArgumentException ex) { text = Messages.text(language, "error.input"); }
                    catch (Exception ex) {
                        LoggerFactory.getLogger(getClass()).warn("Prefix command {} failed ({})", name, ex.getClass().getSimpleName());
                        text = Messages.text(language, "error.generic");
                    }
                    var reply = embed == null ? message.getChannel().sendMessage(clip(text, 1950)) : message.getChannel().sendMessageEmbeds(embed);
                    Runnable callback = delivered;
                    if (!components.isEmpty()) reply.setComponents(components);
                    reply.setAllowedMentions(List.of()).queue(ignored -> callback.run(),
                        ex -> LoggerFactory.getLogger(getClass()).warn("Prefix response failed ({})", ex.getClass().getSimpleName()));
            });
        } catch (RejectedExecutionException ignored) { }
    }
    protected static void require(boolean condition, String key, Object... args) { if (!condition) throw new UserError(key, args); }
    protected static String clip(String value, int max) { return value == null ? "—" : value.substring(0, Math.min(max, value.length())); }
    @Override public void close() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean forceStop = false;
        boolean interrupted = false;
        synchronized (queueMonitor) {
            if (!closed.compareAndSet(false, true)) return;
            while (!guildQueues.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    forceStop = true;
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(queueMonitor, remaining);
                } catch (InterruptedException ex) {
                    forceStop = true;
                    interrupted = true;
                    break;
                }
            }
            if (forceStop) {
                workers.shutdownNow();
                guildQueues.clear();
                pendingCommands = 0;
            } else {
                workers.shutdown();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                if (!workers.awaitTermination(2, TimeUnit.SECONDS))
                    LoggerFactory.getLogger(getClass()).warn("Command workers did not terminate within the shutdown deadline.");
            }
        } catch (InterruptedException ex) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
