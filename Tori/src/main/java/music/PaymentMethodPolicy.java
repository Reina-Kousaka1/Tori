package music;

/** Normalizes the free-form payment-method label without processing a payment. */
final class PaymentMethodPolicy {
    static final int MAX_LENGTH = 60;

    private PaymentMethodPolicy() {}

    static String normalize(String method) {
        if (method == null) return "";
        String normalized = method.strip().replaceAll("[\\p{Cntrl}\\p{Z}]+", " ").strip();
        if (normalized.codePointCount(0, normalized.length()) > MAX_LENGTH)
            throw new IllegalArgumentException("Payment method label is too long.");
        return normalized;
    }
}
