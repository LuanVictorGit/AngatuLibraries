package br.com.angatusistemas.lib.javalin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import br.com.angatusistemas.lib.console.Console;
import io.javalin.http.Context;

/**
 * Leitura e listagem dos arquivos de {@code src/main/resources/public} (servidos pelo Javalin
 * a partir do classpath).
 *
 * <p>Todo caminho é relativo a {@code public/} e começa por barra: {@code "/css/style.css"}
 * lê {@code public/css/style.css}. Caminho com {@code ..}, {@code .}, barra invertida ou
 * segmento vazio é recusado — com o classpath em pasta (desenvolvimento, JAR expandido), um
 * {@code "/../application.properties"} lia arquivo de fora de {@code public/}.</p>
 *
 * <pre>
 * // Lê o conteúdo de public/css/style.css
 * String css = AssetsAPI.readAssetAsString("/css/style.css");
 *
 * // Lista os .js de public/js (e subpastas)
 * List&lt;String&gt; scripts = AssetsAPI.listAssetsByExtension("/js", "js");
 *
 * // Serve um arquivo direto numa rota
 * AssetsAPI.serveAsset(ctx, "/img/logo.png");
 * </pre>
 *
 * <p><strong>Cache:</strong> desligado por padrão, como o resto do servidor — o conteúdo é lido
 * a cada chamada. {@link #setCacheEnabled(boolean)} liga um cache em memória com prazo; só use
 * quando o projeto pedir cache explicitamente.</p>
 *
 * @author Angatu Sistemas
 */
public final class AssetsAPI {

    private static final String ASSETS_ROOT = "public";
    private static final Map<String, CachedAsset> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean cacheEnabled = false;
    private static volatile long defaultCacheTtlMs = 60_000; // 1 minuto

