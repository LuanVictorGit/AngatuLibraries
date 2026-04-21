package br.com.angatusistemas.lib.javalin.html;

import java.util.*;
import java.util.stream.Collectors;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.javalin.IP;
import br.com.angatusistemas.lib.strings.StringAPI;
import io.javalin.Javalin;

public final class HtmlRouteAPI {

    private static final Set<String> IGNORED_PATHS = new HashSet<>();

    private HtmlRouteAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== CONFIG ====================

    public static void addIgnoredPath(String path) {
        IGNORED_PATHS.add(path.toLowerCase());
    }

    public static void removeIgnoredPath(String path) {
        IGNORED_PATHS.remove(path.toLowerCase());
    }

    public static void setIgnoredPaths(Collection<String> paths) {
        IGNORED_PATHS.clear();
        IGNORED_PATHS.addAll(paths.stream().map(String::toLowerCase).collect(Collectors.toSet()));
    }

    // ==================== REGISTRO ====================

    public static void registerAllRoutes(Javalin javalin, String baseTemplatePath, PageProvider pageProvider) {

        List<String> htmlFiles = (pageProvider != null)
                ? pageProvider.getPages()
                : getAllHtmlPages();

        if (htmlFiles == null || htmlFiles.isEmpty()) {
            Console.error("Nenhum HTML encontrado!");
            return;
        }

        Console.log("HTMLs encontrados: " + htmlFiles.size());

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
            String finalFilePath = filePath;

            javalin.unsafe.routes.get(routePath, ctx -> {
                try {
                    Console.debug("Acessando [%s] -> %s", IP.get(ctx), routePath);

                    String baseHtml = null;
                    if (baseTemplatePath != null) {
                        baseHtml = loadContent(baseTemplatePath);
                    }

                    String pageContent = loadContent(finalFilePath);

                    if (pageContent == null) {
                        Console.error("Arquivo não encontrado: " + finalFilePath);
                        ctx.status(404).result("Página não encontrada: " + finalFilePath);
                        return;
                    }

                    String renderedHtml;

                    if (baseHtml != null) {
                        renderedHtml = baseHtml
                                .replace("{page}", StringAPI.capitalize(pageName))
                                .replace("{content}", pageContent);

                        for (String other : htmlFiles) {
                            String otherName = extractPageName(normalizePath(other));
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
                    Console.error("Erro na rota: " + routePath, e);
                    ctx.status(500).result("Erro interno");
                }
            });

            Console.log("Rota [%s] registrada (%s)", routePath, filePath);
        }
    }

    public static void registerAllRoutes(Javalin javalin) {
        registerAllRoutes(javalin, "/index.html", null);
    }

    // ==================== CORE ====================

    private static String loadContent(String path) {
        path = normalizePath(path);
        return AssetsAPI.readAssetAsString(path);
    }

    public static List<String> getAllHtmlPages() {
        return AssetsAPI.listAllAssetsRecursive("/")
                .stream()
                .map(HtmlRouteAPI::normalizePath)
                .filter(file -> file.endsWith(".html"))
                .filter(file -> !file.contains("/emails/") && !file.contains("/others/"))
                .collect(Collectors.toList());
    }

    public static String extractPageName(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";

        filePath = normalizePath(filePath);

        String fileName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        if (fileName.contains(".")) {
            fileName = fileName.substring(0, fileName.lastIndexOf('.'));
        }

        return fileName.toLowerCase();
    }

    // ==================== NORMALIZAÇÃO ====================

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";

        // Windows -> padrão
        path = path.replace("\\", "/");

        // Remove múltiplas barras
        path = path.replaceAll("/+", "/");

        // Garante que começa com /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return path;
    }
}