package br.com.angatusistemas.lib.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O agendador de tarefas sob carga: nada vaza, nenhum {@code Error} mata um timer, e o relógio
 * continua disparando com todos os trabalhadores ocupados.
 *
 * @author Angatu Sistemas
 */
class TaskTest {

    @Test
    @DisplayName("milhares de tarefas terminadas não deixam entrada no mapa")
    void finishedTasksLeaveNothingBehind() throws Exception {
        int tasks = 50_000;
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            if (i % 2 == 0) Task.runAsync(done::countDown);
            else Task.runLater(done::countDown, 0);
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Task.activeTaskCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, Task.activeTaskCount(), "tarefa terminada ficou registrada");
    }

    @Test
    @DisplayName("um Error dentro de uma tarefa periódica não para o timer")
    void errorInsideAPeriodicTaskDoesNotStopIt() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch fiveRuns = new CountDownLatch(5);
        int id = Task.runTimerWithFixedDelay(() -> {
            fiveRuns.countDown();
            if (runs.incrementAndGet() == 2) throw new StackOverflowError("simulado");
        }, 0, 20);
        try {
            assertTrue(fiveRuns.await(10, TimeUnit.SECONDS), "o timer parou depois do Error: " + runs.get() + " execuções");
        } finally {
            Task.cancelTask(id);
        }
    }

    @Test
    @DisplayName("o relógio dispara com todos os trabalhadores ocupados")
    void timersKeepFiringWhileAllWorkersAreBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 64; i++) {
            Task.runAsync(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        CountDownLatch fired = new CountDownLatch(5);
        int id = Task.runTimerWithFixedDelay(fired::countDown, 0, 50);
        try {
            assertTrue(fired.await(5, TimeUnit.SECONDS), "a varredura periódica ficou atrás do trabalho bloqueado");
        } finally {
            Task.cancelTask(id);
            release.countDown();
        }
    }

    @Test
    @DisplayName("tarefa da fila única pode ser cancelada antes de começar")
    void queuedSyncTaskCanBeCancelled() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Task.runSync(() -> {
            blockerStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        AtomicBoolean ran = new AtomicBoolean();
        int queued = Task.runSync(() -> ran.set(true));
        assertTrue(Task.activeTaskCount() >= 1, "tarefa da fila única não era contada");
        Task.cancelTask(queued);
        release.countDown();

        CountDownLatch after = new CountDownLatch(1);
        Task.runSync(after::countDown);
        assertTrue(after.await(5, TimeUnit.SECONDS));
        assertFalse(ran.get(), "tarefa cancelada rodou");
    }

    @Test
    @DisplayName("tarefa com atraso cancelada antes do prazo não roda")
    void delayedTaskCancelledBeforeItsDelayDoesNotRun() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        int id = Task.runLater(() -> ran.set(true), 300);
        Task.cancelTask(id);
        Thread.sleep(600);
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("ID que dá a volta não é entregue a uma tarefa ainda registrada")
    void wrappedIdIsNeverHandedToATaskStillRegistered() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        int timerId = Task.runTimerWithFixedDelay(runs::incrementAndGet, 0, 20);
        try {
            idCounter().set(timerId - 1); // o contador depois de 2^32 tarefas
            CountDownLatch done = new CountDownLatch(1);
            int oneShot = Task.runAsync(done::countDown);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNotEquals(timerId, oneShot, "o timer e a tarefa nova ficaram com o mesmo ID");
        } finally {
            Task.cancelTask(timerId); // a aplicação para o timer dela
        }
        Thread.sleep(100);
        int stopped = runs.get();
        Thread.sleep(300);
        assertEquals(stopped, runs.get(), "cancelTask não parou o timer: o registro dele tinha sido apagado");
    }

    @Test
    @DisplayName("o ID segue positivo depois de o contador dar a volta")
    void idsStayPositiveAfterTheCounterWraps() throws Exception {
        idCounter().set(Integer.MAX_VALUE);
        CountDownLatch done = new CountDownLatch(2);
        int first = Task.runAsync(done::countDown);
        int second = Task.runLater(done::countDown, 0);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(first > 0 && second > 0, "ID zero ou negativo: " + first + ", " + second);
        assertNotEquals(first, second);
    }

    /** O contador de IDs, lido por reflexão: dar a volta de verdade pediria 2^32 tarefas. */
    private static AtomicInteger idCounter() throws ReflectiveOperationException {
        Field field = Task.class.getDeclaredField("TASK_ID_COUNTER");
        field.setAccessible(true);
        return (AtomicInteger) field.get(null);
    }
}
