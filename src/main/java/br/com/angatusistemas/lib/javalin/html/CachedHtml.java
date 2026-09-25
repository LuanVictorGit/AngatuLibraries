package br.com.angatusistemas.lib.javalin.html;

import lombok.Getter;

/**
 * Entrada de cache de conteúdo HTML: armazena o conteúdo e o timestamp de
 * expiração calculado a partir do TTL.
 *
 * @author Angatu Sistemas
 * @deprecated Não é usada por nenhuma parte da biblioteca, e o construtor não é acessível de
 *             fora do pacote: não há como obter uma instância. As páginas não têm cache — são
 *             lidas a cada requisição. Será removida numa próxima versão.
 */
@Deprecated(forRemoval = true)
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
