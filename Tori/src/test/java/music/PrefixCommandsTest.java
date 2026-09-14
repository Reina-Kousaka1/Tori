package music;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class PrefixCommandsTest {
    @TempDir Path dir;
    private SlashCommandData command(String name) {
        return (SlashCommandData)CommandRegistration.definitions().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }
    @Test void freeTextSearchPreservesApostrophesAndUrlParameters() {
        assertEquals(Map.of("query", "Ain't in L.A. from Adela"), PrefixCommands.parse(command("play"), "Ain't in L.A. from Adela"));
        assertEquals(Map.of("query", "https://youtube.com/watch?v=abc&list=def"), PrefixCommands.parse(command("play"), "https://youtube.com/watch?v=abc&list=def"));
        assertEquals(Map.of("query", "Adela Ain't In LA", "source", "youtube"), PrefixCommands.parse(command("play"), "Adela Ain't In LA --source youtube"));
    }
    @Test void moderationArgumentsAndOptionalLyrics() {
        assertEquals(Map.of("user", "<@123456789012345678>", "minutes", "5", "reason", "Too much spam"),
            PrefixCommands.parse(command("timeout"), "<@123456789012345678> 5 Too much spam"));
        assertEquals(Map.of(), PrefixCommands.parse(command("lyrics"), ""));
        assertThrows(IllegalArgumentException.class, () -> PrefixCommands.parse(command("play"), ""));
        assertThrows(IllegalArgumentException.class, () -> PrefixCommands.parse(command("play"), "song --source invalid"));
        assertThrows(IllegalArgumentException.class, () -> PrefixCommands.parse(command("restart"), "extra"));
    }
    @Test void settingsPersistAndStayIsolatedPerServer() throws Exception {
        var store = new ModLogStore(dir.resolve("bot.db"));
        var prefixes = new PrefixSettings(store);
        assertEquals("T.", prefixes.get("server1"));
        prefixes.set("server1", "??");
        var reopened = new PrefixSettings(store);
        assertEquals("??", reopened.get("server1"));
        assertEquals("T.", reopened.get("server2"));
        assertThrows(UserError.class, () -> reopened.set("server1", "@everyone"));
        assertEquals("??", reopened.get("server1"));
    }
}
