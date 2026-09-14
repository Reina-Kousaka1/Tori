package music;

import java.util.Locale;

public enum Language {
    DE("de", "Deutsch"), EN("en", "English"), NL("nl", "Nederlands");
    public final String code;
    public final String label;
    Language(String code, String label) { this.code = code; this.label = label; }
    public static Language parse(String code) {
        for (var language : values()) if (language.code.equals(code)) return language;
        throw new UserError("language.invalid");
    }
    public static Language discordLocale(String code, Language fallback) {
        if (code == null) return fallback;
        String prefix = code.toLowerCase(Locale.ROOT).split("-")[0];
        for (var language : values()) if (language.code.equals(prefix)) return language;
        return fallback;
    }
}
