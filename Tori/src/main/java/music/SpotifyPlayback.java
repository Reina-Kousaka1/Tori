package music;

import dev.arbjerg.lavalink.client.player.Track;
import java.util.function.Function;

final class SpotifyPlayback {
    static boolean needsLookup(Track track) {
        return track != null && "spotify".equals(track.getInfo().getSourceName());
    }
    static Track resolve(Track track, YouTubeSearch search, Function<String, Track> loader) {
        if (!needsLookup(track)) return track;
        String url = search.find(track.getInfo().getTitle(), track.getInfo().getAuthor(), track.getInfo().getLength());
        Track playable = loader.apply(url);
        if (playable == null) throw new UserError("youtube.playback.failed");
        // Keep the Spotify queue entry; correlate YouTube end events with its playback identity.
        playable.setUserData(track.getUserData());
        return playable;
    }
    private SpotifyPlayback() {}
}
