package br.com.angatusistemas.lib.javalin.html;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.strings.StringAPI;
import io.javalin.Javalin;

/**
 * Registra as páginas HTML de {@code src/main/resources/public} como rotas {@code GET}.
 *
 * <h2>Como uma página vira rota</h2>
 * <ul>
 *   <li>{@code public/index.html} é o <strong>template base</strong> e não vira rota por conta
 *       própria: o {@code /} entrega o arquivo cru, pelo servidor de estáticos;</li>
 *   <li>cada outro arquivo {@code .html} de {@code public/}, em qualquer pasta, vira
 *       {@code GET /nome-do-arquivo} — só o nome do arquivo conta, então
 *       {@code public/blog/post.html} vira {@code /post}; as pastas {@code emails/} e
 *       {@code others/} ficam de fora;</li>
 *   <li>a página é montada dentro do template: o conteúdo dela entra em {@code {content}},
 *       {@code {page}} recebe o nome com inicial maiúscula e {@code {%<nome>_active}} vira
 *       {@code bg-blue-600 text-white} na página atual (e vazio nas outras). Sem
 *       {@code {content}} no template, toda página mostra o próprio template.</li>
 * </ul>
 *
 * <p>Duas páginas com o mesmo nome, ou uma página com o mesmo caminho de uma {@code Route},
 * não derrubam mais a subida: a segunda é ignorada com um aviso no console. O
 * {@code JavalinAPI} usa {@link #isPage(String)} para manter a página pública aberta mesmo para
 * quem está com bloqueio longo.</p>
 *
 * <p>O conteúdo é lido do classpath a cada requisição — sem cache, como o resto do servidor.</p>
 *
 * @author Angatu Sistemas
 * @see AssetsAPI
 */
public final class HtmlRouteAPI {

    /** Nomes de página (minúsculos) que não viram rota. */
    private static final Set<String> IGNORED_PATHS = ConcurrentHashMap.newKeySet();

    /** Caminhos já registrados como página, para {@link #isPage(String)} e contra duplicata. */
    private static final Set<String> PAGE_ROUTES = ConcurrentHashMap.newKeySet();

    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("/+");

    private HtmlRouteAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== CONFIG ====================

    /**
     * Impede que uma página vire rota. Chame <strong>antes</strong> de
     * {@code new AngatuLib(...)}, que é quando as páginas são registradas.
     *
     * @param path Nome da página, sem extensão (ex: {@code "rascunho"} para
     *             {@code public/rascunho.html})
     */
    public static void addIgnoredPath(String path) {
        IGNORED_PATHS.add(path.toLowerCase(Locale.ROOT));
    }

    /**
     * Desfaz {@link #addIgnoredPath(String)}. Só vale para registros feitos depois.
     *
     * @param path Nome da página, sem extensão
     */
    public static void removeIgnoredPath(String path) {
        IGNORED_PATHS.remove(path.toLowerCase(Locale.ROOT));
    }

