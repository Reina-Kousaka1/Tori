package music;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class CommandListenerTest {
    @TempDir Path directory;

    @Test void acknowledgementDoesNotWaitForLanguageStoreMonitor() throws Exception {
        var fixture = new InteractionFixture();
        var languages = languages();
        var caller = Executors.newSingleThreadExecutor();
        try (var listener = listener(languages, new AtomicInteger(), new AtomicInteger())) {
            synchronized (languages) {
                var dispatch = caller.submit(() -> listener.onSlashCommandInteraction(fixture.event()));
                dispatch.get(5, TimeUnit.SECONDS);
                assertEquals(1, fixture.acknowledgements.get(),
                    "The initial ACK must be queued even while another thread owns the language store lock");
            }
            assertNotNull(fixture.acknowledgementFailure.get());
        } finally {
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void commandWaitsForAcknowledgementAndDeliversPublicReply() throws Exception {
        var fixture = new InteractionFixture();
        var handled = new AtomicInteger();
        var delivered = new AtomicInteger();
        try (var listener = listener(languages(), handled, delivered)) {
            listener.onSlashCommandInteraction(fixture.event());

            assertEquals(0, handled.get(), "Command side effects must wait until Discord accepts the ACK");
            assertEquals(Boolean.FALSE, fixture.deferredEphemeral.get(), "Guild command responses stay public");
            fixture.acceptAcknowledgement();

            assertTrue(fixture.replyQueued.await(5, TimeUnit.SECONDS));
            assertEquals(1, handled.get());
            assertEquals("result EN", fixture.response.get());
            assertEquals(List.of(), fixture.allowedMentions.get());
            assertNotNull(fixture.replyFailure.get(), "Final responses need a failure callback");
            assertEquals(0, delivered.get(), "Delivery-dependent actions must wait for the final response");
            fixture.acceptReply();
            assertEquals(1, delivered.get());
        }
    }

    @Test void failedAcknowledgementDoesNotExecuteOrRetryCommand() throws Exception {
        var fixture = new InteractionFixture();
        var handled = new AtomicInteger();
        var delivered = new AtomicInteger();
        try (var listener = listener(languages(), handled, delivered)) {
            listener.onSlashCommandInteraction(fixture.event());

            assertNotNull(fixture.acknowledgementFailure.get(), "ACK failures must be handled explicitly");
            assertDoesNotThrow(() -> fixture.acknowledgementFailure.get()
                .accept(new IllegalStateException("Acknowledgement expired")));
            listener.close();

            assertEquals(0, handled.get());
            assertEquals(0, delivered.get());
            assertEquals(1, fixture.acknowledgements.get(), "An expired interaction must not be retried");
            assertEquals(0, fixture.replies.get());
            assertEquals(0, fixture.directReplies.get());
        }
    }

    @Test void failedFinalReplyDoesNotRunDeliveryDependentAction() throws Exception {
        var fixture = new InteractionFixture();
        var handled = new AtomicInteger();
        var delivered = new AtomicInteger();
        try (var listener = listener(languages(), handled, delivered)) {
            listener.onSlashCommandInteraction(fixture.event());
            fixture.acceptAcknowledgement();

            assertTrue(fixture.replyQueued.await(5, TimeUnit.SECONDS));
            assertNotNull(fixture.replyFailure.get());
            assertDoesNotThrow(() -> fixture.replyFailure.get()
                .accept(new IllegalStateException("Reply unavailable")));
            listener.close();

            assertEquals(1, handled.get());
            assertEquals(0, delivered.get());
            assertEquals(1, fixture.replies.get());
        }
    }

    @Test void alreadyAcknowledgedInteractionIsIgnored() throws Exception {
        var fixture = new InteractionFixture();
        fixture.acknowledged.set(true);
        var handled = new AtomicInteger();
        try (var listener = listener(languages(), handled, new AtomicInteger())) {
            listener.onSlashCommandInteraction(fixture.event());
            listener.close();

            assertEquals(0, fixture.acknowledgements.get());
            assertEquals(0, fixture.directReplies.get());
            assertEquals(0, handled.get());
        }
    }

    @Test void directMessageRejectionRemainsEphemeral() throws Exception {
        var fixture = new InteractionFixture();
        fixture.inGuild = false;
        var handled = new AtomicInteger();
        try (var listener = listener(languages(), handled, new AtomicInteger())) {
            listener.onSlashCommandInteraction(fixture.event());

            assertEquals(0, fixture.acknowledgements.get());
            assertEquals(1, fixture.directReplies.get());
            assertEquals(Boolean.TRUE, fixture.directEphemeral.get());
            assertEquals(Messages.text(Language.EN, "server.only"), fixture.response.get());
            assertNotNull(fixture.replyFailure.get());
            assertEquals(0, handled.get());
        }
    }

    @Test void closeDrainsRunningAndQueuedWorkBeforeReturning() throws Exception {
        var listener = listener();
        var workerStarted = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var interrupted = new AtomicBoolean();
        var closer = Executors.newSingleThreadExecutor();
        try {
            listener.serial(1, () -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                    completed.incrementAndGet();
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "First task must be running");
            listener.serial(1, completed::incrementAndGet);

            var closed = closer.submit(() -> {
                closeStarted.countDown();
                listener.close();
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertFalse(closed.isDone(), "Close must wait for accepted work");
            releaseWorker.countDown();
            closed.get(5, TimeUnit.SECONDS);

            assertEquals(2, completed.get(), "Running and queued tasks must finish before close returns");
            assertFalse(interrupted.get(), "Work completing within the grace period must not be interrupted");
        } finally {
            releaseWorker.countDown();
            listener.close();
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void lateEventSubmissionsAfterCloseAreIgnored() throws Exception {
        try (var listener = listener()) {
            listener.close();
            var executed = new AtomicBoolean();

            assertDoesNotThrow(() -> listener.serial(1, () -> executed.set(true)));

            assertFalse(executed.get());
            assertDoesNotThrow(listener::close);
        }
    }

    private CommandListener listener() throws Exception {
        return new CommandListener(Set.of("test"),
                new LanguageStore(directory.resolve("languages.properties"), Language.EN)) {
            @Override protected String handle(SlashCommandInteractionEvent event, Language language) {
                return "test";
            }
        };
    }

    private LanguageStore languages() throws Exception {
        return new LanguageStore(directory.resolve("languages.properties"), Language.EN);
    }

    private CommandListener listener(LanguageStore languages, AtomicInteger handled, AtomicInteger delivered) {
        return new CommandListener(Set.of("test"), languages) {
            @Override protected String handle(SlashCommandInteractionEvent event, Language language) {
                handled.incrementAndGet();
                return "result " + language.name();
            }
            @Override protected Runnable afterReply(SlashCommandInteractionEvent event) {
                return delivered::incrementAndGet;
            }
        };
    }

    /** Captures REST callbacks so tests decide when Discord accepts each request. */
    private static final class InteractionFixture {
        boolean inGuild = true;
        final AtomicBoolean acknowledged = new AtomicBoolean();
        final AtomicInteger acknowledgements = new AtomicInteger();
        final AtomicInteger replies = new AtomicInteger();
        final AtomicInteger directReplies = new AtomicInteger();
        final AtomicReference<Boolean> deferredEphemeral = new AtomicReference<>();
        final AtomicReference<Boolean> directEphemeral = new AtomicReference<>();
        final AtomicReference<String> response = new AtomicReference<>();
        final AtomicReference<Object> allowedMentions = new AtomicReference<>();
        final AtomicReference<Consumer<Object>> acknowledgementSuccess = new AtomicReference<>();
        final AtomicReference<Consumer<Throwable>> acknowledgementFailure = new AtomicReference<>();
        final AtomicReference<Consumer<Object>> replySuccess = new AtomicReference<>();
        final AtomicReference<Consumer<Throwable>> replyFailure = new AtomicReference<>();
        final CountDownLatch replyQueued = new CountDownLatch(1);
        InteractionHook hook;

        SlashCommandInteractionEvent event() {
            var jda = stub(JDA.class, (proxy, method, args) -> {
                throw unexpected(method);
            });
            var guild = stub(Guild.class, (proxy, method, args) -> switch (method.getName()) {
                case "getIdLong" -> 123456789012345678L;
                case "getId" -> "123456789012345678";
                default -> throw unexpected(method);
            });
            var edit = stub(WebhookMessageEditAction.class, (proxy, method, args) -> switch (method.getName()) {
                case "setAllowedMentions" -> {
                    allowedMentions.set(args[0]);
                    yield proxy;
                }
                case "queue" -> {
                    replies.incrementAndGet();
                    captureCallbacks(args, replySuccess, replyFailure);
                    replyQueued.countDown();
                    yield null;
                }
                default -> throw unexpected(method);
            });
            hook = stub(InteractionHook.class, (proxy, method, args) -> switch (method.getName()) {
                case "editOriginal" -> {
                    response.set((String) args[0]);
                    yield edit;
                }
                default -> throw unexpected(method);
            });
            var defer = stub(ReplyCallbackAction.class, (proxy, method, args) -> switch (method.getName()) {
                case "setEphemeral" -> {
                    deferredEphemeral.set((Boolean) args[0]);
                    yield proxy;
                }
                case "queue" -> {
                    acknowledgements.incrementAndGet();
                    captureCallbacks(args, acknowledgementSuccess, acknowledgementFailure);
                    yield null;
                }
                default -> throw unexpected(method);
            });
            var direct = stub(ReplyCallbackAction.class, (proxy, method, args) -> switch (method.getName()) {
                case "setContent" -> {
                    response.set((String) args[0]);
                    yield proxy;
                }
                case "setEphemeral" -> {
                    directEphemeral.set((Boolean) args[0]);
                    yield proxy;
                }
                case "queue" -> {
                    directReplies.incrementAndGet();
                    captureCallbacks(args, replySuccess, replyFailure);
                    yield null;
                }
                default -> throw unexpected(method);
            });
            var interaction = stub(SlashCommandInteraction.class, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "test";
                case "getIdLong" -> 223456789012345678L;
                case "getId" -> "223456789012345678";
                case "getGuild" -> inGuild ? guild : null;
                case "getJDA" -> jda;
                case "isAcknowledged" -> acknowledged.get();
                case "getUserLocale" -> DiscordLocale.ENGLISH_US;
                case "deferReply" -> inGuild ? defer : direct;
                case "reply" -> {
                    response.set((String) args[0]);
                    yield direct;
                }
                default -> throw unexpected(method);
            });
            return new SlashCommandInteractionEvent(jda, 0, interaction);
        }

        void acceptAcknowledgement() {
            assertNotNull(acknowledgementSuccess.get());
            acknowledged.set(true);
            acknowledgementSuccess.get().accept(hook);
        }

        void acceptReply() {
            assertNotNull(replySuccess.get());
            replySuccess.get().accept(null);
        }

        @SuppressWarnings("unchecked")
        private static void captureCallbacks(Object[] args, AtomicReference<Consumer<Object>> success,
                AtomicReference<Consumer<Throwable>> failure) {
            if (args != null && args.length > 0) success.set((Consumer<Object>) args[0]);
            if (args != null && args.length > 1) failure.set((Consumer<Throwable>) args[1]);
        }
    }

    private static <T> T stub(Class<T> type, InvocationHandler behavior) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Test " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw unexpected(method);
                };
            }
            return behavior.invoke(proxy, method, args);
        }));
    }

    private static AssertionError unexpected(Method method) {
        return new AssertionError("Unexpected JDA call: " + method);
    }
}
