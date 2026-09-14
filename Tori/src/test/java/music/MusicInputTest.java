package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MusicInputTest {
    @Test void youtubeIsTheDefaultSearchAndExplicitSourcesRemainAvailable() {
        assertEquals("ytsearch:aint in la", MusicInput.resolve("aint in la", (String) null));
        assertEquals("ytsearch:Hello", MusicInput.resolve("Hello", "youtube"));
        assertEquals("scsearch:Hello", MusicInput.resolve("Hello", "soundcloud"));
        assertEquals("spsearch:Hello", MusicInput.resolve("Hello", "spotify"));
    }
    @ParameterizedTest @ValueSource(strings = {"https://www.youtube.com/watch?v=abcdefghijk", "https://youtu.be/abcdefghijk", "https://music.youtube.com/watch?v=abcdefghijk"})
    void acceptsYouTubeLinks(String url) { assertEquals(url, MusicInput.resolve(url, "youtube")); }
    @Test void rejectsLookalikeYouTubeHostsAndUnknownSearchSources() {
        assertThrows(UserError.class, () -> MusicInput.resolve("https://youtube.com.evil.test/watch?v=abcdefghijk", "youtube"));
        assertThrows(UserError.class, () -> MusicInput.resolve("Hello", "invalid"));
    }
    private static final String ID = "4uLU6hMCjMI75M1A2tKUQC";
    @Test void normalizesLocalizedSpotifyLinks() {
        assertEquals("https://open.spotify.com/track/" + ID,
            MusicInput.resolve("https://open.spotify.com/intl-de/track/" + ID + "?si=abc", false));
    }
    @ParameterizedTest @ValueSource(strings = {"track", "playlist", "album"})
    void supportsSpotifyUris(String type) {
        assertEquals("https://open.spotify.com/" + type + "/" + ID, MusicInput.resolve("spotify:" + type + ":" + ID, false));
    }
    @Test void selectsSearchSourceWithoutInterpretingPrefixes() {
        assertEquals("spsearch:Hello", MusicInput.resolve("Hello", true));
        assertEquals("scsearch:Hello", MusicInput.resolve("Hello", false));
        assertEquals("scsearch:http:foo", MusicInput.resolve("http:foo", false));
    }
    @ParameterizedTest @ValueSource(strings = {"", "spotify:track:bad", "https://open.spotify.com/artist/abc", "https://open.spotify.com.evil.test/track/abc", "https://user:pass@soundcloud.com/x", "http://localhost:2333", "https://soundcloud.com:8080/x"})
    void rejectsInvalidOrForeignUrls(String input) {
        assertThrows(IllegalArgumentException.class, () -> MusicInput.resolve(input, false));
    }
    @Test void keepsExistingSources() {
        assertEquals("https://artist.bandcamp.com/track/test", MusicInput.resolve("https://artist.bandcamp.com/track/test", false));
    }
}
