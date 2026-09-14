package music;

import dev.arbjerg.lavalink.client.player.Track;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyPlaybackTest {
    @Test void extractedAudioPreservesQueueIdentityAndMetadata() throws Exception {
        for (String source : new String[]{"youtube", "soundcloud"}) {
            Track original = track(source);
            Track audio = track("http");
            original.setUserData(Map.of("playbackId", "play-123"));
            Track resolved = ExtractedPlayback.resolve(original, page -> {
                assertEquals(original.getInfo().getUri(), page);
                return "https://rr1.googlevideo.com/audio";
            }, url -> {
                assertEquals("https://rr1.googlevideo.com/audio", url);
                return audio;
            });
            assertSame(audio, resolved);
            assertEquals(original.getUserData(), resolved.getUserData());
            assertEquals(source, original.getInfo().getSourceName());
        }
    }
    @Test void extractionSkipsOtherSourcesAndReportsUnavailableAudio() throws Exception {
        assertNull(ExtractedPlayback.resolve(null, url -> { fail(); return null; }, url -> { fail(); return null; }));
        Track http = track("http");
        assertSame(http, ExtractedPlayback.resolve(http, url -> { fail(); return null; }, url -> { fail(); return null; }));
        assertEquals("youtube.playback.failed", assertThrows(UserError.class,
            () -> ExtractedPlayback.resolve(track("youtube"), url -> "https://rr1.googlevideo.com/audio", url -> null)).getMessage());
    }
    private static YouTubeSearch search() {
        return new YouTubeSearch("key", request -> new YouTubeSearch.Response(200,
            request.uri().getPath().endsWith("/search")
                ? "{\"items\":[{\"id\":{\"videoId\":\"abcdefghijk\"}}]}"
                : "{\"items\":[{\"id\":\"abcdefghijk\",\"snippet\":{\"title\":\"Artist Song\"},\"status\":{\"privacyStatus\":\"public\"},\"contentDetails\":{\"duration\":\"PT3M\"}}]}"), Clock.systemUTC());
    }
    @Test void mapsSpotifyToYoutubeAndKeepsPlaybackIdentity() throws Exception {
        Track spotify = track("spotify");
        Track youtube = track("youtube");
        spotify.setUserData(Map.of("playbackId", "unique-playback"));
        Track result = SpotifyPlayback.resolve(spotify, search(), url -> {
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk", url);
            return youtube;
        });
        assertSame(youtube, result);
        assertEquals(spotify.getUserData(), result.getUserData());
        assertEquals("spotify", spotify.getInfo().getSourceName());
    }
    @Test void otherSourcesAndEmptyQueueDoNotUseApiOrLoader() throws Exception {
        var disabled = new YouTubeSearch("");
        Track soundcloud = track("soundcloud");
        assertSame(soundcloud, SpotifyPlayback.resolve(soundcloud, disabled, url -> { fail(); return null; }));
        assertNull(SpotifyPlayback.resolve(null, disabled, url -> { fail(); return null; }));
    }
    @Test void missingLavalinkTrackDoesNotFallBackToSpotifyAudio() throws Exception {
        var spotify = track("spotify");
        assertEquals("youtube.playback.failed", assertThrows(UserError.class,
            () -> SpotifyPlayback.resolve(spotify, search(), url -> null)).getMessage());
    }
    private static Track track(String source) throws Exception {
        // Protocol's JSON constructor types are runtime dependencies of the Kotlin client.
        Class<?> jsonType = Class.forName("kotlinx.serialization.json.JsonObject");
        Object empty = jsonType.getConstructor(Map.class).newInstance(Map.of());
        Class<?> infoType = Class.forName("dev.arbjerg.lavalink.protocol.v4.TrackInfo");
        Object info = infoType.getConstructor(String.class, boolean.class, String.class, long.class,
            boolean.class, long.class, String.class, String.class, String.class, String.class, String.class)
            .newInstance("id", true, "Artist", 180_000L, false, 0L, "Song", "https://example.com", source, null, null);
        Class<?> protocolType = Class.forName("dev.arbjerg.lavalink.protocol.v4.Track");
        Object protocol = protocolType.getConstructor(String.class, infoType, jsonType, jsonType)
            .newInstance("encoded", info, empty, empty);
        return Track.class.getConstructor(protocolType).newInstance(protocol);
    }
}
