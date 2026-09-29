package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Private economy API client. Only balance reads are wired to Discord behind an
 * explicit opt-in; mutations are retained for isolated contract tests.
 */
public final class EconomyV2Client {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String secret;
    private final Duration requestTimeout;

    public EconomyV2Client(HttpClient http, URI baseUri, String secret) {
        this(http, baseUri, secret, Duration.ofSeconds(3));
    }

    EconomyV2Client(HttpClient http, URI baseUri, String secret, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http);
        this.endpoint = Objects.requireNonNull(baseUri).resolve("/internal/economy/v1/execute");
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        if (requestTimeout.isZero() || requestTimeout.isNegative())
            throw new IllegalArgumentException("Request timeout must be positive");
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("Economy API secret must have at least 32 characters");
        }
        this.secret = secret;
    }

    public record Request(String requestId, String idempotencyKey, String operation,
                          Map<String, String> context, Map<String, Object> args) {
        public Request {
            UUID.fromString(Objects.requireNonNull(requestId));
            Objects.requireNonNull(operation);
            context = Map.copyOf(context);
            args = Map.copyOf(args);
            for (String key : new String[]{"actor_user_id", "guild_id", "channel_id"}) {
                if (!context.containsKey(key) || !context.get(key).matches("[0-9]{1,32}")) {
                    throw new IllegalArgumentException("Invalid Discord context");
                }
            }
            if (idempotencyKey != null && !idempotencyKey.matches("discord-interaction:[0-9]{1,32}")) {
                throw new IllegalArgumentException("Invalid interaction key");
            }
        }

        public static Request mutation(String interactionId, String operation,
                                       Map<String, String> context, Map<String, Object> args) {
            if (interactionId == null || !interactionId.matches("[0-9]{1,32}")) {
                throw new IllegalArgumentException("Invalid interaction ID");
            }
            return new Request(UUID.randomUUID().toString(), "discord-interaction:" + interactionId,
                    operation, context, args);
        }
    }

    public record Response(int httpStatus, JsonNode body) {
        public String errorCode() {
            return body.path("error").path("code").asText("");
        }
    }

    public Response execute(Request request) throws IOException, InterruptedException {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("request_id", request.requestId());
        if (request.idempotencyKey() != null) payload.put("idempotency_key", request.idempotencyKey());
        payload.put("operation", request.operation());
        payload.put("context", request.context());
        payload.put("args", request.args());

        byte[] bytes = JSON.writeValueAsBytes(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + secret)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 400 &&
                response.statusCode() != 401 && response.statusCode() != 403 && response.statusCode() != 409 &&
                response.statusCode() != 503) {
            throw new IOException("Unexpected economy HTTP status: " + response.statusCode());
        }
        JsonNode body = JSON.readTree(response.body());
        if (!body.isObject() || !body.path("status").isTextual()) {
            throw new IOException("Invalid economy response");
        }
        return new Response(response.statusCode(), body);
    }

    public long balance(String actorId, String guildId, String channelId, String targetId)
            throws IOException, InterruptedException {
        Request request = new Request(UUID.randomUUID().toString(), null, "wallet.balance",
                Map.of("actor_user_id", actorId, "guild_id", guildId,
                        "channel_id", channelId, "target_user_id", targetId), Map.of());
        Response response = execute(request);
        JsonNode body = response.body();
        JsonNode result = body.path("result");
        JsonNode amount = result.path("balance");
        if (response.httpStatus() != 200 || !"ok".equals(body.path("status").asText()) ||
                !request.requestId().equals(body.path("request_id").asText()) ||
                !"wallet_balance".equals(result.path("type").asText()) ||
                !targetId.equals(result.path("user_id").asText()) || !amount.isTextual() ||
                !amount.asText().matches("0|[1-9][0-9]*")) {
            throw new IOException("Invalid economy balance response");
        }
        try {
            return Long.parseLong(amount.asText());
        } catch (NumberFormatException ex) {
            throw new IOException("Economy balance is out of range", ex);
        }
    }
}
