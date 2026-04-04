package br.com.angatusistemas.lib.browser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;

import br.com.angatusistemas.lib.console.Console;

/**
 * [PT] Classe utilitária para automação de navegador e manipulação de HTML.
 * <p>
 * Fornece captura de tela otimizada (via URL ou HTML bruto) com configurações
 * padrão de alta performance (bloqueio de imagens, viewport 1920x1080, timeouts
 * curtos). Também inclui web scraping, execução de JavaScript, minificação,
 * extração de links, imagens, metatags, etc.
 * </p>
 * <p>
 * <b>Uso básico:</b>
 * 
 * <pre>
 * BufferedImage img = BrowserAPI.captureScreenshot("https://example.com");
 * BrowserAPI.captureScreenshotToFile("https://example.com", "foto.png");
 *
 * String html = "&lt;html&gt;&lt;body&gt;&lt;h1&gt;Olá&lt;/h1&gt;&lt;/body&gt;&lt;/html&gt;";
 * BufferedImage img2 = BrowserAPI.captureScreenshotFromHtml(html);
 * </pre>
 * </p>
 *
 * [EN] Utility class for browser automation and HTML manipulation.
 * <p>
 * Provides optimized screenshot capture (via URL or raw HTML) with
 * high‑performance default settings (block images, viewport 1920x1080, short
 * timeouts). Also includes web scraping, JavaScript execution, minification,
 * link/image/meta extraction, etc.
 * </p>
 * <p>
 * <b>Basic usage:</b>
 * 
 * <pre>
 * BufferedImage img = BrowserAPI.captureScreenshot("https://example.com");
 * BrowserAPI.captureScreenshotToFile("https://example.com", "foto.png");
 *
 * String html = "&lt;html&gt;&lt;body&gt;&lt;h1&gt;Hello&lt;/h1&gt;&lt;/body&gt;&lt;/html&gt;";
 * BufferedImage img2 = BrowserAPI.captureScreenshotFromHtml(html);
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://playwright.dev/java/">Playwright Java</a>
 */
public final class BrowserAPI {

    // ==================== CONFIGURAÇÕES PADRÃO (OTIMIZADAS) ====================
    private static final int BROWSER_POOL_SIZE = 2;
    private static final int PAGE_TIMEOUT_MS = 10000;
    private static final int SCREENSHOT_TIMEOUT_MS = 8000;
    private static final int DEFAULT_VIEWPORT_WIDTH = 1920;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 1080;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Configurações padrão de bloqueio e espera
    private static final boolean DEFAULT_BLOCK_IMAGES = false;
    private static final boolean DEFAULT_BLOCK_CSS = false;
    private static final boolean DEFAULT_WAIT_NETWORK_IDLE = true;
    private static final boolean DEFAULT_WAIT_IMAGES = true; // Aguarda imagens carregarem

    // Pool de navegadores
    private static final Queue<Playwright> PLAYWRIGHT_POOL = new ConcurrentLinkedQueue<>();
    private static final Queue<Browser> BROWSER_POOL = new ConcurrentLinkedQueue<>();
    private static boolean poolInitialized = false;

    private BrowserAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== INICIALIZAÇÃO DO POOL ====================

