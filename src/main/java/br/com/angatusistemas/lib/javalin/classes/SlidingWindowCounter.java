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
public final class SlidingWindowCounter {

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

    /**
     * Instante da última requisição registrada, em segundos (epoch).
     *
     * <p>Existe para a varredura que <strong>remove</strong> contadores parados dos mapas de
     * rate limiting. Sem ela não havia como saber se uma chave ainda estava em uso, e o mapa
     * só crescia: uma entrada por IP e por rota, para sempre, mesmo para quem passou uma vez
     * e nunca mais voltou. Um varredor de URLs deixava mil entradas permanentes atrás de si.</p>
     *
     * <p>Devolver {@link Long#MAX_VALUE} quando o contador está vazio é deliberado: significa
     * "acabou de ser criado e ainda não registrou nada", e mantém a entrada viva até a
     * varredura seguinte, em vez de removê-la no exato instante entre o
     * {@code computeIfAbsent} e o {@code checkAndIncrement}.</p>
     *
     * @return Segundos (epoch) da última requisição, ou {@link Long#MAX_VALUE} se vazio
     */
    public synchronized long lastSeenSeconds() {
        Long ultimo = timestamps.peekLast();
        return ultimo == null ? Long.MAX_VALUE : ultimo.longValue();
    }

    /**
     * Quantas requisições estão dentro da janela neste momento, sem registrar nada.
     *
     * @return Tamanho atual da janela
     */
    public synchronized int size() {
        return timestamps.size();
    }
}
