package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * QR Code: gerar funciona (antes toda chamada lançava {@code MissingDependencyException}, porque o
 * guard procurava uma classe que não existe), ler devolve o texto gerado, o logo leva correção H, e
 * tamanho e margem têm limite.
 *
 * @author Angatu Sistemas
 */
class QRCodeAPITest {

    private static final String URL = "https://angatusistemas.com.br/pedido?id=12345&origem=qr";

    @TempDir
    Path directory;

    @Test
    @DisplayName("gera um QR Code 300 × 300 que o próprio leitor devolve como o texto original")
    void generatedCodeReadsBack() throws Exception {
        BufferedImage qr = QRCodeAPI.generateQRCode(URL);

        assertEquals(300, qr.getWidth());
        assertEquals(300, qr.getHeight());
        assertEquals(URL, QRCodeAPI.decodeQRCode(qr));
        assertEquals("M", errorCorrectionLevel(qr), "nível padrão");
    }

    @Test
    @DisplayName("ida e volta por Base64 e por arquivo")
    void roundTripThroughBase64AndFile() throws Exception {
        String base64 = QRCodeAPI.generateQRCodeAsBase64(URL, 250, 250);
        assertTrue(base64.startsWith("data:image/png;base64,"), base64.substring(0, 30));
        assertEquals(URL, QRCodeAPI.readQRCodeFromBase64(base64));

        Path file = directory.resolve("qrcode.png");
        QRCodeAPI.generateAndSaveQRCode(URL, file.toString(), 200, 200);
        assertEquals(URL, QRCodeAPI.readQRCodeFromFile(file.toString()));
    }

    @Test
    @DisplayName("parâmetros avançados: nível e margem em módulos são respeitados")
    void advancedParametersAreApplied() throws Exception {
        BufferedImage withMargin = QRCodeAPI.generateQRCode(URL, 330, 330, ErrorCorrectionLevel.Q, 4);
        BufferedImage defaultLevel = QRCodeAPI.generateQRCode(URL, 330, 330, null, 0);

        assertEquals("Q", errorCorrectionLevel(withMargin));
        assertEquals("M", errorCorrectionLevel(defaultLevel));
        assertEquals(Color.WHITE.getRGB(), withMargin.getRGB(20, 20), "a margem de 4 módulos deveria ser branca");
    }

    @Test
    @DisplayName("com logo, o código usa correção H e continua legível")
    void logoUsesHighErrorCorrection() throws Exception {
        BufferedImage logo = ImageTestSupport.solid(60, 60, BufferedImage.TYPE_INT_ARGB, new Color(200, 30, 30));

        BufferedImage qr = QRCodeAPI.generateQRCodeWithLogo(URL, 300, 300, logo, 60);

        assertEquals("H", errorCorrectionLevel(qr));
        assertEquals(URL, QRCodeAPI.decodeQRCode(qr));
        assertEquals(new Color(200, 30, 30).getRGB(), qr.getRGB(150, 150), "o logo deveria estar no centro");
        assertEquals("M", errorCorrectionLevel(QRCodeAPI.generateQRCodeWithLogo(URL, 300, 300, null, 60)));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCodeWithLogo(URL, 300, 300, logo, 0));
    }

    @Test
    @DisplayName("tamanho pedido menor que o mínimo gera a imagem inteira, sem cortar o código")
    void tooSmallSizeIsNotTruncated() throws Exception {
        BufferedImage qr = QRCodeAPI.generateQRCode(URL, 10, 10);

        assertTrue(qr.getWidth() >= 29, "cortado em " + qr.getWidth() + " pixels");
        assertEquals(qr.getWidth(), qr.getHeight());
    }

    @Test
    @DisplayName("tamanho, margem e texto têm limite: nada de alocar a imagem que a requisição pedir")
    void argumentsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode(URL, 100_000, 100_000));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode(URL, 4097, 300));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode(URL, 0, 300));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode(URL, 300, 300, null, -1));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode(URL, 300, 300, null, 65));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode("   "));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.generateQRCode(null));
    }

    @Test
    @DisplayName("imagem sem QR Code lança NotFoundException; conteúdo que não é imagem lança IOException")
    void readingFailuresAreReported() {
        BufferedImage blank = ImageTestSupport.solid(200, 200, BufferedImage.TYPE_INT_RGB, Color.WHITE);

        assertThrows(NotFoundException.class, () -> QRCodeAPI.decodeQRCode(blank));
        assertThrows(IllegalArgumentException.class, () -> QRCodeAPI.decodeQRCode(null));
        assertThrows(IOException.class, () -> QRCodeAPI.readQRCodeFromBase64("data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(ImageTestSupport.SVG_WITH_SCRIPT)));
        assertThrows(IOException.class, () -> QRCodeAPI.readQRCodeFromFile(directory.resolve("nao-existe.png").toString()));
    }

    @Test
    @DisplayName("a gravação de QR Code falha alto quando o formato não tem codificador")
    void savingToAnUnsupportedFormatFails() throws Exception {
        BufferedImage qr = QRCodeAPI.generateQRCode(URL);

        assertThrows(IOException.class, () -> QRCodeAPI.saveQRCodeToFile(qr, directory.resolve("qr.webp").toString()));
    }

    /** Nível de correção lido pelo ZXing do código gerado. */
    private static String errorCorrectionLevel(BufferedImage qr) throws NotFoundException {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        Result result = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(qr))), hints);
        return String.valueOf(result.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL));
    }
}
