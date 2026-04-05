package br.com.angatusistemas.lib.javalin.html;

import java.util.List;

/**
 * [PT] Provedor de lista de páginas HTML (útil para testes ou fontes
 * customizadas). [EN] Provider of HTML page list (useful for tests or custom
 * sources).
 */
@FunctionalInterface
public interface PageProvider {
	List<String> getPages();
}