package music;

/** User-safe failure for economy persistence. Database detail is intentionally not included. */
final class CurrencyStoreException extends Exception {
    CurrencyStoreException() { super("Economy storage operation failed."); }
    CurrencyStoreException(Throwable cause) { super("Economy storage operation failed.", cause); }
}
