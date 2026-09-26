package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-secret-token";
    private static final String APPLICATION = "123456789012345678";
    private static final String GUILD = "223456789012345678";
    private static final Set<String> NAMES = Set.of("repeat", "prefix", "play", "lyrics", "skip", "pause", "resume", "queue", "stop", "leave", "volume",
        "kick", "ban", "unban", "timeout", "untimeout", "purge", "slowmode", "language", "help", "ping", "stats", "status", "restart", "shutdown", "uptime", "snipe", "avatar");

    @Test void globalRegistrationPublishesCompleteCatalogAndConfirmsResponse() throws Exception {
        var transport = new RecordingTransport();
        String output = capture(() -> CommandRegistration.register(TOKEN, " ", transport));
        assertEquals(List.of("GET", "PUT"), transport.requests.stream().map(Request::method).toList());
        assertEquals("https://discord.com/api/v10/oauth2/applications/@me", transport.requests.getFirst().uri().toString());
        var put = transport.requests.getLast();
        assertEquals("https://discord.com/api/v10/applications/" + APPLICATION + "/commands", put.uri().toString());
        assertNull(transport.requests.getFirst().body());
        transport.requests.forEach(request -> assertEquals(TOKEN, request.token()));
        JsonNode payload = JSON.readTree(put.body());
        assertEquals(28, payload.size());
        Set<String> names = new HashSet<>();
        for (var command : payload) {
            names.add(command.path("name").asText());
            assertEquals(1, command.path("type").asInt());
            assertEquals(1, command.path("contexts").size());
            assertEquals(0, command.path("contexts").get(0).asInt(-1));
            assertTrue(command.path("description_localizations").has("de"));
            assertTrue(command.path("description_localizations").has("nl"));
        }
        assertEquals(NAMES, names);
        JsonNode status = named(payload, "status");
        assertEquals(Set.of("action", "texts", "interval_ms"), names(status.path("options")));
        assertEquals(Set.of("start", "stop", "show"), values(named(status.path("options"), "action").path("choices")));
        var interval = named(status.path("options"), "interval_ms");
        assertEquals(StatusRotation.MIN_INTERVAL_MS, interval.path("min_value").asLong());
        assertEquals(StatusRotation.MAX_INTERVAL_MS, interval.path("max_value").asLong());
        assertEquals(Permission.BAN_MEMBERS.getRawValue(), named(payload, "ban").path("default_member_permissions").asLong());
        assertEquals(Permission.MESSAGE_MANAGE.getRawValue(), named(payload, "purge").path("default_member_permissions").asLong());
        assertTrue(named(payload, "ping").path("default_member_permissions").isNull()
            || !named(payload, "ping").has("default_member_permissions"));
        assertTrue(status.path("default_member_permissions").isNull() || !status.has("default_member_permissions"));
        assertTrue(output.contains("28 slash commands (global)"));
        NAMES.forEach(name -> assertTrue(output.contains("/" + name)));
        assertFalse(output.contains(TOKEN));
    }

    @Test void configuredServerUsesOnlyItsGuildEndpoint() {
        var transport = new RecordingTransport();
        String output = capture(() -> CommandRegistration.register(TOKEN, " " + GUILD + " ", transport));
        assertEquals("https://discord.com/api/v10/applications/" + APPLICATION + "/guilds/" + GUILD + "/commands",
            transport.requests.getLast().uri().toString());
        assertTrue(output.contains("server " + GUILD));
        assertEquals(2, transport.requests.size());
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
            transport.putResponse = new CommandRegistration.Response(status, TOKEN);
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

    @Test void missingDuplicateRenamedOrWrongTypeCommandsNeverConfirmSuccess() throws Exception {
        var correct = JSON.readTree(responseCommands());
        var missing = correct.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing).remove(0);
        var duplicate = correct.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.get(0)).put("name", correct.get(1).path("name").asText());
        var renamed = correct.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) renamed.get(0)).put("name", "unexpected");
        var wrongType = correct.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongType.get(0)).put("type", 2);
        for (JsonNode returned : List.of(missing, duplicate, renamed, wrongType, JSON.createObjectNode())) {
            var transport = new RecordingTransport();
            transport.putResponse = new CommandRegistration.Response(200, returned.toString());
            String output = capture(() -> assertThrows(IllegalStateException.class,
                () -> CommandRegistration.register(TOKEN, null, transport)));
            assertTrue(output.isEmpty());
        }
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

    private static String responseCommands() {
        return NAMES.stream().map(name -> "{\"name\":\"" + name + "\",\"type\":1}").collect(Collectors.joining(",", "[", "]"));
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
        CommandRegistration.Response putResponse = new CommandRegistration.Response(200, responseCommands());

        @Override public CommandRegistration.Response send(String method, URI uri, String token, String body) {
            requests.add(new Request(method, uri, token, body));
            return method.equals("GET") ? new CommandRegistration.Response(200, applicationBody) : putResponse;
        }
    }
}
