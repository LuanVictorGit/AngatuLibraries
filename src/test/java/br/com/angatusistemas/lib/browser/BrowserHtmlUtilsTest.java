package br.com.angatusistemas.lib.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Utilitários de HTML da BrowserAPI (não precisam do Playwright): comportamento, igualdade com
 * as regex originais e tempo linear em HTML hostil.
 *
 * @author Angatu Sistemas
 */
class BrowserHtmlUtilsTest {

    /** Tamanho do HTML hostil: com as regex antigas, 200 KB de comentários abertos levavam mais de 30 s. */
    private static final int HOSTILE_SIZE = 200_000;
    private static final Duration LINEAR_BUDGET = Duration.ofSeconds(3);

    // ---- as implementações antigas, copiadas literalmente, como referência de comportamento ----
    private static final Pattern OLD_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern OLD_TAG_BOUNDARY = Pattern.compile(">\\s+<");
    private static final Pattern OLD_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern OLD_LINK = Pattern.compile("<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_IMG = Pattern.compile("<img\\s+[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_META = Pattern.compile("<meta\\s+[^>]*name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_TAG = Pattern.compile("<[^>]*>");

    private static final String[] TOKENS = {
            "<", ">", "<a", "<A", "<a ", "<A\t", "<a\n", "<img", "<IMG ", "<img ", "<meta", "<META ", "<meta ",
            " ", "  ", "\t", "\n", "\u000B", "\f", "\r", "\u00A0",
            "href", "HREF", "src", "SRC", "name", "NAME", "content", "Content",
            "=", " = ", "\"", "'", "x", "/p", "a b", "<!--", "-->", "--", "<!-->", "-",
            "<a href=\"u\">", "<img src='i.png'>", "<meta name=\"d\" content=\"c\">", "</a>", "é"};

    // ==================== MINIFICAÇÃO ====================

    @Test
    @DisplayName("minifyHtml tira comentários e espaços, e mantém um <!-- sem fechamento")
    void minifiesHtml() {
        assertEquals("<div><p> a b </p></div>", BrowserAPI.minifyHtml("<div>  <!-- c -->  <p> a   b </p>\n</div>"));
        assertEquals("a <!-- b", BrowserAPI.minifyHtml("a <!-- b"));
        assertEquals("ab", BrowserAPI.minifyHtml("a<!---->b"));
        assertEquals("ab", BrowserAPI.minifyHtml("a<!--<!-- dentro -->b"));
        assertNull(BrowserAPI.minifyHtml(null));
    }

    @Test
    @DisplayName("minifyHtml é linear: 200 KB de comentários abertos em poucos milissegundos")
    void minifyIsLinearOnUnterminatedComments() {
        String hostile = repeat("<!--x", HOSTILE_SIZE);
        String result = assertTimeoutPreemptively(LINEAR_BUDGET, () -> BrowserAPI.minifyHtml(hostile));
        assertEquals(hostile, result);
    }

    // ==================== REMOÇÃO DE TAGS ====================

    @Test
    @DisplayName("stripHtml tira as tags e mantém um < sem fechamento")
    void stripsTags() {
        assertEquals("ab", BrowserAPI.stripHtml("<p>a<b>b</b></p>"));
        assertEquals("a < b", BrowserAPI.stripHtml("a < b"));
        assertEquals(">", BrowserAPI.stripHtml("<<a>>"));
    }

    @Test
    @DisplayName("stripHtml é linear em HTML com milhares de < abertos")
    void stripIsLinearOnOpenBrackets() {
        String hostile = repeat("<", HOSTILE_SIZE);
        assertEquals(hostile, assertTimeoutPreemptively(LINEAR_BUDGET, () -> BrowserAPI.stripHtml(hostile)));
    }

    // ==================== EXTRAÇÃO ====================

    @Test
    @DisplayName("extrai links, imagens e metatags como antes")
    void extractsLinksImagesAndMetaTags() {
        String html = "<a href=\"/x\">x</a><A class='c' HREF='y'>y</A><a\thref=\"z\"><ab href='nao'><a href=''>"
                + "<img src=\"/i.png\"><IMG alt='' SRC='j.png'>"
                + "<meta name='description' content='Loja'><meta content='x' name='invertida'>";
        assertEquals(List.of("/x", "y", "z"), BrowserAPI.extractLinks(html));
        assertEquals(List.of("/i.png", "j.png"), BrowserAPI.extractImageUrls(html));
        assertEquals(Map.of("description", "Loja"), BrowserAPI.extractMetaTags(html));
    }

    @Test
    @DisplayName("extração linear: milhares de tags <a e <img abertas não travam a thread")
    void extractionIsLinearOnOpenTags() {
        String links = repeat("<a ", HOSTILE_SIZE);
        String images = repeat("<img ", HOSTILE_SIZE);
        assertTimeoutPreemptively(LINEAR_BUDGET, () -> BrowserAPI.extractLinks(links));
        assertTimeoutPreemptively(LINEAR_BUDGET, () -> BrowserAPI.extractImageUrls(images));
    }

    @Test
    @DisplayName("metatags: HTML hostil deixou de custar minutos (o padrão é tentado uma vez por tag)")
    void metaTagExtractionNoLongerExplodes() {
        // Com a regex antiga, 50 KB disto não terminavam em 10 minutos
        String hostile = repeat("<meta name=\"a\" ", 40_000);
        assertEquals(Map.of(), assertTimeoutPreemptively(Duration.ofSeconds(10), () -> BrowserAPI.extractMetaTags(hostile)));
    }

    @Test
    @DisplayName("mesmo resultado das regex antigas em milhares de HTMLs gerados ao acaso")
    void matchesTheOriginalRegexesOnRandomHtml() {
        Random random = new Random(20260924L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder html = new StringBuilder();
            int tokens = 1 + random.nextInt(i % 10 == 0 ? 60 : 16);
            for (int k = 0; k < tokens; k++) html.append(TOKENS[random.nextInt(TOKENS.length)]);
            String input = html.toString();
            assertEquals(oldMinify(input), BrowserAPI.minifyHtml(input), () -> "minifyHtml: " + input);
            assertEquals(OLD_TAG.matcher(input).replaceAll("").trim(), BrowserAPI.stripHtml(input), () -> "stripHtml: " + input);
            assertEquals(findAll(OLD_LINK, input), BrowserAPI.extractLinks(input), () -> "extractLinks: " + input);
            assertEquals(findAll(OLD_IMG, input), BrowserAPI.extractImageUrls(input), () -> "extractImageUrls: " + input);
            assertEquals(oldMetas(input), BrowserAPI.extractMetaTags(input), () -> "extractMetaTags: " + input);
        }
    }

    // ==================== URLS ABSOLUTAS ====================

    @Test
    @DisplayName("absolutizeUrls converte /caminho e deixa //cdn como está")
    void absolutizesRootRelativeUrlsOnly() {
        assertEquals("<script src=\"//cdn.example.com/x.js\"></script><img src=\"https://site.com/a.png\">",
                BrowserAPI.absolutizeUrls("<script src=\"//cdn.example.com/x.js\"></script><img src=\"/a.png\">", "https://site.com"));
        assertEquals("<a href=\"/\">início</a>", BrowserAPI.absolutizeUrls("<a href=\"/\">início</a>", "https://site.com"));
    }

    @Test
    @DisplayName("absolutizeUrls copia $ e \\ da base literalmente, sem erro de referência de grupo")
    void copiesSpecialCharactersOfTheBaseLiterally() {
        assertEquals("<a href=\"https://site.com/$app\\1/p\">x</a>",
                BrowserAPI.absolutizeUrls("<a href=\"/p\">x</a>", "https://site.com/$app\\1"));
    }

    // ==================== APOIO ====================

    private static String repeat(String piece, int totalChars) {
        StringBuilder out = new StringBuilder(totalChars + piece.length());
        while (out.length() < totalChars) out.append(piece);
        return out.toString();
    }

    private static String oldMinify(String html) {
        String noComments = OLD_COMMENT.matcher(html).replaceAll("");
        String noBoundary = OLD_TAG_BOUNDARY.matcher(noComments).replaceAll("><");
        return OLD_WHITESPACE.matcher(noBoundary).replaceAll(" ").trim();
    }

    private static List<String> findAll(Pattern pattern, String html) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static Map<String, String> oldMetas(String html) {
        Map<String, String> metas = new HashMap<>();
        Matcher matcher = OLD_META.matcher(html);
        while (matcher.find()) metas.put(matcher.group(1), matcher.group(2));
        return metas;
    }
}
