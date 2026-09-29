package music;

/** Shared epoch-millisecond cooldown semantics for the legacy Java daily claim. */
final class DailyCooldown {
    static final long DURATION_MILLIS = 24L * 60 * 60 * 1_000;

    private DailyCooldown() {}

    static long remainingMillis(long now, long lastClaim) {
        if (lastClaim <= 0) return 0;
        if (now < lastClaim) return DURATION_MILLIS;
        return Math.max(0, DURATION_MILLIS - (now - lastClaim));
    }
}
