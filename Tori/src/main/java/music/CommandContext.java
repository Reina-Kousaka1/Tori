package music;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import java.util.Map;

/** Shared command input; prefix messages are never forged Discord interactions. */
final class CommandContext {
    private final SlashCommandInteractionEvent slash;
    private final MessageReceivedEvent message;
    private final String name;
    private final Map<String, String> options;
    CommandContext(SlashCommandInteractionEvent slash) {
        this.slash = slash; message = null; name = null; options = Map.of();
    }
    CommandContext(MessageReceivedEvent message, String name, Map<String, String> options) {
        slash = null; this.message = message; this.name = name; this.options = options;
    }
    String getName() { return slash != null ? slash.getName() : name; }
    Guild getGuild() { return slash != null ? slash.getGuild() : message.getGuild(); }
    User getUser() { return slash != null ? slash.getUser() : message.getAuthor(); }
    Member getMember() { return slash != null ? slash.getMember() : message.getMember(); }
    JDA getJDA() { return slash != null ? slash.getJDA() : message.getJDA(); }
    MessageChannelUnion getChannel() { return slash != null ? slash.getChannel() : message.getChannel(); }
    ChannelType getChannelType() { return slash != null ? slash.getChannelType() : message.getChannelType(); }
    String getId() { return slash != null ? slash.getId() : message.getMessageId(); }
    long replyId() { return slash != null ? slash.getHook().retrieveOriginal().complete().getIdLong() : message.getMessageIdLong(); }
    Value getOption(String key) {
        if (slash != null) {
            var option = slash.getOption(key);
            return option == null ? null : new Value(option);
        }
        return options.containsKey(key) ? new Value(options.get(key)) : null;
    }
    final class Value {
        private final String value;
        private final net.dv8tion.jda.api.interactions.commands.OptionMapping mapping;
        Value(String value) { this.value = value; mapping = null; }
        Value(net.dv8tion.jda.api.interactions.commands.OptionMapping mapping) { this.mapping = mapping; value = null; }
        String getAsString() { return mapping == null ? value : mapping.getAsString(); }
        long getAsLong() { return mapping == null ? Long.parseLong(value) : mapping.getAsLong(); }
        int getAsInt() { return mapping == null ? Integer.parseInt(value) : mapping.getAsInt(); }
        User getAsUser() {
            if (mapping != null) return mapping.getAsUser();
            String id = value.replaceAll("^<@!?([0-9]+)>$", "$1");
            if (!id.matches("[0-9]{17,20}")) throw new UserError("mod.user.id");
            return getJDA().retrieveUserById(id).timeout(10, java.util.concurrent.TimeUnit.SECONDS).complete();
        }
    }
}
