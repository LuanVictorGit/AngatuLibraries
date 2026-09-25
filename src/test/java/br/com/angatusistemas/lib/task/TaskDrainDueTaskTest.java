package br.com.angatusistemas.lib.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O {@code drain} não perde a tarefa cujo prazo venceu com o relógio ocupado. Classe própria
 * porque o {@code drain} encerra os pools do processo — cada classe de teste roda numa JVM nova.
 *
 * @author Angatu Sistemas
 */
class TaskDrainDueTaskTest {

    @Test
    @DisplayName("drain roda a tarefa cujo prazo venceu antes de o relógio ocupado dispará-la")
    void drainRunsADueTaskTheBusyTimerHadNotFiredYet() throws Exception {
        // As quatro threads do relógio ocupadas com periódicas lentas — uma sincronização de rede.
        CountDownLatch busy = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            Task.runTimerWithFixedDelay(() -> {
                busy.countDown();
                sleepQuietly(800);
            }, 0, 60_000);
        }
        assertTrue(busy.await(5, TimeUnit.SECONDS));

        AtomicBoolean ran = new AtomicBoolean();
        Task.runLater(() -> ran.set(true), 50);
        Thread.sleep(200); // o prazo venceu, e nenhuma thread do relógio está livre para dispará-lo

        assertTrue(Task.drain(10_000), "o drain não terminou no prazo");
        assertTrue(ran.get(), "tarefa pedida antes do desligamento sumiu sem rodar");
        assertEquals(0, Task.activeTaskCount(), "sobrou tarefa registrada depois do drain");

        assertThrows(RejectedExecutionException.class, () -> Task.runAsync(() -> { }));
        assertThrows(RejectedExecutionException.class, () -> Task.runLater(() -> { }, 1_000));
        assertThrows(RejectedExecutionException.class, () -> Task.runSync(() -> { }));
        assertThrows(RejectedExecutionException.class, () -> Task.runTimer(() -> { }, 0, 1_000));
        assertEquals(0, Task.activeTaskCount(), "tarefa recusada ficou registrada como ativa");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
