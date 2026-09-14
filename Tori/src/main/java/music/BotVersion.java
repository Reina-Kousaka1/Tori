package music;

import java.io.IOException;
import java.util.Properties;

final class BotVersion {
    static final String CURRENT = load();
    private static String load() {
        try (var stream = BotVersion.class.getResourceAsStream("/bot-version.properties")) {
            if (stream == null) return "dev";
            var properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version", "dev").strip();
            return version.isEmpty() ? "dev" : version;
        } catch (IOException ex) { return "dev"; }
    }
    private BotVersion() {}
}
