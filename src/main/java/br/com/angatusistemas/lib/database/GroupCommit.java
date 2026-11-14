package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Group commit for single-statement writes outside a transaction (save, delete): whoever gets the
 * write turn runs the writes waiting in the queue together in one transaction, each still failing
 * alone, so concurrent writers share one BEGIN and one COMMIT instead of paying for their own.
 *
 * @author Angatu Sistemas
 */
final class GroupCommit {

    // Bounded so the turn holder never keeps a waiting transaction() out for long.
    private static final int MAX_GROUP = 256;
    private static final String WHAT = "gravação em grupo";

    private static final ConcurrentLinkedQueue<Pending<?>> QUEUE = new ConcurrentLinkedQueue<>();

    private GroupCommit() {
    }

    static <R> R submit(String what, Database.SqlWork<R> work) {
        Pending<R> pending = new Pending<>(what, work);
        QUEUE.add(pending);
        long deadline = DatabaseSettings.deadlineFromNow();
        while (!pending.isDone()) {
            if (!WriterTurn.acquire(deadline)) {
                if (pending.cancel()) {
                    throw Database.logged(new PersistenceException("Banco ocupado: a vez de gravar não chegou em "
                            + DatabaseSettings.BUSY_TIMEOUT_MS + " ms (" + what + ")"));
                }
                deadline = DatabaseSettings.deadlineFromNow(); // already inside someone's group: wait for it
                continue;
            }
            try {
                if (!pending.isDone()) drain();
            } finally {
                WriterTurn.unlock();
            }
        }
        return pending.result();
    }

    // Holds the write turn: takes what is queued, oldest first, and commits it as one group.
    private static void drain() {
        long lockedAt = System.nanoTime();
        List<Pending<?>> group = new ArrayList<>(MAX_GROUP);
        Pending<?> next;
        while (group.size() < MAX_GROUP && (next = QUEUE.poll()) != null) {
            if (next.start()) group.add(next);
        }
        try {
            while (!group.isEmpty()) group = runGroup(group);
        } finally {
            WriterTurn.warnIfSlow(WHAT, lockedAt);
        }
    }

    // Returns the writes to run again: when SQLite rolls the whole transaction back, the write that
    // caused it fails alone and every other one goes into a fresh transaction, as if it ran by itself.
    private static List<Pending<?>> runGroup(List<Pending<?>> group) {
        ConnectionPools pools = Database.pools();
        Connection conn;
        try {
            conn = pools.borrowWriter();
        } catch (SQLException e) {
            failAll(group, Database.failure("obter a conexão de escrita para " + WHAT, e));
            return List.of();
        }
        Transaction tx = new Transaction(conn);
        boolean commitStarted = false;
        boolean clean = false;
        try {
            Database.begin(tx, WHAT);
            Pending<?> breaker = execute(tx, group);
            if (breaker != null) return pendingWithout(group, breaker);
            commitStarted = true;
            Database.commit(conn, WHAT);
            clean = true;
            for (Pending<?> pending : group) pending.succeed();
            return List.of();
        } catch (PersistenceException e) {
            failAll(group, e);
            return List.of();
        } finally {
            if (!commitStarted && !tx.broken) clean = Database.rollback(conn);
            pools.release(conn, clean);
        }
    }

    // Runs every write; a write that fails alone keeps its failure, and the one that makes SQLite
    // undo the whole transaction is returned so the rest can run again.
    private static Pending<?> execute(Transaction tx, List<Pending<?>> group) {
        for (Pending<?> pending : group) {
            try {
                pending.run(tx);
            } catch (RuntimeException e) {
                pending.fail(e);
                if (tx.broken) return pending;
            }
        }
        return null;
    }

    private static List<Pending<?>> pendingWithout(List<Pending<?>> group, Pending<?> breaker) {
        List<Pending<?>> retry = new ArrayList<>(group.size());
        for (Pending<?> pending : group) {
            if (pending != breaker && !pending.isDone()) retry.add(pending.reset());
        }
        return retry;
    }

    private static void failAll(List<Pending<?>> group, RuntimeException failure) {
        for (Pending<?> pending : group) {
            if (!pending.isDone()) pending.fail(failure);
        }
    }

    private static final class Pending<R> {

        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int CANCELLED = 2;

        private final String what;
        private final Database.SqlWork<R> work;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private R value;
        private RuntimeException failure;
        private boolean ran;
        private volatile boolean done;

        Pending(String what, Database.SqlWork<R> work) {
            this.what = what;
            this.work = work;
        }

        boolean start() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        boolean cancel() {
            return state.compareAndSet(QUEUED, CANCELLED);
        }

        void run(Transaction tx) {
            value = Database.apply(tx, what, work);
            ran = true;
        }

        Pending<R> reset() {
            value = null;
            ran = false;
            return this;
        }

        // A write that failed alone already holds its failure; only the ones that ran are confirmed.
        void succeed() {
            if (ran && !done) done = true;
        }

        void fail(RuntimeException e) {
            failure = e;
            done = true;
        }

        boolean isDone() {
            return done;
        }

        R result() {
            if (failure != null) throw failure;
            return value;
        }
    }
}
