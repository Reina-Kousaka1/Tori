package music;

import java.util.*;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.*;

final class PrefixCommands extends ListenerAdapter {
    private final PrefixSettings settings;
    private final List<CommandListener> handlers;
    private final Map<String, SlashCommandData> definitions = new HashMap<>();
    PrefixCommands(PrefixSettings settings, CommandListener... handlers) {
        this.settings = settings; this.handlers = List.of(handlers);
        CommandRegistration.definitions().forEach(c -> definitions.put(c.getName(), (SlashCommandData)c));
    }
    @Override public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) return;
        String content = event.getMessage().getContentRaw();
        String prefix = settings.get(event.getGuild().getId());
        if (!content.startsWith(prefix)) return;
        String[] parts = content.substring(prefix.length()).strip().split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        var definition = definitions.get(name);
        if (definition == null) return;
        for (var handler : handlers) if (handler.accepts(name)) {
            try { handler.prefix(event, name, parse(definition, parts.length == 1 ? "" : parts[1])); }
            catch (IllegalArgumentException ex) {
                String usage = prefix + name + definition.getOptions().stream()
                    .map(o -> " " + (o.isRequired() ? "<" : "[") + o.getName() + (o.isRequired() ? ">" : "]"))
                    .reduce("", String::concat);
                event.getChannel().sendMessage("`" + usage + "`").setAllowedMentions(List.of()).queue(null, ignored -> {});
            }
            return;
        }
    }
    static Map<String, String> parse(SlashCommandData definition, String input) {
        var result = new LinkedHashMap<String, String>();
        var options = definition.getOptions();
        // Named arguments allow free-text queries and reasons without shell-style escaping.
        var matcher = Pattern.compile("(?:^|\\s)--([a-z_]+)\\s+").matcher(input);
        var starts = new ArrayList<Integer>(); var ends = new ArrayList<Integer>(); var names = new ArrayList<String>();
        while (matcher.find()) { starts.add(matcher.start()); ends.add(matcher.end()); names.add(matcher.group(1)); }
        for (int i = 0; i < names.size(); i++) {
            String key = names.get(i);
            if (options.stream().noneMatch(o -> o.getName().equals(key)) || result.containsKey(key)) throw new IllegalArgumentException();
            result.put(key, input.substring(ends.get(i), i + 1 < names.size() ? starts.get(i + 1) : input.length()).strip());
        }
        String remaining = input.substring(0, starts.isEmpty() ? input.length() : starts.getFirst()).strip();
        for (int i = 0; i < options.size() && !remaining.isEmpty(); i++) {
            var option = options.get(i);
            if (result.containsKey(option.getName())) continue;
            boolean rest = Set.of("query", "reason", "texts").contains(option.getName()) || i == options.size() - 1;
            String[] split = remaining.split("\\s+", 2);
            result.put(option.getName(), rest ? remaining : split[0]);
            remaining = rest || split.length == 1 ? "" : split[1];
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException();
        for (var option : options) {
            String value = result.get(option.getName());
            if (value == null) { if (option.isRequired()) throw new IllegalArgumentException(); else continue; }
            if (value.isBlank()) throw new IllegalArgumentException();
            if (option.getType() == net.dv8tion.jda.api.interactions.commands.OptionType.INTEGER) Long.parseLong(value);
            if (!option.getChoices().isEmpty() && option.getChoices().stream().noneMatch(c -> c.getAsString().equals(value))) throw new IllegalArgumentException();
        }
        return Map.copyOf(result);
    }
}
