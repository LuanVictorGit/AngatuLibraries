package br.com.angatusistemas.lib.images;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import br.com.angatusistemas.lib.console.Console;

/**
 * [PT] Classe utilitária para geração e leitura de QR Codes utilizando a biblioteca ZXing.
 * <p>
 * Permite criar QR Codes a partir de texto/URL, configurar tamanho, margem, correção de erros,
 * e também decodificar QR Codes a partir de arquivos de imagem ou Base64.
 * </p>
 *
 * [EN] Utility class for generating and reading QR Codes using the ZXing library.
 * <p>
 * Enables creating QR Codes from text/URL, configuring size, margin, error correction,
 * and also decoding QR Codes from image files or Base64.
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://github.com/zxing/zxing">ZXing GitHub</a>
 */
public final class QRCodeAPI {

    private static final int DEFAULT_WIDTH = 300;
    private static final int DEFAULT_HEIGHT = 300;
    private static final String DEFAULT_IMAGE_FORMAT = "png";
    private static final ErrorCorrectionLevel DEFAULT_ERROR_CORRECTION = ErrorCorrectionLevel.M;

    private QRCodeAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== GERAÇÃO DE QR CODE ====================

    /**
     * [PT] Gera um QR Code a partir de um texto e retorna como BufferedImage.
     * <p>
     * Utiliza tamanho padrão (300x300) e correção de erro padrão (M).
     * </p>
     *
     * [EN] Generates a QR Code from a text and returns as BufferedImage.
     * <p>
     * Uses default size (300x300) and default error correction (M).
     * </p>
     *
     * @param text [PT] conteúdo do QR Code (URL, texto, etc.)
     *             [EN] QR Code content (URL, text, etc.)
     * @return [PT] imagem do QR Code
     *         [EN] QR Code image
     * @throws WriterException [PT] se não for possível gerar o QR Code
     *                         [EN] if QR Code generation fails
     */
    public static BufferedImage generateQRCode(String text) throws WriterException {
        return generateQRCode(text, DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_ERROR_CORRECTION, 0);
    }

    /**
     * [PT] Gera um QR Code com tamanho personalizado.
     *
     * [EN] Generates a QR Code with custom size.
     *
     * @param text   [PT] conteúdo do QR Code
     *               [EN] QR Code content
     * @param width  [PT] largura em pixels
     *               [EN] width in pixels
     * @param height [PT] altura em pixels
     *               [EN] height in pixels
     * @return [PT] imagem do QR Code
     *         [EN] QR Code image
     * @throws WriterException [PT] se não for possível gerar
     *                         [EN] if generation fails
     */
    public static BufferedImage generateQRCode(String text, int width, int height) throws WriterException {
        return generateQRCode(text, width, height, DEFAULT_ERROR_CORRECTION, 0);
    }

