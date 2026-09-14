package music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ThreadLocalReason;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.*;
import net.dv8tion.jda.api.interactions.commands.build.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ModerationBot extends CommandListener {
    private static final Map<String, Permission> PERMISSIONS = Map.of(
        "kick", Permission.KICK_MEMBERS, "ban", Permission.BAN_MEMBERS, "unban", Permission.BAN_MEMBERS,
        "timeout", Permission.MODERATE_MEMBERS, "untimeout", Permission.MODERATE_MEMBERS,
        "purge", Permission.MESSAGE_MANAGE, "slowmode", Permission.MANAGE_CHANNEL, "snipe", Permission.MESSAGE_MANAGE);
    final SnipeCache snipes = new SnipeCache();
    private final WebhookModLogger modlog;
    public ModerationBot(WebhookModLogger modlog, LanguageStore languages) { super(PERMISSIONS.keySet(), languages); this.modlog = modlog; }
    private static SlashCommandData command(String name, String description) {
        return Commands.slash(name, description).setDefaultPermissions(DefaultMemberPermissions.enabledFor(PERMISSIONS.get(name)));
    }
    private static SlashCommandData targetCommand(String name, String description) {
        return command(name, description).addOption(OptionType.USER, "user", "Zielperson", true);
    }
    private static SlashCommandData reason(SlashCommandData command) {
        return command.addOptions(new OptionData(OptionType.STRING, "reason", "Begründung für das Audit-Log").setMaxLength(350));
    }
    public static List<CommandData> commands() {
        return LocalizedCommands.apply(List.of(
            command("snipe", "Show the last cached deleted message in this channel"),
            reason(targetCommand("kick", "Mitglied vom Server entfernen")),
            reason(targetCommand("ban", "Mitglied bannen; vorhandene Nachrichten bleiben erhalten")),
            reason(command("unban", "Bann anhand einer Nutzer-ID aufheben").addOption(OptionType.STRING, "user_id", "Discord-Nutzer-ID", true)),
            reason(targetCommand("timeout", "Mitglied vorübergehend stummschalten")
                .addOptions(new OptionData(OptionType.INTEGER, "minutes", "1 bis 40320 Minuten (28 Tage)", true).setRequiredRange(1, 40320))),
            reason(targetCommand("untimeout", "Timeout aufheben")),
            reason(command("purge", "Junge, ungepinnte Nachrichten im Textkanal löschen")
                .addOptions(new OptionData(OptionType.INTEGER, "count", "Letzte 1 bis 100 Nachrichten prüfen", true).setRequiredRange(1, 100))),
            reason(command("slowmode", "Slowmode im Textkanal setzen")
                .addOptions(new OptionData(OptionType.INTEGER, "seconds", "0 (aus) bis 21600 Sekunden", true).setRequiredRange(0, 21600)))
        ));
    }
    @Override protected String handle(CommandContext event, Language language) {
        Guild guild = event.getGuild();
        // Refresh the invoking member rather than trusting a potentially stale cache entry.
        Member actor = guild.retrieveMemberById(event.getUser().getId()).complete();
        Member bot = guild.getSelfMember();
        Permission needed = PERMISSIONS.get(event.getName());
        if (event.getName().equals("snipe")) {
            require(event.getChannelType() == ChannelType.TEXT, "mod.text.channel");
            var channel = event.getChannel().asTextChannel();
            require(actor.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY, needed),
                "mod.actor.channel.permission", "View Channel, Read Message History, Manage Messages");
            require(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY), "mod.history.permission");
            var deleted = snipes.last(guild.getIdLong(), channel.getIdLong());
            if (deleted == null) return Messages.text(language, "snipe.empty");
            return Messages.text(language, "snipe.result", deleted.authorId(), deleted.deletedAt().getEpochSecond(), deleted.content());
        }
        boolean channelCommand = Set.of("purge", "slowmode").contains(event.getName());
        if (channelCommand) {
            require(event.getChannelType() == ChannelType.TEXT, "mod.text.channel");
            var channel = event.getChannel().asTextChannel();
            require(actor.hasPermission(channel, needed), "mod.actor.channel.permission", Messages.text(language, "perm." + needed.name()));
            require(bot.hasPermission(channel, needed), "mod.bot.channel.permission", Messages.text(language, "perm." + needed.name()));
        } else {
            require(actor.hasPermission(needed), "mod.actor.permission", Messages.text(language, "perm." + needed.name()));
            require(bot.hasPermission(needed), "mod.bot.permission", Messages.text(language, "perm." + needed.name()));
        }
        String reason = Messages.text(language, "mod.audit.reason", actor.getId(), event.getOption("reason") == null ? Messages.text(language, "mod.no.reason") : event.getOption("reason").getAsString());
        reason = clip(reason, 450);
        switch (event.getName()) {
            case "unban" -> {
                String id = event.getOption("user_id").getAsString().trim();
                require(id.matches("[0-9]{17,20}"), "mod.user.id");
                guild.unban(UserSnowflake.fromId(id)).reason(reason).complete();
                return completed(event, language, Messages.text(language, "mod.user", id), Messages.text(language, "mod.unbanned", id));
            }
            case "purge" -> {
                var channel = event.getChannel().asTextChannel();
                require(bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY), "mod.history.permission");
                int count = event.getOption("count").getAsInt();
                require(count >= 1 && count <= 100, "mod.purge.range");
                long replyId = event.replyId();
                var history = channel.getHistory().retrievePast(count).complete();
                // One-minute buffer prevents crossing Discord's 14-day boundary during the request.
                var cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(14).plusMinutes(1);
                var messages = history.stream().filter(m -> m.getIdLong() != replyId
                    && !m.isPinned() && m.getTimeCreated().isAfter(cutoff)).toList();
                if (messages.isEmpty()) return completed(event, language, Messages.text(language, "mod.purge.target", channel.getId(), count),
                    Messages.text(language, "mod.purge.noop"));
                if (messages.size() == 1) channel.deleteMessageById(messages.getFirst().getId()).reason(reason).complete();
                else try (var ignored = ThreadLocalReason.closable(reason)) { channel.deleteMessages(messages).complete(); }
                return completed(event, language, Messages.text(language, "mod.purge.target", channel.getId(), count),
                    Messages.text(language, "mod.purge.done", messages.size(), history.size() - messages.size()));
            }
            case "slowmode" -> {
                int seconds = event.getOption("seconds").getAsInt();
                require(seconds >= 0 && seconds <= 21600, "mod.slowmode.range");
                event.getChannel().asTextChannel().getManager().setSlowmode(seconds).reason(reason).complete();
                return completed(event, language, Messages.text(language, "mod.slowmode.target", event.getChannel().getId(), seconds),
                    seconds == 0 ? Messages.text(language, "mod.slowmode.off") : Messages.text(language, "mod.slowmode.done", seconds));
            }
            default -> {
                Member target = guild.retrieveMemberById(event.getOption("user").getAsUser().getId()).complete();
                boolean timeout = event.getName().equals("timeout");
                ModerationPolicy.target(actor.getIdLong(), target.getIdLong(), bot.getIdLong(), target.isOwner(),
                    target.hasPermission(Permission.ADMINISTRATOR), actor.canInteract(target), bot.canInteract(target), timeout);
                switch (event.getName()) {
                    case "kick" -> guild.kick(target).reason(reason).complete();
                    case "ban" -> guild.ban(target, 0, TimeUnit.SECONDS).reason(reason).complete();
                    case "timeout" -> {
                        int minutes = event.getOption("minutes").getAsInt();
                        require(minutes >= 1 && minutes <= 40320, "mod.timeout.range");
                        target.timeoutFor(Duration.ofMinutes(minutes)).reason(reason).complete();
                    }
                    case "untimeout" -> target.removeTimeout().reason(reason).complete();
                    default -> throw new UserError("error.unknown");
                }
                String details = target.getUser().getName() + " (" + target.getId() + ")";
                if (timeout) details += Messages.text(language, "mod.duration", event.getOption("minutes").getAsInt());
                return completed(event, language, details, Messages.text(language, "mod.done", event.getName(), target.getId()));
            }
        }
    }

    private String completed(CommandContext event, Language language, String target, String result) {
        // Called only after Discord confirms the operation (or after a successful no-op inspection).
        // Logging must never turn an already completed moderation into an apparent failure.
        try {
            modlog.publish(new WebhookModLogger.Entry(event.getId(), event.getName(), event.getGuild().getName(),
                event.getGuild().getId(), event.getChannel().getId(), event.getUser().getName(), event.getUser().getId(),
                target, event.getOption("reason") == null ? Messages.text(language, "mod.no.reason") : event.getOption("reason").getAsString(),
                result, Instant.now(), language));
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(ModerationBot.class).warn("Modlog konnte nicht erstellt werden; Moderation wurde abgeschlossen.");
        }
        return result;
    }

    @Override public void close() { super.close(); snipes.clear(); modlog.close(); }
}
