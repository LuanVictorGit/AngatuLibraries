package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Routes every database operation: reads to the reader pool (or to the running transaction), writes
 * through the write turn on the single writer connection, nested transactions as savepoints, and
 * every driver failure into a logged PersistenceException.
 *
 * @author Angatu Sistemas
 */
final class Database {

    private static final String SQLITE_COORDINATES = "org.xerial:sqlite-jdbc:3.51.3.0";
    private static final String HIKARI_COORDINATES = "com.zaxxer:HikariCP:7.0.2";
    private static final String GSON_COORDINATES = "com.google.code.gson:gson:2.13.2";
    private static final String PERSISTENCE_FEATURE = "Persistência (Saveable)";

    private static final ReentrantLock POOLS_LOCK = new ReentrantLock();
    private static final ThreadLocal<Transaction> CURRENT_TRANSACTION = new ThreadLocal<>();

    private static volatile ConnectionPools pools;

    private Database() {
    }

    static Transaction currentTransaction() {
        return CURRENT_TRANSACTION.get();
    }

    // Only SQL runs here: the caller converts JSON into objects after the connection is back, so a
    // constructor that queries the database never makes one thread hold two reader connections.
    static <R> R read(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        try (Connection conn = pools().borrowReader()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw failure(what, e);
        }
    }

    static <R> R write(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        return runOutermost(what, () -> apply(CURRENT_TRANSACTION.get(), what, work));
    }

    // A single-statement write outside a transaction joins the group commit: concurrent writers share
    // one transaction instead of paying for their own, and each one still fails alone.
    static <R> R writeGrouped(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        return GroupCommit.submit(what, work);
    }

    // A single statement is already atomic, and SQLite refuses VACUUM, ATTACH and a journal_mode
    // change inside a transaction: outside one, this runs alone in autocommit on the writer.
    static <R> R writeAlone(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        WriterTurn.lock(what);
        long lockedAt = System.nanoTime();
        try (Connection conn = pools().borrowWriter()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw failure(what, e);
        } finally {
            WriterTurn.unlock();
            WriterTurn.warnIfSlow(what, lockedAt);
        }
    }

    static <T> T inTransaction(Supplier<T> actions) {
        Transaction running = CURRENT_TRANSACTION.get();
        return running != null ? runNested(running, actions) : runOutermost("transação", actions);
    }

    // After every SQL failure the transaction is checked: if SQLite undid it whole, whoever caught the
    // exception and went on would otherwise write the rest outside it, each statement on its own.
    static <R> R apply(Transaction tx, String what, SqlWork<R> work) {
        tx.ensureUsable(what);
        try {
            return work.apply(tx.connection);
        } catch (SQLException e) {
            tx.checkStillOpen(e);
            throw failure(tx.broken ? what + " — o SQLite desfez a transação inteira" : what, e);
        }
    }

    static void shutdown() {
        long deadline = DatabaseSettings.deadlineFromNow();
        boolean locked = WriterTurn.acquire(deadline);
        try {
            ConnectionPools current;
            POOLS_LOCK.lock();
            try {
                current = pools;
                pools = null;
            } finally {
                POOLS_LOCK.unlock();
            }
            if (current != null) {
                if (locked) current.optimize();
                current.close(deadline);
            }
            TableRegistry.clear();
            CURRENT_TRANSACTION.remove();
        } finally {
            if (locked) WriterTurn.unlock();
        }
    }

    static PersistenceException failure(String what, SQLException e) {
        TableRegistry.forgetMissingTable(e);
        return logged(new PersistenceException("Erro ao " + what, e));
    }

    static PersistenceException logged(PersistenceException e) {
        Console.error(e.getMessage(), e.getCause());
        return e;
    }

    // While the block runs the transaction is bound to the thread: every read, write, table creation
    // or nested transaction it does joins it instead of asking for a connection that would wait on
    // the file lock this thread already holds.
    private static <T> T runOutermost(String what, Supplier<T> actions) {
        WriterTurn.lock(what);
        long lockedAt = System.nanoTime();
        try {
            ConnectionPools current = pools();
            Connection conn;
            try {
                conn = current.borrowWriter();
            } catch (SQLException e) {
                throw failure("obter a conexão de escrita para " + what, e);
            }
            return runOnWriter(current, conn, what, actions);
        } finally {
            WriterTurn.unlock();
            WriterTurn.warnIfSlow(what, lockedAt);
        }
    }

