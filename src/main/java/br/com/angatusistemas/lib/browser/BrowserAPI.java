package br.com.angatusistemas.lib.browser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Classe utilitária para automação de navegador (Playwright) e manipulação de HTML.
 *
 * <p>Fornece captura de tela (via URL ou HTML bruto) com configurações padrão de
 * alta qualidade (viewport 1920x1080, aguarda o carregamento completo, captura a
 * página inteira), web scraping, execução de JavaScript e utilitários de HTML
 * (minificação, extração de links, imagens, metatags etc.).</p>
 *
 * <p><strong>Dependência:</strong> o módulo de navegação requer
 * {@code com.microsoft.playwright:playwright:1.58.0} no classpath (os
 * utilitários puros de HTML funcionam sem ela). Se ausente, a primeira chamada
 * exibe instruções de instalação e lança
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}.</p>
 *
 * <p><strong>Pool de navegadores:</strong> no máximo 2 instâncias headless de
 * Chromium ficam vivas ao mesmo tempo, cada uma com o seu processo do driver
 * Playwright. Elas são iniciadas sob demanda (ou de uma vez, por
 * {@link #initPool()}) e reaproveitadas entre as chamadas; um navegador que cai é
 * substituído por outro, um por um. Com os 2 ocupados, a chamada seguinte espera até
 * 30 s por um livre e, se nenhum vagar, lança {@link IOException} avisando que a
 * BrowserAPI está ocupada — em vez de abrir navegadores extras sem limite. Chame
 * {@link #shutdown()} ao final da aplicação para liberar os recursos.</p>
 *
 * <p><strong>Segurança de destino:</strong> por padrão, a BrowserAPI só navega em
 * {@code http}/{@code https} e recusa destinos que não sejam da internet pública —
 * a própria máquina, redes privadas, metadados de nuvem ({@code 169.254.169.254})
 * etc. —, depois de resolver o DNS. A regra vale para a URL pedida, para cada
 * redirecionamento e para tudo o que a página carrega, inclusive a partir do HTML
 * passado aos métodos {@code *FromHtml}/{@code *OnHtml}. Para capturar páginas
 * confiáveis da própria aplicação (por exemplo, em {@code localhost}), veja
 * {@link #setAllowPrivateNetworkAccess(boolean)}.</p>
 *
 * <p>Exemplo de uso:</p>
 * <pre>
 * // Screenshot da página inteira a partir de HTML
 * BufferedImage img = BrowserAPI.captureFullPageScreenshotFromHtml(html);
 *
 * // Screenshot a partir de URL
 * BufferedImage img2 = BrowserAPI.captureFullPageScreenshot("https://example.com");
 *
 * // Scraping: extrai o texto do título de uma página
 * String title = BrowserAPI.extractText("https://example.com", "h1");
 * </pre>
 *
 * @author Angatu Sistemas
 * @see <a href="https://playwright.dev/java/">Playwright Java</a>
 */
public final class BrowserAPI {

    // ==================== CONFIGURAÇÕES PADRÃO ====================

    /** Classe usada para detectar o Playwright no classpath. */
    private static final String PLAYWRIGHT_CLASS = "com.microsoft.playwright.Playwright";
    /** Coordenadas Maven da dependência Playwright. */
    private static final String PLAYWRIGHT_COORDINATES = "com.microsoft.playwright:playwright:1.58.0";
    /** Nome da funcionalidade nas mensagens de dependência ausente. */
    private static final String PLAYWRIGHT_FEATURE = "Automação de navegador (Playwright)";

    /** Navegadores no pool: nunca há mais do que isso vivos ao mesmo tempo. */
    private static final int BROWSER_POOL_SIZE = 2;
    /** Quanto uma chamada espera por um navegador livre antes de desistir com "ocupada". */
    private static final long BORROW_TIMEOUT_MS = 30_000;
    /** Quanto o {@link #shutdown()} espera as chamadas em andamento devolverem seus navegadores. */
    private static final long SHUTDOWN_WAIT_MS = 10_000;
    /** Depois de uma falha ao iniciar o navegador, novas tentativas esperam este intervalo. */
    private static final long LAUNCH_RETRY_DELAY_MS = 10_000;

    private static final int PAGE_TIMEOUT_MS = 30_000;
    private static final int SCREENSHOT_TIMEOUT_MS = 30_000;
    private static final int SELECTOR_TIMEOUT_MS = 10_000;
    private static final int FIELD_SELECTOR_TIMEOUT_MS = 5_000;
    private static final int NETWORK_IDLE_TIMEOUT_MS = 15_000;
    private static final int IMAGES_TIMEOUT_MS = 10_000;
    private static final int FONTS_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_VIEWPORT_WIDTH = 1920;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 1080;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Configurações padrão de espera (máxima qualidade)
    private static final boolean DEFAULT_WAIT_NETWORK_IDLE = true;
    private static final boolean DEFAULT_WAIT_IMAGES = true;
    private static final boolean DEFAULT_FULL_PAGE = true;

    // ==================== PADRÕES PRÉ-COMPILADOS (HTML UTILS) ====================

    private static final Pattern TAG_BOUNDARY_PATTERN = Pattern.compile(">\\s+<");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern TAG_OPEN_PATTERN = Pattern.compile("><");
    // "<a\s[^>]*" casa o mesmo que "<a\s+[^>]*" (o [^>]* também aceita espaço), sem que os dois
    // quantificadores disputem cada espaço em branco na volta atrás
    private static final Pattern LINK_PATTERN = Pattern.compile("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_PATTERN = Pattern.compile("<img\\s[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_PATTERN = Pattern.compile("<meta\\s[^>]*name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_PATTERN = Pattern.compile("class\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_PATTERN = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    // "/(?!/)": "//cdn..." é URL relativa ao protocolo e já aponta para outro host; não é caminho do site
    private static final Pattern URL_ABS_PATTERN = Pattern.compile("(src|href)=\"/(?!/)([^\"]+)\"");
    private static final Pattern CLASS_SPLIT_PATTERN = Pattern.compile("\\s+");

    // ==================== SEGURANÇA DE DESTINO ====================

    /**
     * Liberação explícita de destinos de rede interna. Volátil porque é ligada por uma thread
     * (na inicialização da aplicação) e lida pelas threads que navegam e pelo proxy de saída.
     */
    private static volatile boolean allowPrivateNetworkAccess = false;

    /** O DNS de verdade; os testes trocam por um resolvedor falso, sem rede. */
    private static final HostResolver SYSTEM_RESOLVER = InetAddress::getAllByName;

    private BrowserAPI() {
        throw new UnsupportedOperationException("Classe utilitária: não deve ser instanciada.");
    }

    // ==================== INICIALIZAÇÃO DO POOL ====================

    /**
     * Pré-aquece o pool: inicia agora os navegadores headless que ainda não estão abertos
     * (até 2), para que a primeira chamada não pague esse custo.
     *
     * <p>Opcional: sem ela, cada navegador é iniciado sob demanda, na primeira chamada que
     * precisar dele. Vagas que estiverem em uso por outras threads no momento são puladas.</p>
     *
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Playwright não estiver no classpath
     * @throws UncheckedIOException se o navegador não puder ser iniciado (por exemplo,
     *         Chromium não instalado); nada fica aberto pela metade
     */
    public static void initPool() {
        requirePlaywright();
        try {
            Engine.warmUp();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ==================== MÉTODOS PRINCIPAIS - FULL PAGE SCREENSHOT ====================

    /**
     * Captura screenshot da página inteira a partir de uma URL (alta qualidade, PNG).
     *
     * @param url URL da página ({@code http} ou {@code https})
     * @return Imagem capturada
     * @throws IOException se a URL for recusada (esquema ou destino não permitido), se a
     *         BrowserAPI estiver ocupada ou se ocorrer erro de navegação ou captura
     */
    public static BufferedImage captureFullPageScreenshot(String url) throws IOException {
        return captureFullPageScreenshotFromUrl(url, new ScreenshotOptions());
    }

    /**
     * Captura screenshot da página inteira a partir de uma string HTML (alta qualidade, PNG).
     *
     * <p>Os recursos que o HTML referencia (imagens, fontes, scripts) passam pelas mesmas
     * regras de destino das URLs.</p>
     *
     * @param html Código HTML
     * @return Imagem capturada
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de
     *         renderização ou captura
     */
    public static BufferedImage captureFullPageScreenshotFromHtml(String html) throws IOException {
        return captureFullPageScreenshotFromHtml(html, new ScreenshotOptions());
    }

    /**
     * Captura screenshot da página inteira a partir de uma URL e salva em arquivo PNG.
     *
     * @param url        URL da página ({@code http} ou {@code https})
     * @param outputPath Caminho do arquivo de saída (ex: {@code "foto.png"})
     * @throws IOException se a URL for recusada ou se ocorrer erro de captura ou escrita
     */
    public static void captureFullPageScreenshotToFile(String url, String outputPath) throws IOException {
        BufferedImage img = captureFullPageScreenshot(url);
        ImageIO.write(img, "png", new File(outputPath));
    }

    /**
     * Captura screenshot da página inteira a partir de um HTML e salva em arquivo PNG.
     *
     * @param html       Código HTML
     * @param outputPath Caminho do arquivo de saída (ex: {@code "foto.png"})
     * @throws IOException se ocorrer erro de renderização ou escrita
     */
    public static void captureFullPageScreenshotFromHtmlToFile(String html, String outputPath) throws IOException {
        BufferedImage img = captureFullPageScreenshotFromHtml(html);
        ImageIO.write(img, "png", new File(outputPath));
    }

    // ==================== MÉTODOS DE CAPTURA COM OPÇÕES ====================

    /**
     * Captura screenshot a partir de uma URL com opções personalizadas.
     *
     * <p>Todas as opções são aplicadas: viewport, user-agent, headers extras, bloqueio de
     * imagens/CSS/fontes, esperas de carregamento, página inteira ou só a área visível
     * ({@link ScreenshotOptions#fullPage}), recorte ({@code clip*}) e qualidade — com
     * {@link ScreenshotOptions#quality} definida, a captura é feita em JPEG; sem ela, em PNG.</p>
     *
     * @param url     URL da página ({@code http} ou {@code https})
     * @param options Opções de captura; {@code null} usa as opções padrão
     * @return Imagem capturada
     * @throws IllegalArgumentException se {@code quality} estiver fora de 0–100 ou se o
     *         recorte estiver incompleto ou inválido
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou captura
     */
    public static BufferedImage captureFullPageScreenshotFromUrl(String url, ScreenshotOptions options) throws IOException {
        requirePlaywright();
        ScreenshotOptions settings = options != null ? options : new ScreenshotOptions();
        CapturePlan plan = capturePlan(settings);
        checkNavigationUrl(url);
        return Engine.screenshot(PageSource.ofUrl(url), settings, plan, "Falha ao capturar screenshot");
    }

    /**
     * Captura screenshot a partir de um HTML com opções personalizadas.
     *
     * <p>Aplica as mesmas opções de {@link #captureFullPageScreenshotFromUrl(String, ScreenshotOptions)}.</p>
     *
     * @param html    Código HTML
     * @param options Opções de captura; {@code null} usa as opções padrão
     * @return Imagem capturada
     * @throws IllegalArgumentException se {@code quality} estiver fora de 0–100 ou se o
     *         recorte estiver incompleto ou inválido
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de
     *         renderização ou captura
     */
    public static BufferedImage captureFullPageScreenshotFromHtml(String html, ScreenshotOptions options) throws IOException {
        requirePlaywright();
        ScreenshotOptions settings = options != null ? options : new ScreenshotOptions();
        CapturePlan plan = capturePlan(settings);
        return Engine.screenshot(PageSource.ofHtml(html), settings, plan, "Falha ao capturar screenshot a partir de HTML");
    }

    // ==================== MÉTODOS SIMPLIFICADOS (COMPATIBILIDADE) ====================

    /**
     * Captura a página inteira de uma URL — nome antigo de {@link #captureFullPageScreenshot(String)}.
     *
     * @param url URL da página ({@code http} ou {@code https})
     * @return Imagem capturada
     * @throws IOException nas mesmas situações de {@link #captureFullPageScreenshot(String)}
     * @deprecated Use {@link #captureFullPageScreenshot(String)}, que faz exatamente o mesmo.
     */
    @Deprecated
    public static BufferedImage captureScreenshot(String url) throws IOException {
        return captureFullPageScreenshot(url);
    }

    /**
     * Captura a página inteira de um HTML — nome antigo de {@link #captureFullPageScreenshotFromHtml(String)}.
     *
     * @param html Código HTML
     * @return Imagem capturada
     * @throws IOException nas mesmas situações de {@link #captureFullPageScreenshotFromHtml(String)}
     * @deprecated Use {@link #captureFullPageScreenshotFromHtml(String)}, que faz exatamente o mesmo.
     */
    @Deprecated
    public static BufferedImage captureScreenshotFromHtml(String html) throws IOException {
        return captureFullPageScreenshotFromHtml(html);
    }

    /**
     * Captura a página inteira de uma URL e salva em PNG — nome antigo de
     * {@link #captureFullPageScreenshotToFile(String, String)}.
     *
     * @param url        URL da página ({@code http} ou {@code https})
     * @param outputPath Caminho do arquivo de saída
     * @throws IOException nas mesmas situações de {@link #captureFullPageScreenshotToFile(String, String)}
     * @deprecated Use {@link #captureFullPageScreenshotToFile(String, String)}, que faz exatamente o mesmo.
     */
    @Deprecated
    public static void captureScreenshotToFile(String url, String outputPath) throws IOException {
        captureFullPageScreenshotToFile(url, outputPath);
    }

    /**
     * Captura a página inteira de um HTML e salva em PNG — nome antigo de
     * {@link #captureFullPageScreenshotFromHtmlToFile(String, String)}.
     *
     * @param html       Código HTML
     * @param outputPath Caminho do arquivo de saída
     * @throws IOException nas mesmas situações de {@link #captureFullPageScreenshotFromHtmlToFile(String, String)}
     * @deprecated Use {@link #captureFullPageScreenshotFromHtmlToFile(String, String)}, que faz exatamente o mesmo.
     */
    @Deprecated
    public static void captureScreenshotFromHtmlToFile(String html, String outputPath) throws IOException {
        captureFullPageScreenshotFromHtmlToFile(html, outputPath);
    }

    // ==================== WEB SCRAPING ====================

    /**
     * Obtém o HTML completo de uma página após o carregamento.
     *
     * @param url URL da página ({@code http} ou {@code https})
     * @return HTML renderizado
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação
     */
    public static String getPageHtml(String url) throws IOException {
        return getPageHtml(url, new ScrapeOptions());
    }

    /**
     * Obtém o HTML completo renderizado a partir de uma string HTML.
     *
     * @param html Código HTML de origem
     * @return HTML renderizado
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de renderização
     */
    public static String getPageHtmlFromHtml(String html) throws IOException {
        return getPageHtmlFromHtml(html, new ScrapeOptions());
    }

    /**
     * Extrai o texto de um seletor CSS de uma página.
     *
     * @param url      URL da página ({@code http} ou {@code https})
     * @param selector Seletor CSS (ex: {@code "h1"}, {@code ".titulo"})
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou extração
     */
    public static String extractText(String url, String selector) throws IOException {
        return extractText(url, selector, new ScrapeOptions());
    }

    /**
     * Extrai o texto de um seletor CSS a partir de uma string HTML.
     *
     * @param html     Código HTML de origem
     * @param selector Seletor CSS (ex: {@code "h1"}, {@code ".titulo"})
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de extração
     */
    public static String extractTextFromHtml(String html, String selector) throws IOException {
        return extractTextFromHtml(html, selector, new ScrapeOptions());
    }

    /**
     * Extrai os dados de uma tabela HTML (linhas × células).
     *
     * @param url           URL da página ({@code http} ou {@code https})
     * @param tableSelector Seletor CSS da tabela (ex: {@code "#tabela"})
     * @return Lista de linhas, cada uma com a lista de células
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou extração
     */
    public static List<List<String>> extractTableData(String url, String tableSelector) throws IOException {
        return extractTableData(url, tableSelector, new ScrapeOptions());
    }

    /**
     * Extrai múltiplos campos de uma página usando um mapa de rótulo → seletor.
     *
     * @param url       URL da página ({@code http} ou {@code https})
     * @param selectors Mapa {@code rótulo → seletor CSS}
     * @return Mapa {@code rótulo → texto extraído} (ou {@code null} se não encontrado)
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação
     */
    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors) throws IOException {
        return extractMultiple(url, selectors, new ScrapeOptions());
    }

    /**
     * Executa JavaScript em uma página e retorna o resultado.
     *
     * @param url    URL da página ({@code http} ou {@code https})
     * @param script Código JavaScript (ex: {@code "document.title"})
     * @return Resultado da expressão
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou execução
     */
    public static Object evaluateJavaScript(String url, String script) throws IOException {
        return evaluateJavaScript(url, script, new ScrapeOptions());
    }

    /**
     * Executa JavaScript sobre um HTML sem navegação.
     *
     * @param html   Código HTML de origem
     * @param script Código JavaScript
     * @return Resultado da expressão
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de execução
     */
    public static Object evaluateJavaScriptOnHtml(String html, String script) throws IOException {
        return evaluateJavaScriptOnHtml(html, script, new ScrapeOptions());
    }

    // ==================== MÉTODOS AVANÇADOS DE SCRAPING ====================

    /**
     * Obtém o HTML completo de uma página com opções de scraping.
     *
     * @param url     URL da página ({@code http} ou {@code https})
     * @param options Opções de navegação (viewport, bloqueios, esperas, headers); {@code null}
     *                usa as opções padrão
     * @return HTML renderizado
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação
     */
    public static String getPageHtml(String url, ScrapeOptions options) throws IOException {
        requirePlaywright();
        checkNavigationUrl(url);
        return Engine.content(PageSource.ofUrl(url), scrapeOptions(options), "Falha ao obter HTML");
    }

    /**
     * Obtém o HTML renderizado a partir de uma string HTML com opções de scraping.
     *
     * @param html    Código HTML de origem
     * @param options Opções de navegação; {@code null} usa as opções padrão
     * @return HTML renderizado
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de renderização
     */
    public static String getPageHtmlFromHtml(String html, ScrapeOptions options) throws IOException {
        requirePlaywright();
        return Engine.content(PageSource.ofHtml(html), scrapeOptions(options), "Falha ao processar HTML");
    }

    /**
     * Extrai o texto de um seletor CSS com opções de scraping.
     *
     * @param url      URL da página ({@code http} ou {@code https})
     * @param selector Seletor CSS
     * @param options  Opções de navegação; {@code null} usa as opções padrão
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou extração
     */
    public static String extractText(String url, String selector, ScrapeOptions options) throws IOException {
        requirePlaywright();
        checkNavigationUrl(url);
        return Engine.text(PageSource.ofUrl(url), selector, scrapeOptions(options), "Falha ao extrair texto");
    }

    /**
     * Extrai o texto de um seletor CSS a partir de HTML com opções de scraping.
     *
     * @param html     Código HTML de origem
     * @param selector Seletor CSS
     * @param options  Opções de navegação; {@code null} usa as opções padrão
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de extração
     */
    public static String extractTextFromHtml(String html, String selector, ScrapeOptions options) throws IOException {
        requirePlaywright();
        return Engine.text(PageSource.ofHtml(html), selector, scrapeOptions(options), "Falha ao extrair texto do HTML");
    }

    /**
     * Extrai os dados de uma tabela HTML com opções de scraping.
     *
     * @param url           URL da página ({@code http} ou {@code https})
     * @param tableSelector Seletor CSS da tabela
     * @param options       Opções de navegação; {@code null} usa as opções padrão
     * @return Lista de linhas, cada uma com a lista de células
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou extração
     */
    public static List<List<String>> extractTableData(String url, String tableSelector, ScrapeOptions options) throws IOException {
        requirePlaywright();
        checkNavigationUrl(url);
        return Engine.table(PageSource.ofUrl(url), tableSelector, scrapeOptions(options), "Falha ao extrair dados da tabela");
    }

    /**
     * Extrai múltiplos campos com opções de scraping.
     *
     * @param url       URL da página ({@code http} ou {@code https})
     * @param selectors Mapa {@code rótulo → seletor CSS}
     * @param options   Opções de navegação; {@code null} usa as opções padrão
     * @return Mapa {@code rótulo → texto extraído} (ou {@code null} se não encontrado)
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação
     */
    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors, ScrapeOptions options) throws IOException {
        requirePlaywright();
        checkNavigationUrl(url);
        return Engine.fields(PageSource.ofUrl(url), selectors, scrapeOptions(options), "Falha ao extrair múltiplos campos");
    }

    /**
     * Executa JavaScript em uma página com opções de scraping.
     *
     * @param url     URL da página ({@code http} ou {@code https})
     * @param script  Código JavaScript
     * @param options Opções de navegação; {@code null} usa as opções padrão
     * @return Resultado da expressão
     * @throws IOException se a URL for recusada, se a BrowserAPI estiver ocupada ou se
     *         ocorrer erro de navegação ou execução
     */
    public static Object evaluateJavaScript(String url, String script, ScrapeOptions options) throws IOException {
        requirePlaywright();
        checkNavigationUrl(url);
        return Engine.evaluate(PageSource.ofUrl(url), script, scrapeOptions(options), "Falha ao executar JS");
    }

    /**
     * Executa JavaScript sobre um HTML com opções de scraping.
     *
     * @param html    Código HTML de origem
     * @param script  Código JavaScript
     * @param options Opções de navegação; {@code null} usa as opções padrão
     * @return Resultado da expressão
     * @throws IOException se a BrowserAPI estiver ocupada ou se ocorrer erro de execução
     */
    public static Object evaluateJavaScriptOnHtml(String html, String script, ScrapeOptions options) throws IOException {
        requirePlaywright();
        return Engine.evaluate(PageSource.ofHtml(html), script, scrapeOptions(options), "Falha ao executar JS no HTML");
    }

    // ==================== REDE INTERNA (LIBERAÇÃO EXPLÍCITA) ====================

    /**
     * Libera (ou volta a bloquear) o acesso a destinos de rede interna.
     *
     * <p>Por padrão ({@code false}), a BrowserAPI só navega em {@code http}/{@code https} e
     * recusa qualquer destino que não seja da internet pública: {@code localhost} e a
     * própria máquina (127.0.0.0/8, {@code ::1}), redes privadas (10/8, 172.16/12,
     * 192.168/16, {@code fc00::/7}), link-local — onde ficam os metadados de nuvem, como
     * {@code 169.254.169.254} —, CGNAT (100.64/10), o curinga {@code 0.0.0.0} e as demais
     * faixas reservadas. A verificação é feita depois de resolver o DNS, sobre todos os
     * endereços devolvidos, e vale para a URL pedida, para cada redirecionamento, para os
     * recursos da página (imagens, scripts, iframes, {@code fetch}, WebSockets) e para o
     * HTML passado aos métodos {@code *FromHtml}/{@code *OnHtml}.</p>
     *
     * <p>Ative somente se <strong>todas</strong> as URLs e todo o HTML entregues à
     * BrowserAPI forem confiáveis — por exemplo, para capturar páginas da própria
     * aplicação em {@code http://localhost:7070}. Com a liberação ligada, uma URL vinda de
     * usuário consegue ler serviços internos e credenciais de nuvem (SSRF).</p>
     *
     * <p>A mudança vale na hora, para toda a JVM, inclusive para chamadas em andamento.</p>
     *
     * @param allow {@code true} para liberar destinos internos; {@code false} (padrão)
     *              para bloqueá-los
     */
    public static void setAllowPrivateNetworkAccess(boolean allow) {
        allowPrivateNetworkAccess = allow;
    }

    /**
     * Informa se destinos de rede interna estão liberados.
     *
     * @return {@code true} se {@link #setAllowPrivateNetworkAccess(boolean)} liberou o
     *         acesso; {@code false} (padrão) se estão bloqueados
     */
    public static boolean isPrivateNetworkAccessAllowed() {
        return allowPrivateNetworkAccess;
    }

    // ==================== UTILITÁRIOS DE HTML (SEM PLAYWRIGHT) ====================

    /**
     * Remove comentários e espaços desnecessários de um HTML.
     *
     * <p>Um {@code <!--} sem {@code -->} depois dele não é comentário e fica no texto. O
     * tempo é linear no tamanho da entrada.</p>
     *
     * @param html HTML original (pode ser {@code null})
     * @return HTML minificado
     */
    public static String minifyHtml(String html) {
        if (html == null) return null;
        String noComments = removeHtmlComments(html);
        String noBoundaryWhitespace = TAG_BOUNDARY_PATTERN.matcher(noComments).replaceAll("><");
        return WHITESPACE_PATTERN.matcher(noBoundaryWhitespace).replaceAll(" ").trim();
    }

    /**
     * Adiciona quebras de linha entre tags para facilitar a leitura.
     *
     * @param html HTML original
     * @return HTML "pretty printed" (uma tag por linha)
     */
    public static String prettyPrintHtml(String html) {
        return TAG_OPEN_PATTERN.matcher(html).replaceAll(">\n<");
    }

    /**
     * Extrai todas as URLs de links ({@code <a href>}) de um HTML.
     *
     * <p>O tempo é linear no tamanho da entrada, mesmo para HTML malformado ou hostil.</p>
     *
     * @param html HTML de origem
     * @return Lista de URLs encontradas
     */
    public static List<String> extractLinks(String html) {
        List<String> links = new ArrayList<>();
        forEachTagMatch(html, LINK_PATTERN, "a", m -> links.add(m.group(1)));
        return links;
    }

    /**
     * Extrai todas as URLs de imagens ({@code <img src>}) de um HTML.
     *
     * <p>O tempo é linear no tamanho da entrada, mesmo para HTML malformado ou hostil.</p>
     *
     * @param html HTML de origem
     * @return Lista de URLs encontradas
     */
    public static List<String> extractImageUrls(String html) {
        List<String> urls = new ArrayList<>();
        forEachTagMatch(html, IMG_PATTERN, "img", m -> urls.add(m.group(1)));
        return urls;
    }

    /**
     * Extrai as metatags ({@code name → content}) de um HTML.
     *
     * <p>Considera as metatags com {@code name} antes de {@code content}, como antes. O
     * padrão é tentado uma única vez por tag {@code <meta}, e não mais em cada posição do
     * texto: HTML hostil com milhares de tags abertas deixou de custar minutos.</p>
     *
     * @param html HTML de origem
     * @return Mapa de metatags encontradas
     */
    public static Map<String, String> extractMetaTags(String html) {
        Map<String, String> metas = new HashMap<>();
        forEachTagMatch(html, META_PATTERN, "meta", m -> metas.put(m.group(1), m.group(2)));
        return metas;
    }

    /**
     * Extrai o título ({@code <title>}) de um HTML.
     *
     * @param html HTML de origem
     * @return Título, ou {@code null} se não houver
     */
    public static String extractTitle(String html) {
        Matcher m = TITLE_PATTERN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Remove todas as tags HTML, mantendo apenas o texto.
     *
     * <p>Um {@code <} sem {@code >} depois dele não é tag e fica no texto. O tempo é linear
     * no tamanho da entrada.</p>
     *
     * @param html HTML de origem
     * @return Texto puro
     */
    public static String stripHtml(String html) {
        return removeTags(html).trim();
    }

    /**
     * Verificação heurística simples: considera válido se contém tags básicas.
     *
     * @param html HTML de origem
     * @return {@code true} se parece HTML
     */
    public static boolean isValidHtml(String html) {
        return html != null && (html.contains("<html") || html.contains("<body") || html.contains("<div"));
    }

    /**
     * Extrai todas as classes CSS usadas em um HTML.
     *
     * @param html HTML de origem
     * @return Conjunto de nomes de classes
     */
    public static Set<String> extractCssClasses(String html) {
        Set<String> classes = new HashSet<>();
        Matcher m = CLASS_PATTERN.matcher(html);
        while (m.find()) {
            for (String cls : CLASS_SPLIT_PATTERN.split(m.group(1))) classes.add(cls);
        }
        return classes;
    }

    /**
     * Extrai todos os ids usados em um HTML.
     *
     * @param html HTML de origem
     * @return Conjunto de ids
     */
    public static Set<String> extractIds(String html) {
        Set<String> ids = new HashSet<>();
        Matcher m = ID_PATTERN.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    /**
     * Converte URLs relativas à raiz do site ({@code /path}) em URLs absolutas com base em
     * uma URL raiz: {@code src="/x.js"} vira {@code src="https://site.com/x.js"}.
     *
     * <p>Considera atributos {@code src="..."} e {@code href="..."} entre aspas duplas. URLs
     * relativas ao protocolo ({@code //cdn.site.com/x.js}) já apontam para outro host e ficam
     * como estão. A base é copiada literalmente — {@code $} ou {@code \} nela não têm
     * significado especial — e seguida de {@code /}; por isso, informe-a sem a barra final.</p>
     *
     * @param html    HTML de origem
     * @param baseUrl URL base, sem barra final (ex: {@code "https://site.com"})
     * @return HTML com URLs absolutas
     */
    public static String absolutizeUrls(String html, String baseUrl) {
        // Substituição montada por função e escapada: um "$" ou "\" na base não é referência de grupo
        return URL_ABS_PATTERN.matcher(html).replaceAll(match ->
                Matcher.quoteReplacement(match.group(1) + "=\"" + baseUrl + "/" + match.group(2) + "\""));
    }

    /**
     * Remove os comentários {@code <!-- ... -->} com a mesma semântica de
     * {@code "<!--.*?-->"} (DOTALL), mas em tempo linear.
     *
     * <p>A regex tentava casar a partir de cada {@code <!--} e, sem {@code -->} adiante,
     * varria até o fim do texto a cada tentativa: 200 KB de comentários abertos levavam mais
     * de 30 s. Aqui, quando não há {@code -->} depois de um {@code <!--}, também não há depois
     * de nenhum outro posterior, então o resto fica como está.</p>
     */
    private static String removeHtmlComments(String html) {
        int open = html.indexOf("<!--");
        if (open < 0) return html;
        StringBuilder out = new StringBuilder(html.length());
        int from = 0;
        while (open >= 0) {
            int close = html.indexOf("-->", open + 4);
            if (close < 0) break;
            out.append(html, from, open);
            from = close + 3;
            open = html.indexOf("<!--", from);
        }
        return out.append(html, from, html.length()).toString();
    }

    /**
     * Remove as tags com a mesma semântica de {@code "<[^>]*>"}, mas em tempo linear.
     *
     * <p>Mesmo motivo de {@link #removeHtmlComments(String)}: sem {@code >} depois de um
     * {@code <}, nenhum {@code <} posterior fecha tag, e a regex varria o resto do texto a
     * partir de cada um deles.</p>
     */
    private static String removeTags(String html) {
        int open = html.indexOf('<');
        if (open < 0) return html;
        StringBuilder out = new StringBuilder(html.length());
        int from = 0;
        while (open >= 0) {
            int close = html.indexOf('>', open + 1);
            if (close < 0) break;
            out.append(html, from, open);
            from = close + 1;
            open = html.indexOf('<', from);
        }
        return out.append(html, from, html.length()).toString();
    }

    /**
     * Aplica um padrão do tipo {@code <tag\s[^>]*...} com o mesmo resultado de
     * {@link Matcher#find()}, mas sem o custo quadrático (ou cúbico) em HTML hostil.
     *
     * <p>O {@code find()} tenta o padrão em cada posição do texto, e cada tentativa percorre
     * até o próximo {@code >}: milhares de {@code "<a "} sem {@code >} custavam segundos, e
     * as metatags, com dois {@code [^>]*}, minutos. Aqui o padrão só é tentado onde a tag de
     * fato começa e, quando falha, a busca recomeça depois do {@code >} que encerrou a
     * tentativa: um casamento que começasse antes desse {@code >} também casaria a partir do
     * início que falhou — o {@code [^>]*} dele alcança o mesmo trecho —, então também
     * falharia.</p>
     *
     * @param html    HTML de origem
     * @param pattern padrão que começa por {@code <tag\s[^>]*}
     * @param tagName nome da tag, em minúsculas
     * @param onMatch recebe cada casamento, na ordem do texto
     */
    private static void forEachTagMatch(String html, Pattern pattern, String tagName, Consumer<Matcher> onMatch) {
        Matcher matcher = pattern.matcher(html);
        int length = html.length();
        int from = 0;
        while (from < length) {
            int start = indexOfTagOpening(html, tagName, from);
            if (start < 0) return;
            matcher.region(start, length);
            if (matcher.lookingAt()) {
                onMatch.accept(matcher);
                from = matcher.end();
            } else {
                int close = html.indexOf('>', start);
                if (close < 0) return;
                from = close + 1;
            }
        }
    }

    /**
     * Posição do próximo {@code "<" + tagName} seguido de espaço, a partir de {@code from}.
     * Compara só letras ASCII sem diferenciar caixa — como {@link Pattern#CASE_INSENSITIVE}
     * sem {@code UNICODE_CASE} —, para não tentar onde a regex não tentaria.
     */
    private static int indexOfTagOpening(String html, String tagName, int from) {
        int limit = html.length() - tagName.length() - 1;
        for (int i = html.indexOf('<', from); i >= 0 && i < limit; i = html.indexOf('<', i + 1)) {
            if (matchesAsciiIgnoreCase(html, i + 1, tagName) && isRegexWhitespace(html.charAt(i + 1 + tagName.length()))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesAsciiIgnoreCase(String text, int offset, String lowerCaseWord) {
        for (int k = 0; k < lowerCaseWord.length(); k++) {
            char c = text.charAt(offset + k);
            if (c >= 'A' && c <= 'Z') c = (char) (c + ('a' - 'A'));
            if (c != lowerCaseWord.charAt(k)) return false;
        }
        return true;
    }

    /** O {@code \s} das regex Java (sem {@code UNICODE_CHARACTER_CLASS}): {@code [ \t\n\x0B\f\r]}. */
    private static boolean isRegexWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    // ==================== VALIDAÇÃO DE DESTINO ====================

    /**
     * Resolve um nome de host em endereços IP. Existe para os testes trocarem o DNS por um
     * resolvedor falso, sem rede.
     *
     * @author Angatu Sistemas
     */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Decide se um host pode ser contatado e devolve os endereços já verificados, para que
     * a conexão seja feita exatamente neles (sem uma segunda consulta ao DNS).
     *
     * @author Angatu Sistemas
     */
    @FunctionalInterface
    interface DestinationGuard {
        /**
         * Confere o host pedido e devolve os endereços em que a conexão pode ser feita.
         *
         * @param host host pedido (nome, IPv4 ou IPv6, este com ou sem colchetes)
         * @return os endereços permitidos, na ordem em que devem ser tentados
         * @throws UnknownHostException se o host não resolver
         * @throws IOException se o destino for recusado
         */
        InetAddress[] permit(String host) throws IOException;
    }

    /** Pré-validação da URL de navegação, com o DNS real e a política atual. */
    private static void checkNavigationUrl(String url) throws IOException {
        checkNavigationUrl(url, SYSTEM_RESOLVER, allowPrivateNetworkAccess);
    }

    /**
     * Recusa, antes de abrir o navegador, URLs que não sejam {@code http}/{@code https} ou
     * cujo host não seja um destino permitido.
     *
     * <p>É a primeira de três barreiras, a que dá a mensagem mais clara. As outras duas — a
     * rota do contexto e o {@link EgressProxy} — cobrem o que esta não vê: redirecionamentos,
     * recursos da página e respostas de DNS que mudam entre a checagem e a conexão.</p>
     *
     * @throws IOException com a explicação em PT-BR, se a URL for recusada
     */
    static void checkNavigationUrl(String url, HostResolver resolver, boolean allowPrivate) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("URL não informada.");
        }
        String scheme = schemeOf(url);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            // file:, data:, javascript:, chrome: etc. leem o disco ou rodam no contexto do navegador
            throw new IOException("URL recusada: a BrowserAPI só navega em endereços http:// ou https://"
                    + (scheme == null ? " (a URL informada não tem esquema)." : " (recebido: \"" + scheme + ":\")."));
        }
        String host = hostOf(url);
        if (host == null) {
            throw new IOException("URL recusada: o endereço não tem host.");
        }
        resolvePermitted(host, resolver, allowPrivate);
    }

    /** Veredito da rota para uma requisição da página, com o DNS real e a política atual. */
    private static String requestProblem(String url) {
        return requestProblem(url, SYSTEM_RESOLVER, allowPrivateNetworkAccess);
    }

    /**
     * Decide se uma requisição feita pela página pode sair.
     *
     * @return {@code null} se pode; senão, o motivo da recusa (PT-BR)
     */
    static String requestProblem(String url, HostResolver resolver, boolean allowPrivate) {
        String scheme = schemeOf(url);
        if ("data".equals(scheme) || "blob".equals(scheme)) {
            return null; // conteúdo embutido na própria página: não há destino de rede
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return scheme == null ? "Endereço sem esquema." : "Esquema não permitido: \"" + scheme + ":\".";
        }
        String host = hostOf(url);
        if (host == null) {
            return "Endereço sem host.";
        }
        try {
            resolvePermitted(host, resolver, allowPrivate);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** O {@link DestinationGuard} de produção: DNS real e política atual. */
    static InetAddress[] permittedAddresses(String host) throws IOException {
        return resolvePermitted(host, SYSTEM_RESOLVER, allowPrivateNetworkAccess);
    }

    /**
     * Resolve o host e confere <strong>todos</strong> os endereços devolvidos: basta um
     * interno para recusar, porque o navegador pode conectar em qualquer um deles.
     *
     * @param host         host como aparece na URL (IPv6 pode vir entre colchetes)
     * @param resolver     DNS a consultar
     * @param allowPrivate se destinos de rede interna estão liberados
     * @return os endereços resolvidos, todos permitidos
     * @throws UnknownHostException se o host não resolver
     * @throws IOException se o host for inválido ou apontar para destino proibido
     */
    static InetAddress[] resolvePermitted(String host, HostResolver resolver, boolean allowPrivate) throws IOException {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.length() > 1 && name.charAt(0) == '[' && name.charAt(name.length() - 1) == ']') {
            name = name.substring(1, name.length() - 1);
        }
        name = toAsciiHost(name, host);
        if (!allowPrivate && isLocalhostName(name)) {
            // O Chromium resolve "localhost" e "*.localhost" sozinho para a própria máquina
            throw blocked(host, "aponta para a própria máquina");
        }
        InetAddress[] addresses;
        byte[] ipv4 = name.indexOf(':') < 0 ? parseBrowserIpv4(name, host) : null;
        if (ipv4 != null) {
            addresses = new InetAddress[] {InetAddress.getByAddress(ipv4)};
        } else {
            try {
                addresses = resolver.resolve(name);
            } catch (UnknownHostException e) {
                UnknownHostException unknown = new UnknownHostException(
                        "Destino recusado: não foi possível resolver o host \"" + host + "\".");
                unknown.initCause(e);
                throw unknown;
            }
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("Destino recusado: o host \"" + host + "\" não tem endereço.");
        }
        if (!allowPrivate) {
            for (InetAddress address : addresses) {
                if (isNonPublicAddress(address)) {
                    throw blocked(host, "resolve para " + address.getHostAddress() + ", um endereço de rede interna");
                }
            }
        }
        return addresses;
    }

    /**
     * Lê o host como IPv4 do jeito do navegador (padrão WHATWG), com as formas curtas
     * ({@code 127.1}), octal ({@code 0177.0.0.1}) e hexadecimal ({@code 0x7f.1}).
     *
     * <p>O {@link InetAddress} lê {@code 0177.0.0.1} como 177.0.0.1, um endereço público,
     * enquanto o Chromium navega para 127.0.0.1: a checagem precisa ler o host como o
     * navegador vai usá-lo.</p>
     *
     * @param name     host em minúsculas, sem colchetes
     * @param original host como veio na URL, para a mensagem de erro
     * @return os 4 bytes do endereço, ou {@code null} se o host não terminar em número (é um nome)
     * @throws IOException se terminar em número mas não for um IPv4 válido — o navegador
     *         também recusa a URL
     */
    static byte[] parseBrowserIpv4(String name, String original) throws IOException {
        List<String> parts = new ArrayList<>(List.of(name.split("\\.", -1)));
        if (parts.size() > 1 && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1); // "1.2.3.4." é o mesmo host
        }
        String last = parts.get(parts.size() - 1);
        boolean endsInNumber = (!last.isEmpty() && last.chars().allMatch(c -> c >= '0' && c <= '9'))
                || parseIpv4Number(last) >= 0;
        if (!endsInNumber) {
            return null;
        }
        if (parts.size() > 4) {
            throw new IOException("Destino recusado: host inválido \"" + original + "\".");
        }
        long[] numbers = new long[parts.size()];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = parseIpv4Number(parts.get(i));
            boolean isLast = i == numbers.length - 1; // o último pedaço ocupa todos os bytes que sobram
            if (numbers[i] < 0 || (!isLast && numbers[i] > 255)) {
                throw new IOException("Destino recusado: host inválido \"" + original + "\".");
            }
        }
        long address = numbers[numbers.length - 1];
        if (address >= 1L << (8 * (5 - numbers.length))) {
            throw new IOException("Destino recusado: host inválido \"" + original + "\".");
        }
        for (int i = 0; i < numbers.length - 1; i++) {
            address += numbers[i] << (8 * (3 - i));
        }
        return new byte[] {(byte) (address >>> 24), (byte) (address >>> 16), (byte) (address >>> 8), (byte) address};
    }

    /**
     * Um pedaço de IPv4 no padrão WHATWG: {@code 0x} é hexadecimal, zero à esquerda é octal,
     * o resto é decimal; vazio depois do prefixo vale 0.
     *
     * @return o valor (limitado a pouco acima de 32 bits), ou {@code -1} se não for número
     */
    private static long parseIpv4Number(String part) {
        if (part.isEmpty()) return -1;
        int radix = 10;
        String digits = part;
        if (part.length() >= 2 && (part.startsWith("0x") || part.startsWith("0X"))) {
            radix = 16;
            digits = part.substring(2);
        } else if (part.length() >= 2 && part.charAt(0) == '0') {
            radix = 8;
            digits = part.substring(1);
        }
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = Character.digit(digits.charAt(i), radix);
            if (digit < 0 || digits.charAt(i) > 127) return -1;
            // Saturado: qualquer valor acima de 32 bits já é inválido, e não pode transbordar
            value = Math.min(value * radix + digit, 1L << 33);
        }
        return value;
    }

    private static IOException blocked(String host, String reason) {
        return new IOException("Destino bloqueado por segurança: \"" + host + "\" " + reason
                + ". Para páginas confiáveis da própria aplicação, use BrowserAPI.setAllowPrivateNetworkAccess(true).");
    }

    /**
     * Converte o host para ASCII (IDN) e recusa caracteres que não cabem num nome de host
     * ou num IP literal — o que sobra é o que o resolvedor recebe.
     */
    private static String toAsciiHost(String name, String original) throws IOException {
        String ascii = name;
        if (!isAscii(name)) {
            try {
                ascii = IDN.toASCII(name, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new IOException("Destino recusado: host inválido \"" + original + "\".", e);
            }
        }
        if (ascii.isEmpty()) {
            throw new IOException("Destino recusado: host vazio.");
        }
        for (int i = 0; i < ascii.length(); i++) {
            char c = ascii.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == ':';
            if (!allowed) {
                throw new IOException("Destino recusado: host inválido \"" + original + "\".");
            }
        }
        return ascii;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) return false;
        }
        return true;
    }

    /** {@code localhost} e {@code *.localhost}, com ou sem ponto final. */
    static boolean isLocalhostName(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '.') end--;
        name = name.substring(0, end);
        return name.equals("localhost") || name.endsWith(".localhost");
    }

    /**
     * Diz se o endereço não é da internet pública: curinga, loopback, redes privadas,
     * link-local, CGNAT, multicast, faixas reservadas ou de documentação, e os IPv6 que
     * embutem um IPv4 interno (mapeado, compatível, NAT64 e 6to4).
     */
    static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            return isNonPublicIpv4(b, 0);
        }
        int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
        if ((b0 & 0xfe) == 0xfc) return true;                        // fc00::/7 — rede local única
        if (b0 == 0xfe && (b1 & 0x80) == 0x80) return true;          // fe80::/10 e fec0::/10
        if (b0 == 0xff) return true;                                 // ff00::/8 — multicast
        if (isZero(b, 0, 10) && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
            return isNonPublicIpv4(b, 12);                           // ::ffff:a.b.c.d — IPv4 mapeado
        }
        if (isZero(b, 0, 12)) return true;                           // ::/96 — IPv4 compatível (obsoleto), :: e ::1
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b) {
            if (isZero(b, 4, 12)) return isNonPublicIpv4(b, 12);     // 64:ff9b::/96 — NAT64 do IPv4 embutido
            if (b[4] == 0 && b[5] == 1) return true;                 // 64:ff9b:1::/48 — NAT64 de uso local
        }
        if (b0 == 0x20 && b1 == 0x02) return isNonPublicIpv4(b, 2);  // 2002::/16 — 6to4 do IPv4 embutido
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) return true; // 2001:db8::/32 — documentação
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true; // 2001::/32 — Teredo
        return b0 == 0x01 && b1 == 0x00 && isZero(b, 2, 8);          // 100::/64 — descarte
    }

    private static boolean isNonPublicIpv4(byte[] b, int offset) {
        int a0 = b[offset] & 0xff, a1 = b[offset + 1] & 0xff, a2 = b[offset + 2] & 0xff;
        return a0 == 0                                  // 0.0.0.0/8 — "esta rede"; 0.0.0.0 é o curinga
                || a0 == 10                             // 10/8 — privada
                || a0 == 127                            // 127/8 — loopback
                || (a0 == 100 && (a1 & 0xc0) == 64)     // 100.64/10 — CGNAT
                || (a0 == 169 && a1 == 254)             // 169.254/16 — link-local (metadados de nuvem)
                || (a0 == 172 && (a1 & 0xf0) == 16)     // 172.16/12 — privada
                || (a0 == 192 && a1 == 0 && a2 == 0)    // 192.0.0/24 — IETF (inclui metadados da Oracle Cloud)
                || (a0 == 192 && a1 == 0 && a2 == 2)    // 192.0.2/24 — documentação
                || (a0 == 192 && a1 == 88 && a2 == 99)  // 192.88.99/24 — relay 6to4 (obsoleto)
                || (a0 == 192 && a1 == 168)             // 192.168/16 — privada
                || (a0 == 198 && (a1 & 0xfe) == 18)     // 198.18/15 — testes de desempenho
                || (a0 == 198 && a1 == 51 && a2 == 100) // 198.51.100/24 — documentação
                || (a0 == 203 && a1 == 0 && a2 == 113)  // 203.0.113/24 — documentação
                || a0 >= 224;                           // multicast, reservada e broadcast
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) return false;
        }
        return true;
    }

    /**
     * Esquema da URL em minúsculas, lido como o navegador lê (espaços e controles nas pontas
     * e tabs/quebras de linha no meio são ignorados), ou {@code null} se não houver.
     */
    static String schemeOf(String url) {
        if (url == null) return null;
        String s = normalizeUrl(url);
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean other = (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!letter && !(i > 0 && other)) return null;
        }
        return s.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /**
     * Host de uma URL {@code http}/{@code https}, extraído como o navegador extrai (padrão
     * WHATWG): barras e contrabarras depois do esquema são ignoradas, o host começa depois do
     * último {@code @} e é decodificado de {@code %XX}. IPv6 volta entre colchetes.
     *
     * @return o host, ou {@code null} se não houver
     */
    static String hostOf(String url) {
        String s = normalizeUrl(url);
        int colon = s.indexOf(':');
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < s.length() && s.charAt(start) == '/') start++;
        int end = start;
        while (end < s.length() && "/?#".indexOf(s.charAt(end)) < 0) end++;
        String authority = s.substring(start, end);
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        String host;
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            if (close < 0) return null;
            host = hostAndPort.substring(0, close + 1);
        } else {
            int portColon = hostAndPort.indexOf(':');
            host = portColon < 0 ? hostAndPort : hostAndPort.substring(0, portColon);
        }
        host = percentDecode(host);
        return host.isEmpty() ? null : host;
    }

    /**
     * Normaliza a URL como o navegador faz antes de interpretá-la: tira controles e espaços
     * das pontas, remove tabs e quebras de linha e trata {@code \} como {@code /}. Sem isso,
     * {@code " file:..."} ou {@code "http://127.0.0.1\@site.com"} escapariam da checagem.
     */
    private static String normalizeUrl(String url) {
        int start = 0, end = url.length();
        while (start < end && url.charAt(start) <= ' ') start++;
        while (end > start && url.charAt(end - 1) <= ' ') end--;
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            out.append(c == '\\' ? '/' : c);
        }
        return out.toString();
    }

    /** Decodifica {@code %XX} (UTF-8), como o navegador faz com o host antes de resolvê-lo. */
    private static String percentDecode(String text) {
        if (text.indexOf('%') < 0) return text;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int high = Character.digit(bytes[i + 1], 16);
                int low = Character.digit(bytes[i + 2], 16);
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ==================== OPÇÕES → PEDIDO AO NAVEGADOR ====================

    /**
     * Como a captura será pedida ao navegador, já validada a partir de um {@link ScreenshotOptions}.
     *
     * @param jpeg     {@code true} para JPEG (quando há {@code quality}); {@code false} para PNG
     * @param quality  qualidade do JPEG, ou {@code null} no PNG
     * @param fullPage página inteira ({@code true}) ou só a área visível
     * @param clip     recorte, ou {@code null} para nenhum
     * @author Angatu Sistemas
     */
    record CapturePlan(boolean jpeg, Integer quality, boolean fullPage, Region clip) {}

    /**
     * Retângulo de recorte, em pixels CSS.
     *
     * @param x      canto esquerdo
     * @param y      topo
     * @param width  largura, maior que zero
     * @param height altura, maior que zero
     * @author Angatu Sistemas
     */
    record Region(int x, int y, int width, int height) {}

    /**
     * Valida as opções de captura e decide o formato: com {@code quality}, JPEG naquela
     * qualidade (o PNG não tem qualidade — pedir as duas coisas fazia toda captura falhar);
     * sem ela, PNG.
     *
     * @throws IllegalArgumentException se {@code quality} estiver fora de 0–100 ou o recorte
     *         estiver incompleto ou inválido
     */
    static CapturePlan capturePlan(ScreenshotOptions options) {
        Integer quality = options.quality;
        if (quality != null && (quality < 0 || quality > 100)) {
            throw new IllegalArgumentException("quality deve estar entre 0 e 100 (recebido: " + quality + ").");
        }
        return new CapturePlan(quality != null, quality, options.fullPage, clipRegion(options));
    }

    private static Region clipRegion(ScreenshotOptions options) {
        Integer x = options.clipX, y = options.clipY, width = options.clipWidth, height = options.clipHeight;
        if (x == null && y == null && width == null && height == null) return null;
        if (x == null || y == null || width == null || height == null) {
            throw new IllegalArgumentException("Recorte incompleto: informe clipX, clipY, clipWidth e clipHeight juntos.");
        }
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("clipX e clipY não podem ser negativos.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("clipWidth e clipHeight devem ser maiores que zero.");
        }
        return new Region(x, y, width, height);
    }

    /** Tipos de recurso (nomenclatura do Playwright) que as opções mandam bloquear. */
    static Set<String> blockedResourceTypes(BaseBrowserOptions options) {
        Set<String> types = new HashSet<>();
        if (options.blockImages) types.add("image");
        if (options.blockCss) types.add("stylesheet");
        if (options.blockFonts) types.add("font");
        return Set.copyOf(types);
    }

    private static ScrapeOptions scrapeOptions(ScrapeOptions options) {
        return options != null ? options : new ScrapeOptions();
    }

    private static void requirePlaywright() {
        Dependencies.require(PLAYWRIGHT_CLASS, PLAYWRIGHT_COORDINATES, PLAYWRIGHT_FEATURE);
    }

    /**
     * Diz se a falha veio de uma interrupção da thread. O Playwright converte a
     * {@link InterruptedException} em exceção própria e, no caminho, apaga a flag de
     * interrupção — quem trata a falha precisa restaurá-la.
     */
    private static boolean isInterruption(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static String firstLine(Throwable error) {
        String message = String.valueOf(error.getMessage());
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).trim();
    }

    // ==================== CLASSES DE OPÇÕES ====================

    /**
     * Opções base de navegação: viewport, user-agent, bloqueios de recursos, esperas de
     * carregamento e headers extras. Todas são aplicadas em todas as chamadas que as recebem.
     *
     * <p>Campos públicos por convenção da API (acesso direto:
     * {@code options.viewportWidth = 1280;}).</p>
     *
     * <p>Esta classe <strong>não</strong> é {@code final}: é a base de
     * {@link ScreenshotOptions} e {@link ScrapeOptions}, que são {@code final}. Não foi feita
     * para ser estendida fora da biblioteca — nenhum método aceita uma subclasse própria, e
     * novos campos podem ser acrescentados a qualquer versão.</p>
     *
     * @author Angatu Sistemas
     */
    public static class BaseBrowserOptions {

        /** Cria as opções com os valores padrão (viewport 1920x1080, esperas ligadas, nada bloqueado). */
        public BaseBrowserOptions() {
        }

        /** Largura da janela do navegador, em pixels CSS. Padrão: {@code 1920}. */
        public int viewportWidth = DEFAULT_VIEWPORT_WIDTH;

        /** Altura da janela do navegador, em pixels CSS. Padrão: {@code 1080}. */
        public int viewportHeight = DEFAULT_VIEWPORT_HEIGHT;

        /** User-agent enviado pelo navegador; {@code null} (padrão) usa um Chrome de desktop. */
        public String userAgent = null;

        /** Se {@code true}, a página carrega sem imagens (inclusive fundos de CSS). Padrão: {@code false}. */
        public boolean blockImages = false;

        /** Se {@code true}, a página carrega sem folhas de estilo (CSS). Padrão: {@code false}. */
        public boolean blockCss = false;

        /** Se {@code true}, a página carrega sem fontes web (usa as do sistema). Padrão: {@code false}. */
        public boolean blockFonts = false;

        /**
         * Se {@code true} (padrão), espera a rede ficar ociosa (sem requisições por 500 ms,
         * até 15 s) antes de ler ou capturar a página. Páginas que nunca param de fazer
         * requisições seguem depois do limite, com o que já carregou.
         */
        public boolean waitForNetworkIdle = DEFAULT_WAIT_NETWORK_IDLE;

        /**
         * Se {@code true} (padrão), espera todas as imagens da página terminarem de carregar
         * (até 10 s) antes de ler ou capturar a página.
         */
        public boolean waitForImages = DEFAULT_WAIT_IMAGES;

        /**
         * Headers HTTP extras enviados em todas as requisições da página — inclusive às de
         * outros domínios que ela carregar (CDNs, fontes etc.). Padrão: vazio.
         */
        public Map<String, String> extraHeaders = new HashMap<>();
    }

    /**
     * Opções de captura de screenshot: estende {@link BaseBrowserOptions} com controle de
     * página inteira, qualidade (JPEG) e área de recorte.
     *
     * @author Angatu Sistemas
     */
    public static final class ScreenshotOptions extends BaseBrowserOptions {

        /** Cria as opções de captura com os valores padrão (página inteira, PNG, sem recorte). */
        public ScreenshotOptions() {
        }

        /**
         * Se {@code true} (padrão), captura a página inteira, com rolagem; se {@code false},
         * só a área visível da janela ({@code viewportWidth} × {@code viewportHeight}).
         */
        public boolean fullPage = DEFAULT_FULL_PAGE;

        /**
         * Qualidade de 0 a 100. Quando definida, a captura é feita em JPEG, com essa
         * qualidade; {@code null} (padrão) captura em PNG, sem perdas. Valores fora de 0–100
         * são recusados com {@link IllegalArgumentException}.
         */
        public Integer quality = null;

        /**
         * Canto esquerdo do recorte, em pixels CSS. O recorte só vale com os quatro campos
         * {@code clip*} definidos; em coordenadas da página inteira quando {@link #fullPage}
         * é {@code true}, ou da área visível quando é {@code false}. Padrão: {@code null}
         * (sem recorte).
         */
        public Integer clipX = null;

        /** Topo do recorte, em pixels CSS (veja {@link #clipX}). Padrão: {@code null}. */
        public Integer clipY = null;

        /** Largura do recorte, em pixels CSS, maior que zero (veja {@link #clipX}). Padrão: {@code null}. */
        public Integer clipWidth = null;

        /** Altura do recorte, em pixels CSS, maior que zero (veja {@link #clipX}). Padrão: {@code null}. */
        public Integer clipHeight = null;
    }

    /**
     * Opções de scraping: estende {@link BaseBrowserOptions} sem campos adicionais.
     *
     * @author Angatu Sistemas
     */
    public static final class ScrapeOptions extends BaseBrowserOptions {

        /** Cria as opções de scraping com os valores padrão. */
        public ScrapeOptions() {
        }
    }

    // ==================== SHUTDOWN ====================

    /**
     * Fecha os navegadores e os processos do Playwright do pool.
     *
     * <p>Deve ser chamado ao encerrar a aplicação para evitar vazamento de processos
     * headless. Espera até 10 s pelas chamadas em andamento; um navegador que ainda estiver
     * em uso depois disso não é fechado debaixo da chamada — ele é fechado pela própria
     * chamada, quando ela terminar. Chamadas feitas depois do {@code shutdown()} voltam a
     * funcionar, com navegadores novos, iniciados sob demanda.</p>
     */
    public static void shutdown() {
        // Sem Playwright no classpath não há pool a fechar, e tocar no motor exigiria o jar dele
        if (Dependencies.isPresent(PLAYWRIGHT_CLASS)) {
            Engine.shutdown();
        }
        Console.log("BrowserAPI finalizada.");
    }

    // ==================== MOTOR (PLAYWRIGHT) ====================

    /**
     * De onde vem a página: uma URL já validada ou um HTML.
     *
     * @param url  URL a abrir, ou {@code null} quando a página vem de HTML
     * @param html HTML a renderizar, ou {@code null} quando a página vem de URL
     * @author Angatu Sistemas
     */
    private record PageSource(String url, String html) {

        static PageSource ofUrl(String url) {
            return new PageSource(url, null);
        }

        static PageSource ofHtml(String html) throws IOException {
            if (html == null) {
                throw new IOException("HTML não informado.");
            }
            return new PageSource(null, html);
        }
    }

    /**
     * Tudo o que toca no Playwright mora aqui dentro, para que carregar a
     * {@code BrowserAPI} — e usar os utilitários de HTML — não exija o jar dele antes de
     * {@link Dependencies#require} poder explicar o que falta.
     *
     * @author Angatu Sistemas
     */
    private static final class Engine {

        /** Mesmos argumentos para todo navegador: não existe mais um navegador "de socorro" diferente. */
        private static final List<String> LAUNCH_ARGS = List.of(
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--disable-gpu",
                "--disable-extensions",
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--font-render-hinting=none",
                // O WebRTC fala UDP direto, por fora do proxy de saída; sem isso, a página alcançaria a rede interna por ele
                "--force-webrtc-ip-handling-policy=disable_non_proxied_udp");

        private static final String IMAGES_LOADED_JS = "() => Array.from(document.images).every(img => img.complete)";
        private static final String FONTS_LOADED_JS = "() => !document.fonts || document.fonts.status === 'loaded'";
        private static final long LAUNCH_RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(LAUNCH_RETRY_DELAY_MS);
        private static final long SHUTDOWN_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MS);

        /**
         * As vagas do pool: sempre exatamente {@link #BROWSER_POOL_SIZE} objetos. Quem pega uma
         * vaga é o único a usar o navegador dela até devolvê-la — é isso que limita os
         * processos vivos, em vez de abrir um navegador a mais para cada chamada extra.
         */
        private static final ArrayBlockingQueue<Slot> SLOTS = new ArrayBlockingQueue<>(BROWSER_POOL_SIZE);

        static {
            for (int i = 0; i < BROWSER_POOL_SIZE; i++) SLOTS.add(new Slot());
        }

        private static final Object SHUTDOWN_LOCK = new Object();

        /**
         * Threads que criam o driver do Playwright (veja {@link #createPlaywright()}): daemon,
         * nomeadas e no máximo uma por vaga, com folga para a que ainda está saindo.
         */
        private static final ThreadPoolExecutor DRIVER_CREATOR = new ThreadPoolExecutor(0, BROWSER_POOL_SIZE + 2,
                30, TimeUnit.SECONDS, new SynchronousQueue<>(), daemonThreads("Angatu-BrowserAPI-Driver-", 0));

        /**
         * Muda no início e no fim de cada {@link #shutdown()}: navegador de outra geração é
         * fechado por quem o devolve, em vez de ser fechado pelo shutdown no meio de uma chamada.
         */
        private static volatile long generation;
        /** Ligado durante o {@link #shutdown()}: chamadas novas falham na hora, em vez de abrir navegador. */
        private static volatile boolean shuttingDown;
        private static volatile LaunchFailure lastLaunchFailure;

        /**
         * Última falha ao iniciar o navegador, para não tentar de novo a cada chamada.
         *
         * @param atNanos quando ocorreu ({@link System#nanoTime()})
         * @param message primeira linha da mensagem de erro
         * @author Angatu Sistemas
         */
        private record LaunchFailure(long atNanos, String message) {}

        // ---------- operações ----------

        static BufferedImage screenshot(PageSource source, ScreenshotOptions options, CapturePlan plan, String failure) throws IOException {
            byte[] bytes = withPage(options, failure, page -> {
                load(page, source, options);
                Console.debug("Capturando screenshot...");
                return page.screenshot(screenshotOptions(plan));
            });
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IOException(failure + ": o navegador devolveu uma imagem ilegível.");
            }
            Console.debug("Screenshot capturado com sucesso: %dx%d", image.getWidth(), image.getHeight());
            return image;
        }

        static String content(PageSource source, BaseBrowserOptions options, String failure) throws IOException {
            return withPage(options, failure, page -> {
                load(page, source, options);
                return page.content();
            });
        }

        static String text(PageSource source, String selector, BaseBrowserOptions options, String failure) throws IOException {
            return withPage(options, failure, page -> {
                load(page, source, options);
                page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(SELECTOR_TIMEOUT_MS));
                return page.textContent(selector);
            });
        }

        static List<List<String>> table(PageSource source, String tableSelector, BaseBrowserOptions options, String failure) throws IOException {
            return withPage(options, failure, page -> {
                load(page, source, options);
                page.waitForSelector(tableSelector, new Page.WaitForSelectorOptions().setTimeout(SELECTOR_TIMEOUT_MS));
                List<List<String>> data = new ArrayList<>();
                for (ElementHandle row : page.querySelectorAll(tableSelector + " tr")) {
                    List<ElementHandle> cells = row.querySelectorAll("td, th");
                    if (cells.isEmpty()) continue;
                    List<String> values = new ArrayList<>(cells.size());
                    for (ElementHandle cell : cells) values.add(cell.textContent());
                    data.add(values);
                }
                return data;
            });
        }

        static Map<String, String> fields(PageSource source, Map<String, String> selectors, BaseBrowserOptions options, String failure) throws IOException {
            return withPage(options, failure, page -> {
                load(page, source, options);
                Map<String, String> results = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : selectors.entrySet()) {
                    try {
                        page.waitForSelector(entry.getValue(), new Page.WaitForSelectorOptions().setTimeout(FIELD_SELECTOR_TIMEOUT_MS));
                        results.put(entry.getKey(), page.textContent(entry.getValue()));
                    } catch (RuntimeException e) {
                        if (isInterruption(e)) throw e; // interrupção não é "campo ausente"
                        results.put(entry.getKey(), null);
                    }
                }
                return results;
            });
        }

        static Object evaluate(PageSource source, String script, BaseBrowserOptions options, String failure) throws IOException {
            return withPage(options, failure, page -> {
                load(page, source, options);
                return page.evaluate(script);
            });
        }

        // ---------- ciclo de uma chamada ----------

        /**
         * Pega um navegador, abre uma página num contexto novo, protege a página, executa o
         * trabalho e devolve o navegador — sempre, mesmo se fechar a página falhar (antes, uma
         * falha no {@code page.close()} pulava a devolução e o pool perdia a vaga).
         */
        private static <T> T withPage(BaseBrowserOptions options, String failure, Function<Page, T> work) throws IOException {
            Slot slot = borrow();
            CallGuard guard = new CallGuard();
            boolean reusable = false;
            try {
                Page page = slot.browser.newPage(pageOptions(options));
                try {
                    protect(page, options, guard);
                    T result = work.apply(page);
                    guard.throwIfMainFrameBlocked(failure);
                    return result;
                } finally {
                    reusable = closePage(page);
                }
            } catch (RuntimeException e) {
                throw translate(failure, e, guard);
            } finally {
                release(slot, reusable);
            }
        }

        /** Cada chamada ganha um contexto próprio (sem cookies nem armazenamento de outras chamadas). */
        private static Browser.NewPageOptions pageOptions(BaseBrowserOptions options) {
            return new Browser.NewPageOptions()
                    .setViewportSize(options.viewportWidth, options.viewportHeight)
                    .setUserAgent(options.userAgent != null ? options.userAgent : DEFAULT_USER_AGENT)
                    // Requisições feitas por service worker não passam pela rota do contexto
                    .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                    // Página hostil não grava arquivos no disco do servidor
                    .setAcceptDownloads(false);
        }

        /**
         * Instala a rota de todas as requisições do contexto — destino permitido e tipos de
         * recurso bloqueados pelas opções — e o observador de redirecionamentos, que a rota
         * não vê: o Playwright só chama a rota para a primeira URL de um redirecionamento.
         */
        private static void protect(Page page, BaseBrowserOptions options, CallGuard guard) {
            page.setDefaultTimeout(PAGE_TIMEOUT_MS);
            page.setDefaultNavigationTimeout(PAGE_TIMEOUT_MS);
            if (options.extraHeaders != null && !options.extraHeaders.isEmpty()) {
                page.setExtraHTTPHeaders(new HashMap<>(options.extraHeaders));
            }
            Set<String> blockedTypes = blockedResourceTypes(options);
            guard.watch(page);
            BrowserContext context = page.context(); // no contexto, e não na página: cobre também os pop-ups
            context.onRequest(guard::inspectRedirect);
            context.route(url -> true, route -> guard.decide(route, blockedTypes));
        }

        private static void load(Page page, PageSource source, BaseBrowserOptions options) {
            if (source.url() != null) {
                Console.debug("Navegando para: %s", source.url());
                page.navigate(source.url(), new Page.NavigateOptions()
                        .setTimeout(PAGE_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.LOAD));
            } else {
                Console.debug("Renderizando HTML...");
                page.setContent(source.html(), new Page.SetContentOptions()
                        .setTimeout(PAGE_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.LOAD));
            }
            waitForRendering(page, options);
        }

        /**
         * Esperas depois do evento {@code load}: rede ociosa e imagens, conforme as opções, e
         * fontes, sempre. Cada uma tem prazo próprio, e estourar o prazo não derruba a chamada
         * — ela segue com o que já carregou. Substituem a antiga pausa fixa de 500 ms, que
         * atrasava toda chamada sem garantir nada.
         */
        private static void waitForRendering(Page page, BaseBrowserOptions options) {
            if (options.waitForNetworkIdle) {
                bestEffort("a rede ficar ociosa", () -> page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(NETWORK_IDLE_TIMEOUT_MS)));
            }
            if (options.waitForImages) {
                bestEffort("as imagens", () -> page.waitForFunction(IMAGES_LOADED_JS, null,
                        new Page.WaitForFunctionOptions().setTimeout(IMAGES_TIMEOUT_MS)));
            }
            bestEffort("as fontes", () -> page.waitForFunction(FONTS_LOADED_JS, null,
                    new Page.WaitForFunctionOptions().setTimeout(FONTS_TIMEOUT_MS)));
        }

        private static void bestEffort(String what, Runnable wait) {
            try {
                wait.run();
            } catch (RuntimeException e) {
                if (isInterruption(e)) throw e; // interrupção não é "demorou": sobe, e a flag é restaurada no translate
                Console.debug("Espera por %s não concluída (%s); seguindo com o que já carregou.", what, firstLine(e));
            }
        }

        private static Page.ScreenshotOptions screenshotOptions(CapturePlan plan) {
            Page.ScreenshotOptions options = new Page.ScreenshotOptions()
                    .setTimeout(SCREENSHOT_TIMEOUT_MS)
                    .setFullPage(plan.fullPage())
                    .setType(plan.jpeg() ? ScreenshotType.JPEG : ScreenshotType.PNG);
            if (plan.jpeg()) {
                options.setQuality(plan.quality());
            }
            Region clip = plan.clip();
            if (clip != null) {
                options.setClip(clip.x(), clip.y(), clip.width(), clip.height());
            }
            return options;
        }

        /**
         * Fecha a página (e o contexto dela). Devolve {@code false} se não conseguir: o
         * navegador então é descartado, para não acumular contextos órfãos.
         */
        private static boolean closePage(Page page) {
            // Com a flag de interrupção ligada, o close falharia na hora sem fechar nada
            boolean interrupted = Thread.interrupted();
            try {
                page.close();
                return true;
            } catch (RuntimeException e) {
                interrupted |= isInterruption(e);
                Console.debug("Falha ao fechar a página (%s); o navegador será substituído.", firstLine(e));
                return false;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private static IOException translate(String failure, RuntimeException error, CallGuard guard) {
            if (isInterruption(error)) {
                Thread.currentThread().interrupt(); // o Playwright apagou a flag ao converter a InterruptedException
                InterruptedIOException interrupted = new InterruptedIOException(failure + ": a operação foi interrompida.");
                interrupted.initCause(error);
                return interrupted;
            }
            String blocked = guard.mainFrameProblem();
            if (blocked != null) {
                return new IOException(failure + ": " + blocked, error);
            }
            return new IOException(failure + ": " + error.getMessage(), error);
        }

        // ---------- pool ----------

        private static Slot borrow() throws IOException {
            if (shuttingDown) throw closing();
            Slot slot;
            try {
                slot = SLOTS.poll(BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("A espera por um navegador livre da BrowserAPI foi interrompida.");
            }
            if (slot == null) {
                throw new IOException("BrowserAPI ocupada: os " + BROWSER_POOL_SIZE + " navegadores seguiram em uso por "
                        + BORROW_TIMEOUT_MS / 1000 + " s. Tente novamente em instantes.");
            }
            try {
                if (shuttingDown) throw closing();
                ensureLaunched(slot);
                return slot;
            } catch (IOException | RuntimeException | Error e) {
                SLOTS.offer(slot); // a vaga volta vazia: a próxima chamada tenta de novo
                throw e;
            }
        }

        private static IOException closing() {
            return new IOException("A BrowserAPI está sendo encerrada (shutdown em andamento). Tente novamente em instantes.");
        }

        private static void release(Slot slot, boolean reusable) {
            try {
                if (!reusable || slot.bornIn != generation || !isConnected(slot.browser)) {
                    slot.discard();
                }
            } finally {
                if (!SLOTS.offer(slot)) {
                    Console.error("BrowserAPI: vaga do pool perdida — estado inesperado.");
                }
            }
        }

        /**
         * Garante um navegador vivo, da geração atual, na vaga. Um navegador caído ou de antes
         * do último shutdown é trocado por outro — um por um, na mesma vaga.
         */
        private static void ensureLaunched(Slot slot) throws IOException {
            long current = generation;
            if (slot.browser != null && slot.bornIn == current && isConnected(slot.browser)) {
                return;
            }
            slot.discard();
            LaunchFailure failure = lastLaunchFailure;
            if (failure != null && System.nanoTime() - failure.atNanos() < LAUNCH_RETRY_DELAY_NANOS) {
                // Sem isso, cada chamada subiria (e mataria) um processo Node só para falhar de novo
                throw new IOException("O navegador headless não pôde ser iniciado há instantes (" + failure.message()
                        + "). Nova tentativa em alguns segundos.");
            }
            EgressProxy egress = null;
            Playwright playwright = null;
            try {
                egress = new EgressProxy(BrowserAPI::permittedAddresses);
                playwright = createPlaywright();
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(LAUNCH_ARGS)
                        // Toda conexão do navegador passa pelo proxy de saída, que confere o destino de
                        // cada uma — inclusive redirecionamentos e WebSockets, que a rota não vê
                        .setProxy(new Proxy(egress.address())));
                slot.attach(playwright, browser, egress, current);
                lastLaunchFailure = null;
                Console.debug("BrowserAPI: navegador headless iniciado (proxy de saída em %s).", egress.address());
            } catch (IOException | RuntimeException e) {
                closeQuietly(playwright); // antes, o processo do driver ficava vivo a cada falha
                closeQuietly(egress);
                if (isInterruption(e)) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interrupted = new InterruptedIOException("A inicialização do navegador foi interrompida.");
                    interrupted.initCause(e);
                    throw interrupted;
                }
                lastLaunchFailure = new LaunchFailure(System.nanoTime(), firstLine(e));
                throw new IOException("Não foi possível iniciar o navegador headless (Chromium): " + firstLine(e), e);
            }
        }

        /**
         * Cria o Playwright (o processo Node do driver) numa thread à parte e espera o fim da
         * criação mesmo se a chamada for interrompida.
         *
         * <p>Interrompido no meio do handshake, o {@code Playwright.create()} da versão 1.58 lança
         * a exceção sem encerrar o processo que acabou de iniciar, e o driver fica órfão até a JVM
         * terminar. Aqui a criação sempre chega ao fim; a interrupção é restaurada em seguida, e o
         * lançamento do navegador, que vem depois, falha e fecha o driver pelo caminho normal.</p>
         */
        private static Playwright createPlaywright() throws IOException {
            Callable<Playwright> create = Playwright::create;
            Future<Playwright> creation;
            try {
                creation = DRIVER_CREATOR.submit(create);
            } catch (RejectedExecutionException e) {
                throw new IOException("Não foi possível iniciar o driver do Playwright agora.", e);
            }
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        return creation.get();
                    } catch (InterruptedException e) {
                        interrupted = true; // abandonar a criação deixaria o driver órfão: espera ela terminar
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        if (cause instanceof Error error) throw error;
                        throw new IOException("Falha ao iniciar o driver do Playwright.", cause);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        static void warmUp() throws IOException {
            if (shuttingDown) throw closing();
            List<Slot> held = new ArrayList<>(BROWSER_POOL_SIZE);
            try {
                Slot slot;
                while (held.size() < BROWSER_POOL_SIZE && (slot = SLOTS.poll()) != null) {
                    held.add(slot);
                    if (shuttingDown) throw closing();
                    ensureLaunched(slot);
                }
            } finally {
                for (Slot slot : held) SLOTS.offer(slot);
            }
            if (held.size() == BROWSER_POOL_SIZE) {
                Console.log("BrowserAPI: pool pronto, com %d navegadores headless abertos.", BROWSER_POOL_SIZE);
            } else {
                Console.log("BrowserAPI: %d navegador(es) headless aberto(s); os outros %d estavam em uso.",
                        held.size(), BROWSER_POOL_SIZE - held.size());
            }
        }

        static void shutdown() {
            synchronized (SHUTDOWN_LOCK) {
                shuttingDown = true;
                generation++; // só o shutdown escreve, e sob esta trava
                List<Slot> drained = new ArrayList<>(BROWSER_POOL_SIZE);
                boolean interrupted = false;
                try {
                    long deadline = System.nanoTime() + SHUTDOWN_WAIT_NANOS;
                    while (drained.size() < BROWSER_POOL_SIZE) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) break;
                        Slot slot = SLOTS.poll(remaining, TimeUnit.NANOSECONDS);
                        if (slot == null) break;
                        drained.add(slot);
                        slot.discard();
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                } finally {
                    // Segunda troca: navegador aberto durante o shutdown também não sobrevive a ele
                    generation++;
                    lastLaunchFailure = null;
                    shuttingDown = false;
                    for (Slot slot : drained) SLOTS.offer(slot);
                }
                int inUse = BROWSER_POOL_SIZE - drained.size();
                if (inUse > 0) {
                    Console.warn("BrowserAPI: %d navegador(es) seguiam em uso após %d s; cada um será fechado quando a sua chamada terminar.",
                            inUse, SHUTDOWN_WAIT_MS / 1000);
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private static boolean isConnected(Browser browser) {
            try {
                return browser != null && browser.isConnected();
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Fecha ignorando falhas; limpa a flag de interrupção durante o close e a restaura depois. */
        private static void closeQuietly(AutoCloseable resource) {
            if (resource == null) return;
            boolean interrupted = Thread.interrupted();
            try {
                resource.close();
            } catch (Exception e) {
                interrupted |= isInterruption(e);
                Console.debug("BrowserAPI: falha ao fechar recurso do navegador (%s).", firstLine(e));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /**
         * Uma vaga do pool: um processo do driver Playwright, o Chromium dele e o proxy de saída
         * por onde esse Chromium conecta. Os campos só são tocados por quem está com a vaga — a
         * fila faz a passagem entre threads.
         *
         * @author Angatu Sistemas
         */
        private static final class Slot {
            private Playwright playwright;
            private Browser browser;
            private EgressProxy proxy;
            private long bornIn;

            void attach(Playwright playwright, Browser browser, EgressProxy proxy, long generation) {
                this.playwright = playwright;
                this.browser = browser;
                this.proxy = proxy;
                this.bornIn = generation;
            }

            void discard() {
                Browser oldBrowser = browser;
                Playwright oldPlaywright = playwright;
                EgressProxy oldProxy = proxy;
                browser = null;
                playwright = null;
                proxy = null;
                closeQuietly(oldBrowser);    // fecha o Chromium com calma...
                closeQuietly(oldPlaywright); // ...encerra o processo Node do driver, que era o que vazava...
                closeQuietly(oldProxy);      // ...e só então o proxy, sem conexão pendente
            }
        }

        /**
         * Guarda de uma chamada: decide cada requisição da rota e observa os redirecionamentos.
         * Todos os métodos rodam na thread da chamada (o Playwright entrega rota e eventos dentro
         * das chamadas bloqueantes dela), então os campos dispensam sincronização.
         *
         * @author Angatu Sistemas
         */
        private static final class CallGuard {
            private Frame mainFrame;
            private boolean warned;
            private String mainFrameProblem;

            void watch(Page page) {
                mainFrame = page.mainFrame();
            }

            void decide(Route route, Set<String> blockedTypes) {
                boolean allow = false;
                try {
                    Request request = route.request();
                    if (!blockedTypes.contains(request.resourceType())) {
                        String problem = requestProblem(request.url());
                        allow = problem == null;
                        if (!allow) refused(request, problem);
                    }
                } catch (RuntimeException e) {
                    Console.debug("BrowserAPI: requisição não pôde ser avaliada (%s) e foi bloqueada.", firstLine(e));
                }
                try {
                    if (allow) {
                        route.resume();
                    } else {
                        route.abort("blockedbyclient");
                    }
                } catch (RuntimeException e) {
                    if (isInterruption(e)) Thread.currentThread().interrupt();
                    // senão, a página já está fechando: não resta o que decidir
                }
            }

            /**
             * Redirecionamentos não passam pela rota. Quem impede a conexão é o proxy de saída;
             * aqui a chamada registra o motivo, para falhar com mensagem clara.
             */
            void inspectRedirect(Request request) {
                try {
                    if (request.redirectedFrom() == null) return;
                    String problem = requestProblem(request.url());
                    if (problem != null) refused(request, problem);
                } catch (RuntimeException e) {
                    Console.debug("BrowserAPI: redirecionamento não pôde ser avaliado (%s).", firstLine(e));
                }
            }

            /**
             * Se a página principal foi levada (por redirecionamento ou script) a um destino
             * recusado, o que ela mostra é uma página de erro: a chamada falha, em vez de
             * devolvê-la. Recurso, iframe ou pop-up recusado só fica de fora da página.
             */
            void throwIfMainFrameBlocked(String failure) throws IOException {
                if (mainFrameProblem != null) {
                    throw new IOException(failure + ": a página foi levada a um destino recusado. " + mainFrameProblem);
                }
            }

            String mainFrameProblem() {
                return mainFrameProblem;
            }

            private void refused(Request request, String problem) {
                if (mainFrameProblem == null && isMainFrameNavigation(request)) {
                    mainFrameProblem = problem;
                }
                if (warned) {
                    Console.debug("BrowserAPI: requisição bloqueada. %s", problem);
                    return;
                }
                warned = true; // só o primeiro bloqueio da chamada vira aviso: página hostil não inunda o log
                Console.warn("BrowserAPI: a página tentou acessar um destino recusado, e a requisição foi bloqueada. %s", problem);
            }

            private boolean isMainFrameNavigation(Request request) {
                try {
                    return request.isNavigationRequest() && request.frame() == mainFrame;
                } catch (RuntimeException e) {
                    return false; // requisição sem frame (worker): não é a navegação principal
                }
            }
        }
    }

    // ==================== PROXY DE SAÍDA ====================

    /**
     * Proxy SOCKS5 local por onde passa toda conexão TCP de um navegador do pool.
     *
     * <p>A rota do Playwright não vê redirecionamentos, WebSockets nem requisições de
     * workers; e a checagem de DNS feita antes da navegação pode ser contornada por um DNS que
     * responde uma coisa para a checagem e outra para o navegador. Aqui não: o Chromium entrega
     * o nome do host sem resolvê-lo, o proxy resolve, confere <strong>todos</strong> os
     * endereços com o {@link DestinationGuard} e conecta exatamente no endereço conferido. O
     * tráfego HTTPS segue cifrado de ponta a ponta — o proxy só repassa bytes.</p>
     *
     * <p>Um proxy por navegador: uma página hostil que esgote os túneis só atrapalha o próprio
     * navegador. Escuta apenas em {@code 127.0.0.1}. Threads daemon e nomeadas, no máximo
     * {@value #MAX_TUNNELS} túneis simultâneos e duas threads por túnel.</p>
     *
     * @author Angatu Sistemas
     */
    static final class EgressProxy implements Closeable {

        private static final AtomicInteger IDS = new AtomicInteger();
        private static final int MAX_TUNNELS = 64;
        private static final long TUNNEL_WAIT_MS = 5_000;
        private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
        private static final int CONNECT_TIMEOUT_MS = 10_000;
        private static final int MAX_CONNECT_ATTEMPTS = 3;
        private static final int IDLE_TIMEOUT_MS = 30_000;
        private static final long IDLE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MS);
        private static final long CLOSE_WAIT_MS = 5_000;
        private static final int BUFFER_SIZE = 16 * 1024;
        private static final long THREAD_STACK_BYTES = 256 * 1024;

        private static final int SOCKS_VERSION = 5;
        private static final int METHOD_NO_AUTH = 0x00;
        private static final int METHOD_NONE_ACCEPTABLE = 0xFF;
        private static final int COMMAND_CONNECT = 1;
        private static final int ADDRESS_IPV4 = 1;
        private static final int ADDRESS_DOMAIN = 3;
        private static final int ADDRESS_IPV6 = 4;
        private static final int REPLY_SUCCEEDED = 0;
        private static final int REPLY_NOT_ALLOWED = 2;
        private static final int REPLY_HOST_UNREACHABLE = 4;
        private static final int REPLY_CONNECTION_REFUSED = 5;
        private static final int REPLY_COMMAND_NOT_SUPPORTED = 7;
        private static final int REPLY_ADDRESS_NOT_SUPPORTED = 8;

        private final DestinationGuard guard;
        private final ServerSocket server;
        private final Semaphore tunnels = new Semaphore(MAX_TUNNELS);
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final ThreadPoolExecutor workers;
        private volatile boolean closed;

        /**
         * Abre o proxy numa porta livre de {@code 127.0.0.1} e começa a aceitar conexões.
         *
         * @param guard quem decide, a cada conexão, se o destino é permitido
         * @throws IOException se não houver porta local disponível
         */
        EgressProxy(DestinationGuard guard) throws IOException {
            this.guard = Objects.requireNonNull(guard, "guard");
            this.server = new ServerSocket(0, 128, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            try {
                String name = "Angatu-BrowserAPI-Proxy-" + IDS.incrementAndGet();
                // Folga acima de 2 por túnel: a thread de um túnel que acabou pode ainda estar saindo
                this.workers = new ThreadPoolExecutor(0, 2 * MAX_TUNNELS + 16, 30, TimeUnit.SECONDS,
                        new SynchronousQueue<>(), daemonThreads(name + "-Tunnel-", THREAD_STACK_BYTES));
                Thread acceptor = new Thread(this::acceptLoop, name);
                acceptor.setDaemon(true);
                acceptor.start();
            } catch (RuntimeException | Error e) {
                closeQuietly(server);
                throw e;
            }
        }

        /** Endereço do proxy no formato do Playwright ({@code socks5://127.0.0.1:porta}). */
        String address() {
            return "socks5://127.0.0.1:" + server.getLocalPort();
        }

        /** Porta local em que o proxy escuta. */
        int port() {
            return server.getLocalPort();
        }

        /** Para de aceitar conexões e derruba os túneis abertos. */
        @Override
        public void close() {
            closed = true;
            closeQuietly(server);
            for (Socket socket : sockets) closeQuietly(socket);
            workers.shutdown();
        }

        private void acceptLoop() {
            while (!closed) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException e) {
                    if (closed) return;
                    Console.debug("BrowserAPI: o proxy de saída falhou ao aceitar conexão (%s).", e.getMessage());
                    try {
                        Thread.sleep(50); // erro persistente (ex.: sem descritores) não vira laço a 100% de CPU
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                boolean permitted;
                try {
                    // No limite de túneis, espera um pouco por uma vaga antes de recusar
                    permitted = tunnels.tryAcquire(TUNNEL_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    closeQuietly(client);
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!permitted) {
                    closeQuietly(client);
                    continue;
                }
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                    tunnels.release();
                    closeQuietly(client);
                }
            }
        }

        private void serve(Socket client) {
            Socket target = null;
            sockets.add(client);
            try {
                if (closed) return;
                client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                client.setTcpNoDelay(true);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                if (!negotiate(in, out)) return;

                if (readByte(in) != SOCKS_VERSION) return;
                int command = readByte(in);
                readByte(in); // reservado
                int addressType = readByte(in);
                String host;
                switch (addressType) {
                    case ADDRESS_IPV4 -> host = InetAddress.getByAddress(readBytes(in, 4)).getHostAddress();
                    case ADDRESS_IPV6 -> host = InetAddress.getByAddress(readBytes(in, 16)).getHostAddress();
                    case ADDRESS_DOMAIN -> host = new String(readBytes(in, readByte(in)), StandardCharsets.ISO_8859_1);
                    default -> {
                        reply(out, REPLY_ADDRESS_NOT_SUPPORTED);
                        return;
                    }
                }
                int port = (readByte(in) << 8) | readByte(in);
                if (command != COMMAND_CONNECT) {
                    reply(out, REPLY_COMMAND_NOT_SUPPORTED);
                    return;
                }

                InetAddress[] addresses;
                try {
                    addresses = guard.permit(host);
                } catch (UnknownHostException e) {
                    Console.debug("BrowserAPI: o proxy de saída não resolveu %s (%s).", host, e.getMessage());
                    reply(out, REPLY_HOST_UNREACHABLE);
                    return;
                } catch (IOException e) {
                    Console.debug("BrowserAPI: o proxy de saída recusou %s:%d. %s", host, port, e.getMessage());
                    reply(out, REPLY_NOT_ALLOWED);
                    return;
                }
                target = connect(addresses, port);
                if (target == null) {
                    reply(out, REPLY_CONNECTION_REFUSED);
                    return;
                }
                sockets.add(target);
                if (closed) return;
                reply(out, REPLY_SUCCEEDED);
                relay(client, target);
            } catch (IOException | RuntimeException e) {
                // a conexão caiu no meio do caminho: não há a quem avisar
            } finally {
                closeQuietly(client);
                closeQuietly(target);
                sockets.remove(client);
                if (target != null) sockets.remove(target);
                tunnels.release();
            }
        }

        /** Saudação SOCKS5: só o método "sem autenticação" (o único que o Chromium usa). */
        private static boolean negotiate(InputStream in, OutputStream out) throws IOException {
            if (readByte(in) != SOCKS_VERSION) return false;
            byte[] methods = readBytes(in, readByte(in));
            boolean noAuth = false;
            for (byte method : methods) {
                if ((method & 0xff) == METHOD_NO_AUTH) noAuth = true;
            }
            out.write(new byte[] {SOCKS_VERSION, (byte) (noAuth ? METHOD_NO_AUTH : METHOD_NONE_ACCEPTABLE)});
            out.flush();
            return noAuth;
        }

        /** Conecta nos endereços já conferidos, na ordem, sem consultar o DNS de novo. */
        private static Socket connect(InetAddress[] addresses, int port) {
            int attempts = Math.min(addresses.length, MAX_CONNECT_ATTEMPTS);
            for (int i = 0; i < attempts; i++) {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(addresses[i], port), CONNECT_TIMEOUT_MS);
                    return socket;
                } catch (IOException | IllegalArgumentException e) {
                    closeQuietly(socket);
                }
            }
            return null;
        }

        /**
         * Repassa bytes nos dois sentidos: a subida (navegador → destino) em outra thread, a
         * descida (destino → navegador) nesta. Quando o destino encerra, espera a subida terminar
         * antes de o {@code finally} de {@link #serve(Socket)} fechar o navegador: fechar um
         * socket com dados ainda por ler manda RST, e o RST pode descartar o fim da resposta.
         */
        private void relay(Socket client, Socket target) throws IOException {
            client.setSoTimeout(IDLE_TIMEOUT_MS);
            target.setSoTimeout(IDLE_TIMEOUT_MS);
            target.setTcpNoDelay(true);
            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            Future<?> upstream;
            try {
                upstream = workers.submit(() -> pump(client, target, lastActivity));
            } catch (RejectedExecutionException e) {
                return; // sem thread para a subida: o túnel é encerrado
            }
            pump(target, client, lastActivity);
            try {
                upstream.get(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // fecha assim mesmo, no finally de serve
            }
        }

        /**
         * Repassa bytes num sentido. O fim da leitura vira FIN do outro lado, depois de todos os
         * bytes; erro ou ociosidade nos dois sentidos encerram o túnel inteiro.
         */
        private static void pump(Socket from, Socket to, AtomicLong lastActivity) {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                while (true) {
                    int read;
                    try {
                        read = in.read(buffer);
                    } catch (SocketTimeoutException quiet) {
                        // Só este sentido parou; se o outro ainda trafega, o túnel está vivo
                        if (System.nanoTime() - lastActivity.get() < IDLE_TIMEOUT_NANOS) continue;
                        throw quiet;
                    }
                    if (read < 0) {
                        to.shutdownOutput();
                        return;
                    }
                    lastActivity.set(System.nanoTime());
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private static void reply(OutputStream out, int code) throws IOException {
            out.write(new byte[] {SOCKS_VERSION, (byte) code, 0, ADDRESS_IPV4, 0, 0, 0, 0, 0, 0});
            out.flush();
        }

        private static int readByte(InputStream in) throws IOException {
            int value = in.read();
            if (value < 0) throw new EOFException();
            return value;
        }

        private static byte[] readBytes(InputStream in, int count) throws IOException {
            byte[] bytes = in.readNBytes(count);
            if (bytes.length < count) throw new EOFException();
            return bytes;
        }

        private static void closeQuietly(Closeable closeable) {
            if (closeable == null) return;
            try {
                closeable.close();
            } catch (IOException e) {
                // já fechado ou sem conexão: nada a fazer
            }
        }
    }

    /**
     * Fábrica de threads daemon (não seguram o desligamento da JVM) e nomeadas com um prefixo e
     * um número, para aparecerem com clareza em dumps de thread.
     *
     * @param prefix     início do nome de cada thread
     * @param stackBytes tamanho de pilha sugerido; {@code 0} usa o padrão da JVM
     */
    private static ThreadFactory daemonThreads(String prefix, long stackBytes) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(null, runnable, prefix + counter.getAndIncrement(), stackBytes);
            thread.setDaemon(true);
            return thread;
        };
    }
}
