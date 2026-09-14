package music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class GeneralBot extends CommandListener {
    private final StatusRotation rotation;
    private final long configuredOwnerId;
    private final Runnable restart;
    private Runnable shutdown;
    private final Instant startedAt;
    private final Clock clock;
    private ModLogStore statsStore;
    private String creator;
    private PrefixSettings prefixes;
    GeneralBot withPrefixes(PrefixSettings prefixes) { this.prefixes = prefixes; return this; }
    GeneralBot withShutdown(Runnable shutdown) { this.shutdown = Objects.requireNonNull(shutdown); return this; }
    GeneralBot withStats(ModLogStore store, String creator) {
        this.statsStore = Objects.requireNonNull(store);
        this.creator = creator;
        return this;
    }
    // Guarded by rotation's monitor, alongside its scheduled presence updates.
    private boolean defaultRotation;
    private boolean closed;
    public GeneralBot(LanguageStore languages) { this(languages, new StatusRotation(), BotConfig.load().ownerId(), null); }
    GeneralBot(LanguageStore languages, StatusRotation rotation) {
        this(languages, rotation, 0, null);
    }
    GeneralBot(LanguageStore languages, StatusRotation rotation, long ownerId, Runnable restart) {
        this(languages, rotation, ownerId, restart, Instant.now());
    }
    GeneralBot(LanguageStore languages, StatusRotation rotation, long ownerId, Runnable restart, Instant startedAt) {
        this(languages, rotation, ownerId, restart, startedAt, Clock.systemUTC());
    }
    GeneralBot(LanguageStore languages, StatusRotation rotation, long ownerId, Runnable restart, Instant startedAt, Clock clock) {
        super(Set.of("prefix", "language", "help", "ping", "stats", "status", "restart", "shutdown", "uptime", "avatar"), languages);
        this.rotation = rotation;
        this.configuredOwnerId = ownerId;
        this.restart = restart;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public void onReady(ReadyEvent event) { refreshDefaultStatus(event.getJDA()); }
    @Override public void onGuildJoin(GuildJoinEvent event) { refreshDefaultStatus(event.getJDA()); }
    @Override public void onGuildLeave(GuildLeaveEvent event) { refreshDefaultStatus(event.getJDA()); }

    void refreshDefaultStatus(JDA jda) {
        synchronized (rotation) {
            if (!closed && !rotation.snapshot().running()) startDefaultStatus(jda);
        }
    }

    private void startDefaultStatus(JDA jda) {
        rotation.start(List.of("besties", "training"), StatusRotation.DEFAULT_INTERVAL_MS,
            slot -> applyDefaultStatus(jda, slot));
        defaultRotation = true;
    }

    private static void applyDefaultStatus(JDA jda, String slot) {
        var manager = jda.getShardManager();
        long servers = manager == null ? jda.getGuildCache().size() : manager.getGuildCache().size();
        int shards = jda.getShardInfo().getShardTotal();
        boolean training = slot.equals("training");
        var activity = training ? Activity.customStatus("At the Volleyball Training🏐 | (" + shards + ")")
            : Activity.playing("with my Besties! || " + servers + " servers | " + shards + " shards");
        applyPresence(jda, OnlineStatus.ONLINE, activity);
    }

    private static void applyPresence(JDA jda, OnlineStatus status, Activity activity) {
        var manager = jda.getShardManager();
        if (manager == null) jda.getPresence().setPresence(status, activity);
        else manager.setPresence(status, activity);
    }
    public static List<CommandData> commands() {
        return LocalizedCommands.apply(List.of(
            Commands.slash("language", "Show or set the server language")
                .addOptions(new OptionData(OptionType.STRING, "code", "Language")
                    .addChoice("Deutsch", "de").addChoice("English", "en").addChoice("Nederlands", "nl")),
            Commands.slash("help", "Show commands and usage"),
            Commands.slash("avatar", "Show a user's profile picture by Discord ID")
                .addOption(OptionType.STRING, "user_id", "Discord user ID", true),
            Commands.slash("restart", "Restart the bot (configured bot owner only)"),
            Commands.slash("shutdown", "Shut down the bot (bot owner only)"),
            Commands.slash("uptime", "Show session uptime (bot owner only)"),
            Commands.slash("ping", "Show WebSocket and bot REST latency in whole seconds"),
            Commands.slash("prefix", "Show or change this server's command prefix")
                .addOption(OptionType.STRING, "value", "New prefix", false),
            Commands.slash("stats", "Show uptime, servers, members and bot version"),
            Commands.slash("status", "Manage rotating bot status (bot owner only)")
                .addOptions(
                    new OptionData(OptionType.STRING, "action", "Start, stop or show the rotation")
                        .addChoice("Start", "start").addChoice("Stop", "stop").addChoice("Show", "show"),
                    new OptionData(OptionType.STRING, "texts", "One status or multiple texts separated by | or line breaks")
                        .setMaxLength(2000),
                    new OptionData(OptionType.INTEGER, "interval_ms", "Rotation interval in milliseconds")
                        .setRequiredRange(StatusRotation.MIN_INTERVAL_MS, StatusRotation.MAX_INTERVAL_MS))
        ));
    }
    @Override protected String handle(CommandContext event, Language language) {
        if (event.getName().equals("prefix")) {
            require(prefixes != null, "prefix.unavailable");
            var option = event.getOption("value");
            if (option == null) return Messages.text(language, "prefix.current", prefixes.get(event.getGuild().getId()));
            var member = event.getGuild().retrieveMemberById(event.getUser().getId()).complete();
            require(member.hasPermission(Permission.MANAGE_SERVER), "language.permission");
            String value = PrefixSettings.validate(option.getAsString());
            try { prefixes.set(event.getGuild().getId(), value); }
            catch (java.sql.SQLException ex) { throw new UserError("prefix.unavailable"); }
            return Messages.text(language, "prefix.changed", value);
        }
        if (event.getName().equals("help")) return help(language);
        if (event.getName().equals("uptime")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            return Messages.text(language, "stats.uptime") + ": " + sessionUptime(startedAt, clock.instant());
        }
        if (event.getName().equals("shutdown")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            require(shutdown != null, "shutdown.unavailable");
            return Messages.text(language, "shutdown.started");
        }
        if (event.getName().equals("restart")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            require(restart != null, "restart.unavailable");
            return Messages.text(language, "restart.started");
        }
        if (event.getName().equals("status")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            return status(event, language);
        }
        if (event.getName().equals("ping")) {
            long botPing = event.getJDA().getRestPing().timeout(10, TimeUnit.SECONDS).complete();
            long websocketPing = event.getJDA().getGatewayPing();
            return Messages.text(language, "ping.result", latency(language, websocketPing), latency(language, botPing));
        }
        if (event.getOption("code") == null) return Messages.text(language, "language.current", language.label);
        var member = event.getGuild().retrieveMemberById(event.getUser().getId()).complete();
        require(member.hasPermission(Permission.MANAGE_SERVER), "language.permission");
        Language selected = Language.parse(event.getOption("code").getAsString());
        try { languages.set(event.getGuild().getId(), selected); }
        catch (IOException ex) { throw new UserError("language.save.failed"); }
        return Messages.text(selected, "language.changed", selected.label);
    }
    private String status(CommandContext event, Language language) {
        var actionOption = event.getOption("action");
        String action = actionOption == null ? "show" : actionOption.getAsString();
        var textsOption = event.getOption("texts");
        var intervalOption = event.getOption("interval_ms");
        var jda = event.getJDA();
        synchronized (rotation) {
            require(!closed, "shutting.down");
            if (action.equals("show") || action.equals("stop")) {
                require(textsOption == null && intervalOption == null, "status.options");
            }
            switch (action) {
                case "start" -> {
                    var texts = StatusRotation.parseTexts(textsOption == null ? null : textsOption.getAsString());
                    long intervalMs = intervalOption == null ? StatusRotation.DEFAULT_INTERVAL_MS : intervalOption.getAsLong();
                    rotation.start(texts, intervalMs,
                        text -> applyPresence(jda, OnlineStatus.ONLINE, Activity.customStatus(text)));
                    defaultRotation = false;
                    return texts.size() == 1 ? Messages.text(language, "status.single.started")
                        : Messages.text(language, "status.started", texts.size(), intervalMs);
                }
                case "stop" -> {
                    startDefaultStatus(jda);
                    return Messages.text(language, "status.stopped");
                }
                case "show" -> {
                    if (defaultRotation) return Messages.text(language, "status.default");
                    var state = rotation.snapshot();
                    if (state.running() && state.texts().size() == 1) return Messages.text(language, "status.single.running");
                    return state.running() ? Messages.text(language, "status.running", state.texts().size(), state.intervalMs())
                        : Messages.text(language, "status.inactive");
                }
                default -> throw new UserError("status.action");
            }
        }
    }
    public static String help(Language language) {
        var embed = helpEmbed(language);
        var text = new StringBuilder(embed.getTitle());
        for (var field : embed.getFields()) text.append("\n\n").append(field.getName()).append("\n").append(field.getValue());
        return text.toString();
    }
    public static MessageEmbed helpEmbed(Language language) {
        var embed = ToriEmbeds.create(ToriEmbeds.Category.INFO, language)
            .setTitle(Messages.text(language, "help.title"));
        for (String section : List.of("music", "moderation", "general", "owner"))
            embed.addField(Messages.text(language, "help." + section + ".title"),
                Messages.text(language, "help." + section + ".body"), false);
        return embed.build();
    }
    @Override protected MessageEmbed handleEmbed(CommandContext event, Language language) {
        if (event.getName().equals("help")) return helpEmbed(language);
        if (event.getName().equals("stats")) {
            var jda = event.getJDA();
            var manager = jda.getShardManager();
            var guilds = manager == null ? jda.getGuildCache().asList() : manager.getGuildCache().asList();
            long members = guilds.stream().mapToLong(guild -> Math.max(0, guild.getMemberCount())).sum();
            var embed = ToriEmbeds.create(ToriEmbeds.Category.STATS, language)
                .setTitle(Messages.text(language, "stats.title"))
                .addField(Messages.text(language, "stats.uptime"), sessionUptime(startedAt, clock.instant()), false)
                .addField(Messages.text(language, "stats.servers"), Long.toString(guilds.size()), true)
                .addField(Messages.text(language, "stats.members"), members + "\n" + Messages.text(language, "stats.members.note"), false)
                .addField(Messages.text(language, "stats.version"), BotVersion.CURRENT, true)
                ;
            if (statsStore != null) {
                String owner = configuredOwnerId == 0 ? Messages.text(language, "stats.unset") : "<@" + configuredOwnerId + ">";
                var guild = event.getGuild();
                var channel = event.getChannel();
                embed.addField(Messages.text(language, "stats.guild"), clip(guild.getName(), 200) + " (`" + guild.getId() + "`)", false)
                    .addField(Messages.text(language, "stats.channel"), "<#" + channel.getId() + "> (`" + channel.getId() + "`)", false)
                    .addField(Messages.text(language, "stats.owner"), owner, true)
                    .addField(Messages.text(language, "stats.creator"), creator == null || creator.isBlank() ? Messages.text(language, "stats.unset") : clip(creator, 200), true)
                    .addField(Messages.text(language, "stats.code"), "Java 21 · JDA · Lavalink ❤️", false)
                    .setTimestamp(clock.instant())
                    .setFooter(ToriEmbeds.footer(language, Messages.text(language, "stats.bot.message", jda.getSelfUser().getName())));
                try {
                    var saved = statsStore.stats(jda.getSelfUser().getId(), guild.getId(), guild.getName(), channel.getId(), channel.getName());
                    embed.addField(Messages.text(language, "stats.last.restart"), saved.lastRestart() == null ? "—" : "<t:" + Instant.parse(saved.lastRestart()).getEpochSecond() + ":R>", true);
                } catch (java.sql.SQLException ex) {
                    embed.addField(Messages.text(language, "stats.database"), Messages.text(language, "stats.database.failed"), false);
                }
            }
            return embed.build();
        }
        if (!event.getName().equals("avatar")) return null;
        var option = event.getOption("user_id");
        String id = avatarUserId(option == null ? null : option.getAsString());
        try {
            User user = event.getJDA().retrieveUserById(id).timeout(10, TimeUnit.SECONDS).complete();
            return avatarEmbed(user, language);
        } catch (ErrorResponseException ex) {
            if (ex.getErrorResponse() == ErrorResponse.UNKNOWN_USER) throw new UserError("avatar.not_found");
            throw ex;
        }
    }
    static String avatarUserId(String value) {
        String id = value == null ? "" : value.strip();
        require(id.matches("[0-9]{17,20}"), "avatar.invalid_id");
        try { require(Long.parseUnsignedLong(id) != 0, "avatar.invalid_id"); }
        catch (NumberFormatException ex) { throw new UserError("avatar.invalid_id"); }
        return id;
    }
    static MessageEmbed avatarEmbed(User user, Language language) {
        String url = user.getEffectiveAvatarUrl();
        url += (url.contains("?") ? "&" : "?") + "size=1024";
        return ToriEmbeds.create(ToriEmbeds.Category.INFO, language)
            .setTitle(Messages.text(language, "avatar.title", clip(user.getEffectiveName(), 100)))
            .setDescription(Messages.text(language, "avatar.link", url))
            .setImage(url).setFooter(ToriEmbeds.footer(language, "ID: " + user.getId())).build();
    }
    @Override protected Runnable afterReply(CommandContext event) {
        if (event.getName().equals("shutdown")) return shutdown;
        return event.getName().equals("restart") ? restart : super.afterReply(event);
    }
    static String latency(Language language, long milliseconds) {
        return milliseconds < 0 ? Messages.text(language, "ping.unavailable") : Math.round(milliseconds / 1000.0) + " s";
    }
    static String sessionUptime(Instant startedAt, Instant now) {
        long seconds = Math.max(0, Duration.between(startedAt, now).getSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        if (seconds < 86400) return seconds / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
        return seconds / 86400 + "d " + seconds % 86400 / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
    }
    @Override public void close() {
        synchronized (rotation) {
            if (closed) return;
            closed = true;
            rotation.close();
        }
        super.close();
    }
}
