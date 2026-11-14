package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import br.com.angatusistemas.lib.console.Console;

/**
 * The two connection pools of the application database: one writer connection used only with the
 * write turn, and a query_only reader pool; every HikariCP reference lives here so loading Saveable
 * never needs that jar before the dependency guard can explain what is missing.
 *
 * @author Angatu Sistemas
 */
final class ConnectionPools {

    private final HikariDataSource writer;
    private final HikariDataSource readers;

    ConnectionPools(String path) {
        // The writer opens first: on a new database it is the one that switches WAL on.
        writer = new HikariDataSource(config(path, "Saveable-escrita", 1, null));
        try {
            readers = new HikariDataSource(config(path, "Saveable-leitura", DatabaseSettings.READ_POOL_SIZE, "PRAGMA query_only = 1"));
        } catch (RuntimeException e) {
            writer.close();
            throw e;
        }
    }

    Connection borrowReader() throws SQLException {
        return borrow(readers);
    }

    Connection borrowWriter() throws SQLException {
        return borrow(writer);
    }

    // A connection whose transaction state is unknown is evicted, never reused: the next autocommit
    // would confirm exactly what should have been undone.
    void release(Connection conn, boolean clean) {
        if (!clean) {
            try {
                writer.evictConnection(conn);
            } catch (RuntimeException e) {
                Console.error("Falha ao marcar conexão para descarte", e);
            }
        }
        try {
            conn.close();
        } catch (SQLException e) {
            Console.error("Falha ao devolver conexão ao pool", e);
        }
    }

    // Lets SQLite refresh the statistics the planner uses for the json_extract indexes; a failure here
    // only costs a slower plan, so it is logged and the shutdown goes on.
    void optimize() {
        try (Connection conn = borrowWriter()) {
            RecordSql.execute(conn, "PRAGMA optimize");
        } catch (SQLException e) {
            Console.warn("Saveable: PRAGMA optimize falhou no encerramento: %s", e.getMessage());
        }
    }

    // HikariCP's abort does nothing on SQLite: closing with a read in flight leaves that connection
    // open forever, the file locked and the WAL without its closing checkpoint.
    void close(long deadline) {
        awaitIdle(readers, deadline);
        try {
            readers.close();
        } finally {
            writer.close();
        }
    }

    private static HikariConfig config(String path, String name, int size, String initSql) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path);
        config.setPoolName(name);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(Math.min(size, 2));
        // only a pool that shrinks has idle connections to close; a fixed one would only log a warning
        if (size > 2) config.setIdleTimeout(30_000);
        config.setConnectionTimeout(DatabaseSettings.BUSY_TIMEOUT_MS);
        config.setConnectionTestQuery("SELECT 1");
        if (initSql != null) config.setConnectionInitSql(initSql);
        // Values always as text: the driver reads them with getProperty, which ignores non-String values.
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", Integer.toString(DatabaseSettings.BUSY_TIMEOUT_MS));
        config.addDataSourceProperty("transaction_mode", "IMMEDIATE");
        config.addDataSourceProperty("cache_size", Integer.toString(-DatabaseSettings.CACHE_SIZE_KIB)); // negative = KiB
        // Sorts and temporary indexes of a large query go to a temporary file instead of RAM.
        config.addDataSourceProperty("temp_store", "FILE");
        if (DatabaseSettings.MMAP_MIB > 0) {
            config.addDataSourceProperty("mmap_size", Long.toString(DatabaseSettings.MMAP_MIB * 1024L * 1024L));
        }
        config.addDataSourceProperty("journal_size_limit", Long.toString(DatabaseSettings.JOURNAL_SIZE_LIMIT_BYTES));
        return config;
    }

    // An interrupt does not cut the wait short; it goes back to the thread at the end.
    private static Connection borrow(HikariDataSource pool) throws SQLException {
        boolean interrupted = Thread.interrupted();
        long deadline = DatabaseSettings.deadlineFromNow();
        try {
            while (true) {
                try {
                    return pool.getConnection();
                } catch (SQLException e) {
                    if (!(e.getCause() instanceof InterruptedException) || deadline - System.nanoTime() <= 0) throw e;
                    Thread.interrupted();
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void awaitIdle(HikariDataSource pool, long deadline) {
        HikariPoolMXBean state = pool.getHikariPoolMXBean();
        if (state == null) return;
        boolean interrupted = false;
        while (state.getActiveConnections() > 0 && deadline - System.nanoTime() > 0) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
