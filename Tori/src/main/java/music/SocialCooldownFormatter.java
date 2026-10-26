package music;

/** Rounds remaining milliseconds upward so the UI never claims a cooldown ended early. */
final class SocialCooldownFormatter {
    private SocialCooldownFormatter() {}

    static String format(long remainingMillis) {
        long safeMillis = Math.max(0, remainingMillis);
        long minutes = Math.max(1, safeMillis / 60_000 + (safeMillis % 60_000 == 0 ? 0 : 1));
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours == 0 ? minutes + "m" : "%dh %02dm".formatted(hours, remainder);
    }
}
