package br.com.angatusistemas.lib.javalin.html;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.strings.StringAPI;
import io.javalin.Javalin;

/**
 * [PT] Utilitário para registro automático de rotas HTML a partir de arquivos
 * estáticos.
 * <p>
 * Escaneia a pasta /public (classpath ou sistema de arquivos, conforme
 * configuração do Javalin) em busca de arquivos .html e registra rotas
 * dinâmicas baseadas no nome do arquivo. Suporta template com placeholders,
 * cache de conteúdo, exclusão de páginas e personalização.
 * </p>
 *
 * [EN] Utility for automatic registration of HTML routes from static files.
 * <p>
 * Scans the /public folder (classpath or filesystem, depending on Javalin
 * config) for .html files and registers dynamic routes based on file names.
 * Supports template with placeholders, content caching, page exclusion and
 * customization.
 * </p>
 */
public final class HtmlRouteAPI {

	// Lista de paths a serem ignorados (ex: "index", "admin")
	private static final Set<String> IGNORED_PATHS = new HashSet<>(Arrays.asList());

	private HtmlRouteAPI() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== CONFIGURAÇÃO ====================

	/**
	 * [PT] Adiciona um path a ser ignorado no registro automático.
	 * <p>
	 * Exemplo: se ignorar "admin", o arquivo admin.html não gerará rota.
	 * </p>
	 * [EN] Adds a path to be ignored in automatic registration.
	 * <p>
	 * Example: if "admin" is ignored, admin.html will not generate a route.
	 * </p>
	 */
	public static void addIgnoredPath(String path) {
		IGNORED_PATHS.add(path.toLowerCase());
	}

	/**
	 * [PT] Remove um path da lista de ignorados. [EN] Removes a path from the
	 * ignored list.
	 */
	public static void removeIgnoredPath(String path) {
		IGNORED_PATHS.remove(path.toLowerCase());
	}

	/**
	 * [PT] Define a lista de paths ignorados (substitui a atual). [EN] Sets the
	 * list of ignored paths (replaces current).
	 */
	public static void setIgnoredPaths(Collection<String> paths) {
		IGNORED_PATHS.clear();
		IGNORED_PATHS.addAll(paths.stream().map(String::toLowerCase).collect(Collectors.toSet()));
	}

	// ==================== REGISTRO PRINCIPAL ====================

