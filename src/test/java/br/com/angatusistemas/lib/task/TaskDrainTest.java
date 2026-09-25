package br.com.angatusistemas.lib.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O desligamento gradual espera o trabalho já pedido. Classe própria porque o {@code drain}
 * encerra os pools do processo — cada classe de teste roda numa JVM nova.
 *
 * @author Angatu Sistemas
 */
class TaskDrainTest {

    @Test
    @DisplayName("drain deixa a fila terminar e cancela as periódicas")
    void drainLetsQueuedWorkFinishAndCancelsTimers() {
        AtomicInteger finished = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            Task.runAsync(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                finished.incrementAndGet();
            });
        }
        Task.runTimerWithFixedDelay(() -> { }, 0, 10);

        assertTrue(Task.drain(10_000), "o drain não terminou no prazo");
        assertEquals(40, finished.get(), "trabalho enfileirado foi descartado no desligamento");
    }
}
