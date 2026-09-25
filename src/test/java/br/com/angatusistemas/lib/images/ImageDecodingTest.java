package br.com.angatusistemas.lib.images;

import static br.com.angatusistemas.lib.images.ImageTestSupport.HTML_WITH_SCRIPT;
import static br.com.angatusistemas.lib.images.ImageTestSupport.SVG_WITH_SCRIPT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.angatusistemas.lib.images.objects.Image;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Leitura de imagens: bomba de descompressão recusada pelo cabeçalho, conteúdo que não é imagem
 * recusado pelos bytes, orientação EXIF igual à do Thumbnailator e imagens de tipo "exótico" (cinza
 * com alfa, 16 bits, paleta) processadas sem erro e sem mudar de cor.
 *
 * @author Angatu Sistemas
 */
class ImageDecodingTest {

    @TempDir
    Path directory;

    @AfterEach
    void restorePixelLimit() {
        ImageAPI.setMaxPixels(ImageAPI.DEFAULT_MAX_PIXELS);
    }

    // ==================== BOMBA DE DESCOMPRESSÃO ====================

    @Test
    @DisplayName("PNG de 560 KB que declara 12000 × 12000 é recusado sem alocar os 549 MB")
    void decompressionBombIsRejectedBeforeAllocating() throws Exception {
        com.sun.management.ThreadMXBean threads = allocationCounter();
        byte[] bomb = ImageTestSupport.pngBomb(12_000, 12_000);
        assertTrue(bomb.length < 1024 * 1024, "a bomba deveria ser pequena no disco: " + bomb.length);
        ImageAPI.bytesToImage(ImageTestSupport.encoded(ImageTestSupport.solid(4, 4, BufferedImage.TYPE_INT_RGB, Color.RED), "png"));

        long before = threads.getCurrentThreadAllocatedBytes();
        IOException rejected = assertThrows(IOException.class, () -> ImageAPI.bytesToImage(bomb));
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(rejected.getMessage().contains("12000 × 12000"), rejected.getMessage());
        assertTrue(allocated < 32L * 1024 * 1024, "alocou " + allocated / (1024 * 1024) + " MB para recusar a bomba");
    }

