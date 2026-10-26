package music;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;
import java.util.Set;

/** JDA transport names for social actions; Elixir remains authoritative for action rules. */
final class SocialActionCommands {
    private static final List<String> IDS =
        List.of("hug", "kiss", "pat", "cuddle", "poke", "slap", "bite", "tickle", "highfive", "boop", "wave");
    private static final Set<String> ID_SET = Set.copyOf(IDS);

    private SocialActionCommands() {}

    static List<String> ids() { return IDS; }
    static boolean contains(String id) { return ID_SET.contains(id); }

    static List<CommandData> definitions() {
        return IDS.stream().<CommandData>map(id -> Commands.slash(id, "Perform a social action with another member.")
            .addOption(OptionType.USER, "user", "The member who receives the action", true)).toList();
    }
}
