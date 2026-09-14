package music;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.ApplicationTeam;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TeamMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.managers.Presence;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GeneralBotTest {
    private static final long OWNER = 123456789012345678L;
    private static final long CALLER = 223456789012345678L;
    private static final long TEAM_OWNER = 323456789012345678L;

    @TempDir Path directory;

    @Test void statsUsesTheCurrentProcessStartAndIsPublic() throws Exception {
        var now = Instant.parse("2026-09-11T10:00:00Z");
        var fixture = new Fixture();
        var languages = new LanguageStore(directory.resolve("stats.json"), Language.EN);
        try (var oldProcess = new GeneralBot(languages, new StatusRotation(), OWNER, null,
                now.minusSeconds(90_061), Clock.fixed(now, ZoneOffset.UTC));
             var freshProcess = new GeneralBot(languages, new StatusRotation(), OWNER, null,
                now, Clock.fixed(now, ZoneOffset.UTC))) {
            var event = fixture.event("stats", CALLER);
            var fields = freshProcess.handleEmbed(event, Language.EN).getFields();
            assertEquals("4", fields.get(1).getValue());
            assertEquals("106\n" + Messages.text(Language.EN, "stats.members.note"), fields.get(2).getValue());
            assertEquals(BotVersion.CURRENT, fields.get(3).getValue());
            fixture.sharded = true;
            assertEquals("106\n" + Messages.text(Language.EN, "stats.members.note"), freshProcess.handleEmbed(fixture.event("stats", CALLER), Language.EN).getFields().get(2).getValue());
            assertEquals("1d 1h 1m 1s", oldProcess.handleEmbed(event, Language.EN).getFields().getFirst().getValue());
            assertEquals("0s", freshProcess.handleEmbed(event, Language.EN).getFields().getFirst().getValue());
            assertEquals(0, fixture.ownerLookups);
            assertEquals(0, fixture.presenceAccesses.get());
        }
        assertEquals("0s", GeneralBot.sessionUptime(now, now.minusSeconds(1)));
        assertEquals("59s", GeneralBot.sessionUptime(now, now.plusSeconds(59)));
        assertEquals("1m 0s", GeneralBot.sessionUptime(now, now.plusSeconds(60)));
        assertEquals("1h 0m 0s", GeneralBot.sessionUptime(now, now.plusSeconds(3600)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void normalRotationUsesConfiguredIntervalAndDynamicShardCount(boolean sharded) throws Exception {
        var fixture = new Fixture();
        fixture.sharded = sharded;
        var timer = new StatusRotationTest.FakeScheduler();
        try (var bot = bot(timer.rotation())) {
            var jda = fixture.event("help", CALLER).getJDA();
            bot.refreshDefaultStatus(jda);
            assertEquals(OnlineStatus.ONLINE, fixture.lastStatus.get());
            assertEquals(Activity.ActivityType.PLAYING, fixture.lastActivity.get().getType());
            assertEquals("with my Besties! || 4 servers | 1 shards", fixture.lastActivity.get().getName());
            timer.advance(StatusRotation.DEFAULT_INTERVAL_MS - 1);
            assertEquals(OnlineStatus.ONLINE, fixture.lastStatus.get(), "Must not rotate before the interval");
            timer.advance(1);
            assertEquals(OnlineStatus.ONLINE, fixture.lastStatus.get());
            assertEquals(Activity.ActivityType.CUSTOM_STATUS, fixture.lastActivity.get().getType());
            assertEquals("At the Volleyball Training🏐 | (1)", fixture.lastActivity.get().getName());
            fixture.shardCount = 3;
            fixture.serverCount = 8;
            timer.advance(StatusRotation.DEFAULT_INTERVAL_MS);
            assertEquals(OnlineStatus.ONLINE, fixture.lastStatus.get());
            assertEquals("with my Besties! || 8 servers | 3 shards", fixture.lastActivity.get().getName());
            timer.advance(StatusRotation.DEFAULT_INTERVAL_MS);
            assertEquals("At the Volleyball Training🏐 | (3)", fixture.lastActivity.get().getName());
            assertEquals(Messages.text(Language.EN, "status.default"), bot.handle(fixture.event("status", OWNER), Language.EN));
            var beforeClose = fixture.presenceUpdates.get();
            bot.close();
            bot.refreshDefaultStatus(jda);
            timer.advance(60_000);
            assertEquals(beforeClose, fixture.presenceUpdates.get(), "Shutdown must not alter presence");
        }
    }

    @Test void ownerOverrideSurvivesReadyAndStopRestoresNormalPair() throws Exception {
        var fixture = new Fixture();
        var timer = new StatusRotationTest.FakeScheduler();
        try (var bot = bot(timer.rotation())) {
            var jda = fixture.event("help", CALLER).getJDA();
            bot.refreshDefaultStatus(jda);
            timer.advance(StatusRotation.DEFAULT_INTERVAL_MS);
            bot.handle(fixture.event("status", OWNER, Map.of("action", "start", "texts", "Owner text")), Language.EN);
            bot.refreshDefaultStatus(jda);
            timer.advance(StatusRotation.MIN_INTERVAL_MS);
            assertEquals(OnlineStatus.ONLINE, fixture.lastStatus.get());
            assertEquals("Owner text", fixture.lastActivity.get().getName());
            timer.advance(60_000);
            assertEquals("Owner text", fixture.lastActivity.get().getName());
            bot.handle(fixture.event("status", OWNER, Map.of("action", "stop")), Language.EN);
            assertEquals("with my Besties! || 4 servers | 1 shards", fixture.lastActivity.get().getName());
            timer.advance(StatusRotation.DEFAULT_INTERVAL_MS);
            assertEquals(OnlineStatus.ONLINE, fixture.lastStatus.get());
            assertEquals("At the Volleyball Training🏐 | (1)", fixture.lastActivity.get().getName());
        }
    }

    @Test void helpIsAnEmbedAndAvatarIsPublicWithAnIdLookup() throws Exception {
        var fixture = new Fixture();
        try (var bot = bot(new StatusRotation())) {
            assertEquals(4, bot.handleEmbed(fixture.event("help", CALLER), Language.EN).getFields().size());
            var avatar = bot.handleEmbed(fixture.event("avatar", CALLER, Map.of("user_id", " " + TEAM_OWNER + " ")), Language.EN);
            assertEquals(Long.toString(TEAM_OWNER), fixture.avatarLookup);
            assertEquals(ToriEmbeds.footer(Language.EN, "ID: " + TEAM_OWNER), avatar.getFooter().getText());
            assertTrue(avatar.getImage().getUrl().endsWith(".gif?size=1024"));
            assertTrue(avatar.getDescription().contains(avatar.getImage().getUrl()));
            assertEquals(0, fixture.ownerLookups);
        }
    }

    @Test void invalidAvatarIdsFailBeforeLookup() throws Exception {
        var fixture = new Fixture();
        try (var bot = bot(new StatusRotation())) {
            for (String id : List.of("", "abc", "123", "<@123456789012345678>", "99999999999999999999", "00000000000000000")) {
                assertThrows(UserError.class, () -> bot.handleEmbed(fixture.event("avatar", CALLER, Map.of("user_id", id)), Language.EN));
            }
            assertNull(fixture.avatarLookup);
        }
    }

    @Test void avatarUsesDefaultProfileImageWhenNoCustomAvatarExists() {
        var user = stub(User.class, (proxy, method, args) -> switch (method.getName()) {
            case "getEffectiveAvatarUrl" -> "https://cdn.discordapp.com/embed/avatars/0.png";
            case "getEffectiveName" -> "Default User";
            case "getId" -> Long.toString(CALLER);
            default -> throw unexpected(method);
        });
        assertEquals("https://cdn.discordapp.com/embed/avatars/0.png?size=1024",
            GeneralBot.avatarEmbed(user, Language.EN).getImage().getUrl());
    }

    @Test void restartRequiresConfiguredOwnerEvenForAnAdministratorOrApplicationOwner() throws Exception {
        var fixture = new Fixture();
        fixture.guildOwnerAndAdministrator = true;
        var restarts = new AtomicInteger();
        try (var bot = new GeneralBot(new LanguageStore(directory.resolve("restart.json"), Language.EN),
                new StatusRotation(), CALLER, restarts::incrementAndGet)) {
            assertThrows(UserError.class, () -> bot.handle(fixture.event("restart", OWNER), Language.EN));
            var event = fixture.event("restart", CALLER);
            assertEquals(Messages.text(Language.EN, "restart.started"), bot.handle(event, Language.EN));
            assertEquals(0, restarts.get(), "Must wait for reply delivery before restarting");
            bot.afterReply(event).run();
            assertEquals(1, restarts.get());
            assertEquals(0, fixture.ownerLookups, "Configured ID is authoritative");
        }
    }

    @Test void restartFailsClosedWhenOwnerIsNotConfigured() throws Exception {
        var fixture = new Fixture();
        try (var bot = new GeneralBot(new LanguageStore(directory.resolve("missing.properties"), Language.EN), new StatusRotation())) {
            var error = assertThrows(UserError.class, () -> bot.handle(fixture.event("restart", OWNER), Language.EN));
            assertEquals(Messages.text(Language.EN, "owner.unconfigured"), error.localized(Language.EN));
            assertEquals(0, fixture.ownerLookups);
        }
    }

    @Test void configuredOwnerAlsoControlsStatus() throws Exception {
        var fixture = new Fixture();
        try (var bot = new GeneralBot(new LanguageStore(directory.resolve("owner.json"), Language.EN),
                new StatusRotation(), CALLER, () -> {})) {
            assertThrows(UserError.class, () -> bot.handle(fixture.event("status", OWNER), Language.EN));
            assertEquals(Messages.text(Language.EN, "status.inactive"),
                bot.handle(fixture.event("status", CALLER), Language.EN));
            assertEquals(0, fixture.ownerLookups);
        }
    }

    @Test void pingIsPublicAndReportsDistinctWebSocketAndRestMilliseconds() throws Exception {
        var fixture = new Fixture();
        try (var bot = bot(new StatusRotation())) {
            String response = bot.handle(fixture.event("ping", CALLER), Language.EN);
            assertTrue(response.contains("WebSocket: 0 s"), response);
            assertTrue(response.contains("Bot (REST): 0 s"), response);
            assertEquals(1, fixture.restPingRequests);
            assertEquals(0, fixture.ownerLookups, "Public ping must not depend on application ownership");
            assertEquals(0, fixture.presenceAccesses.get());
        }
    }

    @Test void pingDoesNotReportAnUnknownGatewayLatencyAsNegativeMilliseconds() throws Exception {
        var fixture = new Fixture();
        fixture.gatewayPing = -1;
        try (var bot = bot(new StatusRotation())) {
            String response = bot.handle(fixture.event("ping", CALLER), Language.EN);
            assertFalse(response.contains("-1"), response);
            assertTrue(response.contains(Messages.text(Language.EN, "ping.unavailable")), response);
            assertTrue(response.contains("0 s"), response);
            assertEquals(1, fixture.restPingRequests);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {OWNER, TEAM_OWNER})
    void configuredOwnerCanStartInspectAndStopRotation(long caller) throws Exception {
        var fixture = new Fixture();
        if (caller == TEAM_OWNER) fixture.teamOwner = TEAM_OWNER;
        var rotation = new StatusRotation();
        try (var bot = new GeneralBot(new LanguageStore(directory.resolve("configured.properties"), Language.EN), rotation, caller, null)) {
            assertEquals(Messages.text(Language.EN, "status.started", 2, 2 * StatusRotation.MIN_INTERVAL_MS),
                bot.handle(fixture.event("status", caller,
                    Map.of("action", "start", "texts", " Music time | Use /help ", "interval_ms", 2 * StatusRotation.MIN_INTERVAL_MS)), Language.EN));
            assertTrue(rotation.snapshot().running());
            assertEquals(List.of("Music time", "Use /help"), rotation.snapshot().texts());
            assertEquals(2 * StatusRotation.MIN_INTERVAL_MS, rotation.snapshot().intervalMs());
            assertNotNull(fixture.lastActivity.get());
            assertEquals(Activity.ActivityType.CUSTOM_STATUS, fixture.lastActivity.get().getType());
            assertEquals("Music time", fixture.lastActivity.get().getName());

            var running = rotation.snapshot();
            assertEquals(Messages.text(Language.EN, "status.running", 2, 2 * StatusRotation.MIN_INTERVAL_MS),
                bot.handle(fixture.event("status", caller), Language.EN));
            assertEquals(running, rotation.snapshot(), "Showing rotation must preserve its settings");

            assertEquals(Messages.text(Language.EN, "status.stopped"),
                bot.handle(fixture.event("status", caller, Map.of("action", "stop")), Language.EN));
            assertTrue(rotation.snapshot().running());
            assertEquals(Messages.text(Language.EN, "status.default"),
                bot.handle(fixture.event("status", caller, Map.of("action", "show")), Language.EN));
            assertEquals(0, fixture.ownerLookups);
            assertEquals(0, fixture.restPingRequests, "Status controls presence and must not request REST latency");
        }
    }

    @Test void startWithoutIntervalUsesConfiguredDefault() throws Exception {
        var fixture = new Fixture();
        var rotation = new StatusRotation();
        try (var bot = bot(rotation)) {
            assertEquals(Messages.text(Language.EN, "status.started", 2, StatusRotation.DEFAULT_INTERVAL_MS),
                bot.handle(fixture.event("status", OWNER,
                    Map.of("action", "start", "texts", "Music | Use /help")), Language.EN));
            assertTrue(rotation.snapshot().running());
            assertEquals(StatusRotation.DEFAULT_INTERVAL_MS, rotation.snapshot().intervalMs());
        }
    }

    @Test void statusWithoutOptionsShowsAnInactiveRotationWithoutAccessingPresence() throws Exception {
        var fixture = new Fixture();
        fixture.presenceAllowed = false;
        try (var bot = bot(new StatusRotation())) {
            assertEquals(Messages.text(Language.EN, "status.inactive"),
                bot.handle(fixture.event("status", OWNER), Language.EN));
            assertEquals(0, fixture.ownerLookups);
            assertEquals(0, fixture.presenceAccesses.get());
            assertEquals(0, fixture.restPingRequests);
        }
    }

    @Test void closingGeneralBotStopsItsRotation() throws Exception {
        var rotation = new StatusRotation();
        try (var bot = bot(rotation)) {
            rotation.start(List.of("One", "Two"), StatusRotation.MIN_INTERVAL_MS + StatusRotation.MIN_INTERVAL_MS / 2, ignored -> {});
            assertTrue(rotation.snapshot().running());
        }
        assertFalse(rotation.snapshot().running());
    }

    @Test void statusRejectsNonOwnerForEveryActionBeforePresenceAccess() throws Exception {
        assertAllStatusActionsRejected(new Fixture(), CALLER);
    }

    @Test void guildOwnershipAndAdministratorPermissionDoNotBypassApplicationOwnership() throws Exception {
        var fixture = new Fixture();
        fixture.guildOwnerAndAdministrator = true;
        assertAllStatusActionsRejected(fixture, CALLER);
    }

    @Test void statusRejectsRegularTeamMemberForEveryAction() throws Exception {
        var fixture = new Fixture();
        fixture.teamOwner = TEAM_OWNER;
        assertAllStatusActionsRejected(fixture, CALLER);
    }

    @Test void statusDoesNotTreatTeamApplicationOwnerFieldAsAnAdditionalOwner() throws Exception {
        var fixture = new Fixture();
        fixture.teamOwner = TEAM_OWNER;
        assertAllStatusActionsRejected(fixture, TEAM_OWNER);
    }

    @Test void missingConfiguredOwnerNeverChangesRotationOrAccessesPresence() throws Exception {
        var fixture = new Fixture();
        fixture.presenceAllowed = false;
        fixture.ownerLookupFailure = new IllegalStateException("Application lookup unavailable");
        var rotation = new StatusRotation();
        rotation.start(List.of("Existing one", "Existing two"), 2 * StatusRotation.MIN_INTERVAL_MS, ignored -> {});
        try (var bot = new GeneralBot(new LanguageStore(directory.resolve("missing.properties"), Language.EN), rotation)) {
            var original = rotation.snapshot();
            for (String action : List.of("start", "stop", "show")) {
                assertThrows(RuntimeException.class,
                    () -> bot.handle(fixture.event("status", OWNER, actionOptions(action)), Language.EN));
                assertEquals(original, rotation.snapshot());
            }
            assertEquals(0, fixture.ownerLookups);
            assertEquals(0, fixture.presenceAccesses.get());
            assertEquals(0, fixture.restPingRequests);
        }
    }

    @Test void startRequiresTextsWithoutReplacingExistingRotation() throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", "start"), "status.texts.required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " | | ", "\n |\n"})
    void startRejectsMissingOrEmptyEntriesWithoutReplacingExistingRotation(String texts) throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", "start", "texts", texts),
            "status.texts.required");
    }

    @Test void ownerCanSetTheReportedSingleStatusAndInspectIt() throws Exception {
        var fixture = new Fixture();
        var rotation = new StatusRotation();
        try (var bot = bot(rotation)) {
            assertEquals(Messages.text(Language.EN, "status.single.started"), bot.handle(fixture.event("status", OWNER,
                Map.of("action", "start", "texts", "Paying w my bbfs!")), Language.EN));
            assertEquals(List.of("Paying w my bbfs!"), rotation.snapshot().texts());
            assertEquals("Paying w my bbfs!", fixture.lastActivity.get().getName());
            assertEquals(Messages.text(Language.EN, "status.single.running"),
                bot.handle(fixture.event("status", OWNER), Language.EN));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"One | | Two", "One | Two |", " | One | Two", "One\r\nTwo"})
    void ownerCanStartRotationWithExtraSeparatorsOrLineBreaks(String texts) throws Exception {
        var fixture = new Fixture();
        var rotation = new StatusRotation();
        try (var bot = bot(rotation)) {
            bot.handle(fixture.event("status", OWNER, Map.of("action", "start", "texts", texts)), Language.EN);
            assertEquals(List.of("One", "Two"), rotation.snapshot().texts());
        }
    }

    @Test void startRejectsTooManyTextsWithoutReplacingExistingRotation() throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", "start", "texts",
            String.join(" | ", java.util.Collections.nCopies(11, "Text"))), "status.texts.too_many");
    }

    @Test void startRejectsOverlongTextsWithoutReplacingExistingRotation() throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", "start", "texts", "x".repeat(129) + " | Fine"),
            "status.texts.too_long");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, StatusRotation.MIN_INTERVAL_MS - 1, 3_600_001L, Long.MAX_VALUE})
    void startRejectsUnsafeIntervalsWithoutReplacingExistingRotation(long interval) throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", "start", "texts", "One | Two", "interval_ms", interval),
            "status.interval");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "show"})
    void stopAndShowRejectStartOnlyOptionsWithoutChangingRotation(String action) throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", action, "texts", "One | Two"), "status.options");
        assertInvalidOptionsPreserveRotation(Map.of("action", action, "interval_ms", StatusRotation.MIN_INTERVAL_MS + StatusRotation.MIN_INTERVAL_MS / 2), "status.options");
    }

    @Test void defaultShowRejectsStartOnlyOptionsWithoutChangingRotation() throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("texts", "One | Two"), "status.options");
        assertInvalidOptionsPreserveRotation(Map.of("interval_ms", StatusRotation.MIN_INTERVAL_MS + StatusRotation.MIN_INTERVAL_MS / 2), "status.options");
    }

    @Test void unknownActionDoesNotChangeRotation() throws Exception {
        assertInvalidOptionsPreserveRotation(Map.of("action", "unknown"), "status.action");
    }

    private void assertAllStatusActionsRejected(Fixture fixture, long caller) throws Exception {
        fixture.presenceAllowed = false;
        var rotation = new StatusRotation();
        rotation.start(List.of("Existing one", "Existing two"), 2 * StatusRotation.MIN_INTERVAL_MS, ignored -> {});
        try (var bot = bot(rotation)) {
            var original = rotation.snapshot();
            for (String action : List.of("start", "stop", "show")) {
                var error = assertThrows(UserError.class,
                    () -> bot.handle(fixture.event("status", caller, actionOptions(action)), Language.EN));
                assertEquals("owner.only", error.getMessage());
                assertEquals(original, rotation.snapshot(), "Unauthorized " + action + " changed the rotation");
            }
            assertEquals(0, fixture.ownerLookups);
            assertEquals(0, fixture.presenceAccesses.get());
            assertEquals(0, fixture.restPingRequests);
        }
    }

    private void assertInvalidOptionsPreserveRotation(Map<String, ?> options, String errorKey) throws Exception {
        var fixture = new Fixture();
        fixture.presenceAllowed = false;
        var rotation = new StatusRotation();
        rotation.start(List.of("Existing one", "Existing two"), 2 * StatusRotation.MIN_INTERVAL_MS, ignored -> {});
        try (var bot = bot(rotation)) {
            var original = rotation.snapshot();
            var error = assertThrows(UserError.class,
                () -> bot.handle(fixture.event("status", OWNER, options), Language.EN));
            assertEquals(errorKey, error.getMessage());
            assertEquals(original, rotation.snapshot());
            assertEquals(0, fixture.presenceAccesses.get());
            assertEquals(0, fixture.restPingRequests);
        }
    }

    private GeneralBot bot(StatusRotation rotation) throws Exception {
        return new GeneralBot(new LanguageStore(directory.resolve("languages.properties"), Language.EN), rotation, OWNER, null);
    }

    private static Map<String, ?> actionOptions(String action) {
        return action.equals("start")
            ? Map.of("action", action, "texts", "Replacement one | Replacement two", "interval_ms", StatusRotation.MIN_INTERVAL_MS + StatusRotation.MIN_INTERVAL_MS / 2)
            : Map.of("action", action);
    }

    /** Public JDA interfaces are faked; options use JDA's real parser without a Discord connection. */
    private static final class Fixture {
        String avatarLookup;
        long gatewayPing = 37;
        Long teamOwner;
        boolean presenceAllowed = true;
        boolean guildOwnerAndAdministrator;
        RuntimeException ownerLookupFailure;
        int ownerLookups;
        int restPingRequests;
        final AtomicInteger presenceAccesses = new AtomicInteger();
        final AtomicInteger presenceUpdates = new AtomicInteger();
        final AtomicReference<Activity> lastActivity = new AtomicReference<>();
        final AtomicReference<OnlineStatus> lastStatus = new AtomicReference<>();
        boolean sharded;
        long serverCount = 4;
        int shardCount = 1;

        SlashCommandInteractionEvent event(String command, long callerId) {
            return event(command, callerId, Map.of());
        }

        SlashCommandInteractionEvent event(String command, long callerId, Map<String, ?> values) {
            var options = values.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> option(entry.getKey(), entry.getValue())));
            var presence = stub(Presence.class, (proxy, method, args) -> switch (method.getName()) {
                case "setPresence" -> {
                    assertTrue(presenceAllowed, "Presence must only change after successful owner authorization");
                    lastStatus.set((OnlineStatus) args[0]);
                    lastActivity.set((Activity) args[1]);
                    presenceUpdates.incrementAndGet();
                    yield null;
                }
                default -> throw unexpected(method);
            });
            var guilds = stub(SnowflakeCacheView.class, (proxy, method, args) -> switch (method.getName()) {
                case "size" -> serverCount;
                case "asList" -> java.util.stream.IntStream.range(0, (int) serverCount)
                    .mapToObj(index -> stub(Guild.class, (p, m, a) -> switch (m.getName()) {
                        case "getMemberCount" -> 25 + index;
                        default -> throw unexpected(m);
                    })).toList();
                default -> throw unexpected(method);
            });
            var manager = stub(ShardManager.class, (proxy, method, args) -> switch (method.getName()) {
                case "getGuildCache" -> guilds;
                case "setPresence" -> {
                    assertTrue(presenceAllowed);
                    presenceUpdates.incrementAndGet();
                    lastStatus.set((OnlineStatus) args[0]);
                    lastActivity.set((Activity) args[1]);
                    yield null;
                }
                default -> throw unexpected(method);
            });
            var jda = stub(JDA.class, (proxy, method, args) -> switch (method.getName()) {
                case "getShardManager" -> sharded ? manager : null;
                case "getGuildCache" -> guilds;
                case "getShardInfo" -> new JDA.ShardInfo(0, shardCount);
                case "retrieveUserById" -> {
                    avatarLookup = (String) args[0];
                    yield stub(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class, (p, m, a) -> switch (m.getName()) {
                        case "timeout" -> p;
                        case "complete" -> user(Long.parseLong(avatarLookup));
                        default -> throw unexpected(m);
                    });
                }
                case "retrieveApplicationInfo" -> {
                    ownerLookups++;
                    yield action(applicationInfo(), ownerLookupFailure);
                }
                case "getPresence" -> {
                    presenceAccesses.incrementAndGet();
                    assertTrue(presenceAllowed, "Presence must only be accessed after successful owner authorization");
                    yield presence;
                }
                case "getGatewayPing" -> gatewayPing;
                case "getRestPing" -> {
                    restPingRequests++;
                    yield action(84L, null);
                }
                default -> throw unexpected(method);
            });
            var member = stub(Member.class, (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission", "isOwner" -> guildOwnerAndAdministrator;
                case "getUser" -> user(callerId);
                default -> throw unexpected(method);
            });
            var guild = stub(Guild.class, (proxy, method, args) -> switch (method.getName()) {
                case "getOwnerIdLong" -> guildOwnerAndAdministrator ? callerId : OWNER;
                case "getOwnerId" -> Long.toString(guildOwnerAndAdministrator ? callerId : OWNER);
                case "getMember" -> member;
                case "retrieveMemberById" -> action(member, null);
                default -> throw unexpected(method);
            });
            var interaction = stub(SlashCommandInteraction.class, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> command;
                case "getUser" -> user(callerId);
                case "getMember" -> member;
                case "getGuild" -> guild;
                case "getJDA" -> jda;
                case "getOptions" -> List.copyOf(options.values());
                case "getOption" -> args.length == 1 ? options.get(args[0]) : InvocationHandler.invokeDefault(proxy, method, args);
                default -> throw unexpected(method);
            });
            return new SlashCommandInteractionEvent(jda, 0, interaction);
        }

        private ApplicationInfo applicationInfo() {
            return stub(ApplicationInfo.class, (proxy, method, args) -> switch (method.getName()) {
                case "getOwner" -> user(OWNER);
                case "getTeam" -> teamOwner == null ? null : team();
                default -> throw unexpected(method);
            });
        }

        private ApplicationTeam team() {
            return stub(ApplicationTeam.class, (proxy, method, args) -> switch (method.getName()) {
                case "getOwnerIdLong" -> teamOwner;
                case "getOwnerId" -> Long.toString(teamOwner);
                case "getOwner" -> null; // JDA permits an owner ID without a cached owner member.
                case "getMembers" -> List.of(teamMember(CALLER));
                default -> throw unexpected(method);
            });
        }
    }

    private static OptionMapping option(String name, Object value) {
        var type = value instanceof Number ? OptionType.INTEGER : OptionType.STRING;
        var data = DataObject.empty().put("name", name).put("type", type.getKey()).put("value", value);
        try {
            // JDA exposes a Trove parameter in this constructor but supplies Trove only at runtime.
            var mapType = Class.forName("gnu.trove.map.TLongObjectMap");
            var resolved = Class.forName("gnu.trove.map.hash.TLongObjectHashMap").getConstructor().newInstance();
            return OptionMapping.class.getConstructor(DataObject.class, mapType, JDA.class, Guild.class)
                .newInstance(data, resolved, null, null);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Could not construct a JDA command option", ex);
        }
    }

    private static User user(long id) {
        return stub(User.class, (proxy, method, args) -> switch (method.getName()) {
            case "getEffectiveName" -> "Test User";
            case "getEffectiveAvatarUrl" -> "https://cdn.discordapp.com/avatars/" + id + "/a_test.gif";
            case "getIdLong" -> id;
            case "getId" -> Long.toString(id);
            default -> throw unexpected(method);
        });
    }

    private static TeamMember teamMember(long id) {
        return stub(TeamMember.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUser" -> user(id);
            case "getMembershipState" -> TeamMember.MembershipState.ACCEPTED;
            default -> throw unexpected(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> RestAction<T> action(T value, RuntimeException failure) {
        return (RestAction<T>) stub(RestAction.class, (proxy, method, args) -> switch (method.getName()) {
            case "timeout", "deadline" -> proxy;
            case "complete" -> {
                if (failure != null) throw failure;
                yield value;
            }
            default -> throw unexpected(method);
        });
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
