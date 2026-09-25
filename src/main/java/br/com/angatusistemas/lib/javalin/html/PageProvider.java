package br.com.angatusistemas.lib.javalin.html;

import java.util.List;

/**
 * Fonte da lista de páginas HTML para {@link HtmlRouteAPI#registerAllRoutes(io.javalin.Javalin, String, PageProvider)}
 * — útil em testes ou quando as páginas não estão todas em {@code public/}.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface PageProvider {

	/**
	 * Lista as páginas a registrar.
	 *
	 * @return Caminhos relativos a {@code public/} (ex: {@code "/sobre.html"})
	 */
	List<String> getPages();
}
