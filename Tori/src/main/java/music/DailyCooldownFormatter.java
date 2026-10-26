package music;

/** Formats a cooldown duration for user-facing Discord messages. */
final class DailyCooldownFormatter {
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MINUTES_PER_HOUR = 60L;

    private DailyCooldownFormatter() {}

    static String format(Language language, long remainingMillis) {
        if (remainingMillis < 0) throw new IllegalArgumentException("remainingMillis cannot be negative");
        long minutes = remainingMillis / MILLIS_PER_MINUTE;
        if (remainingMillis % MILLIS_PER_MINUTE != 0) minutes++;
        minutes = Math.max(1, minutes);

        long hours = minutes / MINUTES_PER_HOUR;
        long remainingMinutes = minutes % MINUTES_PER_HOUR;
        if (hours == 0) return Messages.text(language, "cooldown.duration.minutes", minutes);
        if (remainingMinutes == 0) return Messages.text(language, "cooldown.duration.hours", hours);
        return Messages.text(language, "cooldown.duration.hours_minutes", hours, remainingMinutes);
    }
}
