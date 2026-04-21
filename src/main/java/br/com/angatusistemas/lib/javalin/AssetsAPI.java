package br.com.angatusistemas.lib.javalin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import br.com.angatusistemas.lib.console.Console;
import io.javalin.http.Context;

/**
 * [PT] Classe utilitária para gerenciamento de assets estáticos em projetos Javalin com Location.CLASSPATH.
 * <p>
 * Assume que os arquivos estão dentro da pasta {@code /public} no classpath.
 * Todos os caminhos relativos devem ser informados a partir da raiz (ex: "/css/style.css").
 * </p>
 * <p>
 * Exemplos:
 * <pre>
 * // Lê o conteúdo de /public/css/style.css
 * String css = AssetManager.readAssetAsString("/css/style.css");
 *
 * // Lista todos os arquivos .js dentro de /public/js
 * List&lt;Path&gt; jsFiles = AssetManager.listAssetsByExtension("/js", "js");
 *
 * // Serve um asset diretamente no Javalin
 * AssetManager.serveAsset(ctx, "/img/logo.png");
 * </pre>
 * </p>
 *
 * [EN] Utility class for managing static assets in Javalin projects with Location.CLASSPATH.
 * <p>
 * Assumes files are inside the {@code /public} folder on the classpath.
 * Relative paths should be given from the root (e.g., "/css/style.css").
 * </p>
 */
public final class AssetsAPI {

    private static final String ASSETS_ROOT = "public";
    private static final Map<String, CachedAsset> CACHE = new ConcurrentHashMap<>();
    private static boolean cacheEnabled = false;
    private static long defaultCacheTtlMs = 60_000; // 1 minute

