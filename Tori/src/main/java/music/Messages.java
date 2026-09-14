package music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Messages {
    private static final Map<Language, Properties> CATALOG = new EnumMap<>(Language.class);
    static {
        for (var language : Language.values()) {
            try (var stream = Messages.class.getResourceAsStream("/i18n/" + language.code + ".properties")) {
                if (stream == null) throw new IOException("Missing language catalog: " + language.code);
                var properties = new Properties();
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                CATALOG.put(language, properties);
            } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
        }
    }
    private Messages() {}
    public static String text(Language language, String key, Object... args) {
        String template = CATALOG.get(language).getProperty(key, CATALOG.get(Language.EN).getProperty(key));
        if (template == null) throw new IllegalArgumentException("Missing translation key: " + key);
        return String.format(Locale.ROOT, template, args);
    }
    static Set<String> keys(Language language) { return CATALOG.get(language).stringPropertyNames(); }
    static String template(Language language, String key) { return CATALOG.get(language).getProperty(key); }
}