    /**
     * Substitui a lista inteira de páginas ignoradas.
     *
     * @param paths Nomes de página, sem extensão
     */
    public static void setIgnoredPaths(Collection<String> paths) {
        IGNORED_PATHS.clear();
        IGNORED_PATHS.addAll(paths.stream().map(p -> p.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
    }

    /**
     * O caminho é de uma página registrada por esta classe?
     *
     * @param path Caminho canônico da requisição (ex: {@code "/sobre"})
     * @return {@code true} se for uma página
     */
    public static boolean isPage(String path) {
        return path != null && PAGE_ROUTES.contains(path);
    }

    // ==================== REGISTRO ====================

    /**
     * Registra cada página como rota {@code GET}, montada dentro do template base.
     *
     * @param javalin          Servidor em que as rotas são registradas
     * @param baseTemplatePath Template base (ex: {@code "/index.html"}); {@code null} entrega
     *                         cada página crua, sem template
     * @param pageProvider     Fonte da lista de páginas; {@code null} lista o que está em
     *                         {@code public/}
     */
    public static void registerAllRoutes(Javalin javalin, String baseTemplatePath, PageProvider pageProvider) {

        List<String> htmlFiles = (pageProvider != null)
                ? pageProvider.getPages()
                : getAllHtmlPages();

        if (htmlFiles == null || htmlFiles.isEmpty()) {
            Console.error("Nenhum HTML encontrado em src/main/resources/public.");
            return;
        }

        Console.log("HTMLs encontrados: " + htmlFiles.size());

        // Pré-computa os nomes das páginas uma única vez (evita recomputar por requisição)
        List<String> pageNames = htmlFiles.stream()
                .map(f -> extractPageName(normalizePath(f)))
                .collect(Collectors.toList());

        for (String rawPath : htmlFiles) {

            String filePath = normalizePath(rawPath);
            String pageName = extractPageName(filePath);

            // Ignora template base
            if (baseTemplatePath != null && normalizePath(baseTemplatePath).equals(filePath)) {
                Console.debug("Template ignorado como rota: " + filePath);
                continue;
            }

            if (IGNORED_PATHS.contains(pageName)) {
                Console.debug("Página ignorada: " + pageName);
                continue;
            }

            String routePath = pageName.equals("index") ? "/" : "/" + pageName;
            if (!PAGE_ROUTES.add(routePath)) {
                Console.warn("Página %s ignorada: já existe uma página em %s (só o nome do arquivo conta).",
                        filePath, routePath);
                continue;
            }

            try {
                javalin.unsafe.routes.get(routePath, ctx -> renderPage(ctx, baseTemplatePath, filePath, pageName, pageNames));
            } catch (IllegalArgumentException duplicate) {
                PAGE_ROUTES.remove(routePath);
                Console.warn("Página %s ignorada: já existe uma rota em %s.", filePath, routePath);
                continue;
            }

            Console.log("Rota [%s] registrada (%s)", routePath, filePath);
        }
    }

    /**
     * Registra as páginas com o template padrão {@code /index.html}.
     *
     * @param javalin Servidor em que as rotas são registradas
     */
    public static void registerAllRoutes(Javalin javalin) {
        registerAllRoutes(javalin, "/index.html", null);
    }

    /** Monta a página dentro do template e responde. */
    private static void renderPage(io.javalin.http.Context ctx, String baseTemplatePath, String filePath,
            String pageName, List<String> pageNames) {
        try {
            String pageContent = loadContent(filePath);
            if (pageContent == null) {
                Console.error("Arquivo não encontrado: " + filePath);
                ctx.status(404).result("Página não encontrada.");
                return;
            }

            String baseHtml = baseTemplatePath != null ? loadContent(baseTemplatePath) : null;
            String renderedHtml = pageContent;
            if (baseHtml != null) {
                renderedHtml = baseHtml
                        .replace("{page}", StringAPI.capitalize(pageName))
                        .replace("{content}", pageContent);
                for (String otherName : pageNames) {
                    renderedHtml = renderedHtml.replace(
                            "{%" + otherName + "_active}",
                            pageName.equals(otherName) ? "bg-blue-600 text-white" : "");
                }
            }

            ctx.contentType("text/html; charset=utf-8");
            ctx.result(renderedHtml).status(200);

        } catch (Exception e) {
            Console.error("Erro na página: " + filePath, e);
            JavalinAPI.markRequestError(ctx, e); // a página responde sozinha; o tipo vai para o log da requisição
            ctx.status(500).result("Erro interno.");
        }
    }

    // ==================== CORE ====================

    private static String loadContent(String path) {
        return AssetsAPI.readAssetAsString(normalizePath(path));
    }

    /**
     * Lista as páginas HTML de {@code public/}, fora de {@code emails/} e {@code others/}.
     *
     * @return Caminhos relativos a {@code public/} (ex: {@code "/sobre.html"})
     */
    public static List<String> getAllHtmlPages() {
        return AssetsAPI.listAllAssetsRecursive("/")
                .stream()
                .map(HtmlRouteAPI::normalizePath)
                .filter(file -> file.endsWith(".html"))
                .filter(file -> !file.contains("/emails/") && !file.contains("/others/"))
                .collect(Collectors.toList());
    }

    /**
     * Nome da página: o nome do arquivo, sem extensão, em minúsculas.
     *
     * @param filePath Caminho do arquivo (ex: {@code "/blog/Post.html"})
     * @return Nome da página (ex: {@code "post"}), ou vazio
     */
    public static String extractPageName(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";

        filePath = normalizePath(filePath);

        String fileName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        if (fileName.contains(".")) {
            fileName = fileName.substring(0, fileName.lastIndexOf('.'));
        }

        return fileName.toLowerCase(Locale.ROOT);
    }

    // ==================== NORMALIZAÇÃO ====================

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";

        // Windows -> padrão; barras repetidas viram uma
        path = MULTIPLE_SLASHES.matcher(path.replace("\\", "/")).replaceAll("/");

        // Garante que começa com /
        return path.startsWith("/") ? path : "/" + path;
    }
}
