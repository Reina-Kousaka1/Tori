package music;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Authenticated client for semantic Tori context; it never receives or writes Discord Presence. */
final class ToriPresenceClient implements ToriPresenceSync.Provider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final URI contextEndpoint;
    private final String secret;
    private final Duration timeout;

    ToriPresenceClient(HttpClient http, URI baseUri, String secret) {
        this(http, baseUri, secret, Duration.ofSeconds(3));
    }

    ToriPresenceClient(HttpClient http, URI baseUri, String secret, Duration timeout) {
        this.http = Objects.requireNonNull(http);
        this.contextEndpoint = Objects.requireNonNull(baseUri).resolve("/internal/persona/v2/context");
        this.secret = Objects.requireNonNull(secret);
        this.timeout = Objects.requireNonNull(timeout);
        if (secret.length() < 32) throw new IllegalArgumentException("Presence API secret is too short");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Request timeout must be positive");
    }

    @Override public ToriPresenceContext fetch() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(contextEndpoint).timeout(timeout)
            .header("Authorization", "Bearer " + secret)
            .GET().build();
        return context(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    @Override public ToriPresenceContext update(String activity, String specialEvent, Long ttlSeconds)
        throws IOException, InterruptedException {
        ObjectNode payload = JSON.createObjectNode()
            .put("schema_version", 2)
            .put("activity", Objects.requireNonNull(activity));
        if (specialEvent == null || specialEvent.isBlank()) payload.putNull("special_event");
        else payload.put("special_event", specialEvent);
        if (ttlSeconds == null) payload.putNull("ttl_seconds");
        else payload.put("ttl_seconds", ttlSeconds);
        var request = HttpRequest.newBuilder(contextEndpoint).timeout(timeout)
            .header("Authorization", "Bearer " + secret)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build();
        return context(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private static ToriPresenceContext context(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200)
            throw new IOException("Presence provider returned HTTP " + response.statusCode());
        return ToriPresenceContext.fromJson(JSON.readTree(response.body()));
    }
}
