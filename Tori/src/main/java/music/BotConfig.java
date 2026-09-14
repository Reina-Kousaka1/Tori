package music;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import java.nio.file.Path;

/** Local .env defaults with process environment overrides, loaded once at startup. */
public final class BotConfig {
    private final Dotenv values;

    private BotConfig(Dotenv values) { this.values = values; }

    public static BotConfig load() { return load(Path.of(".")); }

    static BotConfig load(Path directory) {
        try {
            return new BotConfig(Dotenv.configure().directory(directory.toAbsolutePath().toString())
                .ignoreIfMissing().load());
        } catch (DotenvException ex) {
            // Parser messages can contain complete lines, including credentials.
            throw new IllegalArgumentException(".env konnte nicht geladen werden. Syntax und Leserechte prüfen. Siehe README.md.");
        }
    }

    public String get(String name) { return values.get(name); }
    public long ownerId() {
        String value = get("BOT_OWNER_ID", "").strip();
        if (value.isEmpty()) return 0;
        try {
            if (!value.matches("[0-9]{17,20}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("BOT_OWNER_ID must be a valid Discord user ID in .env.");
        }
    }
    public String get(String name, String fallback) { return values.get(name, fallback); }

    public String required(String name) {
        String value = get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name
                + " fehlt. In .env im Arbeitsverzeichnis oder als Umgebungsvariable setzen. Siehe README.md.");
        }
        return value;
    }
}
