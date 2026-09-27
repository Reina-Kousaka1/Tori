package music;

/** Payout arithmetic is deliberately independent of persona copy and persistence. */
final class GamblingPolicy {
    enum Game { GAMBLE, SLOTS }

    private GamblingPolicy() {}

    static long grossWinnings(Game game, long wager, int roll) {
        if (game == null || wager < 1 || roll < 0 || roll > 99)
            throw new IllegalArgumentException("Invalid wager or roll");
        int winThreshold = game == Game.SLOTS ? 25 : 45;
        if (roll >= winThreshold) return 0;
        return Math.multiplyExact(wager, roll < 8 ? 3 : 2);
    }
}
