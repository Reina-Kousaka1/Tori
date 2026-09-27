package dev.tori.database.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SqlRow {
    private final Map<String, Object> values;

    public SqlRow(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("values are required");
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Object> values() { return values; }
    public Optional<Object> value(String column) { return Optional.ofNullable(values.get(column)); }
    public String string(String column) {
        Object value = values.get(column);
        return value == null ? null : value.toString();
    }
    public long longValue(String column) {
        Object value = values.get(column);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Column is not numeric: " + column);
        return number.longValue();
    }
}
