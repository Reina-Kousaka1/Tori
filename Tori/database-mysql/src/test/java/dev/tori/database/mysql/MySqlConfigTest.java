package dev.tori.database.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MySqlConfigTest {
    @Test void validatesUrlAndPoolBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MySqlConfig("jdbc:postgresql://host/db", "user", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new MySqlConfig("jdbc:mysql://host/db", "user", "secret", 65));
    }

    @Test void redactsCredentialsInToString() {
        var config = new MySqlConfig("jdbc:mysql://host/db", "user", "never-print-this");
        assertFalse(config.toString().contains("never-print-this"));
        assertFalse(config.toString().contains("jdbc:mysql://host/db"));
    }
}
