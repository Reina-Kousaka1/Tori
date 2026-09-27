package dev.tori.database.api;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Common lifecycle contract; SQL and document models remain explicitly separate. */
public interface DatabaseProvider extends AutoCloseable {
    String providerId();
    Set<DatabaseCapability> capabilities();
    CompletionStage<DatabaseHealth> health();
    boolean isClosed();

    @Override void close();
}
