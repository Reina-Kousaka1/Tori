package dev.tori.database.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SqlCommandTest {
    @Test void parametersAreDefensivelyCopiedAndNullValuesAreSupported() {
        var source = new java.util.ArrayList<>(Arrays.<Object>asList("user", null));
        var command = new SqlCommand("SELECT ? WHERE ? IS NULL", source);
        source.set(0, "changed");
        assertEquals("user", command.parameters().get(0));
        assertNull(command.parameters().get(1));
        assertThrows(UnsupportedOperationException.class, () -> command.parameters().add("x"));
    }

    @Test void documentCapabilityIsNotSql() {
        assertFalse(SqlDatabase.class.isAssignableFrom(DocumentDatabase.class));
    }
}
