package music;

import dev.arbjerg.lavalink.client.LavalinkClient;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SwitchStatementWithTooFewBranches")
class MusicBotTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void disconnectedBotReturnsVoiceStartInsteadOfNullPointer(boolean missingVoiceState) throws Exception {
        try (var client = new LavalinkClient(123456789012345678L);
             var bot = new MusicBot(client, new LanguageStore(directory.resolve("language.properties"), Language.EN))) {
            var event = disconnectedEvent(missingVoiceState);
            var error = assertThrows(UserError.class, () -> bot.handle(event, Language.EN));
            assertEquals("voice.start", error.getMessage());
        }
    }

    private static SlashCommandInteractionEvent disconnectedEvent(boolean missingVoiceState) {
        var voice = stub(AudioChannelUnion.class, (p, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 20L;
            case "getType" -> ChannelType.VOICE;
            default -> throw new AssertionError(m);
        });
        var memberVoice = stub(GuildVoiceState.class, (p, m, a) -> switch (m.getName()) {
            case "getChannel" -> voice;
            default -> throw new AssertionError(m);
        });
        Member member = stub(Member.class, (p, m, a) -> switch (m.getName()) {
            case "getVoiceState" -> memberVoice;
            default -> throw new AssertionError(m);
        });
        var selfVoice = stub(GuildVoiceState.class, (p, m, a) -> switch (m.getName()) {
            case "getChannel" -> null;
            default -> throw new AssertionError(m);
        });
        SelfMember self = stub(SelfMember.class, (p, m, a) -> switch (m.getName()) {
            case "getVoiceState" -> missingVoiceState ? null : selfVoice;
            default -> throw new AssertionError(m);
        });
        var guild = stub(Guild.class, (p, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 10L;
            case "getSelfMember" -> self;
            default -> throw new AssertionError(m);
        });
        var jda = stub(JDA.class, (p, m, a) -> { throw new AssertionError(m); });
        var interaction = stub(SlashCommandInteraction.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "pause";
            case "getGuild" -> guild;
            case "getMember" -> member;
            default -> throw new AssertionError(m);
        });
        return new SlashCommandInteractionEvent(jda, 0, interaction);
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
