package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Write transaction bound to the thread that opened it: the writer connection, its savepoints, the
 * tables created inside it (published only after the outermost commit) and whether SQLite already
 * rolled it back on its own; confined to one thread, so it needs no synchronisation.
 *
 * @author Angatu Sistemas
 */
final class Transaction {

    final Connection connection;

    // One layer per open savepoint: a rolled-back savepoint drops its layer because SQLite undid the
    // CREATE TABLE with it.
    private final Deque<Set<Class<?>>> createdTables = new ArrayDeque<>();

    private int savepointSequence;

    // A savepoint could not be undone on its own: nothing of this transaction may be committed.
    boolean rollbackOnly;

    // SQLite rolled the whole transaction back by itself (disk full, I/O, ON CONFLICT ROLLBACK), or it
    // never opened: the connection is in autocommit without the driver knowing, so nothing else runs
    // on it, nothing is committed and the connection is evicted at the end.
    boolean broken;

    Transaction(Connection connection) {
        this.connection = connection;
        createdTables.push(new HashSet<>());
    }

    // Asks with a BEGIN, the same probe the driver uses: an open transaction refuses it with
    // "within a transaction"; otherwise it opens an empty one, undone at once, and this one is lost.
    void checkStillOpen(Throwable failure) {
        if (broken) return;
        try {
            RecordSql.execute(connection, "BEGIN");
        } catch (SQLException refused) {
            String message = refused.getMessage();
            if (message != null && message.contains("within a transaction")) return;
            broken = true;
            failure.addSuppressed(refused);
            return;
        }
        broken = true;
        try {
            RecordSql.execute(connection, "ROLLBACK");
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    void ensureUsable(String what) {
        if (broken) {
            throw Database.logged(new PersistenceException("Transação perdida: depois de uma falha, o SQLite desfez "
                    + "tudo o que ela tinha gravado, e nada mais roda nela (" + what + ")"));
        }
    }

    boolean hasCreated(Class<?> type) {
        for (Set<Class<?>> layer : createdTables) {
            if (layer.contains(type)) return true;
        }
        return false;
    }

    void markCreated(Class<?> type) {
        createdTables.peek().add(type);
    }

    void forget(String table) {
        for (Set<Class<?>> layer : createdTables) {
            layer.removeIf(type -> TableRegistry.tableName(type).equalsIgnoreCase(table));
        }
    }

    String openSavepoint() throws SQLException {
        String name = "angatu_sp_" + (++savepointSequence);
        RecordSql.execute(connection, "SAVEPOINT " + name);
        createdTables.push(new HashSet<>());
        return name;
    }

    void releaseSavepoint(String name) throws SQLException {
        RecordSql.execute(connection, "RELEASE SAVEPOINT " + name);
        Set<Class<?>> layer = createdTables.pop();
        createdTables.peek().addAll(layer);
    }

    void rollbackToSavepoint(String name) throws SQLException {
        createdTables.pop();
        RecordSql.execute(connection, "ROLLBACK TO SAVEPOINT " + name);
        RecordSql.execute(connection, "RELEASE SAVEPOINT " + name);
    }

    // Called only after the commit: only now do the tables exist for the whole process.
    void publishCreatedTables() {
        for (Set<Class<?>> layer : createdTables) TableRegistry.markPrepared(layer);
    }
}
