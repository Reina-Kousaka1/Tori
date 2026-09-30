package music;

import net.dv8tion.jda.api.EmbedBuilder;

/** Discord-only visual themes. Economy rules and Discord layouts stay separate. */
public final class ToriEmbeds {
    public static final int SOFT_LAVENDER = 0xB9A7D4;
    public static final int LAVENDER_ACCENT = 0x9883B8;
    public static final int BALLET_PINK = 0xE6B4CB;
    public static final int VOLLEYBALL_NAVY = 0x1D1D23;
    public static final int VOLLEYBALL_GOLD = 0xD79A19;
    public static final int CHEER_NAVY = 0x26344D;
    public static final int CHEER_DUSTY_LAVENDER = 0xA39AB9;

    public enum Category {
        GENERAL(SOFT_LAVENDER), ECONOMY(SOFT_LAVENDER), SHOP(SOFT_LAVENDER),
        PROFILE(SOFT_LAVENDER), MARRIAGE(SOFT_LAVENDER),
        ORDER_PROCESSING(LAVENDER_ACCENT),
        BALLET(BALLET_PINK, BALLET_PINK, "Tori · Ballet"),
        VOLLEYBALL(VOLLEYBALL_NAVY, VOLLEYBALL_GOLD, "Tori · Volleyball"),
        CHEER(CHEER_NAVY, CHEER_DUSTY_LAVENDER, "Tori · Cheer"),
        SYSTEM_INFO(SOFT_LAVENDER),
        SUCCESS(0x57A773), WARNING(0xE6AD2E), ERROR(0xD9534F);

        private final int color;
        private final int accentColor;
        private final String header;
        Category(int color) { this(color, color, "Tori"); }
        Category(int color, int accentColor, String header) {
            this.color = color;
            this.accentColor = accentColor;
            this.header = header;
        }
        public int color() { return color; }
        /** Optional secondary visual color for future banner/image renderers. */
        public int accentColor() { return accentColor; }
        public String header() { return header; }
    }
    private ToriEmbeds() {}

    /** Callers retain control of timestamps, images and command-specific content. */
    public static EmbedBuilder create(Category category, Language language) {
        return new EmbedBuilder().setColor(category.color()).setAuthor(category.header())
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
