package music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Single-process store. A failed write never changes the effective language. */
public final class LanguageStore {
    private final Path file;
    private final Language defaultLanguage;
    private volatile Map<String, Language> languages = Map.of();
    public LanguageStore(Path file, Language defaultLanguage) throws IOException {
        this.file = file.toAbsolutePath(); this.defaultLanguage = defaultLanguage;
        var loaded = new HashMap<String, Language>();
        if (Files.exists(this.file)) {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) { props.load(reader); }
            for (String id : props.stringPropertyNames()) {
                try { validateId(id); loaded.put(id, Language.parse(props.getProperty(id))); }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid language settings file; restore or repair it before starting."); }
            }
        }
        languages = Map.copyOf(loaded);
    }
    public static LanguageStore fromConfig(BotConfig config) throws IOException {
        return new LanguageStore(Path.of(config.get("BOT_DATA_DIR", "data"), "languages.properties"),
            Language.parse(config.get("BOT_DEFAULT_LANGUAGE", "de")));
    }
    public Language defaultLanguage() { return defaultLanguage; }
    // Reads must not wait for a settings write on a gateway or REST callback thread.
    public Language get(String guildId) { return languages.getOrDefault(guildId, defaultLanguage); }
    public synchronized void set(String guildId, Language language) throws IOException {
        validateId(guildId); Objects.requireNonNull(language);
        var updated = new HashMap<>(languages); updated.put(guildId, language);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "languages-", ".tmp");
        try {
            var props = new Properties(); updated.forEach((id, value) -> props.setProperty(id, value.code));
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { props.store(writer, "Discord server languages"); }
            // No non-atomic fallback: a filesystem without atomic replacement must report failure.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            languages = Map.copyOf(updated);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void validateId(String id) {
        if (!id.matches("[0-9]{17,20}")) throw new IllegalArgumentException("Invalid guild ID");
    }
}
