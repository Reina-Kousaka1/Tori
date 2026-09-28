package music;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compact help UI. Command descriptions come from the registration's i18n keys. */
final class ToriHelp {
    enum Section {
        GENERAL, MUSIC, ECONOMY, ACTIVITIES, SOCIAL, MODERATION, TICKETS_ORDERS, ADMIN
    }

    private static final Map<String, Section> SECTIONS = sections();
    private ToriHelp() {}

    private static Map<String, Section> sections() {
        var result = new LinkedHashMap<String, Section>();
        put(result, Section.GENERAL, "language", "help", "ping", "stats", "avatar", "prefix");
        put(result, Section.MUSIC, "play", "lyrics", "repeat", "skip", "pause", "resume", "queue", "stop", "leave", "volume");
        put(result, Section.ECONOMY, "balance", "daily", "transfer", "leaderboard", "shop", "buy", "sell",
            "inventory", "equip", "unequip", "tools", "iteminfo", "pricehistory");
        put(result, Section.ACTIVITIES, "beg", "work", "loot", "gamble", "slots", "fish", "mine",
            "chop", "craft", "repair", "opencrate");
        put(result, Section.MODERATION, "snipe", "kick", "ban", "unban", "timeout", "untimeout", "purge", "slowmode");
        put(result, Section.TICKETS_ORDERS, "ticket", "order");
        put(result, Section.ADMIN, "restart", "shutdown", "uptime", "status", "grantcredits", "grantitem", "market");
        return Map.copyOf(result);
    }

    private static void put(Map<String, Section> result, Section section, String... names) {
        for (String name : names) {
            if (result.putIfAbsent(name, section) != null) throw new IllegalStateException("Duplicate help command: " + name);
        }
    }

    static Set<String> registeredNames() {
        var result = new java.util.LinkedHashSet<String>();
        for (List<CommandData> commands : List.of(GeneralBot.commands(), MusicBot.commands(),
                ModerationBot.commands(), TicketOrderBot.commands()))
            for (CommandData command : commands) result.add(command.getName());
        return Set.copyOf(result);
    }

    static Set<String> unmappedNames() {
        var names = new java.util.HashSet<>(registeredNames());
        names.removeAll(SECTIONS.keySet());
        return Set.copyOf(names);
    }

    static Map<Section, List<String>> entries() {
        var result = new EnumMap<Section, List<String>>(Section.class);
        for (Section section : Section.values()) result.put(section, new ArrayList<>());
        for (String name : registeredNames()) {
            var section = SECTIONS.get(name);
            if (section == null) throw new IllegalStateException("Unmapped help command: " + name);
            result.get(section).add(name);
        }
        for (var names : result.values()) names.sort(String::compareTo);
        return result;
    }

    static MessageEmbed overview(Language language) {
        var embed = ToriEmbeds.create(ToriEmbeds.Category.INFO, language)
            .setTitle(Messages.text(language, "help.title"))
            .setDescription(Messages.text(language, "help.v2.intro"));
        var entries = entries();
        for (Section section : Section.values())
            embed.addField(label(language, section), entries.get(section).isEmpty()
                ? Messages.text(language, "help.v2.empty")
                : entries.get(section).size() + " " + Messages.text(language, "help.v2.commands"), false);
        return embed.build();
    }

    static MessageEmbed category(Language language, Section section) {
        var names = entries().get(section);
        var body = new StringBuilder();
        for (String name : names) {
            if (!body.isEmpty()) body.append("\n\n");
            body.append("`/").append(name).append("`\n")
                .append(ToriEmbeds.shorten(Messages.text(language, "cmd." + name), 150));
        }
        if (body.isEmpty()) body.append(Messages.text(language, "help.v2.empty"));
        return ToriEmbeds.text(ToriEmbeds.Category.INFO, language, label(language, section), body.toString()).build();
    }

    static List<ActionRow> menu(String userId, Language language) {
        var menu = StringSelectMenu.create("tori:help:" + userId)
            .setPlaceholder(Messages.text(language, "help.v2.select"));
        for (Section section : Section.values()) menu.addOption(label(language, section), section.name());
        return List.of(ActionRow.of(menu.build()));
    }

    static String label(Language language, Section section) {
        return Messages.text(language, "help.v2." + section.name().toLowerCase(java.util.Locale.ROOT));
    }
}
