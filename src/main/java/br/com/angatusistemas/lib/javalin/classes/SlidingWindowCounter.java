package br.com.angatusistemas.lib.javalin.classes;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Contador de janela deslizante (sliding window) para rate limiting.
 *
 * <p>Mantém os timestamps das requisições dentro de uma janela de tempo e
 * responde se uma nova requisição está dentro do limite configurado.</p>
 *
 * <p><strong>Desempenho:</strong> os timestamps são adicionados em ordem
 * cronológica crescente, portanto os expirados estão sempre no início da fila
 * e são removidos em O(1) amortizado — sem varreduras da lista inteira.</p>
 *
 * <p>Exemplo de uso:
 * <pre>
 * SlidingWindowCounter secondWindow = new SlidingWindowCounter(1); // janela de 1 segundo
 * boolean allowed = secondWindow.checkAndIncrement(5, Instant.now().getEpochSecond());
 * </pre>
 * </p>
 *
 * <p><strong>Thread safety:</strong> a instância é segura para uso concorrente
 * (métodos sincronizados). Para rate limiting, mantenha uma instância por
 * chave (IP + rota) em um {@link java.util.concurrent.ConcurrentHashMap}.</p>
 *
 * @author Angatu Sistemas
 */
public class SlidingWindowCounter {

    /** Tamanho da janela em segundos. */
    private final long windowSizeSeconds;
    /** Timestamps ativos dentro da janela, em ordem crescente de inserção. */
    private final Deque<Long> timestamps = new ArrayDeque<>();

    /**
     * Cria um contador com a janela de tempo especificada.
     *
     * @param windowSizeSeconds Tamanho da janela em segundos (deve ser positivo)
     * @throws IllegalArgumentException se {@code windowSizeSeconds <= 0}
     */
    public SlidingWindowCounter(long windowSizeSeconds) {
        if (windowSizeSeconds <= 0) {
            throw new IllegalArgumentException("windowSizeSeconds deve ser positivo: " + windowSizeSeconds);
        }
        this.windowSizeSeconds = windowSizeSeconds;
    }

    /**
     * Remove os timestamps expirados e tenta registrar uma nova requisição na janela.
     *
     * @param limit Número máximo de requisições permitidas na janela
     * @param now   Timestamp atual em segundos (epoch)
     * @return {@code true} se a requisição foi registrada (dentro do limite);
     *         {@code false} se a janela está cheia (requisição deve ser bloqueada)
     */
    public synchronized boolean checkAndIncrement(int limit, long now) {
        // Timestamps expirados estão sempre no início da fila (ordem crescente)
        long cutoff = now - windowSizeSeconds;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }
}
