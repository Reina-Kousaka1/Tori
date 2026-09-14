package music;

import dev.arbjerg.lavalink.client.player.Track;
import java.util.Set;
import java.util.function.Function;

final class ExtractedPlayback {
    static boolean needsLookup(Track track) {
        return track != null && Set.of("youtube", "soundcloud").contains(track.getInfo().getSourceName());
    }
    static Track resolve(Track track, Function<String, String> extractor, Function<String, Track> loader) {
        if (!needsLookup(track)) return track;
        Track playable = loader.apply(extractor.apply(track.getInfo().getUri()));
        if (playable == null) throw new UserError("youtube.playback.failed");
        playable.setUserData(track.getUserData());
        return playable;
    }
    private ExtractedPlayback() {}
}
