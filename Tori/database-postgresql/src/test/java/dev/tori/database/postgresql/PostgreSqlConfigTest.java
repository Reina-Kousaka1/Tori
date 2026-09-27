package dev.tori.database.postgresql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlConfigTest {
    @Test void validatesUrlAndPoolBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PostgreSqlConfig("postgres://host/db", "user", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new PostgreSqlConfig("jdbc:postgresql://host/db", "user", "secret", 0));
    }

    @Test void redactsCredentialsInToString() {
        var config = new PostgreSqlConfig("jdbc:postgresql://host/db", "user", "never-print-this");
        assertFalse(config.toString().contains("never-print-this"));
        assertFalse(config.toString().contains("jdbc:postgresql://host/db"));
    }
}
