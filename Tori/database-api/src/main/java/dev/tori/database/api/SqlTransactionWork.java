package dev.tori.database.api;

@FunctionalInterface
public interface SqlTransactionWork<T> {
    T execute(SqlTransaction transaction) throws Exception;
}
