package br.com.angatusistemas.lib.database;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import br.com.angatusistemas.lib.console.Console;

/**
 * The write turn: one writer at a time in the whole process, in arrival order, with a deadline on
 * every wait, interrupts deferred instead of dropping the write, and a log line for any write that
 * holds the turn too long.
 *
 * @author Angatu Sistemas
 */
final class WriterTurn {

    // Fair on purpose: under a burst of tracking writes, an occasional screen save must not starve.
    // It is the only write lock; a per-record lock taken before it deadlocked save() inside mutate().
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock(true);

    private static final Set<String> INTERNAL_CLASSES = Set.of(Saveable.class.getName(), Database.class.getName(),
            WriterTurn.class.getName(), Transaction.class.getName(), TableRegistry.class.getName(),
            RecordSql.class.getName(), EntityIds.class.getName(), BatchWriter.class.getName(),
            PagedReader.class.getName(), GroupCommit.class.getName());

    private WriterTurn() {
    }

    static void lock(String what) {
        if (!acquire(DatabaseSettings.deadlineFromNow())) {
            throw Database.logged(new PersistenceException("Banco ocupado: a vez de gravar não chegou em "
                    + DatabaseSettings.BUSY_TIMEOUT_MS + " ms (" + what + ")"));
        }
    }

    static void unlock() {
        WRITE_LOCK.unlock();
    }

    // Waits until the deadline without stopping on an interrupt: an interrupted thread still has to
    // persist its state, and the interrupt is handed back to it at the end.
    static boolean acquire(long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    return WRITE_LOCK.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static void warnIfSlow(String what, long lockedAt) {
        long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lockedAt);
        if (heldMs < DatabaseSettings.SLOW_WRITE_MS) return;
        Console.warn("Saveable: \"%s\" (em %s) segurou a vez de gravar por %d ms — as outras gravações "
                + "esperaram. Tire chamada de rede e trabalho lento de dentro da transação.",
                what, callerOutsideLibrary(), heldMs);
    }

    private static String callerOutsideLibrary() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !isInternal(frame.getClassName()))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("?"));
    }

    private static boolean isInternal(String className) {
        int nested = className.indexOf('$');
        return INTERNAL_CLASSES.contains(nested < 0 ? className : className.substring(0, nested));
    }
}