    /**
     * [PT] Gera um QR Code com parâmetros avançados.
     *
     * [EN] Generates a QR Code with advanced parameters.
     *
     * @param text                [PT] conteúdo do QR Code
     *                            [EN] QR Code content
     * @param width               [PT] largura em pixels
     *                            [EN] width in pixels
     * @param height              [PT] altura em pixels
     *                            [EN] height in pixels
     * @param errorCorrectionLevel [PT] nível de correção de erro (L, M, Q, H)
     *                            [EN] error correction level (L, M, Q, H)
     * @param quietZonePixels     [PT] margem branca ao redor do código (em pixels)
     *                            [EN] white margin around the code (in pixels)
     * @return [PT] imagem do QR Code
     *         [EN] QR Code image
     * @throws WriterException [PT] se não for possível gerar
     *                         [EN] if generation fails
     */
    public static BufferedImage generateQRCode(String text, int width, int height,
                                               ErrorCorrectionLevel errorCorrectionLevel,
                                               int quietZonePixels) throws WriterException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("QR Code text cannot be null or empty");
        }

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, errorCorrectionLevel);
        hints.put(EncodeHintType.MARGIN, quietZonePixels);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height, hints);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, bitMatrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }

    // ==================== MÉTODOS DE SAÍDA ====================

    /**
     * [PT] Salva um QR Code em um arquivo de imagem.
     *
     * [EN] Saves a QR Code to an image file.
     *
     * @param qrCode   [PT] imagem do QR Code
     *                 [EN] QR Code image
     * @param filePath [PT] caminho de destino (ex: "qrcode.png")
     *                 [EN] destination path (e.g., "qrcode.png")
     * @throws IOException [PT] se ocorrer erro de escrita
     *                     [EN] if write error occurs
     */
    public static void saveQRCodeToFile(BufferedImage qrCode, String filePath) throws IOException {
        String extension = filePath.substring(filePath.lastIndexOf('.') + 1);
        ImageIO.write(qrCode, extension, new File(filePath));
        Console.log("QR Code salvo em: " + filePath);
    }

    /**
     * [PT] Converte um QR Code (BufferedImage) para string Base64.
     *
     * [EN] Converts a QR Code (BufferedImage) to Base64 string.
     *
     * @param qrCode [PT] imagem do QR Code
     *               [EN] QR Code image
     * @param format [PT] formato da imagem (ex: "png", "jpg")
     *               [EN] image format (e.g., "png", "jpg")
     * @return [PT] string Base64 com prefixo MIME
     *         [EN] Base64 string with MIME prefix
     * @throws IOException [PT] se ocorrer erro na codificação
     *                     [EN] if encoding error occurs
     */
    public static String qrCodeToBase64(BufferedImage qrCode, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(qrCode, format, baos);
        byte[] bytes = baos.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return "data:image/" + format + ";base64," + base64;
    }

    /**
     * [PT] Gera um QR Code e já retorna como Base64 (útil para APIs REST).
     *
     * [EN] Generates a QR Code and returns as Base64 (useful for REST APIs).
     *
     * @param text   [PT] conteúdo do QR Code
     *               [EN] QR Code content
     * @param width  [PT] largura
     *               [EN] width
     * @param height [PT] altura
     *               [EN] height
     * @return [PT] string Base64 do QR Code
     *         [EN] Base64 string of the QR Code
     * @throws WriterException [PT] se falhar na geração
     *                         [EN] if generation fails
     * @throws IOException     [PT] se falhar na conversão Base64
     *                         [EN] if Base64 conversion fails
     */
    public static String generateQRCodeAsBase64(String text, int width, int height) throws WriterException, IOException {
        BufferedImage qr = generateQRCode(text, width, height);
        return qrCodeToBase64(qr, DEFAULT_IMAGE_FORMAT);
    }

    // ==================== LEITURA / DECODIFICAÇÃO DE QR CODE ====================

    /**
     * [PT] Lê/decodifica um QR Code a partir de um arquivo de imagem.
     *
     * [EN] Reads/decodes a QR Code from an image file.
     *
     * @param imagePath [PT] caminho da imagem contendo o QR Code
     *                  [EN] path to the image containing the QR Code
     * @return [PT] texto decodificado do QR Code (ou null se não encontrado)
     *         [EN] decoded text from QR Code (or null if not found)
     * @throws IOException [PT] se ocorrer erro de leitura
     *                     [EN] if read error occurs
     * @throws NotFoundException [PT] se nenhum QR Code for encontrado na imagem
     *                           [EN] if no QR Code is found in the image
     */
    public static String readQRCodeFromFile(String imagePath) throws IOException, NotFoundException {
        BufferedImage image = ImageIO.read(new File(imagePath));
        if (image == null) {
            throw new IOException("Could not read image: " + imagePath);
        }
        return decodeQRCode(image);
    }

    /**
     * [PT] Lê/decodifica um QR Code a partir de uma string Base64.
     *
     * [EN] Reads/decodes a QR Code from a Base64 string.
     *
     * @param base64 [PT] string Base64 da imagem (com ou sem prefixo)
     *               [EN] Base64 string of the image (with or without prefix)
     * @return [PT] texto decodificado
     *         [EN] decoded text
     * @throws IOException     [PT] se falhar na leitura da imagem
     *                         [EN] if image reading fails
     * @throws NotFoundException [PT] se nenhum QR Code for encontrado
     *                           [EN] if no QR Code is found
     */
    public static String readQRCodeFromBase64(String base64) throws IOException, NotFoundException {
        String clean = base64.contains(",") ? base64.split(",")[1] : base64;
        byte[] bytes = Base64.getDecoder().decode(clean);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(bais);
            if (image == null) {
                throw new IOException("Could not decode Base64 to image");
            }
            return decodeQRCode(image);
        }
    }

    /**
     * [PT] Decodifica um QR Code a partir de um BufferedImage.
     *
     * [EN] Decodes a QR Code from a BufferedImage.
     *
     * @param image [PT] imagem contendo o QR Code
     *              [EN] image containing the QR Code
     * @return [PT] texto decodificado
     *         [EN] decoded text
     * @throws NotFoundException [PT] se nenhum QR Code for encontrado
     *                           [EN] if no QR Code is found
     */
    public static String decodeQRCode(BufferedImage image) throws NotFoundException {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Result result = new MultiFormatReader().decode(bitmap);
        return result.getText();
    }

    // ==================== UTILITÁRIOS ====================

    /**
     * [PT] Gera um QR Code e o salva diretamente no disco.
     *
     * [EN] Generates a QR Code and saves it directly to disk.
     *
     * @param text      [PT] conteúdo
     *                  [EN] content
     * @param filePath  [PT] caminho de destino
     *                  [EN] destination path
     * @param width     [PT] largura
     *                  [EN] width
     * @param height    [PT] altura
     *                  [EN] height
     * @throws WriterException [PT] se falhar na geração
     * @throws IOException     [PT] se falhar na escrita
     */
    public static void generateAndSaveQRCode(String text, String filePath, int width, int height)
            throws WriterException, IOException {
        BufferedImage qr = generateQRCode(text, width, height);
        saveQRCodeToFile(qr, filePath);
    }

    /**
     * [PT] Gera um QR Code em preto e branco com logo central opcional.
     * <p>
     * Útil para criar QR Codes personalizados com marca d'água.
     * </p>
     *
     * [EN] Generates a black-and-white QR Code with an optional central logo.
     * <p>
     * Useful for creating branded QR Codes with a watermark.
     * </p>
     *
     * @param text       [PT] conteúdo
     *                   [EN] content
     * @param width      [PT] largura
     *                   [EN] width
     * @param height     [PT] altura
     *                   [EN] height
     * @param logo       [PT] imagem do logo (pode ser null)
     *                   [EN] logo image (may be null)
     * @param logoSize   [PT] tamanho do logo em pixels (largura = altura)
     *                   [EN] logo size in pixels (width = height)
     * @return [PT] imagem do QR Code com logo
     *         [EN] QR Code image with logo
     * @throws WriterException [PT] se falhar na geração
     *                         [EN] if generation fails
     */
    public static BufferedImage generateQRCodeWithLogo(String text, int width, int height,
                                                        BufferedImage logo, int logoSize) throws WriterException {
        BufferedImage qr = generateQRCode(text, width, height);
        if (logo == null) return qr;

        Graphics2D g = qr.createGraphics();
        int x = (width - logoSize) / 2;
        int y = (height - logoSize) / 2;
        g.drawImage(logo, x, y, logoSize, logoSize, null);
        g.dispose();
        return qr;
    }
}