    // Mapeamento básico de extensões para Content-Type
    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("xml", "application/xml");
        MIME_TYPES.put("pdf", "application/pdf");
        MIME_TYPES.put("zip", "application/zip");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf", "font/ttf");
        MIME_TYPES.put("eot", "application/vnd.ms-fontobject");
    }

    private AssetsAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== CONFIGURAÇÃO ====================

    /**
     * [PT] Habilita/desabilita o cache de conteúdo (desabilitado por padrão).
     * [EN] Enables/disables content caching (disabled by default).
     */
    public static void setCacheEnabled(boolean enabled) {
        cacheEnabled = enabled;
        if (!enabled) CACHE.clear();
        Console.debug("Cache de assets " + (enabled ? "habilitado" : "desabilitado"));
    }

    /**
     * [PT] Define o TTL (time-to-live) padrão do cache em milissegundos.
     * [EN] Sets the default cache TTL (time-to-live) in milliseconds.
     */
    public static void setDefaultCacheTtl(long ttlMs) {
        defaultCacheTtlMs = ttlMs;
    }

    /**
     * [PT] Limpa o cache manualmente.
     * [EN] Clears the cache manually.
     */
    public static void clearCache() {
        CACHE.clear();
        Console.debug("Cache de assets limpo");
    }

    // ==================== MÉTODOS PRINCIPAIS (CAMINHOS RELATIVOS A /public) ====================

    /**
     * [PT] Converte um caminho relativo (ex: "/css/style.css") para o caminho completo no classpath.
     *
     * [EN] Converts a relative path (e.g., "/css/style.css") to the full classpath path.
     *
     * @param relativePath [PT] caminho começando com "/" (ex: "/img/logo.png")
     *                     [EN] path starting with "/" (e.g., "/img/logo.png")
     * @return [PT] caminho completo dentro de "public" (ex: "public/css/style.css")
     *         [EN] full path inside "public" (e.g., "public/css/style.css")
     */
    private static String toClasspathPath(String relativePath) {
        if (relativePath == null) return null;
        // Remove leading slash if present
        String normalized = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return ASSETS_ROOT + "/" + normalized;
    }

    /**
     * [PT] Lê um asset como String (UTF-8).
     *
     * [EN] Reads an asset as String (UTF-8).
     *
     * @param relativePath [PT] caminho relativo (ex: "/css/style.css")
     *                     [EN] relative path (e.g., "/css/style.css")
     * @return [PT] conteúdo do asset ou null se não encontrado
     *         [EN] asset content or null if not found
     */
    public static String readAssetAsString(String relativePath) {
        byte[] bytes = readAssetAsBytes(relativePath);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /**
     * [PT] Lê um asset como array de bytes.
     *
     * [EN] Reads an asset as byte array.
     *
     * @param relativePath [PT] caminho relativo (ex: "/img/logo.png")
     *                     [EN] relative path (e.g., "/img/logo.png")
     * @return [PT] bytes do asset ou null se não encontrado
     *         [EN] asset bytes or null if not found
     */
    public static byte[] readAssetAsBytes(String relativePath) {
        String classpathPath = toClasspathPath(relativePath);
        if (classpathPath == null) return null;

        if (cacheEnabled) {
            CachedAsset cached = CACHE.get(classpathPath);
            if (cached != null && !cached.isExpired()) {
                return cached.bytes;
            }
            byte[] bytes = readFromClasspath(classpathPath);
            if (bytes != null) {
                CACHE.put(classpathPath, new CachedAsset(bytes, defaultCacheTtlMs));
            }
            return bytes;
        }
        return readFromClasspath(classpathPath);
    }

    private static byte[] readFromClasspath(String classpathPath) {
        try (InputStream is = AssetsAPI.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (is == null) {
                Console.debug("Asset não encontrado: %s", classpathPath);
                return null;
            }
            return is.readAllBytes();
        } catch (IOException e) {
            Console.error("Erro ao ler asset: %s", e, classpathPath);
            return null;
        }
    }

    /**
     * [PT] Verifica se um asset existe no classpath.
     *
     * [EN] Checks if an asset exists in the classpath.
     *
     * @param relativePath [PT] caminho relativo (ex: "/css/style.css")
     *                     [EN] relative path (e.g., "/css/style.css")
     * @return [PT] true se existir
     *         [EN] true if exists
     */
    public static boolean assetExists(String relativePath) {
        String classpathPath = toClasspathPath(relativePath);
        return AssetsAPI.class.getClassLoader().getResource(classpathPath) != null;
    }

    /**
     * [PT] Obtém o Content-Type (MIME) apropriado para a extensão do asset.
     *
     * [EN] Gets the appropriate Content-Type (MIME) for the asset's extension.
     *
     * @param relativePath [PT] caminho do asset (ex: "/img/logo.png")
     *                     [EN] asset path (e.g., "/img/logo.png")
     * @return [PT] string do Content-Type (ex: "image/png") ou "application/octet-stream"
     *         [EN] Content-Type string (e.g., "image/png") or "application/octet-stream"
     */
    public static String getContentType(String relativePath) {
        String extension = "";
        int lastDot = relativePath.lastIndexOf('.');
        if (lastDot > 0) {
            extension = relativePath.substring(lastDot + 1).toLowerCase();
        }
        return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /**
     * [PT] Serve um asset diretamente para o contexto Javalin (com cache headers básicos).
     *
     * [EN] Serves an asset directly to the Javalin context (with basic cache headers).
     *
     * @param ctx          [PT] contexto da requisição Javalin
     *                     [EN] Javalin request context
     * @param relativePath [PT] caminho relativo do asset (ex: "/css/style.css")
     *                     [EN] relative asset path (e.g., "/css/style.css")
     */
    public static void serveAsset(Context ctx, String relativePath) {
        byte[] data = readAssetAsBytes(relativePath);
        if (data == null) {
            ctx.status(404).result("Asset not found: " + relativePath);
            return;
        }
        ctx.contentType(getContentType(relativePath));
        ctx.header("Cache-Control", "public, max-age=86400"); // 1 dia
        ctx.result(data);
    }

    // ==================== LISTAGEM DE ASSETS (DENTRO DE /public) ====================

    /**
     * [PT] Lista todos os assets dentro de um diretório relativo (não recursivo).
     *
     * [EN] Lists all assets inside a relative directory (non-recursive).
     *
     * @param relativeDir [PT] diretório relativo a /public (ex: "/css")
     *                    [EN] directory relative to /public (e.g., "/css")
     * @return [PT] lista de caminhos relativos (ex: ["/css/style.css", "/css/main.css"])
     *         [EN] list of relative paths (e.g., ["/css/style.css", "/css/main.css"])
     */
    public static List<String> listAssets(String relativeDir) {
        String classpathDir = toClasspathPath(relativeDir);
        if (classpathDir == null) return Collections.emptyList();

        try {
            URL dirUrl = AssetsAPI.class.getClassLoader().getResource(classpathDir);
            if (dirUrl == null) return Collections.emptyList();

            List<String> assets = new ArrayList<>();
            if (dirUrl.getProtocol().equals("file")) {
                Path dir = Paths.get(dirUrl.toURI());
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(Files::isRegularFile)
                          .map(path -> "/" + classpathDir.replace(ASSETS_ROOT + "/", "") + "/" + path.getFileName().toString())
                          .forEach(assets::add);
                }
            } else if (dirUrl.getProtocol().equals("jar")) {
                // Para JARs, é mais complexo; alternativa: usar listClasspathResources
                assets.addAll(listClasspathResources(classpathDir));
            }
            return assets;
        } catch (IOException | URISyntaxException e) {
            Console.error("Erro ao listar assets: %s", e, relativeDir);
            return Collections.emptyList();
        }
    }

    /**
     * [PT] Lista assets recursivamente filtrando por extensão.
     *
     * [EN] Lists assets recursively filtering by extension.
     *
     * @param relativeDir [PT] diretório relativo (ex: "/js")
     *                    [EN] relative directory (e.g., "/js")
     * @param extension   [PT] extensão sem ponto (ex: "js", "css")
     *                    [EN] extension without dot (e.g., "js", "css")
     * @return [PT] lista de caminhos relativos dos assets encontrados
     *         [EN] list of relative paths of found assets
     */
    public static List<String> listAssetsByExtension(String relativeDir, String extension) {
        String classpathDir = toClasspathPath(relativeDir);
        if (classpathDir == null) return Collections.emptyList();

        return listClasspathResources(classpathDir).stream()
                .filter(path -> path.endsWith("." + extension))
                .map(path -> "/" + path.replace(ASSETS_ROOT + "/", ""))
                .collect(Collectors.toList());
    }

    /**
     * [PT] Lista recursivamente todos os recursos (arquivos) dentro de uma pasta do classpath.
     *
     * [EN] Recursively lists all resources (files) inside a classpath folder.
     *
     * @param classpathFolder [PT] caminho completo dentro do classpath (ex: "public/js")
     *                        [EN] full path inside classpath (e.g., "public/js")
     * @return [PT] lista de caminhos relativos ao classpath
     *         [EN] list of paths relative to classpath
     */
    public static List<String> listClasspathResources(String classpathFolder) {
        List<String> files = new ArrayList<>();

        try {
            // 🔥 Usa o ClassLoader correto (do contexto da thread)
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            Enumeration<URL> resources = classLoader.getResources(classpathFolder);

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();

                if ("file".equals(url.getProtocol())) {
                    Path dir = Paths.get(url.toURI());

                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(Files::isRegularFile)
                            .map(path -> classpathFolder + "/" + dir.relativize(path).toString().replace("\\", "/"))
                            .forEach(files::add);
                    }

                } else if ("jar".equals(url.getProtocol())) {

                    String raw = url.toString(); 
                    // exemplo: jar:file:/app.jar!/public

                    String jarPath = raw.substring(0, raw.indexOf("!"));
                    URI jarUri = URI.create(jarPath);

                    FileSystem fs;

                    try {
                        fs = FileSystems.getFileSystem(jarUri);
                    } catch (Exception e) {
                        fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap());
                    }

                    Path jarDir = fs.getPath(classpathFolder);

                    if (Files.exists(jarDir)) {
                        try (Stream<Path> walk = Files.walk(jarDir)) {
                            walk.filter(Files::isRegularFile)
                                .map(path -> classpathFolder + "/" + jarDir.relativize(path).toString().replace("\\", "/"))
                                .forEach(files::add);
                        }
                    }
                }
            }

        } catch (Exception e) {
            Console.error("Erro ao listar recursos do classpath: %s", e, classpathFolder);
        }

        return files;
    }

    /**
     * [PT] Lista todos os assets de um diretório recursivamente (todas as extensões).
     *
     * [EN] Lists all assets in a directory recursively (all extensions).
     *
     * @param relativeDir [PT] diretório relativo (ex: "/images")
     *                    [EN] relative directory (e.g., "/images")
     * @return [PT] lista de caminhos relativos completos
     *         [EN] list of full relative paths
     */
    public static List<String> listAllAssetsRecursive(String relativeDir) {
        String classpathDir = toClasspathPath(relativeDir);
        if (classpathDir == null) return Collections.emptyList();
        return listClasspathResources(classpathDir).stream()
                .map(path -> "/" + path.replace(ASSETS_ROOT + "/", ""))
                .collect(Collectors.toList());
    }

    // ==================== UTILIDADES ADICIONAIS ====================

    /**
     * [PT] Obtém o tamanho de um asset em bytes.
     *
     * [EN] Gets the size of an asset in bytes.
     *
     * @param relativePath [PT] caminho relativo
     *                     [EN] relative path
     * @return [PT] tamanho em bytes ou -1 se não existir
     *         [EN] size in bytes or -1 if not exists
     */
    public static long getAssetSize(String relativePath) {
        byte[] data = readAssetAsBytes(relativePath);
        return data != null ? data.length : -1;
    }

    /**
     * [PT] Retorna o timestamp da última modificação do asset (se disponível).
     * <p>
     * No classpath, pode não estar disponível. Retorna -1 se não for possível determinar.
     * </p>
     *
     * [EN] Returns the last modified timestamp of the asset (if available).
     * <p>
     * In classpath, it may not be available. Returns -1 if cannot be determined.
     * </p>
     *
     * @param relativePath [PT] caminho relativo
     *                     [EN] relative path
     * @return [PT] timestamp em milissegundos ou -1
     *         [EN] timestamp in milliseconds or -1
     */
    public static long getAssetLastModified(String relativePath) {
        String classpathPath = toClasspathPath(relativePath);
        URL url = AssetsAPI.class.getClassLoader().getResource(classpathPath);
        if (url == null) return -1;
        if (url.getProtocol().equals("file")) {
            try {
                return Files.getLastModifiedTime(Paths.get(url.toURI())).toMillis();
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * [PT] Registra manualmente um asset no cache (útil para pré-carregamento).
     *
     * [EN] Manually registers an asset in the cache (useful for preloading).
     *
     * @param relativePath [PT] caminho relativo
     *                     [EN] relative path
     * @param data         [PT] conteúdo do asset
     *                     [EN] asset content
     * @param ttlMs        [PT] TTL em milissegundos (0 = TTL padrão)
     *                     [EN] TTL in milliseconds (0 = default TTL)
     */
    public static void putInCache(String relativePath, byte[] data, long ttlMs) {
        if (!cacheEnabled) return;
        String classpathPath = toClasspathPath(relativePath);
        CACHE.put(classpathPath, new CachedAsset(data, ttlMs > 0 ? ttlMs : defaultCacheTtlMs));
    }

    // ==================== CLASSE INTERNA DE CACHE ====================

    private static class CachedAsset {
        final byte[] bytes;
        final long expiry;

        CachedAsset(byte[] bytes, long ttlMs) {
            this.bytes = bytes;
            this.expiry = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }
}