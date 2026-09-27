package dev.tori.database.api;

import java.util.List;

/** Synchronous, thread-confined transaction view, called only on the provider's database worker. */
public interface SqlTransaction {
    List<SqlRow> query(SqlCommand command);
    int execute(SqlCommand command);
}
