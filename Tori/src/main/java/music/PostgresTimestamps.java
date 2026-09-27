package music;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Bind Java instants to PostgreSQL TIMESTAMPTZ using a JDBC-supported type. */
final class PostgresTimestamps {
    private PostgresTimestamps() { }

    static void bind(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }
}
