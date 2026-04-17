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
 * padrão de alta qualidade (viewport 1920x1080, aguarda carregamento completo,
 * captura página inteira). Também inclui web scraping, execução de JavaScript,
 * minificação, extração de links, imagens, metatags, etc.
 * </p>
 * <p>
 * <b>Uso básico:</b>
 * 
 * <pre>
 * // Screenshot da página inteira a partir de HTML
 * BufferedImage img = BrowserAPI.captureFullPageScreenshotFromHtml(html);
 * BrowserAPI.captureFullPageScreenshotFromHtmlToFile(html, "foto.png");
 * 
 * // Screenshot a partir de URL
 * BufferedImage img2 = BrowserAPI.captureFullPageScreenshot("https://example.com");
 * </pre>
 * </p>
 *
 * [EN] Utility class for browser automation and HTML manipulation.
 * <p>
 * Provides optimized screenshot capture (via URL or raw HTML) with high quality
 * default settings (viewport 1920x1080, waits for full load, captures full page).
 * Also includes web scraping, JavaScript execution, minification,
 * link/image/meta extraction, etc.
 * </p>
 * <p>
 * <b>Basic usage:</b>
 * 
 * <pre>
 * // Full page screenshot from HTML
 * BufferedImage img = BrowserAPI.captureFullPageScreenshotFromHtml(html);
 * BrowserAPI.captureFullPageScreenshotFromHtmlToFile(html, "photo.png");
 * 
 * // Screenshot from URL
 * BufferedImage img2 = BrowserAPI.captureFullPageScreenshot("https://example.com");
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://playwright.dev/java/">Playwright Java</a>
 */
public final class BrowserAPI {

