package music;

import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.build.*;
import java.util.List;

/** Discord displays descriptions in the client's language; command names stay stable. */
public final class LocalizedCommands {
    private LocalizedCommands() {}
    public static List<CommandData> apply(List<CommandData> commands) {
        for (var data : commands) {
            var slash = (SlashCommandData) data;
            String key = "cmd." + data.getName();
            slash.setDescription(Messages.text(Language.EN, key));
            for (var language : Language.values()) {
                for (var locale : locales(language)) {
                    slash.setDescriptionLocalization(locale, Messages.text(language, key));
                    for (var option : slash.getOptions())
                        option.setDescriptionLocalization(locale, Messages.text(language, optionKey(data.getName(), option.getName())));
                    for (var subcommand : slash.getSubcommands()) {
                        subcommand.setDescriptionLocalization(locale, Messages.text(language, subcommandKey(data.getName(), subcommand.getName())));
                        for (var option : subcommand.getOptions())
                            option.setDescriptionLocalization(locale, Messages.text(language, optionKey(data.getName(), option.getName())));
                    }
                    for (var group : slash.getSubcommandGroups()) {
                        group.setDescriptionLocalization(locale, Messages.text(language, subcommandKey(data.getName(), group.getName())));
                        for (var subcommand : group.getSubcommands()) {
                            subcommand.setDescriptionLocalization(locale, Messages.text(language, subcommandKey(data.getName(), subcommand.getName())));
                            for (var option : subcommand.getOptions())
                                option.setDescriptionLocalization(locale, Messages.text(language, optionKey(data.getName(), option.getName())));
                        }
                    }
                }
            }
            for (var option : slash.getOptions()) option.setDescription(Messages.text(Language.EN, optionKey(data.getName(), option.getName())));
            for (var subcommand : slash.getSubcommands()) {
                subcommand.setDescription(Messages.text(Language.EN, subcommandKey(data.getName(), subcommand.getName())));
                for (var option : subcommand.getOptions()) option.setDescription(Messages.text(Language.EN, optionKey(data.getName(), option.getName())));
            }
            for (var group : slash.getSubcommandGroups()) {
                group.setDescription(Messages.text(Language.EN, subcommandKey(data.getName(), group.getName())));
                for (var subcommand : group.getSubcommands()) {
                    subcommand.setDescription(Messages.text(Language.EN, subcommandKey(data.getName(), subcommand.getName())));
                    for (var option : subcommand.getOptions()) option.setDescription(Messages.text(Language.EN, optionKey(data.getName(), option.getName())));
                }
            }
        }
        return commands;
    }
    private static List<DiscordLocale> locales(Language language) {
        return switch (language) {
            case DE -> List.of(DiscordLocale.GERMAN);
            case EN -> List.of(DiscordLocale.ENGLISH_US, DiscordLocale.ENGLISH_UK);
            case NL -> List.of(DiscordLocale.DUTCH);
        };
    }

    static String optionKey(String command, String option) {
        if (command.equals("shop") && option.equals("category")) return "shop.option.category";
        if (command.equals("market")) return "market.option." + option;
        return "opt." + option;
    }

    private static String subcommandKey(String command, String subcommand) {
        return command.equals("market") ? "market.subcommand." + subcommand : "opt." + subcommand;
    }
}
