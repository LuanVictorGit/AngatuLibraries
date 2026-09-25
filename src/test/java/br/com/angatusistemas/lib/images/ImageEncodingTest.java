package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gravação e utilitários: toda gravação falha alto em vez de voltar sem ter gravado, o tipo MIME do
 * data URI é o oficial, o recorte é uma cópia, as miniaturas em lote aceitam extensão maiúscula e
 * diretório vazio, e os métodos de vídeo, que nunca funcionaram, deixam de fingir.
 *
 * @author Angatu Sistemas
 */
class ImageEncodingTest {

    @TempDir
    Path directory;

    private static final BufferedImage OPAQUE = ImageTestSupport.solid(20, 10, BufferedImage.TYPE_INT_RGB, Color.RED);

    // ==================== GRAVAÇÃO QUE FALHAVA EM SILÊNCIO ====================

    @Test
    @DisplayName("PNG com transparência salvo como .jpg vira JPEG achatado sobre branco, em vez de arquivo nenhum")
    void transparentImageSavedAsJpegIsFlattenedOnWhite() throws Exception {
        BufferedImage argb = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        argb.setRGB(15, 15, Color.BLUE.getRGB());
        Path jpg = directory.resolve("transparente.jpg");

        ImageAPI.saveImage(argb, jpg.toString());

        byte[] bytes = Files.readAllBytes(jpg);
        assertEquals(0xFF, bytes[0] & 0xFF, "assinatura JPEG (SOI)");
        assertEquals(0xD8, bytes[1] & 0xFF, "assinatura JPEG (SOI)");
        BufferedImage saved = ImageIO.read(jpg.toFile());
        assertTrue(ImageTestSupport.red(saved, 2, 2) > 240, "o transparente deveria virar branco");
        assertTrue(ImageAPI.imageToBytes(argb, "jpg").length > 0);
        assertTrue(ImageAPI.imageToBytes(argb, "bmp").length > 0);
    }

    @Test
    @DisplayName("formato sem codificador (.webp) lança IOException e não cria arquivo")
    void formatWithoutEncoderFailsLoudly() {
        Path webp = directory.resolve("foto.webp");

        IOException saving = assertThrows(IOException.class, () -> ImageAPI.saveImage(OPAQUE, webp.toString()));
        assertTrue(saving.getMessage().contains("webp"), saving.getMessage());
        assertFalse(Files.exists(webp));
        assertThrows(IOException.class, () -> ImageAPI.imageToBytes(OPAQUE, "webp"));
        assertThrows(IOException.class, () -> ImageAPI.imageToBase64(OPAQUE, "webp"));
        assertThrows(IOException.class, () -> QRCodeAPI.qrCodeToBase64(OPAQUE, "webp"));
        assertThrows(IOException.class, () -> ImageAPI.saveImage(OPAQUE, directory.resolve("sem-extensao").toString()));
        assertThrows(FileNotFoundException.class,
                () -> ImageAPI.saveImage(OPAQUE, directory.resolve("nao-existe/foto.png").toString()));
    }

    @Test
    @DisplayName("a entidade Image criada de um BufferedImage nunca fica com bytes vazios")
    void imageEntityIsNeverEmpty() throws Exception {
        BufferedImage argb = ImageTestSupport.solid(10, 10, BufferedImage.TYPE_INT_ARGB, new Color(0, 0, 255, 128));

        var transparent = ImageAPI.extractToImageObject("com-alfa", argb);
        var opaque = ImageAPI.extractToImageObject("sem-alfa", OPAQUE);

        assertEquals("image/png", transparent.getMimeType());
        assertEquals("image/jpeg", opaque.getMimeType());
        assertEquals(10, ImageAPI.bytesToImage(transparent.getBytes()).getWidth());
        assertEquals(20, ImageAPI.bytesToImage(opaque.getBytes()).getWidth());
    }

