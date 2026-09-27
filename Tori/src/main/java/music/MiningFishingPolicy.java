package music;

/** Existing gather drop thresholds are isolated from presentation and persistence. */
final class MiningFishingPolicy {
    private MiningFishingPolicy() {}

    static String drop(String activity, int tier, int roll) {
        if (tier < 1 || tier > 8 || roll < 0 || roll > 99) throw new IllegalArgumentException("Invalid tier or roll");
        return switch (activity) {
            case "fish" -> roll < 2 && tier >= 5 ? "shark" : roll < 4 ? "fish_crate"
                : roll < 8 && tier >= 3 ? "shell" : roll < 16 ? "squid"
                : roll < 25 ? "crab" : roll < 40 ? "tropical_fish" : roll < 50 ? "blowfish" : "fish";
            case "mine" -> roll < 2 && tier >= 8 ? "sparkle_fragment"
                : roll < 7 && tier >= 4 ? "moon_rune" : roll < 9 ? "mine_crate"
                : roll < 24 ? "gem_fragment" : roll < 40 ? "cobweb" : "rock";
            case "chop" -> roll < 2 && tier >= 8 ? "sparkle_fragment"
                : roll < 7 && tier >= 4 ? "moon_rune" : roll < 9 ? "chop_crate"
                : roll < 20 ? "pear" : roll < 35 ? "apple" : roll < 50 ? "leaves" : "wood";
            default -> throw new IllegalArgumentException("Unknown gather activity");
        };
    }

    static int tier(int durability) {
        if (durability < 1) throw new IllegalArgumentException("durability must be positive");
        return Math.max(1, Math.min(8, durability / 40));
    }
}
