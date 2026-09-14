package music;

import java.util.*;
import java.sql.SQLException;

final class PrefixSettings {
    private final ModLogStore store;
    private volatile Map<String, String> values;
    PrefixSettings(ModLogStore store) throws SQLException {
        this.store = store;
        var loaded = new HashMap<String, String>();
        try (var connection = store.connect(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS guild_prefixes (guild_id TEXT PRIMARY KEY, prefix TEXT NOT NULL)");
            try (var rows = sql.executeQuery("SELECT guild_id, prefix FROM guild_prefixes")) {
                while (rows.next()) loaded.put(rows.getString(1), validate(rows.getString(2)));
            }
        }
        values = Map.copyOf(loaded);
    }
    String get(String guild) { return values.getOrDefault(guild, "T."); }
    static String validate(String value) {
        if (value == null || !value.matches("[\\p{L}\\p{N}!#$%&*+,.?~^_=;:|\\-]{1,10}")) throw new UserError("prefix.invalid");
        return value;
    }
    synchronized void set(String guild, String value) throws SQLException {
        validate(value);
        try (var connection = store.connect(); var sql = connection.prepareStatement(
            "INSERT INTO guild_prefixes VALUES (?, ?) ON CONFLICT(guild_id) DO UPDATE SET prefix=excluded.prefix")) {
            sql.setString(1, guild); sql.setString(2, value); sql.executeUpdate();
        }
        var updated = new HashMap<>(values); updated.put(guild, value); values = Map.copyOf(updated);
    }
}
