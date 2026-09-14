package music;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Public LRCLIB lookup; no credentials or Discord identifiers are sent. */
final class LyricsSearch {
    record Result(String title, String artist, String lyrics, boolean instrumental, String url) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Result find(String query) {
        query = query.strip();
        if (query.isEmpty() || query.length() > 500) throw new UserError("input.length");
        var request = HttpRequest.newBuilder(URI.create("https://lrclib.net/api/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(15)).header("User-Agent", "DiscordMusicBot/" + BotVersion.CURRENT).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 2_000_000) throw new UserError("lyrics.unavailable");
            var results = new ObjectMapper().readTree(response.body());
            if (!results.isArray()) throw new UserError("lyrics.unavailable");
            for (var item : results) {
                String lyrics = item.path("plainLyrics").asText("");
                if (lyrics.isBlank()) lyrics = item.path("syncedLyrics").asText("").replaceAll("(?m)\\[\\d{2}:\\d{2}(?:\\.\\d+)?]", "");
                boolean instrumental = item.path("instrumental").asBoolean();
                long id = item.path("id").asLong(-1);
                if (id < 0 || (!instrumental && lyrics.isBlank())) continue;
                return new Result(item.path("trackName").asText("—"), item.path("artistName").asText("—"),
                    lyrics, instrumental, "https://lrclib.net/api/get/" + id);
            }
            throw new UserError("lyrics.not.found");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UserError("shutting.down");
        } catch (java.io.IOException ex) { throw new UserError("lyrics.unavailable"); }
    }
    static String currentQuery(String title, String artist) {
        String clean = title.replaceAll("(?i)\\s*[\\[(](?:official[^\\])]*|lyrics?|audio|music video)[\\])]", "").strip();
        String author = artist.replaceFirst("(?i)\\s*-\\s*Topic$", "").strip();
        return clean.toLowerCase(java.util.Locale.ROOT).contains(author.toLowerCase(java.util.Locale.ROOT))
            ? clean : clean + " " + author;
    }
}
