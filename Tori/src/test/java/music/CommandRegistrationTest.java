package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-secret-token";
    private static final String APPLICATION = "123456789012345678";
    private static final String GUILD = "223456789012345678";
    private static final String ENDPOINT = "https://discord.com/api/v10/applications/" + APPLICATION + "/commands";
    private static final Set<String> NAMES = Set.of("repeat", "prefix", "play", "lyrics", "skip", "pause", "resume", "queue", "stop", "leave", "volume",
        "kick", "ban", "unban", "timeout", "untimeout", "purge", "slowmode", "language", "help", "ping", "stats", "status", "restart", "shutdown", "uptime", "snipe", "avatar", "order", "ticket",
        "balance", "daily", "shop", "buy", "sell", "inventory", "equip", "tools", "fish", "mine", "chop", "craft", "repair", "opencrate",
        "market", "iteminfo", "pricehistory", "beg", "work", "loot", "transfer", "gamble", "slots",
        "leaderboard", "grantcredits", "grantitem", "unequip");

    @Test void globalRegistrationUpsertsTheJavaCatalogWithoutReplacingOtherCommands() throws Exception {
        var transport = new RecordingTransport();
        String output = capture(() -> CommandRegistration.register(TOKEN, " ", transport));
        assertEquals(2 + NAMES.size(), transport.requests.size());
        assertEquals(List.of("GET", "GET"), transport.requests.subList(0, 2).stream().map(Request::method).toList());
        assertEquals("https://discord.com/api/v10/oauth2/applications/@me", transport.requests.getFirst().uri().toString());
        assertEquals(ENDPOINT, transport.requests.get(1).uri().toString());
        assertNull(transport.requests.getFirst().body());

        var payload = JSON.createArrayNode();
        for (var request : transport.requests.subList(2, transport.requests.size())) {
            assertEquals("POST", request.method());
            assertEquals(ENDPOINT, request.uri().toString());
            assertEquals(TOKEN, request.token());
            payload.add(JSON.readTree(request.body()));
        }

        assertEquals(57, payload.size());
        Set<String> names = new HashSet<>();
        for (var command : payload) {
            names.add(command.path("name").asText());
            assertEquals(1, command.path("type").asInt());
            assertEquals(1, command.path("contexts").size());
            assertEquals(0, command.path("contexts").get(0).asInt(-1));
            assertTrue(command.path("description_localizations").has("en-US"));
            assertTrue(command.path("description_localizations").has("en-GB"));
            assertTrue(command.path("description_localizations").has("de"));
            assertTrue(command.path("description_localizations").has("nl"));
        }
        assertEquals(NAMES, names);
        assertFalse(names.contains("career"));
        assertFalse(names.contains("profile"));

        JsonNode status = named(payload, "status");
        assertEquals(Set.of("action", "texts", "interval_ms", "activity", "special_event", "ttl_seconds"),
            names(status.path("options")));
        assertEquals(Set.of("start", "stop", "show", "activity"),
            values(named(status.path("options"), "action").path("choices")));
        assertEquals(Set.of("general", "school", "ballet", "volleyball", "cheer", "resting"),
            values(named(status.path("options"), "activity").path("choices")));
        var interval = named(status.path("options"), "interval_ms");
        assertEquals(StatusRotation.MIN_INTERVAL_MS, interval.path("min_value").asLong());
        assertEquals(StatusRotation.MAX_INTERVAL_MS, interval.path("max_value").asLong());
        assertEquals(64, named(status.path("options"), "special_event").path("max_length").asInt());
        var ttl = named(status.path("options"), "ttl_seconds");
        assertEquals(60, ttl.path("min_value").asLong());
        assertEquals(604800, ttl.path("max_value").asLong());
        for (String locale : List.of("en-US", "en-GB", "de", "nl")) {
            assertTrue(status.path("description_localizations").has(locale), "Missing /status locale " + locale);
            for (String option : List.of("action", "texts", "interval_ms", "activity", "special_event", "ttl_seconds"))
                assertTrue(named(status.path("options"), option).path("description_localizations").has(locale),
                    "Missing /status " + option + " locale " + locale);
        }
        assertEquals(Permission.BAN_MEMBERS.getRawValue(), named(payload, "ban").path("default_member_permissions").asLong());
        assertEquals(Permission.MESSAGE_MANAGE.getRawValue(), named(payload, "purge").path("default_member_permissions").asLong());
        assertTrue(named(payload, "ping").path("default_member_permissions").isNull()
            || !named(payload, "ping").has("default_member_permissions"));
        assertTrue(status.path("default_member_permissions").isNull() || !status.has("default_member_permissions"));
        assertTrue(output.contains("57 Java-owned slash commands (global)"));
        NAMES.forEach(name -> assertTrue(output.contains("/" + name)));
        assertFalse(output.contains(TOKEN));
        assertTrue(transport.requests.stream().noneMatch(request -> Set.of("PUT", "DELETE").contains(request.method())));
    }

    @Test void configuredServerUsesOnlyItsGuildEndpoint() {
        var transport = new RecordingTransport();
        String output = capture(() -> CommandRegistration.register(TOKEN, " " + GUILD + " ", transport));
        String endpoint = "https://discord.com/api/v10/applications/" + APPLICATION + "/guilds/" + GUILD + "/commands";
        assertEquals(endpoint, transport.requests.get(1).uri().toString());
        assertTrue(output.contains("server " + GUILD));
        assertTrue(transport.requests.subList(2, transport.requests.size())
            .stream().allMatch(request -> request.uri().toString().equals(endpoint)));
        assertEquals(2 + NAMES.size(), transport.requests.size());
    }

    @Test void javaCatalogUpdatesLeaveNostrumOwnedCommandsUntouched() {
        var transport = new RecordingTransport();
        transport.existingCommandsBody = """
            [
              {"id":"323456789012345678","name":"career","type":1},
              {"id":"423456789012345678","name":"profile","type":1},
              {"id":"523456789012345678","name":"status","type":1}
            ]
            """;

        String output = capture(() -> CommandRegistration.register(TOKEN, GUILD, transport));
        assertTrue(output.contains("Java-owned slash commands (server " + GUILD + ")"));
        assertTrue(transport.requests.stream().noneMatch(request -> Set.of("PUT", "DELETE").contains(request.method())));

        var careerOrProfileMutations = transport.requests.stream()
            .filter(request -> request.body() != null)
            .map(request -> read(request.body()).path("name").asText())
            .filter(name -> Set.of("career", "profile").contains(name))
            .toList();
        assertTrue(careerOrProfileMutations.isEmpty());

        var statusUpdate = transport.requests.stream()
            .filter(request -> "PATCH".equals(request.method()))
            .findFirst().orElseThrow();
        assertTrue(statusUpdate.uri().toString().endsWith("/523456789012345678"));
        assertEquals("status", read(statusUpdate.body()).path("name").asText());
        assertEquals(2 + NAMES.size(), transport.requests.size());
    }

    @Test void jdaStartupOnlyUpsertsMissingJavaCommandsAndLeavesElixirCommandsAlone() {
        var commands = CommandRegistration.definitions();
        var existing = Set.of("career", "profile", "status", "ping");
        var missing = CommandRegistration.missingCommands(commands, existing);
        var names = missing.stream().map(CommandData::getName).collect(Collectors.toSet());
        var expected = new HashSet<>(NAMES);
        expected.removeAll(existing);

        assertFalse(names.contains("career"));
        assertFalse(names.contains("profile"));
        assertFalse(names.contains("status"));
        assertFalse(names.contains("ping"));
        assertEquals(expected, names);
    }

    @Test void manualRegistrationUsesAnIndividualUpsertForEachJavaCommand() {
        var calls = new ArrayList<String>();
        List<String> registered = CommandRegistration.upsertIndividually(CommandRegistration.definitions(), command -> {
            calls.add(command.getName());
            return CompletableFuture.completedFuture(command.getName());
        });

        assertEquals(calls, registered);
        assertEquals(NAMES, Set.copyOf(calls));
        assertFalse(calls.contains("career"));
        assertFalse(calls.contains("profile"));
    }

    @Test void invalidCredentialsAndGuildIdsFailBeforeAnyRequest() {
        CommandRegistration.Transport noNetwork = (method, uri, token, body) -> { fail("Must not send a request"); return null; };
        for (String token : new String[] {null, "", " ", TOKEN + "\r\n"})
            assertThrows(IllegalArgumentException.class, () -> CommandRegistration.register(token, null, noNetwork));
        for (String guild : List.of("abc", "123", "123456789012345678901", "../" + TOKEN)) {
            var error = assertThrows(IllegalArgumentException.class, () -> CommandRegistration.register(TOKEN, guild, noNetwork));
            assertScrubbed(error);
        }
    }

    @Test void invalidApplicationIdNeverReachesRegistrationEndpoint() {
        var transport = new RecordingTransport();
        transport.applicationBody = "{\"id\":\"../" + TOKEN + "\"}";
        var error = assertThrows(IllegalStateException.class, () -> CommandRegistration.register(TOKEN, GUILD, transport));
        assertEquals(1, transport.requests.size());
        assertScrubbed(error);
    }

    @Test void httpFailuresAndMalformedBodiesNeverExposeSecretsOrPrintSuccess() {
        for (int status : new int[] {301, 401, 403, 429, 500}) {
            var transport = new RecordingTransport();
            transport.mutationResponse = new CommandRegistration.Response(status, TOKEN);
            String output = capture(() -> {
                var error = assertThrows(IllegalStateException.class, () -> CommandRegistration.register(TOKEN, null, transport));
                assertTrue(error.getMessage().contains("HTTP " + status));
                assertScrubbed(error);
            });
            assertTrue(output.isEmpty());
        }

        var transport = new RecordingTransport();
        transport.applicationBody = TOKEN;
        var error = assertThrows(IllegalStateException.class, () -> CommandRegistration.register(TOKEN, null, transport));
        assertScrubbed(error);
        assertEquals(1, transport.requests.size());
    }

    @Test void malformedCommandListsAndUnconfirmedUpdatesNeverReportSuccess() {
        var malformedList = new RecordingTransport();
        malformedList.existingCommandsBody = "{}";
        var listError = assertThrows(IllegalStateException.class,
            () -> CommandRegistration.register(TOKEN, null, malformedList));
        assertScrubbed(listError);
        assertEquals(2, malformedList.requests.size());

        var mismatchedUpdate = new RecordingTransport();
        mismatchedUpdate.mutationResponse =
            new CommandRegistration.Response(201, "{\"name\":\"unexpected\",\"type\":1}");
        String output = capture(() -> {
            var error = assertThrows(IllegalStateException.class,
                () -> CommandRegistration.register(TOKEN, null, mismatchedUpdate));
            assertScrubbed(error);
        });
        assertTrue(output.isEmpty());
    }

    @Test void networkFailureDoesNotAttachUnsafeCause() {
        var error = assertThrows(IllegalStateException.class, () -> CommandRegistration.register(TOKEN, null,
            (method, uri, token, body) -> { throw new IOException(TOKEN); }));
        assertScrubbed(error);
    }

    @Test void interruptedRequestPreservesInterruptFlagWithoutExposingCause() {
        try {
            var error = assertThrows(IllegalStateException.class, () -> CommandRegistration.register(TOKEN, null,
                (method, uri, token, body) -> { throw new InterruptedException(TOKEN); }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertScrubbed(error);
        } finally { Thread.interrupted(); }
    }

    private static JsonNode named(JsonNode nodes, String name) {
        for (var node : nodes) if (node.path("name").asText().equals(name)) return node;
        throw new AssertionError("Missing " + name);
    }

    private static Set<String> names(JsonNode nodes) {
        Set<String> names = new HashSet<>();
        nodes.forEach(node -> names.add(node.path("name").asText()));
        return names;
    }

    private static Set<String> values(JsonNode nodes) {
        Set<String> values = new HashSet<>();
        nodes.forEach(node -> values.add(node.path("value").asText()));
        return values;
    }

    private static JsonNode read(String value) {
        try { return JSON.readTree(value); }
        catch (IOException ex) { throw new AssertionError(ex); }
    }

    private static void assertScrubbed(Throwable error) {
        assertFalse(error.getMessage().contains(TOKEN));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static String capture(Runnable action) {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (var replacement = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            action.run();
        } finally { System.setOut(previous); }
        return output.toString(StandardCharsets.UTF_8);
    }

    private record Request(String method, URI uri, String token, String body) {}

    private static class RecordingTransport implements CommandRegistration.Transport {
        final List<Request> requests = new ArrayList<>();
        String applicationBody = "{\"id\":\"" + APPLICATION + "\"}";
        String existingCommandsBody = "[]";
        CommandRegistration.Response mutationResponse;

        @Override public CommandRegistration.Response send(String method, URI uri, String token, String body) {
            requests.add(new Request(method, uri, token, body));
            if (method.equals("GET") && uri.toString().endsWith("/oauth2/applications/@me"))
                return new CommandRegistration.Response(200, applicationBody);
            if (method.equals("GET"))
                return new CommandRegistration.Response(200, existingCommandsBody);
            if (mutationResponse != null) return mutationResponse;
            return new CommandRegistration.Response(method.equals("POST") ? 201 : 200, body);
        }
    }
}
