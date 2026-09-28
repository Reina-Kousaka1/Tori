package music;

import net.dv8tion.jda.api.EmbedBuilder;

/** Main Tori branding. Dev/varsity branding deliberately does not belong here. */
public final class ToriEmbeds {
    public static final int NAVY = 0x1D1D23;
    public static final int SECONDARY_DARK = 0x2C303C;
    public static final int GOLD = 0xD79A19;
    public static final int WARM_GOLD = 0xE6AD2E;
    public static final int CREAM = 0xF2EFE9;

    public enum Category {
        DEFAULT(NAVY), SECONDARY(SECONDARY_DARK), HIGHLIGHT(WARM_GOLD),
        INFO(GOLD), MUSIC(GOLD), STATS(NAVY),
        SUCCESS(0x57A773), WARNING(0xE6AD2E), ERROR(0xD9534F);

        private final int color;
        Category(int color) { this.color = color; }
        public int color() { return color; }
    }
    private ToriEmbeds() {}

    /** Callers retain control of timestamps, images and command-specific content. */
    public static EmbedBuilder create(Category category, Language language) {
        return new EmbedBuilder().setColor(category.color()).setAuthor("Tori")
            .setFooter(footer(language, null));
    }

    /** Keep long legacy command output inside Discord's 4096-character description limit. */
    public static EmbedBuilder text(Category category, Language language, String title, String body) {
        return create(category, language).setTitle(shorten(title, 256))
            .setDescription(shorten(body == null || body.isBlank() ? "—" : body, 4096));
    }

    public static String shorten(String text, int max) {
        if (text == null || text.isBlank()) return "—";
        if (text.length() <= max) return text;
        if (max < 2) throw new IllegalArgumentException("max must be at least 2");
        int boundary = max - 1;
        if (boundary > 0 && Character.isHighSurrogate(text.charAt(boundary - 1))) boundary--;
        return text.substring(0, boundary).stripTrailing() + "…";
    }

    /** Reuse the existing translations, preserving contextual attribution and IDs. */
    public static String footer(Language language, String detail) {
        String love = Messages.text(language, "stats.love");
        return detail == null || detail.isBlank() ? love : detail + " · " + love;
    }
}
