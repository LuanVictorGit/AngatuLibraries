package br.com.angatusistemas.lib.javalin.html;

import lombok.Getter;

/**
 * Entrada de cache de conteúdo HTML: armazena o conteúdo e o timestamp de
 * expiração calculado a partir do TTL.
 *
 * <p>Classe de suporte interno do pacote {@code html} (construtor e método de
 * expiração package-private).</p>
 *
 * @author Angatu Sistemas
 */
@Getter
public final class CachedHtml {

    /** Conteúdo HTML cacheado. */
    final String content;
    /** Timestamp (ms) a partir do qual a entrada é considerada expirada. */
    final long expiry;

    /**
     * Cria uma entrada de cache.
     *
     * @param content Conteúdo HTML
     * @param ttlMs   Tempo de vida em milissegundos
     */
    CachedHtml(String content, long ttlMs) {
        this.content = content;
        this.expiry = System.currentTimeMillis() + ttlMs;
    }

    /**
     * Verifica se a entrada expirou.
     *
     * @return {@code true} se o TTL passou
     */
    boolean isExpired() {
        return System.currentTimeMillis() > expiry;
    }
}
