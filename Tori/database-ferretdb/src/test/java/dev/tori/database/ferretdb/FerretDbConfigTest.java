package dev.tori.database.ferretdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FerretDbConfigTest {
    @Test void acceptsMongoWireUrlsWithoutTreatingThemAsJdbc() {
        var config = new FerretDbConfig("mongodb://user:password@localhost:27017", "tori");
        assertEquals("tori", config.database());
        assertFalse(config.toString().contains("password"));
        assertThrows(IllegalArgumentException.class,
            () -> new FerretDbConfig("jdbc:postgresql://localhost/tori", "tori"));
    }
}
