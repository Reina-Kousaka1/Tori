package music;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class MusicInput {
    private static final Pattern SPOTIFY_URI = Pattern.compile("spotify:(track|album|playlist):[A-Za-z0-9]{22}");
    private static final Pattern SPOTIFY_PATH = Pattern.compile("/(?:intl-[a-z]{2}/)?(track|album|playlist)/[A-Za-z0-9]{22}/?");
    private MusicInput() {}
    public static String resolve(String input, boolean spotifySearch) {
        return resolve(input, spotifySearch ? "spotify" : "soundcloud");
    }
    public static String resolve(String input, String source) {
        String query = input.trim();
        if (query.isEmpty() || query.length() > 500) throw new UserError("input.length");
        if (query.startsWith("spotify:")) {
            if (!SPOTIFY_URI.matcher(query).matches()) throw new UserError("input.spotify.uri");
            return "https://open.spotify.com/" + query.substring(8).replace(':', '/');
        }
        if (query.toLowerCase(Locale.ROOT).startsWith("https://") || query.toLowerCase(Locale.ROOT).startsWith("http://")) {
            URI uri;
            try { uri = URI.create(query); } catch (IllegalArgumentException e) { throw new UserError("input.url"); }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (uri.getUserInfo() != null || uri.getPort() != -1) throw new UserError("input.url.credentials");
            if (host.equals("open.spotify.com")) {
                if (!SPOTIFY_PATH.matcher(uri.getPath()).matches()) throw new UserError("input.spotify.link");
                return "https://open.spotify.com" + uri.getPath().replaceFirst("^/intl-[a-z]{2}", "");
            }
            if (Set.of("youtube.com", "youtu.be", "soundcloud.com", "bandcamp.com").stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h)))
                return "https://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            throw new UserError("input.source");
        }
        return switch (source == null ? "youtube" : source) {
            case "youtube" -> "ytsearch:" + query;
            case "soundcloud" -> "scsearch:" + query;
            case "spotify" -> "spsearch:" + query;
            default -> throw new UserError("input.source");
        };
    }
}
