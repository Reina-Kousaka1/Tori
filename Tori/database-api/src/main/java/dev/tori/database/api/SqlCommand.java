package dev.tori.database.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** SQL is application-authored and all values are bound as prepared-statement parameters. */
public record SqlCommand(String text, List<Object> parameters) {
    public SqlCommand {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("SQL text is required");
        parameters = parameters == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    public SqlCommand(String text) { this(text, List.of()); }
}
