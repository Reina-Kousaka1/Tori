package music;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.*;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import java.time.*;
import java.util.*;

/** Bounded, in-memory cache: only messages observed during this bot session. */
final class SnipeCache extends ListenerAdapter {
    private static final Duration TTL = Duration.ofHours(1);
    private final Clock clock;
    private final int capacity;
    private final LinkedHashMap<Long, Cached> messages = new LinkedHashMap<>();
    private final LinkedHashMap<Channel, Deleted> deleted = new LinkedHashMap<>();
    record Channel(long guild, long channel) {}
    record Cached(Channel channel, long authorId, String content, Instant observedAt) {}
    record Deleted(long authorId, String content, Instant deletedAt) {}
    SnipeCache() { this(Clock.systemUTC(), 2000); }
    SnipeCache(Clock clock, int capacity) { this.clock = clock; this.capacity = capacity; }

    private void remember(Message message) {
        if (!message.isFromGuild() || message.getAuthor().isBot() || message.isWebhookMessage()) return;
        String content = message.getContentRaw();
        for (var attachment : message.getAttachments()) content += "\n<" + attachment.getUrl() + ">";
        remember(message.getIdLong(), message.getGuild().getIdLong(), message.getChannel().getIdLong(),
            message.getAuthor().getIdLong(), content);
    }
    synchronized void remember(long id, long guild, long channel, long author, String content) {
        prune();
        messages.remove(id);
        if (content.isBlank()) return;
        messages.put(id, new Cached(new Channel(guild, channel), author,
            content.substring(0, Math.min(1700, content.length())), clock.instant()));
        trim(messages);
    }
    synchronized void delete(long id, long guild, long channel) {
        prune();
        var key = new Channel(guild, channel);
        var cached = messages.remove(id);
        // Never present an older deletion as the latest when its replacement was not cached.
        deleted.remove(key);
        if (cached != null && cached.channel().equals(key)) {
            deleted.put(key, new Deleted(cached.authorId(), cached.content(), clock.instant()));
            trim(deleted);
        }
    }
    synchronized Deleted last(long guild, long channel) { prune(); return deleted.get(new Channel(guild, channel)); }
    synchronized void clear() { messages.clear(); deleted.clear(); }
    private void prune() {
        Instant cutoff = clock.instant().minus(TTL);
        messages.values().removeIf(value -> !value.observedAt().isAfter(cutoff));
        deleted.values().removeIf(value -> !value.deletedAt().isAfter(cutoff));
    }
    private <K, V> void trim(LinkedHashMap<K, V> map) {
        while (map.size() > capacity) map.remove(map.keySet().iterator().next());
    }
    @Override public void onMessageReceived(MessageReceivedEvent event) { remember(event.getMessage()); }
    @Override public void onMessageUpdate(MessageUpdateEvent event) { remember(event.getMessage()); }
    @Override public void onMessageDelete(MessageDeleteEvent event) {
        if (event.isFromGuild()) delete(event.getMessageIdLong(), event.getGuild().getIdLong(), event.getChannel().getIdLong());
    }
    @Override public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        event.getMessageIds().stream().map(Long::parseLong).sorted().forEach(id ->
            delete(id, event.getGuild().getIdLong(), event.getChannel().getIdLong()));
    }
}