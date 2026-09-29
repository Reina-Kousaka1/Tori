package music;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PostgresTimestampsTest {
    @Test void bindsInstantAsUtcOffsetDateTimeForTimestamptz() throws Exception {
        var index = new AtomicInteger();
        var value = new AtomicReference<Object>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(), new Class<?>[] { PreparedStatement.class },
            (proxy, method, args) -> {
                if (!method.getName().equals("setObject") || args.length != 2)
                    throw new AssertionError("Unexpected JDBC call: " + method.getName());
                index.set((Integer) args[0]);
                value.set(args[1]);
                return null;
            });
        Instant instant = Instant.parse("2026-09-27T23:45:12.123456Z");

        PostgresTimestamps.bind(statement, 9, instant);

        assertEquals(9, index.get());
        OffsetDateTime bound = assertInstanceOf(OffsetDateTime.class, value.get());
        assertEquals(ZoneOffset.UTC, bound.getOffset());
        assertEquals(instant, bound.toInstant());
    }
}
