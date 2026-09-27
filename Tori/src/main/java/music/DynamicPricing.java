package music;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Price movement is independent of wallets, stock and persona presentation. */
final class DynamicPricing {
    record Rules(long basePrice, long minimumPrice, long maximumPrice, double volatility) {
        Rules {
            if (basePrice < 1 || minimumPrice < 1 || maximumPrice < minimumPrice
                || basePrice < minimumPrice || basePrice > maximumPrice
                || !Double.isFinite(volatility) || volatility < 0 || volatility > 0.25)
                throw new IllegalArgumentException("Invalid dynamic-pricing rules");
        }
    }

    private DynamicPricing() {}

    static long next(long previousPrice, Rules rules, DoubleSupplier randomUnit) {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(randomUnit);
        double sample = randomUnit.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1)
            throw new IllegalArgumentException("Random source must return a value in [0, 1)");
        long previous = Math.clamp(previousPrice, rules.minimumPrice(), rules.maximumPrice());
        double movement = (sample * 2.0 - 1.0) * rules.volatility();
        long proposed = Math.round(previous * (1.0 + movement));
        return Math.clamp(proposed, rules.minimumPrice(), rules.maximumPrice());
    }
}