    // ==================== CONFIGURAÇÕES PADRÃO ====================
    private static final int BROWSER_POOL_SIZE = 2;
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final int SCREENSHOT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_VIEWPORT_WIDTH = 1920;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 1080;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Configurações padrão de espera (máxima qualidade)
    private static final boolean DEFAULT_WAIT_NETWORK_IDLE = true;
    private static final boolean DEFAULT_WAIT_IMAGES = true;
    private static final boolean DEFAULT_FULL_PAGE = true;

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
     *
     * [EN] Initializes the browser pool. Automatically called on first use.
     */
    public static synchronized void initPool() {
        if (poolInitialized) return;
        for (int i = 0; i < BROWSER_POOL_SIZE; i++) {
            Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(List.of(
                                    "--disable-dev-shm-usage",
                                    "--no-sandbox",
                                    "--disable-gpu",
                                    "--disable-extensions",
                                    "--disable-background-timer-throttling",
                                    "--disable-backgrounding-occluded-windows",
                                    "--disable-renderer-backgrounding",
                                    "--font-render-hinting=none")));
            PLAYWRIGHT_POOL.offer(playwright);
            BROWSER_POOL.offer(browser);
        }
        poolInitialized = true;
        Console.log("Browser pool inicializado com %d instâncias", BROWSER_POOL_SIZE);
    }

    private static Browser getBrowser() {
        if (!poolInitialized) initPool();
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

    // ==================== MÉTODOS PRINCIPAIS - FULL PAGE SCREENSHOT ====================

    /**
     * [PT] Captura screenshot da página inteira a partir de uma URL (alta qualidade).
     *
     * [EN] Captures full page screenshot from a URL (high quality).
     *
     * @param url [PT] URL da página
     * @return [PT] imagem capturada
     * @throws IOException [PT] se ocorrer erro
     */
    public static BufferedImage captureFullPageScreenshot(String url) throws IOException {
        return captureFullPageScreenshotFromUrl(url, createHighQualityOptions());
    }

    /**
     * [PT] Captura screenshot da página inteira a partir de uma string HTML (alta qualidade).
     *
     * [EN] Captures full page screenshot from an HTML string (high quality).
     *
     * @param html [PT] código HTML
     * @return [PT] imagem capturada
     * @throws IOException [PT] se ocorrer erro
     */
    public static BufferedImage captureFullPageScreenshotFromHtml(String html) throws IOException {
        return captureFullPageScreenshotFromHtml(html, createHighQualityOptions());
    }

    /**
     * [PT] Captura screenshot da página inteira a partir de uma URL e salva em arquivo.
     *
     * [EN] Captures full page screenshot from a URL and saves to file.
     *
     * @param url        [PT] URL da página
     * @param outputPath [PT] caminho de saída
     * @throws IOException [PT] se ocorrer erro
     */
    public static void captureFullPageScreenshotToFile(String url, String outputPath) throws IOException {
        BufferedImage img = captureFullPageScreenshot(url);
        ImageIO.write(img, "png", new File(outputPath));
    }

    /**
     * [PT] Captura screenshot da página inteira a partir de um HTML e salva em arquivo.
     *
     * [EN] Captures full page screenshot from an HTML string and saves to file.
     *
     * @param html       [PT] código HTML
     * @param outputPath [PT] caminho de saída
     * @throws IOException [PT] se ocorrer erro
     */
    public static void captureFullPageScreenshotFromHtmlToFile(String html, String outputPath) throws IOException {
        BufferedImage img = captureFullPageScreenshotFromHtml(html);
        ImageIO.write(img, "png", new File(outputPath));
    }

    // ==================== MÉTODOS DE CAPTURA COM OPÇÕES ====================

    /**
     * [PT] Captura screenshot da página inteira a partir de uma URL com opções personalizadas.
     *
     * [EN] Captures full page screenshot from a URL with custom options.
     */
    public static BufferedImage captureFullPageScreenshotFromUrl(String url, ScreenshotOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            
            Console.debug("Navegando para: " + url);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            
            waitForFullPageLoad(page);
            
            Console.debug("Capturando screenshot da página inteira...");
            byte[] bytes = page.screenshot(createFullPageScreenshotOptions(options));
            
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            Console.debug("Screenshot capturado com sucesso: %dx%d", image.getWidth(), image.getHeight());
            return image;
            
        } catch (Exception e) {
            throw new IOException("Failed to capture screenshot: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * [PT] Captura screenshot da página inteira a partir de um HTML com opções personalizadas.
     *
     * [EN] Captures full page screenshot from an HTML string with custom options.
     */
    public static BufferedImage captureFullPageScreenshotFromHtml(String html, ScreenshotOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            
            Console.debug("Renderizando HTML...");
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            
            waitForFullPageLoad(page);
            
            Console.debug("Capturando screenshot da página inteira...");
            byte[] bytes = page.screenshot(createFullPageScreenshotOptions(options));
            
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            Console.debug("Screenshot capturado com sucesso: %dx%d", image.getWidth(), image.getHeight());
            return image;
            
        } catch (Exception e) {
            throw new IOException("Failed to capture screenshot from HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    // ==================== MÉTODOS SIMPLIFICADOS (COMPATIBILIDADE) ====================

    public static BufferedImage captureScreenshot(String url) throws IOException {
        return captureFullPageScreenshot(url);
    }

    public static BufferedImage captureScreenshotFromHtml(String html) throws IOException {
        return captureFullPageScreenshotFromHtml(html);
    }

    public static void captureScreenshotToFile(String url, String outputPath) throws IOException {
        captureFullPageScreenshotToFile(url, outputPath);
    }

    public static void captureScreenshotFromHtmlToFile(String html, String outputPath) throws IOException {
        captureFullPageScreenshotFromHtmlToFile(html, outputPath);
    }

    // ==================== WEB SCRAPING ====================

    public static String getPageHtml(String url) throws IOException {
        return getPageHtml(url, createDefaultScrapeOptions());
    }

    public static String getPageHtmlFromHtml(String html) throws IOException {
        return getPageHtmlFromHtml(html, createDefaultScrapeOptions());
    }

    public static String extractText(String url, String selector) throws IOException {
        return extractText(url, selector, createDefaultScrapeOptions());
    }

    public static String extractTextFromHtml(String html, String selector) throws IOException {
        return extractTextFromHtml(html, selector, createDefaultScrapeOptions());
    }

    public static List<List<String>> extractTableData(String url, String tableSelector) throws IOException {
        return extractTableData(url, tableSelector, createDefaultScrapeOptions());
    }

    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors) throws IOException {
        return extractMultiple(url, selectors, createDefaultScrapeOptions());
    }

    public static Object evaluateJavaScript(String url, String script) throws IOException {
        return evaluateJavaScript(url, script, createDefaultScrapeOptions());
    }

    public static Object evaluateJavaScriptOnHtml(String html, String script) throws IOException {
        return evaluateJavaScriptOnHtml(html, script, createDefaultScrapeOptions());
    }

    // ==================== MÉTODOS AVANÇADOS DE SCRAPING ====================

    public static String getPageHtml(String url, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            return page.content();
        } catch (Exception e) {
            throw new IOException("Failed to get HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static String getPageHtmlFromHtml(String html, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            return page.content();
        } catch (Exception e) {
            throw new IOException("Failed to process HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static String extractText(String url, String selector, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(10000));
            return page.textContent(selector);
        } catch (Exception e) {
            throw new IOException("Failed to extract text: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static String extractTextFromHtml(String html, String selector, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(10000));
            return page.textContent(selector);
        } catch (Exception e) {
            throw new IOException("Failed to extract text from HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static List<List<String>> extractTableData(String url, String tableSelector, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            page.waitForSelector(tableSelector, new Page.WaitForSelectorOptions().setTimeout(10000));
            List<ElementHandle> rows = page.querySelectorAll(tableSelector + " tr");
            List<List<String>> data = new ArrayList<>();
            for (ElementHandle row : rows) {
                List<ElementHandle> cells = row.querySelectorAll("td, th");
                if (cells.isEmpty()) continue;
                List<String> rowData = new ArrayList<>();
                for (ElementHandle cell : cells) rowData.add(cell.textContent());
                data.add(rowData);
            }
            return data;
        } catch (Exception e) {
            throw new IOException("Failed to extract table data: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            Map<String, String> results = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : selectors.entrySet()) {
                try {
                    page.waitForSelector(entry.getValue(), new Page.WaitForSelectorOptions().setTimeout(5000));
                    results.put(entry.getKey(), page.textContent(entry.getValue()));
                } catch (Exception e) {
                    results.put(entry.getKey(), null);
                }
            }
            return results;
        } catch (Exception e) {
            throw new IOException("Failed to extract multiple: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static Object evaluateJavaScript(String url, String script, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            return page.evaluate(script);
        } catch (Exception e) {
            throw new IOException("Failed to evaluate JS: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    public static Object evaluateJavaScriptOnHtml(String html, String script, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            return page.evaluate(script);
        } catch (Exception e) {
            throw new IOException("Failed to evaluate JS on HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    // ==================== UTILITÁRIOS DE HTML ====================

    public static String minifyHtml(String html) {
        if (html == null) return null;
        String noComments = html.replaceAll("<!--.*?-->", "");
        String minified = noComments.replaceAll(">\\s+<", "><");
        minified = minified.replaceAll("\\s+", " ");
        return minified.trim();
    }

    public static String prettyPrintHtml(String html) {
        return html.replaceAll("><", ">\n<");
    }

    public static List<String> extractLinks(String html) {
        List<String> links = new ArrayList<>();
        Pattern p = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) links.add(m.group(1));
        return links;
    }

    public static List<String> extractImageUrls(String html) {
        List<String> urls = new ArrayList<>();
        Pattern p = Pattern.compile("<img\\s+[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) urls.add(m.group(1));
        return urls;
    }

    public static Map<String, String> extractMetaTags(String html) {
        Map<String, String> metas = new HashMap<>();
        Pattern p = Pattern.compile("<meta\\s+[^>]*name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) metas.put(m.group(1), m.group(2));
        return metas;
    }

    public static String extractTitle(String html) {
        Pattern p = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    public static String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", "").trim();
    }

    public static boolean isValidHtml(String html) {
        return html != null && (html.contains("<html") || html.contains("<body") || html.contains("<div"));
    }

    public static Set<String> extractCssClasses(String html) {
        Set<String> classes = new HashSet<>();
        Pattern p = Pattern.compile("class\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            for (String cls : m.group(1).split("\\s+")) classes.add(cls);
        }
        return classes;
    }

    public static Set<String> extractIds(String html) {
        Set<String> ids = new HashSet<>();
        Pattern p = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    public static String absolutizeUrls(String html, String baseUrl) {
        return html.replaceAll("(src|href)=\"/([^\"]+)\"", "$1=\"" + baseUrl + "/$2\"");
    }

    // ==================== MÉTODOS PRIVADOS DE CONFIGURAÇÃO ====================

    private static void waitForFullPageLoad(Page page) {
        try {
            // Aguarda o DOM estar completamente carregado
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10000));
            
            // Aguarda a rede ficar ociosa
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000));
            
            // Aguarda todas as imagens carregarem
            page.waitForFunction("() => Array.from(document.images).every(img => img.complete)", 
                    new Page.WaitForFunctionOptions().setTimeout(10000));
            
            // Aguarda fontes e recursos externos
            page.waitForFunction("() => document.fonts && document.fonts.ready", 
                    new Page.WaitForFunctionOptions().setTimeout(5000));
            
            // Aguarda um tempo extra para renderização
            Thread.sleep(500);
            
            Console.debug("Página completamente carregada");
        } catch (Exception e) {
            Console.debug("Timeout ao aguardar carregamento, continuando...");
        }
    }

    private static void configurePageForQuality(Page page, BaseBrowserOptions options) {
        // Configurações para máxima qualidade
        page.setDefaultTimeout(30000);
        page.setDefaultNavigationTimeout(30000);
        
        // Remove bloqueios para garantir renderização completa
        if (options.extraHeaders != null && !options.extraHeaders.isEmpty()) {
            page.setExtraHTTPHeaders(options.extraHeaders);
        }
    }

    private static ScreenshotOptions createHighQualityOptions() {
        ScreenshotOptions opts = new ScreenshotOptions();
        opts.viewportWidth = DEFAULT_VIEWPORT_WIDTH;
        opts.viewportHeight = DEFAULT_VIEWPORT_HEIGHT;
        opts.blockImages = false;      // Não bloqueia imagens
        opts.blockCss = false;         // Não bloqueia CSS
        opts.waitForNetworkIdle = true; // Aguarda rede ociosa
        opts.waitForImages = true;      // Aguarda imagens
        opts.fullPage = true;           // Captura página inteira
        opts.quality = null;            // Sem compressão (PNG)
        return opts;
    }

    private static ScrapeOptions createDefaultScrapeOptions() {
        ScrapeOptions opts = new ScrapeOptions();
        opts.viewportWidth = DEFAULT_VIEWPORT_WIDTH;
        opts.viewportHeight = DEFAULT_VIEWPORT_HEIGHT;
        opts.blockImages = false;
        opts.blockCss = false;
        opts.waitForNetworkIdle = DEFAULT_WAIT_NETWORK_IDLE;
        opts.waitForImages = DEFAULT_WAIT_IMAGES;
        return opts;
    }

    private static Browser.NewPageOptions createPageOptions(BaseBrowserOptions options) {
        return new Browser.NewPageOptions()
                .setViewportSize(options.viewportWidth, options.viewportHeight)
                .setUserAgent(options.userAgent != null ? options.userAgent : DEFAULT_USER_AGENT);
    }

    private static Page.ScreenshotOptions createFullPageScreenshotOptions(ScreenshotOptions options) {
        Page.ScreenshotOptions opts = new Page.ScreenshotOptions()
                .setType(ScreenshotType.PNG)
                .setFullPage(true)
                .setTimeout(SCREENSHOT_TIMEOUT_MS);
        if (options.quality != null && options.quality > 0 && options.quality <= 100) {
            opts.setQuality(options.quality);
        }
        return opts;
    }

    // ==================== CLASSES DE OPÇÕES ====================

    public static class BaseBrowserOptions {
        public int viewportWidth = DEFAULT_VIEWPORT_WIDTH;
        public int viewportHeight = DEFAULT_VIEWPORT_HEIGHT;
        public String userAgent = null;
        public boolean blockImages = false;
        public boolean blockCss = false;
        public boolean waitForNetworkIdle = DEFAULT_WAIT_NETWORK_IDLE;
        public boolean waitForImages = DEFAULT_WAIT_IMAGES;
        public Map<String, String> extraHeaders = new HashMap<>();
    }

    public static class ScreenshotOptions extends BaseBrowserOptions {
        public boolean fullPage = DEFAULT_FULL_PAGE;
        public Integer quality = null;
        public Integer clipX = null, clipY = null, clipWidth = null, clipHeight = null;
    }

    public static class ScrapeOptions extends BaseBrowserOptions {}

    // ==================== SHUTDOWN ====================

    public static void shutdown() {
        while (!BROWSER_POOL.isEmpty()) {
            Browser b = BROWSER_POOL.poll();
            if (b != null) b.close();
        }
        while (!PLAYWRIGHT_POOL.isEmpty()) {
            Playwright p = PLAYWRIGHT_POOL.poll();
            if (p != null) p.close();
        }
        Console.log("BrowserAPI finalizada.");
    }
}