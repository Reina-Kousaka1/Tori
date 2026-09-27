package dev.tori.database.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record DatabaseHealth(String providerId, boolean healthy, Instant checkedAt,
                             Duration latency, String detail) {
    public DatabaseHealth {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId is required");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(latency, "latency");
        if (latency.isNegative()) throw new IllegalArgumentException("latency cannot be negative");
        detail = detail == null ? "" : detail;
    }

    public static DatabaseHealth available(String providerId, Instant checkedAt, Duration latency) {
        return new DatabaseHealth(providerId, true, checkedAt, latency, "available");
    }

    public static DatabaseHealth unavailable(String providerId, Instant checkedAt, Duration latency) {
        return new DatabaseHealth(providerId, false, checkedAt, latency, "unavailable");
    }
}