    /** Tipos por extensão. Com {@code X-Content-Type-Options: nosniff}, tipo errado é script recusado. */
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("eot", "application/vnd.ms-fontobject"));

    /** Um arquivo de JAR aberto como sistema de arquivos por vez: evita a disputa entre listagens. */
    private static final Object JAR_FILESYSTEM_LOCK = new Object();

    private AssetsAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== CONFIGURAÇÃO ====================

    /**
     * Liga ou desliga o cache de conteúdo em memória (desligado por padrão).
     *
     * @param enabled {@code true} para guardar o conteúdo lido por {@link #setDefaultCacheTtl(long)}
     */
    public static void setCacheEnabled(boolean enabled) {
        cacheEnabled = enabled;
        if (!enabled) CACHE.clear();
        Console.debug("Cache de assets " + (enabled ? "habilitado" : "desabilitado"));
    }

    /**
     * Define o prazo padrão do cache, em milissegundos.
     *
     * @param ttlMs Prazo de cada entrada
     */
    public static void setDefaultCacheTtl(long ttlMs) {
        defaultCacheTtlMs = ttlMs;
    }

    /**
     * Esvazia o cache.
     */
    public static void clearCache() {
        CACHE.clear();
        Console.debug("Cache de assets limpo");
    }

    // ==================== MÉTODOS PRINCIPAIS (CAMINHOS RELATIVOS A /public) ====================

    /**
     * Converte um caminho relativo (ex: {@code "/css/style.css"}) no caminho completo do
     * classpath ({@code "public/css/style.css"}).
     *
     * @param relativePath Caminho começando por barra
     * @return Caminho no classpath, ou {@code null} se o caminho sair de {@code public/} ou for
     *         inválido
     */
    private static String toClasspathPath(String relativePath) {
        if (relativePath == null) return null;
        String normalized = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        if (!normalized.isEmpty() && !isSafeRelativePath(normalized)) {
            Console.debug("Caminho de asset recusado: %s", relativePath);
            return null;
        }
        return normalized.isEmpty() ? ASSETS_ROOT : ASSETS_ROOT + "/" + normalized;
    }

    /**
     * Nenhum segmento pode subir de pasta, ser vazio, ser {@code .}, ou usar barra invertida:
     * com o classpath em pasta, qualquer um deles permite ler arquivo de fora de
     * {@code public/}.
     */
    private static boolean isSafeRelativePath(String path) {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /**
     * Lê um asset como texto UTF-8.
     *
     * @param relativePath Caminho relativo (ex: {@code "/css/style.css"})
     * @return Conteúdo, ou {@code null} se não existir
     */
    public static String readAssetAsString(String relativePath) {
        byte[] bytes = readAssetAsBytes(relativePath);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /**
     * Lê um asset como bytes.
     *
     * @param relativePath Caminho relativo (ex: {@code "/img/logo.png"})
     * @return Bytes do arquivo, ou {@code null} se não existir
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
            Console.error("Erro ao ler asset: %s", classpathPath, e);
            return null;
        }
    }

    /**
     * Verifica se um asset existe no classpath.
     *
     * @param relativePath Caminho relativo (ex: {@code "/css/style.css"})
     * @return {@code true} se existir
     */
    public static boolean assetExists(String relativePath) {
        String classpathPath = toClasspathPath(relativePath);
        return classpathPath != null && AssetsAPI.class.getClassLoader().getResource(classpathPath) != null;
    }

    /**
     * Tipo (MIME) do asset pela extensão.
     *
     * @param relativePath Caminho do asset (ex: {@code "/img/logo.png"})
     * @return Tipo (ex: {@code "image/png"}), ou {@code "application/octet-stream"}
     */
    public static String getContentType(String relativePath) {
        String extension = "";
        int lastDot = relativePath.lastIndexOf('.');
        if (lastDot > 0) {
            extension = relativePath.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        }
        return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /**
     * Responde a requisição com o asset, com o tipo certo.
     *
     * <p>Não define {@code Cache-Control}: o padrão da casa é não guardar conteúdo no navegador
     * sem pedido, e quem decide é o projeto (o valor fixo de um dia que existia aqui fazia a
     * versão nova de um arquivo demorar até 24 horas para aparecer). Asset inexistente responde
     * 404 sem repetir o caminho pedido.</p>
     *
     * @param ctx          Contexto da requisição
     * @param relativePath Caminho relativo do asset (ex: {@code "/css/style.css"})
     */
    public static void serveAsset(Context ctx, String relativePath) {
        byte[] data = readAssetAsBytes(relativePath);
        if (data == null) {
            ctx.status(404).result("Arquivo não encontrado.");
            return;
        }
        ctx.contentType(getContentType(relativePath));
        ctx.result(data);
    }

    // ==================== LISTAGEM DE ASSETS (DENTRO DE /public) ====================

    /**
     * Lista os arquivos de uma pasta, sem entrar nas subpastas.
     *
     * <p>Devolve sempre caminhos relativos a {@code public/}, com o classpath em pasta ou em
     * JAR — antes, dentro do JAR, voltava {@code "public/css/x.css"} e ainda incluía as
     * subpastas.</p>
     *
     * @param relativeDir Pasta relativa a {@code public/} (ex: {@code "/css"})
     * @return Caminhos relativos (ex: {@code ["/css/style.css", "/css/main.css"]})
     */
    public static List<String> listAssets(String relativeDir) {
        String classpathDir = toClasspathPath(relativeDir);
        if (classpathDir == null) return Collections.emptyList();
        String prefix = classpathDir + "/";
        return listClasspathResources(classpathDir).stream()
                .filter(path -> path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0)
                .map(AssetsAPI::toRelative)
                .collect(Collectors.toList());
    }

    /**
     * Lista arquivos de uma pasta e das subpastas, filtrando pela extensão.
     *
     * @param relativeDir Pasta relativa (ex: {@code "/js"})
     * @param extension   Extensão sem ponto (ex: {@code "js"})
     * @return Caminhos relativos dos arquivos encontrados
     */
    public static List<String> listAssetsByExtension(String relativeDir, String extension) {
        String classpathDir = toClasspathPath(relativeDir);
        if (classpathDir == null) return Collections.emptyList();

        return listClasspathResources(classpathDir).stream()
                .filter(path -> path.endsWith("." + extension))
                .map(AssetsAPI::toRelative)
                .collect(Collectors.toList());
    }

    /** {@code "public/css/x.css"} → {@code "/css/x.css"}. */
    private static String toRelative(String classpathPath) {
        return classpathPath.startsWith(ASSETS_ROOT + "/")
                ? classpathPath.substring(ASSETS_ROOT.length())
                : "/" + classpathPath;
    }

    /**
     * Lista, com as subpastas, todos os arquivos de uma pasta do classpath.
     *
     * @param classpathFolder Caminho completo no classpath (ex: {@code "public/js"})
     * @return Caminhos relativos ao classpath (ex: {@code "public/js/app.js"})
     */
    public static List<String> listClasspathResources(String classpathFolder) {
        List<String> files = new ArrayList<>();

        try {
            // O ClassLoader do contexto da thread enxerga o classpath da aplicação
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
                    listJarResources(url, classpathFolder, files);
                }
            }

        } catch (Exception e) {
            Console.error("Erro ao listar recursos do classpath: %s", classpathFolder, e);
        }

        return files;
    }

    /**
     * Lista uma pasta de dentro de um JAR ({@code jar:file:/app.jar!/public}).
     *
     * <p>Usa o sistema de arquivos do JAR já aberto, se houver, e fecha só o que abriu aqui.
     * A trava evita que duas listagens simultâneas tentem abrir o mesmo JAR ao mesmo tempo — a
     * segunda recebia {@code FileSystemAlreadyExistsException} e devolvia lista incompleta.</p>
     */
    private static void listJarResources(URL url, String classpathFolder, List<String> files)
            throws IOException, URISyntaxException {
        String raw = url.toString();
        URI jarUri = new URI(raw.substring(0, raw.indexOf('!')));

        synchronized (JAR_FILESYSTEM_LOCK) {
            boolean openedHere = false;
            FileSystem fs;
            try {
                fs = FileSystems.getFileSystem(jarUri);
            } catch (FileSystemNotFoundException notOpen) {
                try {
                    fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap());
                    openedHere = true;
                } catch (FileSystemAlreadyExistsException raced) {
                    fs = FileSystems.getFileSystem(jarUri);
                }
            }

            try {
                Path jarDir = fs.getPath(classpathFolder);
                if (Files.exists(jarDir)) {
                    try (Stream<Path> walk = Files.walk(jarDir)) {
                        walk.filter(Files::isRegularFile)
                            .map(path -> classpathFolder + "/" + jarDir.relativize(path).toString().replace("\\", "/"))
                            .forEach(files::add);
                    }
                }
            } finally {
                if (openedHere) fs.close();
            }
        }
    }

    /**
     * Lista, com as subpastas, todos os arquivos de uma pasta relativa a {@code public/}.
     *
     * @param relativeDir Pasta relativa (ex: {@code "/images"}; {@code "/"} para todas)
     * @return Caminhos relativos dos arquivos
     */
    public static List<String> listAllAssetsRecursive(String relativeDir) {
        String classpathDir = toClasspathPath(relativeDir);
        if (classpathDir == null) return Collections.emptyList();
        return listClasspathResources(classpathDir).stream()
                .map(AssetsAPI::toRelative)
                .collect(Collectors.toList());
    }

    // ==================== UTILIDADES ADICIONAIS ====================

    /**
     * Tamanho do asset em bytes, sem ler o conteúdo quando dá para evitar.
     *
     * @param relativePath Caminho relativo
     * @return Tamanho em bytes, ou {@code -1} se não existir
     */
    public static long getAssetSize(String relativePath) {
        String classpathPath = toClasspathPath(relativePath);
        if (classpathPath == null) return -1;
        try {
            URL url = AssetsAPI.class.getClassLoader().getResource(classpathPath);
            if (url == null) return -1;
            if ("file".equals(url.getProtocol())) {
                return Files.size(Paths.get(url.toURI()));
            }
            long length = url.openConnection().getContentLengthLong();
            return length >= 0 ? length : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Instante da última modificação do asset, quando o classpath é pasta.
     *
     * <p>Dentro de um JAR não há essa informação por arquivo, e o retorno é {@code -1}.</p>
     *
     * @param relativePath Caminho relativo
     * @return Milissegundos (epoch), ou {@code -1}
     */
    public static long getAssetLastModified(String relativePath) {
        String classpathPath = toClasspathPath(relativePath);
        if (classpathPath == null) return -1;
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
     * Coloca um conteúdo no cache à mão (pré-carga). Não faz nada com o cache desligado.
     *
     * @param relativePath Caminho relativo
     * @param data         Conteúdo do asset
     * @param ttlMs        Prazo em milissegundos ({@code 0} = prazo padrão)
     */
    public static void putInCache(String relativePath, byte[] data, long ttlMs) {
        if (!cacheEnabled) return;
        String classpathPath = toClasspathPath(relativePath);
        if (classpathPath == null) return;
        CACHE.put(classpathPath, new CachedAsset(data, ttlMs > 0 ? ttlMs : defaultCacheTtlMs));
    }

    // ==================== CLASSE INTERNA DE CACHE ====================

    private static final class CachedAsset {
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