    @Test
    @DisplayName("a bomba é recusada em todos os caminhos de leitura: arquivo, Base64, validação e QR Code")
    void decompressionBombIsRejectedOnEveryPath() throws Exception {
        byte[] bomb = ImageTestSupport.pngBomb(12_000, 12_000);
        Path file = Files.write(directory.resolve("bomba.png"), bomb);

        assertThrows(IOException.class, () -> ImageAPI.readImage(file.toString()));
        assertThrows(IOException.class, () -> ImageAPI.base64ToImage("data:image/png;base64,"
                + Base64.getEncoder().encodeToString(bomb)));
        assertThrows(IOException.class, () -> ImageAPI.extractToImageObject("bomba", file.toString()));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.extractToImageObject("bomba", bomb));
        assertThrows(IOException.class, () -> QRCodeAPI.readQRCodeFromFile(file.toString()));
        assertFalse(ImageAPI.isValidImage(file.toString()));
    }

    @Test
    @DisplayName("dimensões vêm só do cabeçalho: a bomba informa 12000 × 12000 sem ser decodificada")
    void dimensionsAreReadFromTheHeaderOnly() throws Exception {
        com.sun.management.ThreadMXBean threads = allocationCounter();
        Path file = Files.write(directory.resolve("bomba.png"), ImageTestSupport.pngBomb(12_000, 12_000));
        ImageAPI.getImageDimensions(file.toString());

        long before = threads.getCurrentThreadAllocatedBytes();
        int[] dimensions = ImageAPI.getImageDimensions(file.toString());
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertArrayEquals(new int[] {12_000, 12_000}, dimensions);
        assertTrue(allocated < 32L * 1024 * 1024, "alocou " + allocated / (1024 * 1024) + " MB para ler o cabeçalho");
    }

    @Test
    @DisplayName("o limite de pixels é configurável e vale para a próxima leitura")
    void pixelLimitIsConfigurable() throws Exception {
        byte[] image = ImageTestSupport.encoded(ImageTestSupport.solid(300, 200, BufferedImage.TYPE_INT_RGB, Color.RED), "png");
        assertEquals(ImageAPI.DEFAULT_MAX_PIXELS, ImageAPI.getMaxPixels());

        ImageAPI.setMaxPixels(50_000);
        assertThrows(IOException.class, () -> ImageAPI.bytesToImage(image));

        ImageAPI.setMaxPixels(60_000);
        assertEquals(300, ImageAPI.bytesToImage(image).getWidth());
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.setMaxPixels(0));
    }

    // ==================== CONTEÚDO QUE NÃO É IMAGEM ====================

    @Test
    @DisplayName("SVG com script e HTML são recusados na leitura, por bytes, Base64 e arquivo")
    void svgAndHtmlAreRejectedOnRead() throws Exception {
        Path svg = Files.write(directory.resolve("logo.svg"), SVG_WITH_SCRIPT);
        Path htmlNamedPng = Files.write(directory.resolve("foto.png"), HTML_WITH_SCRIPT);

        assertThrows(IOException.class, () -> ImageAPI.bytesToImage(SVG_WITH_SCRIPT));
        assertThrows(IOException.class, () -> ImageAPI.bytesToImage(HTML_WITH_SCRIPT));
        assertThrows(IOException.class, () -> ImageAPI.base64ToImage("data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(SVG_WITH_SCRIPT)));
        assertThrows(IOException.class, () -> ImageAPI.readImage(svg.toString()));
        assertThrows(IOException.class, () -> ImageAPI.readImage(htmlNamedPng.toString()));
        assertFalse(ImageAPI.isValidImage(htmlNamedPng.toString()));
    }

    @Test
    @DisplayName("SVG e HTML não viram entidade Image, qualquer que seja o tipo ou a extensão informados")
    @SuppressWarnings("deprecation")
    void svgAndHtmlNeverBecomeAStoredImage() throws Exception {
        Path svg = Files.write(directory.resolve("logo.svg"), SVG_WITH_SCRIPT);
        Path htmlNamedPng = Files.write(directory.resolve("foto.png"), HTML_WITH_SCRIPT);

        assertThrows(IOException.class, () -> ImageAPI.extractToImageObject("a", svg.toString()));
        assertThrows(IOException.class, () -> ImageAPI.extractToImageObject("b", htmlNamedPng.toString()));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.extractToImageObject("c", SVG_WITH_SCRIPT));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAPI.extractToImageObject("d", SVG_WITH_SCRIPT, "image/svg+xml"));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAPI.extractToImageObject("e", HTML_WITH_SCRIPT, "image/png"));
    }

    @Test
    @DisplayName("o tipo MIME vem dos bytes, não da extensão nem do tipo informado pelo chamador")
    @SuppressWarnings("deprecation")
    void mimeTypeComesFromTheBytes() throws Exception {
        BufferedImage opaque = ImageTestSupport.solid(8, 8, BufferedImage.TYPE_INT_RGB, Color.RED);
        byte[] png = ImageTestSupport.encoded(opaque, "png");
        Path pngNamedJpg = Files.write(directory.resolve("foto.jpg"), png);

        assertEquals("image/png", ImageAPI.extractToImageObject("a", pngNamedJpg.toString()).getMimeType());
        assertEquals("image/png", ImageAPI.extractToImageObject("b", png, "text/html").getMimeType());
        assertEquals("image/png", ImageAPI.extractToImageObject("c", png).getMimeType());
        assertEquals("image/jpeg", ImageAPI.extractToImageObject("d", ImageTestSupport.encoded(opaque, "jpg")).getMimeType());
        assertEquals("image/gif", ImageAPI.extractToImageObject("e", ImageTestSupport.encoded(opaque, "gif")).getMimeType());
        assertEquals("image/bmp", ImageAPI.extractToImageObject("f", ImageTestSupport.encoded(opaque, "bmp")).getMimeType());
        assertEquals("image/tiff", ImageAPI.extractToImageObject("t", ImageTestSupport.encoded(opaque, "tiff")).getMimeType());

        Image stored = ImageAPI.extractToImageObject("g", png, null);
        assertArrayEquals(png, stored.getBytes(), "os bytes são gravados como vieram");
    }

    @Test
    @DisplayName("WebP é reconhecido pelos bytes e decodificado pelo leitor do TwelveMonkeys")
    void webpIsReadThroughTwelveMonkeys() throws Exception {
        // Amostra pública de 1 × 1 pixel (WebP sem perdas), a mesma que o Modernizr usa para detectar suporte.
        byte[] webp = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

        assertEquals(1, ImageAPI.bytesToImage(webp).getWidth());
        assertEquals("image/webp", ImageAPI.extractToImageObject("w", webp).getMimeType());
    }

    @Test
    @DisplayName("BMP com PNG embutido é recusado antes de o leitor alocar o tamanho declarado")
    void bmpWithEmbeddedPngIsRejected() throws Exception {
        IOException rejected = assertThrows(IOException.class, () -> ImageAPI.bytesToImage(ImageTestSupport.bmpWithEmbeddedPng()));
        assertTrue(rejected.getMessage().contains("BMP"), rejected.getMessage());

        BufferedImage bmp = ImageAPI.bytesToImage(ImageTestSupport.encoded(
                ImageTestSupport.solid(5, 5, BufferedImage.TYPE_INT_RGB, Color.BLUE), "bmp"));
        assertEquals(5, bmp.getWidth());
    }

    @Test
    @DisplayName("Base64 inválido lança IOException, como o Javadoc promete; quebra de linha é aceita")
    void base64ErrorsAreIoExceptions() throws Exception {
        assertThrows(IOException.class, () -> ImageAPI.base64ToImage("!!!isto não é base64"));
        assertThrows(IOException.class, () -> ImageAPI.base64ToImage("data:image/png;base64,"));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.base64ToImage(null));

        byte[] png = ImageTestSupport.encoded(ImageTestSupport.solid(6, 6, BufferedImage.TYPE_INT_RGB, Color.GREEN), "png");
        String wrapped = Base64.getMimeEncoder().encodeToString(png);
        assertTrue(wrapped.contains("\r\n") || png.length < 57, "o Base64 do teste deveria ter quebra de linha");
        assertEquals(6, ImageAPI.base64ToImage("data:image/png;base64," + wrapped).getWidth());
    }

    @Test
    @DisplayName("bytes vazios ou arquivo inexistente falham com IOException clara")
    void emptyOrMissingInputFails() {
        assertThrows(IOException.class, () -> ImageAPI.bytesToImage(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.bytesToImage(null));
        assertThrows(IOException.class, () -> ImageAPI.readImage(directory.resolve("nao-existe.png").toString()));
        assertFalse(ImageAPI.isValidImage(null));
    }

    // ==================== ORIENTAÇÃO EXIF ====================

    @ParameterizedTest(name = "orientação EXIF {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("readImage, bytesToImage e createThumbnail põem a foto na mesma posição que o Thumbnailator")
    void exifOrientationMatchesThumbnailator(int orientation) throws Exception {
        byte[] jpeg = ImageTestSupport.quadrantJpeg(64, 32, orientation, orientation % 2 == 0);
        Path file = Files.write(directory.resolve("foto-" + orientation + ".jpg"), jpeg);

        BufferedImage expected = Thumbnails.of(file.toFile()).scale(1.0).asBufferedImage();
        BufferedImage fromFile = ImageAPI.readImage(file.toString());
        BufferedImage fromBytes = ImageAPI.bytesToImage(jpeg);

        boolean swapped = orientation >= 5;
        assertEquals(swapped ? 32 : 64, expected.getWidth(), "referência do Thumbnailator");
        assertEquals(expected.getWidth(), fromFile.getWidth());
        assertEquals(expected.getHeight(), fromFile.getHeight());
        assertEquals(ImageTestSupport.quadrants(expected), ImageTestSupport.quadrants(fromFile));
        assertEquals(ImageTestSupport.quadrants(expected), ImageTestSupport.quadrants(fromBytes));
        assertArrayEquals(new int[] {expected.getWidth(), expected.getHeight()}, ImageAPI.getImageDimensions(file.toString()));

        Path thumbnail = directory.resolve("mini-" + orientation + ".png");
        ImageAPI.createThumbnail(file.toString(), thumbnail.toString(), 64, 64);
        BufferedImage mini = ImageAPI.readImage(thumbnail.toString());
        assertEquals(ImageTestSupport.quadrants(expected), ImageTestSupport.quadrants(mini));
    }

    @Test
    @DisplayName("a orientação vale também para o redimensionamento de uma foto lida")
    void resizedPhotoKeepsTheOrientation() throws Exception {
        BufferedImage photo = ImageAPI.bytesToImage(ImageTestSupport.quadrantJpeg(64, 32, 6, false));
        BufferedImage resized = ImageAPI.resizeMaintainAspect(photo, 16, 16);

        assertEquals(8, resized.getWidth());
        assertEquals(16, resized.getHeight());
        assertEquals(ImageTestSupport.quadrants(photo), ImageTestSupport.quadrants(resized));
    }

    @Test
    @DisplayName("bloco EXIF sem orientação, truncado ou com valor inválido não gira a imagem")
    void malformedExifIsIgnored() {
        assertEquals(0, ImageAPI.orientationFromExif("JFIF".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(6, ImageAPI.orientationFromExif(ImageTestSupport.exifOrientation(6, true)));
        assertEquals(3, ImageAPI.orientationFromExif(ImageTestSupport.exifOrientation(3, false)));
        assertEquals(1, ImageAPI.orientationFromExif(ImageTestSupport.exifOrientation(9, false)));
        byte[] truncated = ImageTestSupport.exifOrientation(6, false);
        assertEquals(1, ImageAPI.orientationFromExif(java.util.Arrays.copyOf(truncated, 18)));
    }

    // ==================== TIPOS EXÓTICOS ====================

    @Test
    @DisplayName("PNG cinza com alfa (TYPE_CUSTOM) passa por todas as operações sem erro e sem clarear")
    void grayAlphaPngIsProcessed() throws Exception {
        BufferedImage gray = ImageAPI.bytesToImage(ImageTestSupport.grayAlphaPng(40, 30, 128, 255));
        assertEquals(BufferedImage.TYPE_CUSTOM, gray.getType());

        assertMidGray(ImageAPI.resize(gray, 20, 15));
        assertMidGray(ImageAPI.rotate(gray, 90));
        assertMidGray(ImageAPI.crop(gray, 5, 5, 10, 10));
        assertMidGray(ImageAPI.resizeMaintainAspect(gray, 20, 20));
        assertMidGray(ImageAPI.bytesToImage(ImageAPI.imageToBytes(gray, "jpg")));
        assertMidGray(ImageAPI.bytesToImage(ImageAPI.imageToBytes(gray, "png")));

        Path jpg = directory.resolve("cinza.jpg");
        ImageAPI.saveImage(gray, jpg.toString());
        assertMidGray(ImageAPI.readImage(jpg.toString()));
        Path gif = directory.resolve("cinza.gif");
        ImageAPI.createAnimatedGif(java.util.List.of(gray, gray), gif.toString(), 100, true);
        assertMidGray(ImageAPI.readImage(gif.toString()));
        assertNotNull(ImageAPI.imageToBase64(gray));
    }

    @Test
    @DisplayName("PNG de 16 bits por canal (TYPE_CUSTOM) é redimensionado e rotacionado sem erro")
    void sixteenBitPngIsProcessed() throws Exception {
        BufferedImage deep = ImageAPI.bytesToImage(ImageTestSupport.rgb16Png(30, 20, new Color(200, 100, 50)));
        assertEquals(BufferedImage.TYPE_CUSTOM, deep.getType());

        BufferedImage resized = ImageAPI.resize(deep, 15, 10);
        assertEquals(BufferedImage.TYPE_INT_RGB, resized.getType());
        assertEquals(200, ImageTestSupport.red(resized, 7, 5), 2);
        assertEquals(20, ImageAPI.rotate(deep, 90).getWidth());
        assertTrue(ImageAPI.imageToBytes(deep, "jpg").length > 0);
    }

    @Test
    @DisplayName("imagem com paleta mantém a cor ao redimensionar e rotacionar (antes caía na paleta padrão)")
    void indexedImageKeepsItsColors() {
        byte[] reds = {10, (byte) 250};
        byte[] greens = {(byte) 200, (byte) 250};
        byte[] blues = {30, (byte) 250};
        BufferedImage indexed = new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(8, 2, reds, greens, blues));

        int expected = new Color(10, 200, 30).getRGB();
        assertEquals(expected, ImageAPI.resize(indexed, 10, 10).getRGB(5, 5));
        assertEquals(expected, ImageAPI.rotate(indexed, 180).getRGB(10, 10));
    }

    /**
     * O cinza no centro é o 128 original, como o navegador exibiria. Imagem que continua em cinza
     * ({@code TYPE_CUSTOM}, como o recorte ou o PNG regravado) é lida pela amostra: nela, o próprio
     * {@code getRGB} do JDK trata o cinza como linear e devolveria 188.
     */
    private static void assertMidGray(BufferedImage image) {
        int x = image.getWidth() / 2;
        int y = image.getHeight() / 2;
        int value;
        if (image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY) {
            int max = (1 << image.getColorModel().getComponentSize(0)) - 1;
            value = (int) Math.round(image.getRaster().getSample(x, y, 0) * 255.0 / max);
        } else {
            value = ImageTestSupport.red(image, x, y);
        }
        assertEquals(128, value, 4, "o cinza 128 mudou para " + value + " (tipo " + image.getType() + ")");
        assertEquals(255, ImageTestSupport.alpha(image, x, y));
    }

    private static com.sun.management.ThreadMXBean allocationCounter() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "JVM sem contagem de alocação por thread");
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(),
                "contagem de alocação por thread desligada");
        return threads;
    }
}
