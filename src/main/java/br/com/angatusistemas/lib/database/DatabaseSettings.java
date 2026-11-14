package br.com.angatusistemas.lib.database;

import java.util.concurrent.TimeUnit;

/**
 * Persistence settings read once from the environment: database file, pool size, page cache, wait
 * deadline and memory map, each clamped to a safe range and falling back to its default when unreadable.
 *
 * @author Angatu Sistemas
 */
final class DatabaseSettings {

    static final String DEFAULT_DATABASE = "database.db";

    // Every read connection carries its own native page cache, so the pool size is a memory decision too.
    static final int READ_POOL_SIZE = intFromEnvironment("ANGATU_DB_POOL_SIZE", 12, 1, 64);

    // 2 MiB per connection by default: CPU before RAM. Indexed lookups touch few pages, and the OS
    // page cache still holds the hot ones for every connection at once.
    static final int CACHE_SIZE_KIB = intFromEnvironment("ANGATU_DB_CACHE_KIB", 2_048, 64, 1_048_576);

    // Off by default: an I/O error on a memory-mapped file can bring the JVM down instead of failing
    // one query, so the map is the project's explicit choice.
    static final int MMAP_MIB = intFromEnvironment("ANGATU_DB_MMAP_MIB", 0, 0, 65_536);

    static final int BUSY_TIMEOUT_MS = intFromEnvironment("ANGATU_DB_BUSY_TIMEOUT_MS", 30_000, 1_000, 300_000);

    // Without a limit the -wal file keeps the largest size it ever reached.
    static final long JOURNAL_SIZE_LIMIT_BYTES = 64L * 1024 * 1024;

    static final long SLOW_WRITE_MS = 2_000;

    private DatabaseSettings() {
    }

    static String databasePath() {
        String path = System.getProperty("angatu.db");
        if (path == null || path.isBlank()) path = System.getenv("ANGATU_DB_PATH");
        return path == null || path.isBlank() ? DEFAULT_DATABASE : path.trim();
    }

    static long deadlineFromNow() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_TIMEOUT_MS);
    }

    // System.getenv on purpose: this runs during static initialisation, before the library's Env exists.
    static int intFromEnvironment(String key, int fallback, int min, int max) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) return fallback;
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (RuntimeException e) {
            // a misspelled variable must not keep the application from starting
            return fallback;
        }
    }
}
