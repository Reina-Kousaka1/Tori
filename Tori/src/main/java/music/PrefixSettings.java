package music;

import java.util.*;
import java.sql.SQLException;

final class PrefixSettings {
    private final BotStore store;
    private volatile Map<String, String> values;
    PrefixSettings(BotStore store) throws SQLException {
        this.store = store;
        var loaded = new HashMap<String, String>();
        store.prefixes().forEach((guild, prefix) -> loaded.put(guild, validate(prefix)));
        values = Map.copyOf(loaded);
    }
    String get(String guild) { return values.getOrDefault(guild, "T."); }
    static String validate(String value) {
        if (value == null || !value.matches("[\\p{L}\\p{N}!#$%&*+,.?~^_=;:|\\-]{1,10}")) throw new UserError("prefix.invalid");
        return value;
    }
    synchronized void set(String guild, String value) throws SQLException {
        validate(value);
        store.setPrefix(guild, value);
        var updated = new HashMap<>(values); updated.put(guild, value); values = Map.copyOf(updated);
    }
}
