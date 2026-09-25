package br.com.angatusistemas.lib.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Opções da BrowserAPI: valores padrão e como cada opção vira pedido ao navegador — formato e
 * qualidade da captura, página inteira, recorte e tipos de recurso bloqueados. Sem navegador.
 *
 * @author Angatu Sistemas
 */
class BrowserOptionsTest {

    @Test
    @DisplayName("os padrões continuam os documentados: 1920x1080, esperas ligadas, nada bloqueado, página inteira em PNG")
    void defaultsAreTheDocumentedOnes() {
        BrowserAPI.ScreenshotOptions options = new BrowserAPI.ScreenshotOptions();
        assertEquals(1920, options.viewportWidth);
        assertEquals(1080, options.viewportHeight);
        assertNull(options.userAgent);
        assertFalse(options.blockImages || options.blockCss || options.blockFonts);
        assertTrue(options.waitForNetworkIdle && options.waitForImages);
        assertTrue(options.extraHeaders.isEmpty());
        assertTrue(options.fullPage);
        assertNull(options.quality);
        assertNull(options.clipX);
    }

    @Test
    @DisplayName("sem quality, a captura é PNG da página inteira, sem recorte")
    void withoutQualityTheCaptureIsPng() {
        BrowserAPI.CapturePlan plan = BrowserAPI.capturePlan(new BrowserAPI.ScreenshotOptions());
        assertFalse(plan.jpeg());
        assertNull(plan.quality());
        assertTrue(plan.fullPage());
        assertNull(plan.clip());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 80, 100})
    @DisplayName("com quality, a captura é JPEG naquela qualidade (antes, toda captura falhava)")
    void qualityMeansJpeg(int quality) {
        BrowserAPI.ScreenshotOptions options = new BrowserAPI.ScreenshotOptions();
        options.quality = quality;
        BrowserAPI.CapturePlan plan = BrowserAPI.capturePlan(options);
        assertTrue(plan.jpeg());
        assertEquals(quality, plan.quality());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, 150})
    @DisplayName("quality fora de 0–100 é recusada com mensagem clara")
    void rejectsQualityOutOfRange(int quality) {
        BrowserAPI.ScreenshotOptions options = new BrowserAPI.ScreenshotOptions();
        options.quality = quality;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> BrowserAPI.capturePlan(options));
        assertTrue(error.getMessage().contains("entre 0 e 100"), error.getMessage());
    }

    @Test
    @DisplayName("fullPage = false pede só a área visível")
    void fullPageFalseCapturesTheViewport() {
        BrowserAPI.ScreenshotOptions options = new BrowserAPI.ScreenshotOptions();
        options.fullPage = false;
        assertFalse(BrowserAPI.capturePlan(options).fullPage());
    }

    @Test
    @DisplayName("recorte com os quatro campos vira a região pedida")
    void completeClipBecomesARegion() {
        BrowserAPI.ScreenshotOptions options = new BrowserAPI.ScreenshotOptions();
        options.clipX = 10;
        options.clipY = 20;
        options.clipWidth = 300;
        options.clipHeight = 200;
        assertEquals(new BrowserAPI.Region(10, 20, 300, 200), BrowserAPI.capturePlan(options).clip());
    }

    @Test
    @DisplayName("recorte incompleto, negativo ou vazio é recusado, em vez de ser ignorado em silêncio")
    void rejectsInvalidClips() {
        BrowserAPI.ScreenshotOptions partial = new BrowserAPI.ScreenshotOptions();
        partial.clipX = 10;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BrowserAPI.capturePlan(partial))
                .getMessage().contains("Recorte incompleto"));

        BrowserAPI.ScreenshotOptions negative = clip(-1, 0, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> BrowserAPI.capturePlan(negative));

        BrowserAPI.ScreenshotOptions empty = clip(0, 0, 0, 10);
        assertThrows(IllegalArgumentException.class, () -> BrowserAPI.capturePlan(empty));
    }

    @Test
    @DisplayName("cada bloqueio liga o tipo de recurso correspondente do navegador")
    void blockFlagsMapToResourceTypes() {
        BrowserAPI.ScrapeOptions options = new BrowserAPI.ScrapeOptions();
        assertEquals(Set.of(), BrowserAPI.blockedResourceTypes(options));

        options.blockImages = true;
        assertEquals(Set.of("image"), BrowserAPI.blockedResourceTypes(options));

        options.blockCss = true;
        options.blockFonts = true;
        assertEquals(Set.of("image", "stylesheet", "font"), BrowserAPI.blockedResourceTypes(options));
    }

    private static BrowserAPI.ScreenshotOptions clip(int x, int y, int width, int height) {
        BrowserAPI.ScreenshotOptions options = new BrowserAPI.ScreenshotOptions();
        options.clipX = x;
        options.clipY = y;
        options.clipWidth = width;
        options.clipHeight = height;
        return options;
    }
}
