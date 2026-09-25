package br.com.angatusistemas.lib.javalin.html;

/**
 * Carregador de conteúdo personalizado.
 *
 * @author Angatu Sistemas
 * @deprecated Nenhuma API da biblioteca recebe um {@code ContentLoader}: implementar esta
 *             interface não muda nada. As páginas são lidas de {@code public/} pelo
 *             {@link HtmlRouteAPI}; para outra fonte de lista, use {@link PageProvider}. Será
 *             removida numa próxima versão.
 */
@Deprecated(forRemoval = true)
@FunctionalInterface
public interface ContentLoader {

	/**
	 * Carrega o conteúdo de um caminho.
	 *
	 * @param path Caminho do conteúdo
	 * @return Conteúdo carregado
	 */
	String load(String path);
}
