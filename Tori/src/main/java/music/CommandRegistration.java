package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Maintains JDA-owned commands without replacing commands owned by another runtime. */
public final class CommandRegistration {
    private static final String API = "https://discord.com/api/v10";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CommandRegistration() {}

    public static List<CommandData> definitions() {
        var commands = new ArrayList<>(MusicBot.commands());
        commands.addAll(ModerationBot.commands());
        commands.addAll(GeneralBot.commands());
        commands.addAll(TicketOrderBot.commands());
        commands.forEach(command -> ((SlashCommandData) command).setContexts(InteractionContextType.GUILD));
        return List.copyOf(commands);
    }

    public static void register(JDA jda, String guildId) {
        String guild = guildId(guildId);
        var commands = definitions();
        var server = guild == null ? null : jda.getGuildById(guild);
        if (guild != null && server == null)
            throw new RegistrationException("Configured DISCORD_GUILD_ID is not available to this bot. Check the server ID and bot membership.");

        Set<String> names;
        try {
            List<Command> existing = server == null
                ? jda.retrieveCommands().complete()
                : server.retrieveCommands().complete();
            Set<String> targetNames = commands.stream().map(CommandData::getName).collect(Collectors.toSet());
            Set<String> existingJavaNames = new HashSet<>();
            for (var command : existing) {
                if (command.getType() == Command.Type.SLASH && targetNames.contains(command.getName()))
                    existingJavaNames.add(command.getName());
            }

            var missing = missingCommands(commands, existingJavaNames);
            List<Command> created = upsertIndividually(missing,
                command -> (server == null ? jda.upsertCommand(command) : server.upsertCommand(command)).submit());

            names = new HashSet<>(existingJavaNames);
            for (var command : created) {
                if (command.getType() != Command.Type.SLASH || !names.add(command.getName())) throw incomplete();
            }
        } catch (RuntimeException ex) {
            if (ex instanceof RegistrationException safe) throw safe;
            String detail = ex instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException discord
                ? "Discord error code " + discord.getErrorCode() : ex.getClass().getSimpleName();
            throw new RegistrationException("Discord slash-command registration failed (" + detail + "). Check server access and connection.");
        }
        confirm(names, commands, guild);
    }

    static List<CommandData> missingCommands(List<CommandData> commands, Set<String> existingCommandNames) {
        return commands.stream().filter(command -> !existingCommandNames.contains(command.getName())).toList();
    }

    static <T> List<T> upsertIndividually(
        List<CommandData> commands, Function<CommandData, CompletableFuture<T>> upsert
    ) {
        var requests = commands.stream().map(upsert).toList();
        try {
            CompletableFuture.allOf(requests.toArray(CompletableFuture<?>[]::new)).get(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RegistrationException("Discord slash-command registration was interrupted.");
        } catch (ExecutionException | TimeoutException ex) {
            throw new RegistrationException("Discord slash-command registration failed. Check server access and connection.");
        }
        return requests.stream().map(CompletableFuture::join).toList();
    }

    public static void register(String token, String guildId) {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            register(token, guildId, (method, uri, credential, body) -> {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bot " + credential)
                    .header("User-Agent", "DiscordMusicBot/1.0")
                    .header("Content-Type", "application/json");
                if (method.equals("GET")) request.GET();
                else request.method(method, HttpRequest.BodyPublishers.ofString(body));
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body());
            });
        }
    }

    static void register(String token, String guildId, Transport transport) {
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("DISCORD_TOKEN fehlt oder ist ungueltig. Siehe README.md.");
        String guild = guildId(guildId);
        var commands = definitions();
        JsonNode application = response(send(transport, "GET", URI.create(API + "/oauth2/applications/@me"), token, null));
        String applicationId = application.path("id").asText();
        if (!applicationId.matches("[0-9]{17,20}"))
            throw new RegistrationException("Discord returned an invalid application ID.");

        URI endpoint = URI.create(API + "/applications/" + applicationId
            + (guild == null ? "" : "/guilds/" + guild) + "/commands");
        JsonNode existing = response(send(transport, "GET", endpoint, token, null));
        if (!existing.isArray()) throw incomplete();

        Set<String> names = new HashSet<>();
        for (var command : commands) {
            JsonNode current = findSlashCommand(existing, command.getName());
            String method = current == null ? "POST" : "PATCH";
            URI target = endpoint;

            if (current != null) {
                String id = current.path("id").asText();
                if (!id.matches("[0-9]{17,20}")) throw incomplete();
                target = URI.create(endpoint + "/" + id);
            }

            Set<Integer> expectedStatuses = current == null ? Set.of(200, 201) : Set.of(200);
            JsonNode updated = response(send(transport, method, target, token, command.toData().toString()),
                expectedStatuses);
            if (updated.path("type").asInt(-1) != 1
                || !updated.path("name").asText().equals(command.getName())
                || !names.add(command.getName())) throw incomplete();
        }
        confirm(names, commands, guild);
    }

    private static JsonNode findSlashCommand(JsonNode commands, String name) {
        for (var command : commands) {
            if (command.path("type").asInt(-1) == 1 && command.path("name").asText().equals(name))
                return command;
        }
        return null;
    }

    private static String guildId(String value) {
        if (value == null || value.isBlank()) return null;
        String guild = value.trim();
        if (!guild.matches("[0-9]{17,20}"))
            throw new IllegalArgumentException("DISCORD_GUILD_ID must be a numeric Discord server ID.");
        return guild;
    }

    private static Response send(Transport transport, String method, URI uri, String token, String body) {
        try {
            return transport.send(method, uri, token, body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RegistrationException("Discord slash-command registration was interrupted.");
        } catch (IOException | RuntimeException ex) {
            throw new RegistrationException("Discord slash-command registration failed. Check the connection and try again.");
        }
    }

    private static JsonNode response(Response response) {
        return response(response, Set.of(200));
    }

    private static JsonNode response(Response response, Set<Integer> expectedStatuses) {
        if (!expectedStatuses.contains(response.statusCode()))
            throw new RegistrationException("Discord slash-command registration failed (HTTP " + response.statusCode() + ").");
        return parse(response.body());
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode json = JSON.readTree(text);
            if (json == null || json.isNull()) throw new IllegalArgumentException();
            return json;
        } catch (IOException | RuntimeException ex) {
            throw new RegistrationException("Discord slash-command registration received invalid JSON.");
        }
    }

    private static void confirm(Set<String> names, List<CommandData> commands, String guild) {
        if (!names.equals(commands.stream().map(CommandData::getName).collect(Collectors.toSet()))) throw incomplete();
        System.out.println("Registered " + names.size() + " Java-owned slash commands ("
            + (guild == null ? "global" : "server " + guild) + "): "
            + commands.stream().map(command -> "/" + command.getName()).collect(Collectors.joining(", ")));
    }

    private static IllegalStateException incomplete() {
        return new RegistrationException("Discord did not confirm all Java-owned slash-command updates.");
    }

    /** Only locally authored messages; never external response bodies or exception causes. */
    static final class RegistrationException extends IllegalStateException {
        RegistrationException(String message) { super(message); }
    }

    @FunctionalInterface
    interface Transport {
        Response send(String method, URI uri, String token, String body) throws IOException, InterruptedException;
    }

    record Response(int statusCode, String body) {}
}