    /**
     * [PT] Inicializa o pool de navegadores. Chamada automática na primeira utilização.
     * <p>
     * Cria um número fixo de instâncias do Chromium headless para reutilização,
     * reduzindo a latência de inicialização para capturas subsequentes.
     * </p>
     *
     * [EN] Initializes the browser pool. Automatically called on first use.
     * <p>
     * Creates a fixed number of headless Chromium instances for reuse,
     * reducing startup latency for subsequent captures.
     * </p>
     */
    public static synchronized void initPool() {
        if (poolInitialized)
            return;
        for (int i = 0; i < BROWSER_POOL_SIZE; i++) {
            Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(true)
                            .setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox", "--disable-gpu",
                                    "--disable-extensions", "--disable-background-timer-throttling",
                                    "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding")));
            PLAYWRIGHT_POOL.offer(playwright);
            BROWSER_POOL.offer(browser);
        }
        poolInitialized = true;
        Console.log("Browser pool inicializado com %d instâncias", BROWSER_POOL_SIZE);
    }

    private static Browser getBrowser() {
        if (!poolInitialized)
            initPool();
        Browser browser = BROWSER_POOL.poll();
        if (browser == null || !browser.isConnected()) {
            Playwright playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }
        return browser;
    }

    private static void returnBrowser(Browser browser) {
        if (browser != null && browser.isConnected()) {
            BROWSER_POOL.offer(browser);
        }
    }

    // ==================== MÉTODOS SIMPLIFICADOS (APENAS URL/HTML) ====================

    /**
     * [PT] Captura screenshot de uma URL usando configurações otimizadas padrão.
     * <p>
     * Configurações padrão: viewport 1920x1080, aguarda rede ociosa e imagens carregarem,
     * não bloqueia recursos (imagens/CSS), captura página inteira.
     * </p>
     *
     * [EN] Captures screenshot from a URL using default optimized settings.
     * <p>
     * Default settings: viewport 1920x1080, waits for network idle and images to load,
     * does not block resources (images/CSS), captures full page.
     * </p>
     *
     * @param url [PT] URL da página a ser capturada
     *            [EN] URL of the page to capture
     * @return [PT] imagem capturada como BufferedImage
     *         [EN] captured image as BufferedImage
     * @throws IOException [PT] se ocorrer erro na captura
     *                     [EN] if capture fails
     */
    public static BufferedImage captureScreenshot(String url) throws IOException {
        return captureScreenshotFromUrl(url, createDefaultScreenshotOptions());
    }

    /**
     * [PT] Captura screenshot de uma string HTML usando configurações otimizadas padrão.
     *
     * [EN] Captures screenshot from an HTML string using default optimized settings.
     *
     * @param html [PT] código HTML a ser renderizado
     *             [EN] HTML code to render
     * @return [PT] imagem capturada como BufferedImage
     *         [EN] captured image as BufferedImage
     * @throws IOException [PT] se ocorrer erro na captura
     *                     [EN] if capture fails
     */
    public static BufferedImage captureScreenshotFromHtml(String html) throws IOException {
        return captureScreenshotFromHtml(html, createDefaultScreenshotOptions());
    }

    /**
     * [PT] Captura screenshot de uma URL e salva diretamente em arquivo PNG.
     *
     * [EN] Captures screenshot from a URL and saves directly to a PNG file.
     *
     * @param url        [PT] URL da página
     *                   [EN] page URL
     * @param outputPath [PT] caminho do arquivo de saída (ex: "screenshot.png")
     *                   [EN] output file path (e.g., "screenshot.png")
     * @throws IOException [PT] se ocorrer erro na captura ou escrita
     *                     [EN] if capture or write fails
     */
    public static void captureScreenshotToFile(String url, String outputPath) throws IOException {
        BufferedImage img = captureScreenshot(url);
        ImageIO.write(img, "png", new File(outputPath));
    }

    /**
     * [PT] Captura screenshot de uma string HTML e salva diretamente em arquivo PNG.
     *
     * [EN] Captures screenshot from an HTML string and saves directly to a PNG file.
     *
     * @param html       [PT] código HTML
     *                   [EN] HTML code
     * @param outputPath [PT] caminho do arquivo de saída
     *                   [EN] output file path
     * @throws IOException [PT] se ocorrer erro na captura ou escrita
     *                     [EN] if capture or write fails
     */
    public static void captureScreenshotFromHtmlToFile(String html, String outputPath) throws IOException {
        BufferedImage img = captureScreenshotFromHtml(html);
        ImageIO.write(img, "png", new File(outputPath));
    }

    // ==================== MÉTODOS AVANÇADOS (COM OPÇÕES) ====================

    /**
     * [PT] Captura screenshot de uma URL com opções personalizadas.
     *
     * [EN] Captures screenshot from a URL with custom options.
     *
     * @param url     [PT] URL da página
     *                [EN] page URL
     * @param options [PT] opções de captura (viewport, bloqueio, qualidade, etc.)
     *                [EN] capture options (viewport, blocking, quality, etc.)
     * @return [PT] imagem capturada
     *         [EN] captured image
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static BufferedImage captureScreenshotFromUrl(String url, ScreenshotOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            byte[] bytes = page.screenshot(createScreenshotOptions(options));
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IOException("Failed to capture screenshot: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Captura screenshot de uma string HTML com opções personalizadas.
     *
     * [EN] Captures screenshot from an HTML string with custom options.
     *
     * @param html    [PT] código HTML
     *                [EN] HTML code
     * @param options [PT] opções de captura
     *                [EN] capture options
     * @return [PT] imagem capturada
     *         [EN] captured image
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static BufferedImage captureScreenshotFromHtml(String html, ScreenshotOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            byte[] bytes = page.screenshot(createScreenshotOptions(options));
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IOException("Failed to capture screenshot from HTML: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    // ==================== WEB SCRAPING SIMPLIFICADO ====================

    /**
     * [PT] Obtém o HTML completo de uma URL (após carregamento dinâmico).
     *
     * [EN] Gets the full HTML of a URL (after dynamic loading).
     *
     * @param url [PT] URL da página
     *            [EN] page URL
     * @return [PT] conteúdo HTML da página
     *         [EN] page HTML content
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String getPageHtml(String url) throws IOException {
        return getPageHtml(url, createDefaultScrapeOptions());
    }

    /**
     * [PT] Processa uma string HTML e retorna o HTML após renderização (útil para SPAs).
     *
     * [EN] Processes an HTML string and returns the rendered HTML (useful for SPAs).
     *
     * @param html [PT] código HTML original
     *             [EN] original HTML code
     * @return [PT] HTML renderizado
     *         [EN] rendered HTML
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String getPageHtmlFromHtml(String html) throws IOException {
        return getPageHtmlFromHtml(html, createDefaultScrapeOptions());
    }

    /**
     * [PT] Extrai texto de um elemento CSS a partir de uma URL.
     *
     * [EN] Extracts text from a CSS element from a URL.
     *
     * @param url      [PT] URL da página
     *                 [EN] page URL
     * @param selector [PT] seletor CSS (ex: "h1", ".title", "#main")
     *                 [EN] CSS selector (e.g., "h1", ".title", "#main")
     * @return [PT] texto do elemento ou null se não encontrado
     *         [EN] element text or null if not found
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String extractText(String url, String selector) throws IOException {
        return extractText(url, selector, createDefaultScrapeOptions());
    }

    /**
     * [PT] Extrai texto de um elemento CSS a partir de uma string HTML.
     *
     * [EN] Extracts text from a CSS element from an HTML string.
     *
     * @param html     [PT] código HTML
     *                 [EN] HTML code
     * @param selector [PT] seletor CSS
     *                 [EN] CSS selector
     * @return [PT] texto do elemento ou null
     *         [EN] element text or null
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String extractTextFromHtml(String html, String selector) throws IOException {
        return extractTextFromHtml(html, selector, createDefaultScrapeOptions());
    }

    /**
     * [PT] Extrai dados de uma tabela HTML a partir de uma URL.
     *
     * [EN] Extracts data from an HTML table from a URL.
     *
     * @param url           [PT] URL da página
     *                      [EN] page URL
     * @param tableSelector [PT] seletor da tabela (ex: "table", "#data-table")
     *                      [EN] table selector (e.g., "table", "#data-table")
     * @return [PT] lista de linhas, cada linha é uma lista de strings (células)
     *         [EN] list of rows, each row is a list of strings (cells)
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static List<List<String>> extractTableData(String url, String tableSelector) throws IOException {
        return extractTableData(url, tableSelector, createDefaultScrapeOptions());
    }

    /**
     * [PT] Extrai múltiplos campos via seletores CSS a partir de uma URL.
     *
     * [EN] Extracts multiple fields via CSS selectors from a URL.
     *
     * @param url       [PT] URL da página
     *                  [EN] page URL
     * @param selectors [PT] mapa (nome do campo -> seletor CSS)
     *                  [EN] map (field name -> CSS selector)
     * @return [PT] mapa com os valores extraídos (null se o seletor não for encontrado)
     *         [EN] map with extracted values (null if selector not found)
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors) throws IOException {
        return extractMultiple(url, selectors, createDefaultScrapeOptions());
    }

    /**
     * [PT] Executa JavaScript em uma URL e retorna o resultado.
     *
     * [EN] Executes JavaScript on a URL and returns the result.
     *
     * @param url    [PT] URL da página
     *               [EN] page URL
     * @param script [PT] código JavaScript a ser executado
     *               [EN] JavaScript code to execute
     * @return [PT] resultado da execução (pode ser String, Number, Boolean, etc.)
     *         [EN] execution result (may be String, Number, Boolean, etc.)
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static Object evaluateJavaScript(String url, String script) throws IOException {
        return evaluateJavaScript(url, script, createDefaultScrapeOptions());
    }

    /**
     * [PT] Executa JavaScript em uma string HTML e retorna o resultado.
     *
     * [EN] Executes JavaScript on an HTML string and returns the result.
     *
     * @param html   [PT] código HTML
     *               [EN] HTML code
     * @param script [PT] código JavaScript
     *               [EN] JavaScript code
     * @return [PT] resultado da execução
     *         [EN] execution result
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static Object evaluateJavaScriptOnHtml(String html, String script) throws IOException {
        return evaluateJavaScriptOnHtml(html, script, createDefaultScrapeOptions());
    }

    // ==================== MÉTODOS AVANÇADOS DE SCRAPING (COM OPÇÕES) ====================

    /**
     * [PT] Obtém o HTML completo de uma URL com opções personalizadas.
     *
     * [EN] Gets the full HTML of a URL with custom options.
     *
     * @param url     [PT] URL da página
     *                [EN] page URL
     * @param options [PT] opções de scraping
     *                [EN] scraping options
     * @return [PT] conteúdo HTML
     *         [EN] HTML content
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String getPageHtml(String url, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            return page.content();
        } catch (Exception e) {
            throw new IOException("Failed to get HTML: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Processa uma string HTML com opções personalizadas.
     *
     * [EN] Processes an HTML string with custom options.
     *
     * @param html    [PT] código HTML
     *                [EN] HTML code
     * @param options [PT] opções de scraping
     *                [EN] scraping options
     * @return [PT] HTML renderizado
     *         [EN] rendered HTML
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String getPageHtmlFromHtml(String html, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            return page.content();
        } catch (Exception e) {
            throw new IOException("Failed to process HTML: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Extrai texto de um elemento CSS a partir de uma URL com opções personalizadas.
     *
     * [EN] Extracts text from a CSS element from a URL with custom options.
     *
     * @param url      [PT] URL da página
     *                 [EN] page URL
     * @param selector [PT] seletor CSS
     *                 [EN] CSS selector
     * @param options  [PT] opções de scraping
     *                 [EN] scraping options
     * @return [PT] texto do elemento
     *         [EN] element text
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String extractText(String url, String selector, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(5000));
            return page.textContent(selector);
        } catch (Exception e) {
            throw new IOException("Failed to extract text: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Extrai texto de um elemento CSS a partir de uma string HTML com opções personalizadas.
     *
     * [EN] Extracts text from a CSS element from an HTML string with custom options.
     *
     * @param html     [PT] código HTML
     *                 [EN] HTML code
     * @param selector [PT] seletor CSS
     *                 [EN] CSS selector
     * @param options  [PT] opções de scraping
     *                 [EN] scraping options
     * @return [PT] texto do elemento
     *         [EN] element text
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static String extractTextFromHtml(String html, String selector, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(5000));
            return page.textContent(selector);
        } catch (Exception e) {
            throw new IOException("Failed to extract text from HTML: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Extrai dados de uma tabela HTML a partir de uma URL com opções personalizadas.
     *
     * [EN] Extracts data from an HTML table from a URL with custom options.
     *
     * @param url           [PT] URL da página
     *                      [EN] page URL
     * @param tableSelector [PT] seletor da tabela
     *                      [EN] table selector
     * @param options       [PT] opções de scraping
     *                      [EN] scraping options
     * @return [PT] dados da tabela
     *         [EN] table data
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static List<List<String>> extractTableData(String url, String tableSelector, ScrapeOptions options)
            throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            page.waitForSelector(tableSelector, new Page.WaitForSelectorOptions().setTimeout(5000));
            List<ElementHandle> rows = page.querySelectorAll(tableSelector + " tr");
            List<List<String>> data = new ArrayList<>();
            for (ElementHandle row : rows) {
                List<ElementHandle> cells = row.querySelectorAll("td, th");
                if (cells.isEmpty())
                    continue;
                List<String> rowData = new ArrayList<>();
                for (ElementHandle cell : cells)
                    rowData.add(cell.textContent());
                data.add(rowData);
            }
            return data;
        } catch (Exception e) {
            throw new IOException("Failed to extract table data: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Extrai múltiplos campos via seletores CSS com opções personalizadas.
     *
     * [EN] Extracts multiple fields via CSS selectors with custom options.
     *
     * @param url       [PT] URL da página
     *                  [EN] page URL
     * @param selectors [PT] mapa (nome -> seletor)
     *                  [EN] map (name -> selector)
     * @param options   [PT] opções de scraping
     *                  [EN] scraping options
     * @return [PT] mapa com os valores extraídos
     *         [EN] map with extracted values
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors, ScrapeOptions options)
            throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            Map<String, String> results = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : selectors.entrySet()) {
                try {
                    page.waitForSelector(entry.getValue(), new Page.WaitForSelectorOptions().setTimeout(3000));
                    results.put(entry.getKey(), page.textContent(entry.getValue()));
                } catch (Exception e) {
                    results.put(entry.getKey(), null);
                }
            }
            return results;
        } catch (Exception e) {
            throw new IOException("Failed to extract multiple: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Executa JavaScript em uma URL com opções personalizadas.
     *
     * [EN] Executes JavaScript on a URL with custom options.
     *
     * @param url     [PT] URL da página
     *                [EN] page URL
     * @param script  [PT] código JavaScript
     *                [EN] JavaScript code
     * @param options [PT] opções de scraping
     *                [EN] scraping options
     * @return [PT] resultado da execução
     *         [EN] execution result
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static Object evaluateJavaScript(String url, String script, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePage(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            return page.evaluate(script);
        } catch (Exception e) {
            throw new IOException("Failed to evaluate JS: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Executa JavaScript em uma string HTML com opções personalizadas.
     *
     * [EN] Executes JavaScript on an HTML string with custom options.
     *
     * @param html    [PT] código HTML
     *                [EN] HTML code
     * @param script  [PT] código JavaScript
     *                [EN] JavaScript code
     * @param options [PT] opções de scraping
     *                [EN] scraping options
     * @return [PT] resultado da execução
     *         [EN] execution result
     * @throws IOException [PT] se ocorrer erro
     *                     [EN] if error occurs
     */
    public static Object evaluateJavaScriptOnHtml(String html, String script, ScrapeOptions options)
            throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForPageReady(page, options.waitForImages, options.waitForNetworkIdle);
            return page.evaluate(script);
        } catch (Exception e) {
            throw new IOException("Failed to evaluate JS on HTML: " + e.getMessage(), e);
        } finally {
            if (page != null)
                page.close();
            returnBrowser(browser);
        }
    }

    // ==================== UTILITÁRIOS DE MANIPULAÇÃO DE HTML (puros) ====================

    /**
     * [PT] Minifica HTML: remove comentários, espaços redundantes e quebras de linha.
     * [EN] Minifies HTML: removes comments, redundant spaces and line breaks.
     *
     * @param html [PT] código HTML original
     *             [EN] original HTML code
     * @return [PT] HTML minificado
     *         [EN] minified HTML
     */
    public static String minifyHtml(String html) {
        if (html == null)
            return null;
        String noComments = html.replaceAll("<!--.*?-->", "");
        String minified = noComments.replaceAll(">\\s+<", "><");
        minified = minified.replaceAll("\\s+", " ");
        return minified.trim();
    }

    /**
     * [PT] Formata/indenta HTML de forma simples (adiciona quebras de linha entre tags).
     * [EN] Pretty-prints HTML (adds line breaks between tags).
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] HTML indentado
     *         [EN] indented HTML
     */
    public static String prettyPrintHtml(String html) {
        return html.replaceAll("><", ">\n<");
    }

    /**
     * [PT] Extrai todas as URLs de links (&lt;a href="..."&gt;) do HTML.
     * [EN] Extracts all link URLs (&lt;a href="..."&gt;) from HTML.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] lista de URLs
     *         [EN] list of URLs
     */
    public static List<String> extractLinks(String html) {
        List<String> links = new ArrayList<>();
        Pattern p = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find())
            links.add(m.group(1));
        return links;
    }

    /**
     * [PT] Extrai todas as URLs de imagens (&lt;img src="..."&gt;) do HTML.
     * [EN] Extracts all image URLs (&lt;img src="..."&gt;) from HTML.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] lista de URLs de imagens
     *         [EN] list of image URLs
     */
    public static List<String> extractImageUrls(String html) {
        List<String> urls = new ArrayList<>();
        Pattern p = Pattern.compile("<img\\s+[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find())
            urls.add(m.group(1));
        return urls;
    }

    /**
     * [PT] Extrai metatags (name/content) do HTML.
     * [EN] Extracts meta tags (name/content) from HTML.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] mapa (nome da meta -> conteúdo)
     *         [EN] map (meta name -> content)
     */
    public static Map<String, String> extractMetaTags(String html) {
        Map<String, String> metas = new HashMap<>();
        Pattern p = Pattern.compile(
                "<meta\\s+[^>]*name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find())
            metas.put(m.group(1), m.group(2));
        return metas;
    }

    /**
     * [PT] Extrai o título da página.
     * [EN] Extracts the page title.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] título da página ou null se não encontrado
     *         [EN] page title or null if not found
     */
    public static String extractTitle(String html) {
        Pattern p = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /**
     * [PT] Remove todas as tags HTML, retornando apenas o texto puro.
     * [EN] Strips all HTML tags, returning only plain text.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] texto puro
     *         [EN] plain text
     */
    public static String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", "").trim();
    }

    /**
     * [PT] Valida se a string parece ser um HTML válido (verifica tags básicas).
     * [EN] Validates if the string appears to be valid HTML (checks basic tags).
     *
     * @param html [PT] string a validar
     *             [EN] string to validate
     * @return [PT] true se parece HTML
     *         [EN] true if looks like HTML
     */
    public static boolean isValidHtml(String html) {
        return html != null && (html.contains("<html") || html.contains("<body") || html.contains("<div"));
    }

    /**
     * [PT] Extrai todas as classes CSS usadas no HTML.
     * [EN] Extracts all CSS classes used in the HTML.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] conjunto de nomes de classes
     *         [EN] set of class names
     */
    public static Set<String> extractCssClasses(String html) {
        Set<String> classes = new HashSet<>();
        Pattern p = Pattern.compile("class\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            for (String cls : m.group(1).split("\\s+"))
                classes.add(cls);
        }
        return classes;
    }

    /**
     * [PT] Extrai todos os IDs usados no HTML.
     * [EN] Extracts all IDs used in the HTML.
     *
     * @param html [PT] código HTML
     *             [EN] HTML code
     * @return [PT] conjunto de IDs
     *         [EN] set of IDs
     */
    public static Set<String> extractIds(String html) {
        Set<String> ids = new HashSet<>();
        Pattern p = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find())
            ids.add(m.group(1));
        return ids;
    }

    /**
     * [PT] Converte URLs relativas em absolutas com base em uma URL base.
     * [EN] Converts relative URLs to absolute based on a base URL.
     *
     * @param html    [PT] código HTML
     *                [EN] HTML code
     * @param baseUrl [PT] URL base (ex: "https://example.com")
     *                [EN] base URL (e.g., "https://example.com")
     * @return [PT] HTML com URLs absolutas
     *         [EN] HTML with absolute URLs
     */
    public static String absolutizeUrls(String html, String baseUrl) {
        return html.replaceAll("(src|href)=\"/([^\"]+)\"", "$1=\"" + baseUrl + "/$2\"");
    }

    // ==================== MÉTODOS PRIVADOS DE CRIAÇÃO DE OPÇÕES PADRÃO ====================

    private static ScreenshotOptions createDefaultScreenshotOptions() {
        ScreenshotOptions opts = new ScreenshotOptions();
        opts.viewportWidth = DEFAULT_VIEWPORT_WIDTH;
        opts.viewportHeight = DEFAULT_VIEWPORT_HEIGHT;
        opts.blockImages = DEFAULT_BLOCK_IMAGES;
        opts.blockCss = DEFAULT_BLOCK_CSS;
        opts.waitForNetworkIdle = DEFAULT_WAIT_NETWORK_IDLE;
        opts.waitForImages = DEFAULT_WAIT_IMAGES;
        opts.fullPage = true;
        return opts;
    }

    private static ScrapeOptions createDefaultScrapeOptions() {
        ScrapeOptions opts = new ScrapeOptions();
        opts.viewportWidth = DEFAULT_VIEWPORT_WIDTH;
        opts.viewportHeight = DEFAULT_VIEWPORT_HEIGHT;
        opts.blockImages = DEFAULT_BLOCK_IMAGES;
        opts.blockCss = DEFAULT_BLOCK_CSS;
        opts.waitForNetworkIdle = DEFAULT_WAIT_NETWORK_IDLE;
        opts.waitForImages = DEFAULT_WAIT_IMAGES;
        return opts;
    }

    private static Browser.NewPageOptions createPageOptions(BaseBrowserOptions options) {
        return new Browser.NewPageOptions().setViewportSize(options.viewportWidth, options.viewportHeight)
                .setUserAgent(options.userAgent != null ? options.userAgent : DEFAULT_USER_AGENT);
    }

    private static void configurePage(Page page, BaseBrowserOptions options) {
        if (options.blockImages)
            blockImages(page);
        if (options.blockCss)
            blockCss(page);
        if (options.extraHeaders != null && !options.extraHeaders.isEmpty()) {
            page.setExtraHTTPHeaders(options.extraHeaders);
        }
    }

    private static void blockImages(Page page) {
        page.route("**/*", route -> {
            if ("image".equals(route.request().resourceType()))
                route.abort();
            else
                route.resume();
        });
    }

    private static void blockCss(Page page) {
        page.route("**/*", route -> {
            if ("stylesheet".equals(route.request().resourceType()))
                route.abort();
            else
                route.resume();
        });
    }

    private static void waitForPageReady(Page page, boolean waitForImages, boolean waitForNetworkIdle) {
        if (waitForNetworkIdle) {
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000.0));
            } catch (Exception ignored) {
            }
        }
        if (waitForImages) {
            try {
                page.waitForFunction("() => Array.from(document.images).every(img => img.complete)",
                        new Page.WaitForFunctionOptions().setTimeout(3000.0));
            } catch (Exception ignored) {
            }
        }
    }

    private static Page.ScreenshotOptions createScreenshotOptions(ScreenshotOptions options) {
        Page.ScreenshotOptions opts = new Page.ScreenshotOptions().setType(ScreenshotType.PNG)
                .setTimeout(SCREENSHOT_TIMEOUT_MS);
        if (options.fullPage)
            opts.setFullPage(true);
        if (options.quality != null && options.quality > 0 && options.quality <= 100)
            opts.setQuality(options.quality);
        if (options.clipX != null && options.clipY != null && options.clipWidth != null && options.clipHeight != null) {
            opts.setClip(options.clipX, options.clipY, options.clipWidth, options.clipHeight);
        }
        return opts;
    }

    // ==================== SHUTDOWN ====================

    /**
     * [PT] Encerra todos os navegadores e o pool. Chamar ao desligar a aplicação.
     * <p>
     * Fecha todas as instâncias do Playwright e libera os recursos associados.
     * </p>
     *
     * [EN] Shuts down all browsers and the pool. Call on application shutdown.
     * <p>
     * Closes all Playwright instances and releases associated resources.
     * </p>
     */
    public static void shutdown() {
        while (!BROWSER_POOL.isEmpty()) {
            Browser b = BROWSER_POOL.poll();
            if (b != null)
                b.close();
        }
        while (!PLAYWRIGHT_POOL.isEmpty()) {
            Playwright p = PLAYWRIGHT_POOL.poll();
            if (p != null)
                p.close();
        }
        Console.log("BrowserAPI finalizada.");
    }

}