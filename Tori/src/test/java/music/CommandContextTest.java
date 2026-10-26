package music;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class CommandContextTest {
    @Test void cachedTextChannelRequiresAnAttachedMatchingGuildChannel() {
        TextChannel matching = channel(42L);
        Guild guild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(), new Class<?>[] {Guild.class},
            (proxy, method, args) -> method.getName().equals("getTextChannelById") && Long.valueOf(42L).equals(args[0])
                ? matching : null);

        assertSame(matching, CommandContext.cachedTextChannel(guild, 42L));
        assertNull(CommandContext.cachedTextChannel(guild, 43L));
        assertNull(CommandContext.cachedTextChannel(null, 42L));

        Guild mismatchedGuild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(), new Class<?>[] {Guild.class},
            (proxy, method, args) -> method.getName().equals("getTextChannelById") ? channel(99L) : null);
        assertNull(CommandContext.cachedTextChannel(mismatchedGuild, 42L));
    }

    private static TextChannel channel(long id) {
        return (TextChannel) Proxy.newProxyInstance(TextChannel.class.getClassLoader(), new Class<?>[] {TextChannel.class},
            (proxy, method, args) -> method.getName().equals("getIdLong") ? id : null);
    }
}
