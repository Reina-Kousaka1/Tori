package dev.tori.database.api;

public record DocumentWriteResult(long matchedCount, long modifiedCount, String upsertedId) {
    public DocumentWriteResult {
        if (matchedCount < 0 || modifiedCount < 0) throw new IllegalArgumentException("Counts cannot be negative");
    }
}
