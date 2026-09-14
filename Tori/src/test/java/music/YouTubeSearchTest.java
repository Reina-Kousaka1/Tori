package music;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class YouTubeSearchTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
    private static final String SEARCH = "{\"items\":[{\"id\":{\"videoId\":\"abcdefghijk\"}},{\"id\":{\"videoId\":\"lmnopqrstuv\"}}]}";
    private static String video(String id, String title, String duration) {
        return "{\"id\":\"" + id + "\",\"snippet\":{\"title\":\"" + title + "\",\"channelTitle\":\"Artist - Topic\",\"liveBroadcastContent\":\"none\"},"
            + "\"status\":{\"privacyStatus\":\"public\"},\"contentDetails\":{\"duration\":\"" + duration + "\"}}";
    }
    @Test void selectsMatchingDurationInsteadOfFirstHitAndCachesIt() {
        var calls = new AtomicInteger();
        var search = new YouTubeSearch("test key", request -> {
            assertEquals("www.googleapis.com", request.uri().getHost());
            assertEquals(Duration.ofSeconds(10), request.timeout().orElseThrow());
            calls.incrementAndGet();
            if (request.uri().getPath().endsWith("/search")) {
                assertTrue(request.uri().getRawQuery().contains("q=Artist+Song"));
                assertTrue(request.uri().getRawQuery().contains("key=test+key"));
                return new YouTubeSearch.Response(200, SEARCH);
            }
            return new YouTubeSearch.Response(200, "{\"items\":[" + video("abcdefghijk", "Artist Song extended", "PT10M") + "," + video("lmnopqrstuv", "Artist Song", "PT3M") + "]}");
        }, CLOCK);
        assertEquals("https://www.youtube.com/watch?v=lmnopqrstuv", search.find("Song", "Artist", 180_000));
        assertEquals("https://www.youtube.com/watch?v=lmnopqrstuv", search.find("Song", "Artist", 180_000));
        assertEquals(2, calls.get());
    }
    @Test void missingKeyDoesNotMakeRequests() {
        var search = new YouTubeSearch(" ", request -> { fail("HTTP must not run"); return null; }, CLOCK);
        assertEquals("youtube.key.missing", assertThrows(UserError.class, () -> search.find("Song", "Artist", 180_000)).getMessage());
    }
    @Test void quotaFailureIsSafeAndPreventsImmediateRetries() {
        var calls = new AtomicInteger();
        var search = new YouTubeSearch("SECRET", request -> {
            calls.incrementAndGet();
            return new YouTubeSearch.Response(403, "{\"error\":{\"reason\":\"quotaExceeded\",\"message\":\"SECRET\"}}");
        }, CLOCK);
        for (int i = 0; i < 2; i++) {
            var error = assertThrows(UserError.class, () -> search.find("Song", "Artist", 180_000));
            assertEquals("youtube.quota", error.getMessage());
            assertNull(error.getCause());
        }
        assertEquals(1, calls.get());
    }
    @Test void invalidKeyAndNetworkErrorsNeverExposeCredentials() {
        var invalid = new YouTubeSearch("SECRET", request -> new YouTubeSearch.Response(403, "SECRET"), CLOCK);
        assertEquals("youtube.key.invalid", assertThrows(UserError.class, () -> invalid.find("Song", "Artist", 180_000)).getMessage());
        var network = new YouTubeSearch("SECRET", request -> { throw new IOException(request.uri().toString()); }, CLOCK);
        var error = assertThrows(UserError.class, () -> network.find("Song", "Artist", 180_000));
        assertEquals("youtube.unavailable", error.getMessage());
        assertNull(error.getCause());
    }
    @Test void rejectsUnrelatedTitleDespiteMatchingArtistAndLength() {
        var search = new YouTubeSearch("key", request -> new YouTubeSearch.Response(200,
            request.uri().getPath().endsWith("/search") ? SEARCH : "{\"items\":[" + video("abcdefghijk", "Other music", "PT3M") + "]}"), CLOCK);
        assertEquals("youtube.no.match", assertThrows(UserError.class, () -> search.find("Song", "Artist", 180_000)).getMessage());
    }
    @Test void malformedResponseIsSafe() {
        var search = new YouTubeSearch("key", request -> new YouTubeSearch.Response(200, "invalid"), CLOCK);
        assertEquals("youtube.unavailable", assertThrows(UserError.class, () -> search.find("Song", "Artist", 180_000)).getMessage());
    }
    @Test void rejectsUntrustedVideoIdsWithoutLoadingThem() {
        var search = new YouTubeSearch("key", request -> new YouTubeSearch.Response(200, "{\"items\":[{\"id\":{\"videoId\":\"https://example.com\"}}]}"), CLOCK);
        assertEquals("youtube.no.match", assertThrows(UserError.class, () -> search.find("Song", "Artist", 180_000)).getMessage());
    }
    @Test void failedCurrentKeepsRemainingPlaylist() {
        var queue = new TrackQueue<String>();
        queue.add(java.util.List.of("failed", "next"));
        queue.advance();
        queue.finishCurrent();
        assertNull(queue.current());
        assertEquals("next", queue.advance());
    }
}
