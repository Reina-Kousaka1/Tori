package dev.tori.database.ferretdb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import dev.tori.database.api.DatabaseCapability;
import dev.tori.database.api.DatabaseException;
import dev.tori.database.api.DatabaseHealth;
import dev.tori.database.api.DocumentDatabase;
import dev.tori.database.api.DocumentWriteResult;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** MongoDB-compatible document adapter. It does not masquerade as a relational database. */
public final class FerretDbDatabase implements DocumentDatabase {
    private static final Set<DatabaseCapability> CAPABILITIES = Set.of(
        DatabaseCapability.ASYNC_OPERATIONS, DatabaseCapability.HEALTH_CHECK,
        DatabaseCapability.DOCUMENT_OPERATIONS);
    private static final Set<String> UPDATE_OPERATORS = Set.of("$set", "$inc", "$unset", "$push", "$addToSet", "$pull");

    private final MongoClient client;
    private final MongoDatabase database;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FerretDbDatabase(FerretDbConfig config) {
        var settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(config.connectionString()))
            .applyToConnectionPoolSettings(pool -> pool.minSize(0).maxSize(config.maxPoolSize()))
            .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
            .build();
        client = MongoClients.create(settings);
        database = client.getDatabase(config.database());
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "database-ferretdb-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public static FerretDbDatabase connect(FerretDbConfig config) {
        return new FerretDbDatabase(Objects.requireNonNull(config, "config"));
    }

    @Override public String providerId() { return "ferretdb"; }
    @Override public Set<DatabaseCapability> capabilities() { return CAPABILITIES; }
    @Override public boolean isClosed() { return closed.get(); }

    @Override public CompletionStage<DatabaseHealth> health() {
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        return submit("health", () -> {
            database.runCommand(new Document("ping", 1));
            return DatabaseHealth.available(providerId(), checkedAt, elapsed(started));
        }).exceptionally(failure -> DatabaseHealth.unavailable(providerId(), checkedAt, elapsed(started)));
    }

    @Override public CompletionStage<Optional<Map<String, Object>>> findOne(String name, Map<String, ?> filter) {
        return submit("find", () -> Optional.ofNullable(collection(name).find(document(filter)).first())
            .map(FerretDbDatabase::copy));
    }

    @Override public CompletionStage<List<Map<String, Object>>> find(String name, Map<String, ?> filter, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return submit("find", () -> {
            var found = new ArrayList<Map<String, Object>>();
            for (Document item : collection(name).find(document(filter)).limit(limit)) found.add(copy(item));
            return List.copyOf(found);
        });
    }

    @Override public CompletionStage<String> insertOne(String name, Map<String, ?> value) {
        Document content = document(value);
        return submit("insert", () -> {
            collection(name).insertOne(content);
            return idString(content.get("_id"));
        });
    }

    @Override public CompletionStage<DocumentWriteResult> updateOne(String name, Map<String, ?> filter,
                                                                     Map<String, ?> operators, boolean upsert) {
        Document update = updateDocument(operators);
        return submit("update", () -> {
            var result = collection(name).updateOne(document(filter), update, new UpdateOptions().upsert(upsert));
            return new DocumentWriteResult(result.getMatchedCount(), result.getModifiedCount(),
                upsertedIdString(result.getUpsertedId()));
        });
    }

    @Override public CompletionStage<Long> deleteOne(String name, Map<String, ?> filter) {
        return submit("delete", () -> collection(name).deleteOne(document(filter)).getDeletedCount());
    }

    private MongoCollection<Document> collection(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,120}") || name.startsWith("system."))
            throw new IllegalArgumentException("Collection name is invalid");
        return database.getCollection(name);
    }

    private static Document updateDocument(Map<String, ?> operators) {
        if (operators == null || operators.isEmpty()) throw new IllegalArgumentException("At least one update operator is required");
        for (String key : operators.keySet())
            if (!UPDATE_OPERATORS.contains(key)) throw new IllegalArgumentException("Unsupported document update operator");
        return document(operators, true);
    }

    private static Document document(Map<String, ?> values) { return document(values, false); }

    private static Document document(Map<String, ?> values, boolean allowUpdateOperators) {
        if (values == null) throw new IllegalArgumentException("Document/filter is required");
        var result = new Document();
        values.forEach((key, value) -> {
            boolean allowedOperator = allowUpdateOperators && key != null && UPDATE_OPERATORS.contains(key);
            if (key == null || key.isBlank() || key.indexOf('\0') >= 0 || key.contains("$") && !allowedOperator)
                throw new IllegalArgumentException("Document field is invalid");
            result.put(key, bsonValue(value));
        });
        return result;
    }

    private static Object bsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var nested = new Document();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String name) || name.isBlank() || name.indexOf('\0') >= 0 || name.contains("$"))
                    throw new IllegalArgumentException("Document field is invalid");
                nested.put(name, bsonValue(nestedValue));
            });
            return nested;
        }
        if (value instanceof List<?> list) return list.stream().map(FerretDbDatabase::bsonValue).toList();
        return value;
    }

    private static Map<String, Object> copy(Document source) {
        var copied = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copied.put(key, copyValue(value)));
        return Collections.unmodifiableMap(copied);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Document document) return copy(document);
        if (value instanceof List<?> list) return list.stream().map(FerretDbDatabase::copyValue).toList();
        return value;
    }

    private static String idString(Object id) {
        if (id instanceof ObjectId objectId) return objectId.toHexString();
        return id == null ? "" : id.toString();
    }

    private static String upsertedIdString(org.bson.BsonValue id) {
        if (id == null) return null;
        if (id instanceof BsonObjectId objectId) return objectId.getValue().toHexString();
        if (id instanceof BsonString string) return string.getValue();
        return id.toString();
    }

    private <T> CompletionStage<T> submit(String operation, CheckedSupplier<T> supplier) {
        if (closed.get()) return CompletableFuture.failedFuture(new DatabaseException(providerId(), operation));
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return supplier.get(); }
                catch (DatabaseException ex) { throw ex; }
                catch (Exception ex) { throw new DatabaseException(providerId(), operation, ex); }
            }, executor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new DatabaseException(providerId(), operation, ex));
        }
    }

    private static Duration elapsed(long started) { return Duration.ofNanos(Math.max(0, System.nanoTime() - started)); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            client.close();
        }
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
