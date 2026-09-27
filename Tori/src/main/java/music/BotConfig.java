package music;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Local .env defaults with process environment overrides, loaded once at startup. */
public final class BotConfig {
    static final class ConfigurationException extends IllegalArgumentException {
        ConfigurationException(String message) { super(message); }
    }

    private final Dotenv values;

    private BotConfig(Dotenv values) { this.values = values; }

    public static BotConfig load() { return load(Path.of(".")); }

    static BotConfig load(Path directory) {
        try {
            return new BotConfig(Dotenv.configure().directory(directory.toAbsolutePath().toString())
                .ignoreIfMissing().load());
        } catch (DotenvException ex) {
            // Parser messages can contain complete lines, including credentials.
            throw new ConfigurationException(".env could not be loaded. Check its syntax and file permissions.");
        }
    }

    public String get(String name) { return values.get(name); }
    String discordToken() { return required("DISCORD_TOKEN").strip(); }

    long discordBotId() {
        return botIdFromToken(discordToken());
    }

    static long botIdFromToken(String rawToken) {
        String token = rawToken == null ? "" : rawToken.strip();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new ConfigurationException("DISCORD_TOKEN must be the raw Discord bot token without the 'Bot ' prefix.");
        }
        try {
            long id = Long.parseLong(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException ex) {
            throw new ConfigurationException("DISCORD_TOKEN has an invalid format. Use the raw bot token from the Discord Developer Portal.");
        }
    }

    void validateForStartup() {
        discordBotId();
        ownerId();
        required("LAVALINK_PASSWORD");
        validateDefaultLanguage(get("BOT_DEFAULT_LANGUAGE", "de"));
    }

    static void validateDefaultLanguage(String language) {
        try { Language.parse(language); }
        catch (UserError ex) { throw new ConfigurationException("BOT_DEFAULT_LANGUAGE must be one of: de, en, nl."); }
    }

    public long ownerId() {
        String value = get("BOT_OWNER_ID", "").strip();
        if (value.isEmpty()) return 0;
        try {
            if (!value.matches("[0-9]{17,20}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new ConfigurationException("BOT_OWNER_ID must be a valid Discord user ID.");
        }
    }
    public String get(String name, String fallback) { return values.get(name, fallback); }

    String redactSensitive(String text) {
        String safe = text;
        for (String key : List.of("DISCORD_TOKEN", "LAVALINK_PASSWORD", "TORI_DATABASE_PASSWORD",
            "TORI_POSTGRES_PASSWORD", "SPOTIFY_CLIENT_SECRET", "YOUTUBE_API_KEY", "MODLOG_WEBHOOK_URL",
            "TORI_DATABASE_URL", "LAVALINK_URI")) {
            String value = get(key);
            if (value != null && value.length() >= 4) safe = safe.replace(value, "[REDACTED:" + key + "]");
        }
        return safe;
    }

    public String required(String name) {
        String value = get(name);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(name + " is missing. Set it in .env or as an environment variable.");
        }
        return value;
    }
}

