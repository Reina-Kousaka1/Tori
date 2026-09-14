package music;

import dev.arbjerg.lavalink.client.*;
import dev.arbjerg.lavalink.client.event.*;
import dev.arbjerg.lavalink.client.player.*;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;
import reactor.core.Disposable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicBot extends CommandListener {
    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    private final LavalinkClient client;
    private final YouTubeSearch youtube;
    private final YtDlp extractor;
    private final LyricsSearch lyrics = new LyricsSearch();
    private final Map<Long, MessageChannel> replyChannels = new ConcurrentHashMap<>();
    private final Map<Long, TrackQueue<Track>> queues = new ConcurrentHashMap<>();
    private final Map<Long, Long> channels = new ConcurrentHashMap<>();
    private final List<Disposable> subscriptions;
    public MusicBot(LavalinkClient client, LanguageStore languages) {
        this(client, languages, new YouTubeSearch(System.getenv("YOUTUBE_API_KEY")));
    }
    public MusicBot(LavalinkClient client, LanguageStore languages, YouTubeSearch youtube) {
        this(client, languages, youtube, YtDlp.defaults());
    }
    MusicBot(LavalinkClient client, LanguageStore languages, YouTubeSearch youtube, YtDlp extractor) {
        super(Set.of("repeat", "play", "lyrics", "skip", "pause", "resume", "queue", "stop", "leave", "volume"), languages);
        this.client = client;
        this.youtube = youtube;
        this.extractor = extractor;
        subscriptions = List.of(
            client.on(TrackEndEvent.class).subscribe(e -> {
                if (Set.of("FINISHED", "LOAD_FAILED").contains(e.getEndReason().name()))
                    serial(e.getGuildId(), () -> finish(e.getGuildId(), e.getTrack(), e.getEndReason().name().equals("FINISHED")));
            }),
            client.on(TrackExceptionEvent.class).subscribe(e -> serial(e.getGuildId(), () -> reportFailure(e.getGuildId(), e.getTrack()))),
            client.on(TrackStuckEvent.class).subscribe(e -> serial(e.getGuildId(), () -> {
                reportFailure(e.getGuildId(), e.getTrack());
                finish(e.getGuildId(), e.getTrack());
            }))
        );
    }
    public static List<CommandData> commands() {
        return LocalizedCommands.apply(List.of(
            Commands.slash("play", "Musik suchen oder YouTube-/Spotify-/SoundCloud-/Bandcamp-Link laden")
                .addOption(OptionType.STRING, "query", "Suchbegriff, Link oder Spotify-URI", true)
                .addOptions(new OptionData(OptionType.STRING, "source", "Quelle für Textsuche")
                    .addChoice("YouTube", "youtube").addChoice("SoundCloud", "soundcloud").addChoice("Spotify", "spotify")),
            Commands.slash("lyrics", "Lyrics zum aktuellen Song oder einem Suchbegriff finden")
                .addOption(OptionType.STRING, "query", "Songtitel und Künstler; leer für den aktuellen Song", false),
            Commands.slash("repeat", "Toggle repeating the current song")
                .addOptions(new OptionData(OptionType.STRING, "mode", "on or off")
                    .addChoice("On", "on").addChoice("Off", "off")),
            Commands.slash("skip", "Nächster Titel"), Commands.slash("pause", "Pausieren"),
            Commands.slash("resume", "Fortsetzen"), Commands.slash("queue", "Warteschlange anzeigen"),
            Commands.slash("stop", "Stoppen und Warteschlange leeren"),
            Commands.slash("leave", "Sprachkanal verlassen"),
            Commands.slash("volume", "Lautstärke ändern").addOptions(new OptionData(OptionType.INTEGER, "percent", "0 bis 100", true).setRequiredRange(0, 100))
        ));
    }
    @Override protected net.dv8tion.jda.api.entities.MessageEmbed handleEmbed(CommandContext event, Language language) {
        if (!event.getName().equals("lyrics")) return null;
        var option = event.getOption("query");
        String query;
        if (option != null) query = option.getAsString();
        else {
            var queue = queues.get(event.getGuild().getIdLong());
            require(queue != null && queue.current() != null, "lyrics.no.track");
            var info = queue.current().getInfo();
            query = LyricsSearch.currentQuery(info.getTitle(), info.getAuthor());
        }
        var found = lyrics.find(query);
        String body = found.instrumental() ? Messages.text(language, "lyrics.instrumental") : found.lyrics();
        if (body.length() > 3700) body = body.substring(0, 3700) + "\n\n" + Messages.text(language, "lyrics.shortened");
        return ToriEmbeds.create(ToriEmbeds.Category.MUSIC, language)
            .setTitle(clip(found.artist() + " — " + found.title(), 256), found.url())
            .setDescription(body).setFooter(ToriEmbeds.footer(language, Messages.text(language, "lyrics.footer"))).build();
    }
    private void finish(long id, Track ended) {
        finish(id, ended, false);
    }
    private void finish(long id, Track ended, boolean naturalEnd) {
        var queue = queues.get(id);
        if (queue != null && queue.current() != null && queue.current().getUserData().path("playbackId")
            .equals(ended.getUserData().path("playbackId"))) advance(id, naturalEnd);
    }
    private void reportFailure(long id, Track failed) {
        var channel = replyChannels.get(id);
        var queue = queues.get(id);
        var current = queue == null ? null : queue.current();
        if (channel == null || current == null || !current.getUserData().path("playbackId").equals(failed.getUserData().path("playbackId"))) return;
        channel.sendMessage(Messages.text(languages.get(Long.toString(id)), "music.playback.failed",
            clip(current.getInfo().getTitle(), 150))).setAllowedMentions(List.of()).queue(null,
                ex -> org.slf4j.LoggerFactory.getLogger(MusicBot.class).warn("Could not deliver playback failure ({})", ex.getClass().getSimpleName()));
    }
    @Override protected String handle(CommandContext event, Language language) {
        var guild = event.getGuild();
        long id = guild.getIdLong();
        var queue = queues.computeIfAbsent(id, ignored -> new TrackQueue<>());
        if (event.getName().equals("queue")) {
            StringBuilder text = new StringBuilder(Messages.text(language, "queue.current", queue.current() == null ? Messages.text(language, "queue.nothing") : clip(queue.current().getInfo().getTitle(), 150)));
            var pending = queue.snapshot();
            for (int i = 0; i < Math.min(10, pending.size()); i++) text.append("\n").append(i + 1).append(". ").append(clip(pending.get(i).getInfo().getTitle(), 100));
            return text.append("\n").append(Messages.text(language, "queue.pending", pending.size())).toString();
        }
        var member = event.getMember();
        var voice = member == null || member.getVoiceState() == null ? null : member.getVoiceState().getChannel();
        require(voice != null, "voice.join");
        require(voice.getType() == ChannelType.VOICE, "voice.normal");
        var selfVoice = guild.getSelfMember().getVoiceState();
        Long botChannel = channels.get(id);
        if (selfVoice != null && selfVoice.getChannel() != null) {
            botChannel = selfVoice.getChannel().getIdLong();
        }
        require(botChannel == null || botChannel == voice.getIdLong(), "voice.same");
        if (event.getName().equals("leave")) {
            queue.clear();
            var cached = client.getLinkIfCached(id);
            if (cached != null) cached.destroy().block(TIMEOUT);
            event.getJDA().getDirectAudioController().disconnect(guild);
            channels.remove(id);
            replyChannels.remove(id);
            return Messages.text(language, "voice.left");
        }
        if (!event.getName().equals("play")) require(botChannel != null, "voice.start");
        var link = client.getOrCreateLink(id);
        switch (event.getName()) {
            case "repeat" -> {
                require(queue.current() != null, "music.none");
                var option = event.getOption("mode");
                if (option != null) require(Set.of("on", "off").contains(option.getAsString()), "error.input");
                queue.repeat(option == null ? !queue.repeating() : option.getAsString().equals("on"));
                return Messages.text(language, queue.repeating() ? "repeat.on" : "repeat.off");
            }
            case "play" -> {
                require(guild.getSelfMember().hasPermission(voice, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK), "voice.permissions");
                String source = event.getOption("source") == null ? "youtube" : event.getOption("source").getAsString();
                String identifier = MusicInput.resolve(event.getOption("query").getAsString(), source);
                boolean spotify = identifier.contains("open.spotify.com/") || identifier.startsWith("spsearch:");
                if (spotify) youtube.requireConfigured();
                var result = link.loadItem(identifier).block(TIMEOUT);
                List<Track> tracks;
                if (result instanceof TrackLoaded loaded) tracks = List.of(loaded.getTrack());
                else if (result instanceof SearchResult search) tracks = search.getTracks().stream().limit(1).toList();
                else if (result instanceof PlaylistLoaded playlist) tracks = playlist.getTracks();
                else if (result instanceof LoadFailed) return Messages.text(language, "music.load.failed");
                else tracks = List.of();
                if (tracks.isEmpty()) return Messages.text(language, "music.no.matches");
                tracks.forEach(t -> t.setUserData(Map.of("playbackId", UUID.randomUUID().toString())));
                queue.add(tracks);
                replyChannels.put(id, event.getChannel());
                if (botChannel == null) {
                    channels.put(id, voice.getIdLong());
                    try { event.getJDA().getDirectAudioController().connect(voice); }
                    catch (RuntimeException ex) { channels.remove(id); queue.clear(); throw ex; }
                }
                if (queue.current() == null) advance(id);
                return Messages.text(language, "music.added", tracks.size(), clip(tracks.getFirst().getInfo().getTitle(), 150))
                    + (spotify ? "\n" + Messages.text(language, "music.spotify") : "");
            }
            case "skip" -> { advance(id); return queue.current() == null ? Messages.text(language, "music.ended") : Messages.text(language, "music.now", clip(queue.current().getInfo().getTitle(), 150)); }
            case "pause", "resume" -> {
                require(queue.current() != null, "music.none");
                link.createOrUpdatePlayer().setPaused(event.getName().equals("pause")).block(TIMEOUT);
                return event.getName().equals("pause") ? Messages.text(language, "music.paused") : Messages.text(language, "music.resumed");
            }
            case "stop" -> { link.createOrUpdatePlayer().stopTrack().block(TIMEOUT); queue.clear(); return Messages.text(language, "music.stopped"); }
            case "volume" -> {
                int volume = event.getOption("percent").getAsInt();
                require(volume >= 0 && volume <= 100, "music.volume.range");
                link.createOrUpdatePlayer().setVolume(volume).block(TIMEOUT);
                return Messages.text(language, "music.volume", volume);
            }
            default -> { return Messages.text(language, "error.unknown"); }
        }
    }
    private void advance(long id) {
        advance(id, false);
    }
    private void advance(long id, boolean naturalEnd) {
        var queue = queues.get(id);
        if (queue == null) return;
        var link = client.getOrCreateLink(id);
        Track next = queue.advance(naturalEnd);
        if (next != null) next.setUserData(Map.of("playbackId", UUID.randomUUID().toString()));
        try {
            if (SpotifyPlayback.needsLookup(next) || ExtractedPlayback.needsLookup(next)) {
                link.createOrUpdatePlayer().stopTrack().block(TIMEOUT);
            }
            Track playable = SpotifyPlayback.resolve(next, youtube, url -> {
                var result = link.loadItem(url).block(TIMEOUT);
                return result instanceof TrackLoaded loaded ? loaded.getTrack() : null;
            });
            playable = ExtractedPlayback.resolve(playable, extractor::streamUrl, url -> {
                var result = link.loadItem(url).block(TIMEOUT);
                return result instanceof TrackLoaded loaded ? loaded.getTrack() : null;
            });
            link.createOrUpdatePlayer().setTrack(playable).setPaused(false).block(TIMEOUT);
        } catch (RuntimeException ex) {
            queue.finishCurrent();
            try { link.createOrUpdatePlayer().stopTrack().block(TIMEOUT); }
            catch (RuntimeException ignored) { /* Preserve the original failure. */ }
            org.slf4j.LoggerFactory.getLogger(MusicBot.class).warn("Playback stopped for guild {} ({}); remaining queue preserved",
                id, ex instanceof UserError ? ex.getMessage() : ex.getClass().getSimpleName());
            throw ex;
        }
    }
    @Override public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getMember().getIdLong() == event.getJDA().getSelfUser().getIdLong() && event.getChannelJoined() == null)
            serial(event.getGuild().getIdLong(), () -> {
                long id = event.getGuild().getIdLong();
                queues.remove(id); channels.remove(id); replyChannels.remove(id);
                var link = client.getLinkIfCached(id);
                if (link != null) link.destroy().block(TIMEOUT);
            });
    }
    /** Stop command work, destroy Lavalink players, and disconnect voice before JDA shuts down. */
    public void close(JDA jda) {
        subscriptions.forEach(Disposable::dispose);
        super.close();
        var ids = new HashSet<>(queues.keySet());
        ids.addAll(channels.keySet());
        try {
            reactor.core.publisher.Flux.fromIterable(ids).flatMap(id -> {
                var link = client.getLinkIfCached(id);
                return link == null ? reactor.core.publisher.Mono.empty() : link.destroy()
                    .onErrorResume(ex -> reactor.core.publisher.Mono.empty());
            }, 8).then().block(Duration.ofSeconds(10));
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(MusicBot.class).warn("Music shutdown could not finish every player request ({})", ex.getClass().getSimpleName());
        } finally {
            queues.values().forEach(TrackQueue::clear);
            queues.clear(); channels.clear(); replyChannels.clear();
            for (var guild : jda.getGuildCache()) {
                try {
                    var voice = guild.getSelfMember().getVoiceState();
                    if (voice != null && voice.getChannel() != null) jda.getDirectAudioController().disconnect(guild);
                } catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(MusicBot.class).warn("Voice disconnect failed during shutdown ({})", ex.getClass().getSimpleName());
                }
            }
        }
    }
    @Override public void close() { subscriptions.forEach(Disposable::dispose); super.close(); queues.clear(); channels.clear(); replyChannels.clear(); }
}
