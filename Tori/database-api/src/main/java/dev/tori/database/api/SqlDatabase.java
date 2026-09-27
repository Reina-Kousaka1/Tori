package dev.tori.database.api;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Relational capability. FerretDB intentionally does not implement this interface. */
public interface SqlDatabase extends DatabaseProvider {
    CompletionStage<List<SqlRow>> query(SqlCommand command);
    CompletionStage<Integer> execute(SqlCommand command);
    <T> CompletionStage<T> transaction(SqlTransactionWork<T> work);
    CompletionStage<Integer> migrate(String... locations);
}