    @Test
    @DisplayName("data URI usa o tipo MIME oficial: image/jpeg, nunca image/jpg")
    void dataUriUsesTheOfficialMimeType() throws Exception {
        assertTrue(ImageAPI.imageToBase64(OPAQUE).startsWith("data:image/jpeg;base64,"));
        assertTrue(ImageAPI.imageToBase64(OPAQUE, "jpg").startsWith("data:image/jpeg;base64,"));
        assertTrue(ImageAPI.imageToBase64(OPAQUE, "JPG").startsWith("data:image/jpeg;base64,"));
        assertTrue(ImageAPI.imageToBase64(OPAQUE, "png").startsWith("data:image/png;base64,"));
        assertTrue(ImageAPI.imageToBase64(ImageTestSupport.solid(4, 4, BufferedImage.TYPE_INT_ARGB, Color.RED))
                .startsWith("data:image/png;base64,"));
        assertTrue(QRCodeAPI.qrCodeToBase64(OPAQUE, "jpg").startsWith("data:image/jpeg;base64,"));

        String roundTrip = ImageAPI.imageToBase64(OPAQUE, "png");
        assertEquals(20, ImageAPI.base64ToImage(roundTrip).getWidth());
    }

    @Test
    @DisplayName("saveImageWithQuality sobre um arquivo maior não deixa lixo no fim, e aceita transparência")
    void jpegWithQualityTruncatesAndFlattens() throws Exception {
        Path jpg = directory.resolve("qualidade.jpg");
        Files.write(jpg, new byte[2 * 1024 * 1024]);

        ImageAPI.saveImageWithQuality(OPAQUE, jpg.toString(), 0.8f);
        long size = Files.size(jpg);
        assertTrue(size < 64 * 1024, "sobrou lixo do arquivo anterior: " + size + " bytes");
        assertEquals(20, ImageIO.read(jpg.toFile()).getWidth());

        ImageAPI.saveImageWithQuality(ImageTestSupport.solid(8, 8, BufferedImage.TYPE_INT_ARGB, new Color(255, 0, 0, 90)),
                jpg.toString(), 0.9f);
        assertEquals(8, ImageIO.read(jpg.toFile()).getWidth());
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.saveImageWithQuality(OPAQUE, jpg.toString(), 1.5f));
    }

    @Test
    @DisplayName("o cache em disco do ImageIO fica desligado: nenhum imageio*.tmp por stream")
    void imageIoDiskCacheIsDisabled() {
        ImageAPI.getMaxPixels();
        assertFalse(ImageIO.getUseCache());
    }

    // ==================== REDIMENSIONAMENTO E CORTE ====================

    @Test
    @DisplayName("redimensionar mantém a proporção e recusa tamanho de saída acima do limite de pixels")
    void resizingRespectsTheOutputLimit() throws Exception {
        BufferedImage fitted = ImageAPI.resizeMaintainAspect(ImageTestSupport.solid(3000, 2000, BufferedImage.TYPE_INT_RGB, Color.GREEN), 300, 300);
        assertEquals(300, fitted.getWidth());
        assertEquals(200, fitted.getHeight());

        assertThrows(IllegalArgumentException.class, () -> ImageAPI.resize(OPAQUE, 100_000, 100_000));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.resize(OPAQUE, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.resizeMaintainAspect(OPAQUE, 100_000, 100_000));
        assertEquals(BufferedImage.TYPE_INT_ARGB,
                ImageAPI.resize(ImageTestSupport.solid(4, 4, BufferedImage.TYPE_INT_ARGB, Color.RED), 2, 2).getType());
        assertEquals(BufferedImage.TYPE_INT_RGB,
                ImageAPI.resize(ImageTestSupport.solid(4, 4, BufferedImage.TYPE_3BYTE_BGR, Color.RED), 2, 2).getType());
    }

    @Test
    @DisplayName("o recorte é uma cópia: pintar o recorte não altera a imagem original")
    void cropIsACopy() {
        BufferedImage original = ImageTestSupport.solid(10, 10, BufferedImage.TYPE_INT_RGB, Color.WHITE);

        BufferedImage cropped = ImageAPI.crop(original, 2, 2, 5, 5);
        cropped.setRGB(0, 0, Color.BLACK.getRGB());

        assertEquals(Color.WHITE.getRGB(), original.getRGB(2, 2));
        assertEquals(BufferedImage.TYPE_INT_RGB, cropped.getType());
        assertEquals(5, ImageAPI.cropCenter(original, 5, 3).getWidth());
        assertThrows(RasterFormatException.class, () -> ImageAPI.crop(original, 8, 8, 5, 5));
        assertThrows(RasterFormatException.class, () -> ImageAPI.cropCenter(original, 20, 20));
    }

    // ==================== MINIATURAS EM LOTE ====================

    @Test
    @DisplayName("miniaturas em lote aceitam extensão maiúscula (FOTO.JPG)")
    void batchThumbnailsAcceptUppercaseExtensions() throws Exception {
        Path source = Files.createDirectory(directory.resolve("origem"));
        Path target = directory.resolve("miniaturas");
        ImageIO.write(ImageTestSupport.solid(200, 100, BufferedImage.TYPE_INT_RGB, Color.RED), "jpg", source.resolve("FOTO.JPG").toFile());
        ImageIO.write(ImageTestSupport.solid(100, 200, BufferedImage.TYPE_INT_RGB, Color.BLUE), "png", source.resolve("logo.Png").toFile());
        Files.writeString(source.resolve("leia-me.txt"), "não é imagem");

        ImageAPI.batchCreateThumbnails(source.toString(), target.toString(), 50, 50);

        String[] created = target.toFile().list();
        Arrays.sort(created);
        assertEquals(2, created.length, Arrays.toString(created));
        assertTrue(created[0].startsWith("thumbnail.FOTO."), Arrays.toString(created));
        assertTrue(created[1].startsWith("thumbnail.logo."), Arrays.toString(created));
        assertEquals(50, ImageIO.read(target.resolve(created[0]).toFile()).getWidth());
    }

    @Test
    @DisplayName("miniaturas em lote: diretório vazio não é erro; diretório inexistente é, com mensagem clara")
    void batchThumbnailsHandleEmptyAndMissingDirectories() throws Exception {
        Path empty = Files.createDirectory(directory.resolve("vazio"));

        ImageAPI.batchCreateThumbnails(empty.toString(), directory.resolve("saida").toString(), 50, 50);

        assertThrows(FileNotFoundException.class, () -> ImageAPI.batchCreateThumbnails(
                directory.resolve("nao-existe").toString(), directory.resolve("saida").toString(), 50, 50));
    }

    @Test
    @DisplayName("miniatura em lote recusa a origem inválida antes de gravar qualquer arquivo")
    void batchThumbnailsValidateEverySourceFirst() throws Exception {
        Path source = Files.createDirectory(directory.resolve("origem"));
        Path target = directory.resolve("miniaturas");
        ImageIO.write(ImageTestSupport.solid(20, 20, BufferedImage.TYPE_INT_RGB, Color.RED), "png", source.resolve("a.png").toFile());
        Files.write(source.resolve("b.png"), ImageTestSupport.HTML_WITH_SCRIPT);

        assertThrows(IOException.class, () -> ImageAPI.batchCreateThumbnails(source.toString(), target.toString(), 10, 10));
        assertFalse(Files.exists(target.resolve("thumbnail.a.png")));
    }

    // ==================== UTILITÁRIOS ====================

    @Test
    @DisplayName("os métodos de vídeo, que nunca funcionaram, lançam UnsupportedOperationException")
    @SuppressWarnings("removal")
    void videoMethodsFailLoudly() {
        assertThrows(UnsupportedOperationException.class, () -> ImageAPI.extractVideoFrames("a.mp4", "saida", 10));
        assertThrows(UnsupportedOperationException.class,
                () -> ImageAPI.createVideoFromImages(List.of(OPAQUE), "b.mp4", 20, 10, 30));
        assertThrows(UnsupportedOperationException.class, () -> ImageAPI.videoToGif("a.mp4", "b.gif", 10));
    }

    @Test
    @DisplayName("listImageFiles não diferencia maiúsculas na extensão")
    void listImageFilesIgnoresCase() throws Exception {
        Files.write(directory.resolve("A.JPG"), new byte[1]);
        Files.write(directory.resolve("b.png"), new byte[1]);
        Files.write(directory.resolve("c.txt"), new byte[1]);

        List<String> files = ImageAPI.listImageFiles(directory.toString());

        assertEquals(2, files.size(), files.toString());
    }

    @Test
    @DisplayName("isValidImage aceita imagem real e recusa o resto, sem lançar exceção")
    void isValidImageAnswersWithoutThrowing() throws Exception {
        Path png = directory.resolve("ok.png");
        ImageIO.write(OPAQUE, "png", png.toFile());
        Path text = Files.writeString(directory.resolve("texto.png"), "não sou imagem");

        assertTrue(ImageAPI.isValidImage(png.toString()));
        assertFalse(ImageAPI.isValidImage(text.toString()));
        assertFalse(ImageAPI.isValidImage(directory.resolve("nao-existe.png").toString()));
    }
}
