package dev.tori.database.api;

/** A provider advertises only the data-model capabilities it actually supports. */
public enum DatabaseCapability {
    ASYNC_OPERATIONS,
    HEALTH_CHECK,
    RELATIONAL_QUERIES,
    TRANSACTIONS,
    SCHEMA_MIGRATIONS,
    DOCUMENT_OPERATIONS
}
