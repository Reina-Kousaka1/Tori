package music;

import net.dv8tion.jda.api.EmbedBuilder;

/** Main Tori branding. Dev/varsity branding deliberately does not belong here. */
public final class ToriEmbeds {
    public enum Category {
        MUSIC(0xE69293), INFO(0xEBC597), STATS(0xA87AA2),
        SUCCESS(0x57A773), ERROR(0xD9534F);

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

    /** Reuse the existing translations, preserving contextual attribution and IDs. */
    public static String footer(Language language, String detail) {
        String love = Messages.text(language, "stats.love");
        return detail == null || detail.isBlank() ? love : detail + " · " + love;
    }
}
