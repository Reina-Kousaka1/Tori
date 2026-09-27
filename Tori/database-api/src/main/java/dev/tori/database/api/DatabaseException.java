package dev.tori.database.api;

/** Safe boundary exception; messages omit SQL, parameters, connection strings, and documents. */
public final class DatabaseException extends RuntimeException {
    private final String providerId;
    private final String operation;
    private final String causeType;

    public DatabaseException(String providerId, String operation, Throwable cause) {
        super("Database operation failed for provider '" + safe(providerId) + "' (" + safe(operation) + ").");
        this.providerId = safe(providerId);
        this.operation = safe(operation);
        this.causeType = cause == null ? "" : safeType(cause.getClass().getSimpleName());
    }

    public DatabaseException(String providerId, String operation) { this(providerId, operation, null); }
    public String providerId() { return providerId; }
    public String operation() { return operation; }
    public String causeType() { return causeType; }

    private static String safe(String value) {
        return value != null && value.matches("[a-zA-Z0-9._-]{1,64}") ? value : "unknown";
    }

    private static String safeType(String value) {
        return value != null && value.matches("[a-zA-Z0-9_$]{1,96}") ? value : "Exception";
    }
}
