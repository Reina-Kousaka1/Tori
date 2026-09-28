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
 * Inactive until an explicit command cutover. Keeps Discord identifiers as strings and
 * never computes economy rules. Retrying the same Request preserves its mutation key.
 */
public final class EconomyV2Client {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String secret;

    public EconomyV2Client(HttpClient http, URI baseUri, String secret) {
        this.http = Objects.requireNonNull(http);
        this.endpoint = Objects.requireNonNull(baseUri).resolve("/internal/economy/v1/execute");
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
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + secret)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 400 &&
                response.statusCode() != 401 && response.statusCode() != 409 &&
                response.statusCode() != 503) {
            throw new IOException("Unexpected economy HTTP status: " + response.statusCode());
        }
        JsonNode body = JSON.readTree(response.body());
        if (!body.isObject() || !body.path("status").isTextual()) {
            throw new IOException("Invalid economy response");
        }
        return new Response(response.statusCode(), body);
    }
}
