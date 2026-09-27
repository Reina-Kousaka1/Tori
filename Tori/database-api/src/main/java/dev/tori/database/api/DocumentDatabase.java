package dev.tori.database.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** MongoDB-wire-compatible document operations; not a SQL facade or implicit replication layer. */
public interface DocumentDatabase extends DatabaseProvider {
    CompletionStage<Optional<Map<String, Object>>> findOne(String collection, Map<String, ?> filter);
    CompletionStage<List<Map<String, Object>>> find(String collection, Map<String, ?> filter, int limit);
    CompletionStage<String> insertOne(String collection, Map<String, ?> document);
    CompletionStage<DocumentWriteResult> updateOne(String collection, Map<String, ?> filter,
                                                    Map<String, ?> updateOperators, boolean upsert);
    CompletionStage<Long> deleteOne(String collection, Map<String, ?> filter);
}
