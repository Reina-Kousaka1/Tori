package music;

/** Legacy market cutover gate. V2 is deliberately unavailable until the replacement exists. */
enum MarketMode {
    LEGACY, READ_ONLY, V2;

    static MarketMode parse(String value) {
        try {
            MarketMode mode = value == null ? LEGACY : valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
            if (mode == V2) throw new IllegalArgumentException("Market V2 is not implemented");
            return mode;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("TORI_MARKET_MODE must be LEGACY or READ_ONLY (V2 is not available)");
        }
    }

    void requireWritable() {
        if (this != LEGACY) throw new IllegalStateException("Market is read-only");
    }
}