	/**
	 * [PT] Registra automaticamente todas as rotas HTML a partir dos arquivos
	 * estáticos.
	 * <p>
	 * Para cada arquivo .html na pasta /public (exceto os ignorados e o template
	 * base, se houver), cria uma rota GET no formato "/nome_do_arquivo". Utiliza um
	 * template base (ex: index.html) para envolver o conteúdo da página. Os
	 * placeholders suportados são:
	 * <ul>
	 * <li>{page} - Nome da página capitalizado (substituído automaticamente)</li>
	 * <li>{content} - Conteúdo do arquivo HTML específico da página</li>
	 * <li>{%nome_active} - Para marcação de menu ativo (ex: {%home_active})</li>
	 * </ul>
	 * </p>
	 *
	 * [EN] Automatically registers all HTML routes from static files.
	 * <p>
	 * For each .html file in the /public folder (except ignored ones and the base
	 * template), creates a GET route in the format "/file_name". Uses a base
	 * template (e.g., index.html) to wrap the page content. Supported placeholders:
	 * <ul>
	 * <li>{page} - Capitalized page name (replaced automatically)</li>
	 * <li>{content} - Content of the specific page's HTML file</li>
	 * <li>{%name_active} - For active menu highlighting (e.g., {%home_active})</li>
	 * </ul>
	 * </p>
	 *
	 * @param javalin          [PT] Instância do Javalin onde as rotas serão
	 *                         registradas [EN] Javalin instance where routes will
	 *                         be registered
	 * @param baseTemplatePath [PT] Caminho do template base (ex: "/index.html") –
	 *                         pode ser null [EN] Path to the base template (e.g.,
	 *                         "/index.html") – may be null
	 * @param pageProvider     [PT] Provedor de lista de páginas (opcional, se null
	 *                         usa {@link #getAllHtmlPages()}) [EN] Page list
	 *                         provider (optional, if null uses
	 *                         {@link #getAllHtmlPages()})
	 * @param contentLoader    [PT] Carregador de conteúdo personalizado (opcional)
	 *                         [EN] Custom content loader (optional)
	 */
	public static void registerAllRoutes(Javalin javalin, String baseTemplatePath, PageProvider pageProvider) {

	    List<String> htmlFiles = (pageProvider != null) ? pageProvider.getPages() : getAllHtmlPages();

	    if (htmlFiles == null || htmlFiles.isEmpty()) {
	        Console.error("Nenhum arquivo HTML encontrado para registrar rotas!");
	        return;
	    }

	    Console.log("Arquivos HTML encontrados: " + htmlFiles.size());

	    for (String filePath : htmlFiles) {

	        if (filePath == null || !filePath.endsWith(".html")) continue;

	        String pageName = extractPageName(filePath);

	        // Ignorar template base (ex: index.html)
	        if (baseTemplatePath != null && filePath.equalsIgnoreCase(baseTemplatePath)) {
	            Console.debug("Template base ignorado como rota: " + filePath);
	            continue;
	        }

	        if (IGNORED_PATHS.contains(pageName)) {
	            Console.debug("Página ignorada: " + pageName);
	            continue;
	        }

	        // Rota especial: index vira "/"
	        String routePath = pageName.equalsIgnoreCase("index") ? "/" : "/" + pageName;
	        String finalFilePath = filePath;

	        javalin.unsafe.routes.get(routePath, ctx -> {
	            try {
	                String clientIp = ctx.ip();
	                Console.debug("Acessando rota [%s] -> %s", clientIp, routePath);

	                String baseHtml = null;
	                if (baseTemplatePath != null) {
	                    baseHtml = loadContent(baseTemplatePath);
	                }

	                String pageContent = loadContent(finalFilePath);
	                if (pageContent == null) {
	                    ctx.status(404).result("Página não encontrada: " + finalFilePath);
	                    return;
	                }

	                String renderedHtml;

	                if (baseHtml != null) {
	                    renderedHtml = baseHtml
	                            .replace("{page}", StringAPI.capitalize(pageName))
	                            .replace("{content}", pageContent);

	                    for (String otherPage : htmlFiles) {
	                        String otherName = extractPageName(otherPage);
	                        renderedHtml = renderedHtml.replace(
	                                "{%" + otherName + "_active}",
	                                pageName.equals(otherName) ? "bg-blue-600 text-white" : ""
	                        );
	                    }
	                } else {
	                    renderedHtml = pageContent;
	                }

	                ctx.contentType("text/html");
	                ctx.result(renderedHtml).status(200);

	            } catch (Exception e) {
	                Console.error("Erro ao processar rota: " + routePath, e);
	                ctx.result("Não foi possível acessar esta página!").status(500);
	            }
	        });

	        Console.log("Rota [%s] registrada com sucesso! (arquivo: %s)", routePath, filePath);
	    }
	}

	/**
	 * [PT] Registra rotas usando valores padrão (template base = "/index.html").
	 * [EN] Registers routes using default values (base template = "/index.html").
	 */
	public static void registerAllRoutes(Javalin javalin) {
		registerAllRoutes(javalin, "/index.html", null);
	}

	// ==================== CARREGAMENTO DE CONTEÚDO ====================

	private static String loadContent(String path) {
		return AssetsAPI.readAssetAsString(path);
	}

	/**
	 * [PT] Obtém a lista de todos os arquivos HTML na pasta pública (AssetsAPI).
	 * [EN] Gets the list of all HTML files in the public folder (AssetsAPI).
	 */
	public static List<String> getAllHtmlPages() {
		return AssetsAPI.listAllAssetsRecursive("/").stream().filter(file -> file.endsWith(".html")).filter(file -> {
			String normalized = file.replace("\\", "/");
			return !normalized.contains("/emails/") && !normalized.contains("/others/");
		}).collect(Collectors.toList());
	}

	/**
	 * [PT] Extrai o nome da página a partir do caminho do arquivo (ex:
	 * "/sobre.html" -> "sobre"). [EN] Extracts the page name from the file path
	 * (e.g., "/about.html" -> "about").
	 */
	public static String extractPageName(String filePath) {
	    if (filePath == null || filePath.isEmpty())
	        return "";

	    filePath = filePath.replace("\\", "/");

	    if (filePath.startsWith("//")) {
	        filePath = filePath.substring(1);
	    }

	    while (filePath.contains("//")) {
	        filePath = filePath.replace("//", "/");
	    }

	    if (filePath.endsWith("/")) {
	        filePath = filePath.substring(0, filePath.length() - 1);
	    }

	    String fileName = filePath.contains("/")
	            ? filePath.substring(filePath.lastIndexOf('/') + 1)
	            : filePath;

	    if (fileName.contains(".")) {
	        fileName = fileName.substring(0, fileName.lastIndexOf('.'));
	    }

	    return fileName.toLowerCase();
	}

}