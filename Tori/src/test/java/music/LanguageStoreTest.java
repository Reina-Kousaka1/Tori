package music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageStoreTest {
    @TempDir Path directory;
    private static final String FIRST = "123456789012345678";
    private static final String SECOND = "223456789012345678";
    @Test void persistsIndependentlyForEachGuildAcrossRestart() throws Exception {
        Path file = directory.resolve("languages.properties");
        var store = new LanguageStore(file, Language.DE);
        assertEquals(Language.DE, store.get(FIRST));
        store.set(FIRST, Language.EN); store.set(SECOND, Language.NL);
        var restarted = new LanguageStore(file, Language.DE);
        assertEquals(Language.EN, restarted.get(FIRST));
        assertEquals(Language.NL, restarted.get(SECOND));
        store.set(FIRST, Language.DE);
        assertEquals(Language.DE, new LanguageStore(file, Language.EN).get(FIRST));
    }
    @Test void failedWriteDoesNotChangeEffectiveLanguage() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        var store = new LanguageStore(blocked.resolve("languages.properties"), Language.EN);
        assertThrows(IOException.class, () -> store.set(FIRST, Language.NL));
        assertEquals(Language.EN, store.get(FIRST));
    }
    @Test void invalidSettingsFailInsteadOfSilentlyResetting() throws Exception {
        Path file = directory.resolve("languages.properties");
        Files.writeString(file, FIRST + "=invalid");
        assertThrows(IOException.class, () -> new LanguageStore(file, Language.DE));
    }
    @Test void invalidGuildCannotCreateSettings() throws Exception {
        var store = new LanguageStore(directory.resolve("languages.properties"), Language.DE);
        assertThrows(IllegalArgumentException.class, () -> store.set("../something", Language.NL));
        assertFalse(Files.exists(directory.resolve("languages.properties")));
    }
    @Test void concurrentGuildChangesDoNotOverwriteEachOther() throws Exception {
        Path file = directory.resolve("languages.properties");
        var store = new LanguageStore(file, Language.DE);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { store.set(FIRST, Language.EN); return null; });
            var b = workers.submit(() -> { store.set(SECOND, Language.NL); return null; });
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
        var restored = new LanguageStore(file, Language.DE);
        assertEquals(Language.EN, restored.get(FIRST));
        assertEquals(Language.NL, restored.get(SECOND));
    }
    @Test void readsDoNotWaitForTheSettingsWriteLock() throws Exception {
        var store = new LanguageStore(directory.resolve("languages.properties"), Language.DE);
        store.set(FIRST, Language.EN);
        try (var reader = Executors.newSingleThreadExecutor()) {
            synchronized (store) {
                assertEquals(Language.EN, reader.submit(() -> store.get(FIRST)).get(2, TimeUnit.SECONDS));
                assertEquals(Language.DE, reader.submit(() -> store.get(SECOND)).get(2, TimeUnit.SECONDS));
            }
        }
        store.set(FIRST, Language.NL);
        assertEquals(Language.NL, store.get(FIRST), "Reads must see the new snapshot after a successful write");
    }
}
