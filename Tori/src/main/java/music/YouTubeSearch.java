package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Official Data API lookup only. Lavalink loads and plays the returned video URL. */
public final class YouTubeSearch {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 256 * 1024;
    record Response(int status, String body) {}
    @FunctionalInterface interface Transport { Response get(HttpRequest request) throws IOException, InterruptedException; }
    private record Entry(String url, Instant expires) {}
    private final String key;
    private final Transport transport;
    private final Clock clock;
    private final Map<String, Entry> cache = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<String>> inFlight = new HashMap<>();
    private volatile Instant blockedUntil = Instant.MIN;

    public YouTubeSearch(String key) {
        this(key, httpTransport(), Clock.systemUTC());
    }
    YouTubeSearch(String key, Transport transport, Clock clock) {
        this.key = key == null ? "" : key.strip();
        this.transport = transport;
        this.clock = clock;
    }
    private static Transport httpTransport() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body().length() > MAX_BODY) throw new IOException("Response too large");
            return new Response(response.statusCode(), response.body());
        };
    }
    public void requireConfigured() {
        if (key.isBlank()) throw new UserError("youtube.key.missing");
    }
    /** Share lookups for the same track without holding a global lock during network I/O. */
    public String find(String title, String artist, long lengthMs) {
        requireConfigured();
        String cacheKey = title + "\n" + artist + "\n" + lengthMs;
        CompletableFuture<String> pending;
        boolean owner;
        synchronized (cache) {
            var entry = cache.get(cacheKey);
            if (entry != null && entry.expires().isAfter(clock.instant())) return entry.url();
            if (blockedUntil.isAfter(clock.instant())) throw new UserError("youtube.quota");
            pending = inFlight.get(cacheKey);
            owner = pending == null;
            if (owner) {
                if (inFlight.size() >= 32) throw new UserError("busy");
                pending = new CompletableFuture<>();
                inFlight.put(cacheKey, pending);
            }
        }
        if (!owner) {
            try { return pending.get(25, TimeUnit.SECONDS); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new UserError("youtube.unavailable");
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof UserError safe) throw safe;
                throw new UserError("youtube.unavailable");
            } catch (TimeoutException ex) { throw new UserError("youtube.unavailable"); }
        }
        try {
            String url = lookup(title, artist, lengthMs);
            synchronized (cache) {
                cache.put(cacheKey, new Entry(url, clock.instant().plus(Duration.ofHours(6))));
                while (cache.size() > 512) cache.remove(cache.keySet().iterator().next());
            }
            pending.complete(url);
            return url;
        } catch (RuntimeException | Error ex) {
            pending.completeExceptionally(ex);
            throw ex;
        } finally {
            synchronized (cache) { inFlight.remove(cacheKey); }
        }
    }
    private String lookup(String title, String artist, long lengthMs) {
        JsonNode search = request("search?part=snippet&type=video&maxResults=5&q=" + encode(artist + " " + title));
        List<String> ids = new ArrayList<>();
        for (JsonNode item : search.path("items")) {
            String id = item.path("id").path("videoId").asText();
            if (id.matches("[A-Za-z0-9_-]{11}")) ids.add(id);
        }
        if (ids.isEmpty()) throw new UserError("youtube.no.match");
        JsonNode videos = request("videos?part=snippet,contentDetails,status&id=" + String.join(",", ids));
        String best = null;
        double bestScore = -1;
        for (JsonNode video : videos.path("items")) {
            String id = video.path("id").asText();
            if (!ids.contains(id) || !video.path("status").path("privacyStatus").asText().equals("public")) continue;
            var snippet = video.path("snippet");
            if (!snippet.path("liveBroadcastContent").asText("none").equals("none")) continue;
            long duration;
            try { duration = Duration.parse(video.path("contentDetails").path("duration").asText()).toMillis(); }
            catch (RuntimeException ex) { continue; }
            long tolerance = Math.max(15_000, lengthMs / 10);
            if (duration <= 0 || lengthMs <= 0 || Math.abs(duration - lengthMs) > tolerance) continue;
            String videoTitle = snippet.path("title").asText();
            double titleMatch = overlap(title, videoTitle);
            double artistMatch = overlap(artist, videoTitle + " " + snippet.path("channelTitle").asText());
            if (titleMatch < 0.6 || artistMatch < 0.3) continue;
            double score = titleMatch * 2 + artistMatch - (double) Math.abs(duration - lengthMs) / tolerance;
            if (score > bestScore) { bestScore = score; best = id; }
        }
        if (best == null) throw new UserError("youtube.no.match");
        return "https://www.youtube.com/watch?v=" + best;
    }
    private JsonNode request(String path) {
        try {
            var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/youtube/v3/" + path + "&key=" + encode(key)))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build();
            Response response = transport.get(request);
            if (response.status() == 429 || response.status() == 403 &&
                (response.body().contains("quotaExceeded") || response.body().contains("dailyLimitExceeded") || response.body().contains("rateLimitExceeded"))) {
                blockedUntil = clock.instant().plus(Duration.ofMinutes(5));
                throw new UserError("youtube.quota");
            }
            if (response.status() == 400 || response.status() == 401 || response.status() == 403)
                throw new UserError("youtube.key.invalid");
            if (response.status() != 200) throw new UserError("youtube.unavailable");
            JsonNode data = JSON.readTree(response.body());
            if (data == null || !data.path("items").isArray()) throw new UserError("youtube.unavailable");
            return data;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UserError("youtube.unavailable");
        } catch (IOException | IllegalArgumentException ex) {
            if (ex instanceof UserError safe) throw safe;
            // Never retain request URLs, API response bodies or exception causes containing keys.
            throw new UserError("youtube.unavailable");
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static double overlap(String expected, String actual) {
        Set<String> words = new HashSet<>(Arrays.asList(expected.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        words.remove("");
        Set<String> available = new HashSet<>(Arrays.asList(actual.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        return words.isEmpty() ? 0 : (double) words.stream().filter(available::contains).count() / words.size();
    }
}
