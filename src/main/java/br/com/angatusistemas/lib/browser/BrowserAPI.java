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
import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Classe utilitária para automação de navegador (Playwright) e manipulação de HTML.
 *
 * <p>Fornece captura de tela (via URL ou HTML bruto) com configurações padrão de
 * alta qualidade (viewport 1920x1080, aguarda carregamento completo, captura
 * página inteira), web scraping, execução de JavaScript e utilitários de HTML
 * (minificação, extração de links, imagens, metatags, etc.).</p>
 *
 * <p><strong>Dependência:</strong> o módulo de navegação requer
 * {@code com.microsoft.playwright:playwright:1.58.0} no classpath (os
 * utilitários puros de HTML funcionam sem ela). Se ausente, a primeira chamada
 * exibe instruções de instalação e lança
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}.</p>
 *
 * <p><strong>Pool de navegadores:</strong> a biblioteca mantém um pool de 2
 * instâncias headless de Chromium. Chame {@link #shutdown()} ao final da
 * aplicação para liberar os recursos.</p>
 *
 * <p>Exemplo de uso:
 * <pre>
 * // Screenshot da página inteira a partir de HTML
 * BufferedImage img = BrowserAPI.captureFullPageScreenshotFromHtml(html);
 *
 * // Screenshot a partir de URL
 * BufferedImage img2 = BrowserAPI.captureFullPageScreenshot("https://example.com");
 *
 * // Scraping: extrai o texto do título de uma página
 * String texto = BrowserAPI.extractText("https://example.com", "h1");
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://playwright.dev/java/">Playwright Java</a>
 */
public final class BrowserAPI {

    // ==================== CONFIGURAÇÕES PADRÃO ====================
    /** Coordenadas Maven da dependência Playwright. */
    private static final String PLAYWRIGHT_COORDINATES = "com.microsoft.playwright:playwright:1.58.0";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String PLAYWRIGHT_FEATURE = "Browser Automation (Playwright)";

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

    // ==================== PADRÕES PRÉ-COMPILADOS (HTML UTILS) ====================

    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TAG_BOUNDARY_PATTERN = Pattern.compile(">\\s+<");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern TAG_OPEN_PATTERN = Pattern.compile("><");
    private static final Pattern LINK_PATTERN = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_PATTERN = Pattern.compile("<img\\s+[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_PATTERN = Pattern.compile("<meta\\s+[^>]*name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_STRIP_PATTERN = Pattern.compile("<[^>]*>");
    private static final Pattern CLASS_PATTERN = Pattern.compile("class\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_PATTERN = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_ABS_PATTERN = Pattern.compile("(src|href)=\"/([^\"]+)\"");
    private static final Pattern CLASS_SPLIT_PATTERN = Pattern.compile("\\s+");

    // ==================== POOL DE NAVEGADORES ====================

    private static final Queue<Playwright> PLAYWRIGHT_POOL = new ConcurrentLinkedQueue<>();
    private static final Queue<Browser> BROWSER_POOL = new ConcurrentLinkedQueue<>();
    private static boolean poolInitialized = false;

    private BrowserAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== INICIALIZAÇÃO DO POOL ====================

    /**
     * Inicializa o pool de navegadores headless. Chamada automaticamente na
     * primeira utilização (não precisa ser invocada manualmente).
     *
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Playwright não estiver no classpath
     */
    public static synchronized void initPool() {
        Dependencies.require("com.microsoft.playwright.Playwright", PLAYWRIGHT_COORDINATES, PLAYWRIGHT_FEATURE);
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
            // Browser criado fora do pool: registra o Playwright para que o
            // shutdown() consiga liberar o recurso (evita vazamento)
            Playwright playwright = Playwright.create();
            PLAYWRIGHT_POOL.offer(playwright);
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
     * Captura screenshot da página inteira a partir de uma URL (alta qualidade).
     *
     * @param url URL da página
     * @return Imagem capturada
     * @throws IOException se ocorrer erro de navegação ou captura
     */
    public static BufferedImage captureFullPageScreenshot(String url) throws IOException {
        return captureFullPageScreenshotFromUrl(url, createHighQualityOptions());
    }

    /**
     * Captura screenshot da página inteira a partir de uma string HTML (alta qualidade).
     *
     * @param html Código HTML
     * @return Imagem capturada
     * @throws IOException se ocorrer erro de renderização ou captura
     */
    public static BufferedImage captureFullPageScreenshotFromHtml(String html) throws IOException {
        return captureFullPageScreenshotFromHtml(html, createHighQualityOptions());
    }

    /**
     * Captura screenshot da página inteira a partir de uma URL e salva em arquivo PNG.
     *
     * @param url        URL da página
     * @param outputPath Caminho do arquivo de saída (ex: {@code "foto.png"})
     * @throws IOException se ocorrer erro de captura ou escrita
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
     * Captura screenshot da página inteira a partir de uma URL com opções personalizadas.
     *
     * @param url     URL da página
     * @param options Opções de captura (viewport, aguardas, qualidade)
     * @return Imagem capturada
     * @throws IOException se ocorrer erro de navegação ou captura
     */
    public static BufferedImage captureFullPageScreenshotFromUrl(String url, ScreenshotOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            configurePageForQuality(page, options);

            Console.debug("Navegando para: %s", url);
            page.navigate(url, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));

            waitForFullPageLoad(page);

            Console.debug("Capturando screenshot da página inteira...");
            byte[] bytes = page.screenshot(createFullPageScreenshotOptions(options));

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            Console.debug("Screenshot capturado com sucesso: %dx%d", image.getWidth(), image.getHeight());
            return image;

        } catch (Exception e) {
            throw new IOException("Falha ao capturar screenshot: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Captura screenshot da página inteira a partir de um HTML com opções personalizadas.
     *
     * @param html    Código HTML
     * @param options Opções de captura (viewport, aguardas, qualidade)
     * @return Imagem capturada
     * @throws IOException se ocorrer erro de renderização ou captura
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
            throw new IOException("Falha ao capturar screenshot a partir de HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    // ==================== MÉTODOS SIMPLIFICADOS (COMPATIBILIDADE) ====================

    /** @deprecated Use {@link #captureFullPageScreenshot(String)}. */
    @Deprecated
    public static BufferedImage captureScreenshot(String url) throws IOException {
        return captureFullPageScreenshot(url);
    }

    /** @deprecated Use {@link #captureFullPageScreenshotFromHtml(String)}. */
    @Deprecated
    public static BufferedImage captureScreenshotFromHtml(String html) throws IOException {
        return captureFullPageScreenshotFromHtml(html);
    }

    /** @deprecated Use {@link #captureFullPageScreenshotToFile(String, String)}. */
    @Deprecated
    public static void captureScreenshotToFile(String url, String outputPath) throws IOException {
        captureFullPageScreenshotToFile(url, outputPath);
    }

    /** @deprecated Use {@link #captureFullPageScreenshotFromHtmlToFile(String, String)}. */
    @Deprecated
    public static void captureScreenshotFromHtmlToFile(String html, String outputPath) throws IOException {
        captureFullPageScreenshotFromHtmlToFile(html, outputPath);
    }

    // ==================== WEB SCRAPING ====================

    /**
     * Obtém o HTML completo de uma página após o carregamento.
     *
     * @param url URL da página
     * @return HTML renderizado
     * @throws IOException se ocorrer erro de navegação
     */
    public static String getPageHtml(String url) throws IOException {
        return getPageHtml(url, createDefaultScrapeOptions());
    }

    /**
     * Obtém o HTML completo renderizado a partir de uma string HTML.
     *
     * @param html Código HTML de origem
     * @return HTML renderizado
     * @throws IOException se ocorrer erro de renderização
     */
    public static String getPageHtmlFromHtml(String html) throws IOException {
        return getPageHtmlFromHtml(html, createDefaultScrapeOptions());
    }

    /**
     * Extrai o texto de um seletor CSS de uma página.
     *
     * @param url      URL da página
     * @param selector Seletor CSS (ex: {@code "h1"}, {@code ".titulo"})
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se ocorrer erro de navegação ou extração
     */
    public static String extractText(String url, String selector) throws IOException {
        return extractText(url, selector, createDefaultScrapeOptions());
    }

    /**
     * Extrai o texto de um seletor CSS a partir de uma string HTML.
     *
     * @param html     Código HTML de origem
     * @param selector Seletor CSS (ex: {@code "h1"}, {@code ".titulo"})
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se ocorrer erro de extração
     */
    public static String extractTextFromHtml(String html, String selector) throws IOException {
        return extractTextFromHtml(html, selector, createDefaultScrapeOptions());
    }

    /**
     * Extrai os dados de uma tabela HTML (linhas × células).
     *
     * @param url           URL da página
     * @param tableSelector Seletor CSS da tabela (ex: {@code "#tabela"})
     * @return Lista de linhas, cada uma com a lista de células
     * @throws IOException se ocorrer erro de navegação ou extração
     */
    public static List<List<String>> extractTableData(String url, String tableSelector) throws IOException {
        return extractTableData(url, tableSelector, createDefaultScrapeOptions());
    }

    /**
     * Extrai múltiplos campos de uma página usando um mapa de rótulo → seletor.
     *
     * @param url       URL da página
     * @param selectors Mapa {@code rótulo → seletor CSS}
     * @return Mapa {@code rótulo → texto extraído} (ou {@code null} se não encontrado)
     * @throws IOException se ocorrer erro de navegação
     */
    public static Map<String, String> extractMultiple(String url, Map<String, String> selectors) throws IOException {
        return extractMultiple(url, selectors, createDefaultScrapeOptions());
    }

    /**
     * Executa JavaScript em uma página e retorna o resultado.
     *
     * @param url    URL da página
     * @param script Código JavaScript (ex: {@code "document.title"})
     * @return Resultado da expressão
     * @throws IOException se ocorrer erro de navegação ou execução
     */
    public static Object evaluateJavaScript(String url, String script) throws IOException {
        return evaluateJavaScript(url, script, createDefaultScrapeOptions());
    }

    /**
     * Executa JavaScript sobre um HTML sem navegação.
     *
     * @param html   Código HTML de origem
     * @param script Código JavaScript
     * @return Resultado da expressão
     * @throws IOException se ocorrer erro de execução
     */
    public static Object evaluateJavaScriptOnHtml(String html, String script) throws IOException {
        return evaluateJavaScriptOnHtml(html, script, createDefaultScrapeOptions());
    }

    // ==================== MÉTODOS AVANÇADOS DE SCRAPING ====================

    /**
     * Obtém o HTML completo de uma página com opções de scraping.
     *
     * @param url     URL da página
     * @param options Opções de navegação (viewport, aguardas, headers)
     * @return HTML renderizado
     * @throws IOException se ocorrer erro de navegação
     */
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
            throw new IOException("Falha ao obter HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Obtém o HTML renderizado a partir de uma string HTML com opções de scraping.
     *
     * @param html    Código HTML de origem
     * @param options Opções de navegação
     * @return HTML renderizado
     * @throws IOException se ocorrer erro de renderização
     */
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
            throw new IOException("Falha ao processar HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Extrai o texto de um seletor CSS com opções de scraping.
     *
     * @param url      URL da página
     * @param selector Seletor CSS
     * @param options  Opções de navegação
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se ocorrer erro de navegação ou extração
     */
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
            throw new IOException("Falha ao extrair texto: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Extrai o texto de um seletor CSS a partir de HTML com opções de scraping.
     *
     * @param html     Código HTML de origem
     * @param selector Seletor CSS
     * @param options  Opções de navegação
     * @return Texto do primeiro elemento correspondente
     * @throws IOException se ocorrer erro de extração
     */
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
            throw new IOException("Falha ao extrair texto do HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Extrai os dados de uma tabela HTML com opções de scraping.
     *
     * @param url           URL da página
     * @param tableSelector Seletor CSS da tabela
     * @param options       Opções de navegação
     * @return Lista de linhas, cada uma com a lista de células
     * @throws IOException se ocorrer erro de navegação ou extração
     */
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
            throw new IOException("Falha ao extrair dados da tabela: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Extrai múltiplos campos com opções de scraping.
     *
     * @param url       URL da página
     * @param selectors Mapa {@code rótulo → seletor CSS}
     * @param options   Opções de navegação
     * @return Mapa {@code rótulo → texto extraído} (ou {@code null} se não encontrado)
     * @throws IOException se ocorrer erro de navegação
     */
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
            throw new IOException("Falha ao extrair múltiplos campos: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Executa JavaScript em uma página com opções de scraping.
     *
     * @param url    URL da página
     * @param script Código JavaScript
     * @param options Opções de navegação
     * @return Resultado da expressão
     * @throws IOException se ocorrer erro de navegação ou execução
     */
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
            throw new IOException("Falha ao executar JS: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    /**
     * Executa JavaScript sobre um HTML com opções de scraping.
     *
     * @param html    Código HTML de origem
     * @param script  Código JavaScript
     * @param options Opções de navegação
     * @return Resultado da expressão
     * @throws IOException se ocorrer erro de execução
     */
    public static Object evaluateJavaScriptOnHtml(String html, String script, ScrapeOptions options) throws IOException {
        Browser browser = getBrowser();
        Page page = null;
        try {
            page = browser.newPage(createPageOptions(options));
            page.setContent(html, new Page.SetContentOptions().setTimeout(PAGE_TIMEOUT_MS));
            waitForFullPageLoad(page);
            return page.evaluate(script);
        } catch (Exception e) {
            throw new IOException("Falha ao executar JS no HTML: " + e.getMessage(), e);
        } finally {
            if (page != null) page.close();
            returnBrowser(browser);
        }
    }

    // ==================== UTILITÁRIOS DE HTML (SEM PLAYWRIGHT) ====================

    /**
     * Remove comentários e espaços desnecessários de um HTML.
     *
     * @param html HTML original (pode ser {@code null})
     * @return HTML minificado
     */
    public static String minifyHtml(String html) {
        if (html == null) return null;
        String noComments = COMMENT_PATTERN.matcher(html).replaceAll("");
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
     * @param html HTML de origem
     * @return Lista de URLs encontradas
     */
    public static List<String> extractLinks(String html) {
        List<String> links = new ArrayList<>();
        Matcher m = LINK_PATTERN.matcher(html);
        while (m.find()) links.add(m.group(1));
        return links;
    }

    /**
     * Extrai todas as URLs de imagens ({@code <img src>}) de um HTML.
     *
     * @param html HTML de origem
     * @return Lista de URLs encontradas
     */
    public static List<String> extractImageUrls(String html) {
        List<String> urls = new ArrayList<>();
        Matcher m = IMG_PATTERN.matcher(html);
        while (m.find()) urls.add(m.group(1));
        return urls;
    }

    /**
     * Extrai as metatags ({@code name → content}) de um HTML.
     *
     * @param html HTML de origem
     * @return Mapa de metatags encontradas
     */
    public static Map<String, String> extractMetaTags(String html) {
        Map<String, String> metas = new HashMap<>();
        Matcher m = META_PATTERN.matcher(html);
        while (m.find()) metas.put(m.group(1), m.group(2));
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
     * @param html HTML de origem
     * @return Texto puro
     */
    public static String stripHtml(String html) {
        return TAG_STRIP_PATTERN.matcher(html).replaceAll("").trim();
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
     * Converte URLs relativas ({@code /path}) em URLs absolutas com base em uma URL raiz.
     *
     * @param html    HTML de origem
     * @param baseUrl URL base (ex: {@code "https://site.com"})
     * @return HTML com URLs absolutas
     */
    public static String absolutizeUrls(String html, String baseUrl) {
        return URL_ABS_PATTERN.matcher(html).replaceAll("$1=\"" + baseUrl + "/$2\"");
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

    /**
     * Opções base de navegação: viewport, user-agent, bloqueios de recursos,
     * aguardas de carregamento e headers extras.
     *
     * <p>Campos públicos por convenção da API (acesso direto:
     * {@code options.viewportWidth = 1280;}).</p>
     */
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

    /**
     * Opções de captura de screenshot: estende {@link BaseBrowserOptions} com
     * controle de página inteira, qualidade (JPEG) e área de recorte.
     */
    public static class ScreenshotOptions extends BaseBrowserOptions {
        public boolean fullPage = DEFAULT_FULL_PAGE;
        public Integer quality = null;
        public Integer clipX = null, clipY = null, clipWidth = null, clipHeight = null;
    }

    /**
     * Opções de scraping: estende {@link BaseBrowserOptions} sem campos adicionais.
     */
    public static class ScrapeOptions extends BaseBrowserOptions {}

    // ==================== SHUTDOWN ====================

    /**
     * Fecha todos os navegadores e instâncias do Playwright do pool.
     *
     * <p>Deve ser chamado ao encerrar a aplicação para evitar vazamento de
     * processos headless.</p>
     */
    public static void shutdown() {
        while (!BROWSER_POOL.isEmpty()) {
            Browser b = BROWSER_POOL.poll();
            if (b != null) b.close();
        }
        while (!PLAYWRIGHT_POOL.isEmpty()) {
            Playwright p = PLAYWRIGHT_POOL.poll();
            if (p != null) p.close();
        }
        poolInitialized = false;
        Console.log("BrowserAPI finalizada.");
    }
}