    private static <T> T runOnWriter(ConnectionPools current, Connection conn, String what, Supplier<T> actions) {
        Transaction tx = new Transaction(conn);
        boolean commitStarted = false;
        boolean clean = false;
        try {
            begin(tx, what);
            CURRENT_TRANSACTION.set(tx);
            T result = actions.get();
            tx.ensureUsable(what); // the block swallowed a failure that took the transaction with it
            if (tx.rollbackOnly) {
                throw logged(new PersistenceException("Transação desfeita inteira: um trecho aninhado falhou e "
                        + "não pôde ser desfeito sozinho (" + what + ")"));
            }
            commitStarted = true;
            commit(conn, what);
            clean = true;
            tx.publishCreatedTables();
            return result;
        } finally {
            CURRENT_TRANSACTION.remove();
            if (!commitStarted && !tx.broken) clean = rollback(conn);
            current.release(conn, clean);
        }
    }

    static void begin(Transaction tx, String what) {
        try {
            tx.connection.setAutoCommit(false); // BEGIN IMMEDIATE: the file write lock from now on
        } catch (SQLException e) {
            tx.broken = true; // never opened: nothing to undo, and the driver is out of sync
            throw failure("abrir a transação para " + what, e);
        }
    }

    // In a transaction SQLite already undid, a SAVEPOINT would open a new one and the block would
    // write alone what should go with the rest, so a broken transaction refuses to nest.
    private static <T> T runNested(Transaction tx, Supplier<T> actions) {
        tx.ensureUsable("abrir transação aninhada");
        String savepoint;
        try {
            savepoint = tx.openSavepoint();
        } catch (SQLException e) {
            tx.checkStillOpen(e);
            throw failure("abrir transação aninhada", e);
        }
        T result;
        try {
            result = actions.get();
        } catch (Throwable failure) {
            undoNested(tx, savepoint, failure);
            throw failure;
        }
        tx.ensureUsable("confirmar transação aninhada");
        try {
            tx.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            tx.rollbackOnly = true;
            tx.checkStillOpen(e);
            throw failure("confirmar transação aninhada", e);
        }
        return result;
    }

    private static void undoNested(Transaction tx, String savepoint, Throwable failure) {
        if (tx.broken) return; // SQLite already undid everything, the savepoint with it
        try {
            tx.rollbackToSavepoint(savepoint);
        } catch (SQLException undo) {
            tx.rollbackOnly = true; // the block's state is unknown: nothing of this transaction counts
            tx.checkStillOpen(undo);
            failure.addSuppressed(undo);
        }
    }

    // setAutoCommit(true), not commit(): the driver's commit() chains a new BEGIN IMMEDIATE that can
    // fail after the data is already written, and the caller would repeat a write that counted.
    static void commit(Connection conn, String what) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw failure("confirmar " + what, e);
        }
    }

    static boolean rollback(Connection conn) {
        try {
            conn.rollback();          // undoes; the driver reopens a BEGIN right after...
            conn.setAutoCommit(true); // ...and this COMMIT closes that empty BEGIN
            return true;
        } catch (SQLException e) {
            Console.error("Falha ao desfazer a transação do Saveable", e);
            return false;
        }
    }

    static ConnectionPools pools() {
        ConnectionPools current = pools;
        if (current != null) return current;
        Dependencies.require("org.sqlite.JDBC", SQLITE_COORDINATES, PERSISTENCE_FEATURE);
        Dependencies.require("com.zaxxer.hikari.HikariDataSource", HIKARI_COORDINATES, PERSISTENCE_FEATURE);
        Dependencies.require("com.google.gson.Gson", GSON_COORDINATES, PERSISTENCE_FEATURE);
        POOLS_LOCK.lock();
        try {
            if (pools == null) pools = open(DatabaseSettings.databasePath());
            return pools;
        } finally {
            POOLS_LOCK.unlock();
        }
    }

    private static ConnectionPools open(String path) {
        ConnectionPools opened;
        try {
            opened = new ConnectionPools(path);
        } catch (RuntimeException e) {
            throw logged(new PersistenceException("Não foi possível abrir o banco SQLite em " + path, e));
        }
        Console.log("&7Banco SQLite: &f%s &7(WAL, 1 conexão de escrita e %d de leitura, cache de %d MiB por conexão)",
                path, DatabaseSettings.READ_POOL_SIZE, Integer.valueOf(DatabaseSettings.CACHE_SIZE_KIB / 1024));
        return opened;
    }

    // An operation on a connection already resolved, from the reader pool or the running transaction.
    @FunctionalInterface
    interface SqlWork<R> {
        R apply(Connection conn) throws SQLException;
    }
}
