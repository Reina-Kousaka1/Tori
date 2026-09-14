package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class YtDlpTest {
    @Test void passesThePageAsOneArgumentAndRequestsOnlyAudio() {
        String page = "https://www.youtube.com/watch?v=abcdefghijk&list=test";
        var resolver = new YtDlp("yt-dlp", "node:tools/node.exe", command -> {
            assertEquals("yt-dlp", command.getFirst());
            assertEquals("--", command.get(command.size() - 2));
            assertEquals(page, command.getLast());
            assertTrue(command.contains("--ignore-config"));
            assertTrue(command.contains("--no-playlist"));
            assertTrue(command.contains("--skip-download"));
            assertEquals("bestaudio/best", command.get(command.indexOf("--format") + 1));
            assertEquals("node:tools/node.exe", command.get(command.indexOf("--js-runtimes") + 1));
            return "https://rr1.googlevideo.com/videoplayback?expire=123\n";
        });
        assertEquals("https://rr1.googlevideo.com/videoplayback?expire=123", resolver.streamUrl(page));
    }
    @ParameterizedTest @ValueSource(strings = {"--exec=bad", "http://localhost/a", "https://youtube.com.evil.test/a", "https://user:secret@youtube.com/a", "https://youtube.com:123/a"})
    void rejectsForeignPagesBeforeLaunchingAProcess(String url) {
        var resolver = new YtDlp("yt-dlp", "node", command -> { fail("Must not launch for an untrusted page"); return ""; });
        assertEquals("input.source", assertThrows(UserError.class, () -> resolver.streamUrl(url)).getMessage());
    }
    @ParameterizedTest @ValueSource(strings = {"", "http://127.0.0.1:2333/", "https://googlevideo.com.evil.test/audio", "https://user:secret@googlevideo.com/audio", "https://rr1.googlevideo.com/a\nhttps://rr1.googlevideo.com/b"})
    void neverForwardsForeignOrMultipleStreamUrlsToLavalink(String result) {
        var resolver = new YtDlp("yt-dlp", "node", command -> result);
        assertEquals("youtube.extract.failed", assertThrows(UserError.class,
            () -> resolver.streamUrl("https://youtu.be/abcdefghijk")).getMessage());
    }
    @Test void reportsMissingToolsAndTimeoutsWithoutLeakingProcessDetails() {
        var missing = new YtDlp("yt-dlp", "node", command -> { throw new IOException("private path"); });
        var timeout = new YtDlp("yt-dlp", "node", command -> { throw new TimeoutException("signed URL"); });
        assertEquals("youtube.tool.missing", assertThrows(UserError.class, () -> missing.streamUrl("https://youtu.be/abcdefghijk")).getMessage());
        assertEquals("youtube.extract.timeout", assertThrows(UserError.class, () -> timeout.streamUrl("https://youtu.be/abcdefghijk")).getMessage());
    }
    @Test void acceptsSoundcloudAudioCdn() {
        var resolver = new YtDlp("yt-dlp", "node", command -> "https://cf-hls-media.sndcdn.com/audio.m3u8");
        assertEquals("https://cf-hls-media.sndcdn.com/audio.m3u8", resolver.streamUrl("https://soundcloud.com/artist/song"));
    }
}
