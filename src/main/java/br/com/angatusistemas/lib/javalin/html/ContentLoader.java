package br.com.angatusistemas.lib.javalin.html;

/**
 * [PT] Carregador de conteúdo personalizado (ex: do banco de dados, cache,
 * etc.). [EN] Custom content loader (e.g., from database, cache, etc.).
 */
@FunctionalInterface
public interface ContentLoader {
	String load(String path);
}