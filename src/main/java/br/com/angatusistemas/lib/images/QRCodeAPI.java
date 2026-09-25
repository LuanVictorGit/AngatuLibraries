package br.com.angatusistemas.lib.images;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Geração e leitura de QR Codes com a biblioteca ZXing.
 *
 * <p>Gera QR Codes a partir de texto ou URL — com tamanho, margem, nível de correção de erro e
 * logo central configuráveis — e lê QR Codes de arquivo, Base64 ou {@link BufferedImage}.</p>
 *
 * <h2>Tamanho, margem e correção de erro</h2>
 * <ul>
 *   <li>Largura e altura vão de 1 a 4096 pixels. Se o conteúdo precisar de mais módulos do que
 *       cabem no tamanho pedido, a imagem sai com o tamanho mínimo que o comporta (maior que o
 *       pedido); se sobrar espaço, o código é centralizado com borda branca.</li>
 *   <li>A margem (<em>quiet zone</em>) é contada em <strong>módulos</strong> — os quadradinhos do
 *       código —, não em pixels, de 0 a 64. O padrão é 0, como sempre foi nesta biblioteca; a norma
 *       do QR Code recomenda 4, e ela faz diferença para câmeras quando o código fica sobre fundo
 *       escuro ou estampado.</li>
 *   <li>O nível de correção padrão é M (recupera cerca de 15% do código). Com logo, o nível é H
 *       (cerca de 30%): o logo cobre parte dos módulos.</li>
 * </ul>
 *
 * <h2>Leitura</h2>
 * <p>A imagem é lida com as mesmas proteções da {@link ImageAPI} — formato reconhecido pelos
 * bytes, limite de pixels conferido pelo cabeçalho antes de decodificar — e o leitor procura só
 * QR Code, sem tentar os formatos de código de barras.</p>
 *
 * <h2>Dependências</h2>
 * <p>Gerar exige {@code com.google.zxing:core:3.5.3}; ler exige também
 * {@code com.google.zxing:javase:3.5.3}. Sem elas, a chamada exibe a instrução de instalação e lança
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}. Toda referência ao
 * ZXing fica numa classe interna carregada só depois dessa verificação — nenhum campo estático da
 * {@code QRCodeAPI} tem tipo do ZXing —, então a classe carrega sem o jar. Os tipos do ZXing que
 * continuam nas assinaturas públicas ({@code ErrorCorrectionLevel}, {@code WriterException},
 * {@code NotFoundException}) não são carregados pela JVM ao usar a classe; só o código que os
 * menciona (um {@code catch (WriterException e)} no projeto) precisa do jar.</p>
 *
 * @author Angatu Sistemas
 * @see ImageAPI
 * @see <a href="https://github.com/zxing/zxing">ZXing no GitHub</a>
 */
public final class QRCodeAPI {

    private static final int DEFAULT_WIDTH = 300;
    private static final int DEFAULT_HEIGHT = 300;
    private static final String DEFAULT_IMAGE_FORMAT = "png";

    /** Margem padrão, em módulos: a mesma desde a primeira versão (ver o Javadoc da classe). */
    private static final int DEFAULT_QUIET_ZONE_MODULES = 0;

    /**
     * Maior largura ou altura aceita, em pixels. Sem teto, um tamanho vindo da requisição alocava
     * a imagem que pedisse — {@code 100000 × 100000} é um OutOfMemoryError.
     */
    private static final int MAX_SIZE_PIXELS = 4096;

    /** Maior margem aceita, em módulos: a norma pede 4; acima de 64 só desperdiça a imagem. */
    private static final int MAX_QUIET_ZONE_MODULES = 64;

    /** Coordenadas Maven das dependências ZXing. */
    private static final String ZXING_CORE_COORDINATES = "com.google.zxing:core:3.5.3";
    private static final String ZXING_JAVASE_COORDINATES = "com.google.zxing:javase:3.5.3";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String QRCODE_FEATURE = "QR Code (ZXing)";

    private QRCodeAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== GERAÇÃO DE QR CODE ====================

    /**
     * Gera um QR Code de 300 × 300 pixels, com correção de erro M e margem padrão (0 módulo).
     *
     * @param text conteúdo do QR Code (URL, texto etc.)
     * @return imagem do QR Code ({@code TYPE_INT_RGB}, preto sobre branco)
     * @throws WriterException          se o ZXing não conseguir codificar o conteúdo (ex.: texto
     *                                  longo demais para um QR Code)
     * @throws IllegalArgumentException se o texto for nulo ou vazio
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core
     */
    public static BufferedImage generateQRCode(String text) throws WriterException {
        return generateQRCode(text, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Gera um QR Code com tamanho escolhido, correção de erro M e margem padrão (0 módulo).
     *
     * @param text   conteúdo do QR Code
     * @param width  largura em pixels (1 a 4096)
     * @param height altura em pixels (1 a 4096)
     * @return imagem do QR Code; maior que o pedido se o conteúdo não couber (ver o Javadoc da classe)
     * @throws WriterException          se o ZXing não conseguir codificar o conteúdo
     * @throws IllegalArgumentException se o texto for vazio ou o tamanho estiver fora do limite
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core
     */
    public static BufferedImage generateQRCode(String text, int width, int height) throws WriterException {
        requireGeneration(text, width, height, DEFAULT_QUIET_ZONE_MODULES);
        return QrSupport.generate(text, width, height, null, DEFAULT_QUIET_ZONE_MODULES, false);
    }

    /**
     * Gera um QR Code com todos os parâmetros.
     *
     * @param text                 conteúdo do QR Code
     * @param width                largura em pixels (1 a 4096)
     * @param height               altura em pixels (1 a 4096)
     * @param errorCorrectionLevel nível de correção de erro (L, M, Q ou H); {@code null} usa M
     * @param quietZoneModules     margem branca em volta do código, em <strong>módulos</strong> (não
     *                             em pixels), de 0 a 64; a norma recomenda 4
     * @return imagem do QR Code; maior que o pedido se o conteúdo não couber (ver o Javadoc da classe)
     * @throws WriterException          se o ZXing não conseguir codificar o conteúdo
     * @throws IllegalArgumentException se o texto for vazio, ou o tamanho ou a margem estiverem fora
     *                                  do limite
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core
     */
    public static BufferedImage generateQRCode(String text, int width, int height,
                                               ErrorCorrectionLevel errorCorrectionLevel,
                                               int quietZoneModules) throws WriterException {
        requireGeneration(text, width, height, quietZoneModules);
        return QrSupport.generate(text, width, height, errorCorrectionLevel, quietZoneModules, false);
    }

    // ==================== MÉTODOS DE SAÍDA ====================

    /**
     * Grava um QR Code em arquivo, no formato da extensão do caminho.
     *
     * <p>Grava com {@link ImageAPI#saveImage(BufferedImage, String)}: falha com {@link IOException}
     * quando nada pode ser gravado (ex.: extensão sem codificador instalado), em vez de voltar sem
     * ter criado o arquivo.</p>
     *
     * @param qrCode   imagem do QR Code
     * @param filePath caminho de destino (ex.: {@code "qrcode.png"})
     * @throws IOException              se a gravação falhar
     * @throws IllegalArgumentException se a imagem ou o caminho forem nulos
     */
    public static void saveQRCodeToFile(BufferedImage qrCode, String filePath) throws IOException {
        ImageAPI.saveImage(qrCode, filePath);
        Console.log("QR Code salvo em: " + filePath);
    }

    /**
     * Converte um QR Code em data URI Base64 ({@code data:image/png;base64,...}).
     *
     * <p>O prefixo leva o tipo MIME oficial do formato ({@code "jpg"} gera {@code image/jpeg}). Não
     * depende do ZXing.</p>
     *
     * @param qrCode imagem do QR Code
     * @param format formato da imagem (ex.: {@code "png"}, {@code "jpg"})
     * @return data URI com a imagem
     * @throws IOException              se não houver codificador para o formato ou a gravação falhar
     * @throws IllegalArgumentException se a imagem ou o formato forem nulos
     */
    public static String qrCodeToBase64(BufferedImage qrCode, String format) throws IOException {
        return ImageAPI.imageToBase64(qrCode, format);
    }

    /**
     * Gera um QR Code e o devolve como data URI Base64 em PNG — útil para APIs REST.
     *
     * @param text   conteúdo do QR Code
     * @param width  largura em pixels (1 a 4096)
     * @param height altura em pixels (1 a 4096)
     * @return data URI {@code data:image/png;base64,...}
     * @throws WriterException          se o ZXing não conseguir codificar o conteúdo
     * @throws IOException              se a conversão para PNG falhar
     * @throws IllegalArgumentException se o texto for vazio ou o tamanho estiver fora do limite
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core
     */
    public static String generateQRCodeAsBase64(String text, int width, int height) throws WriterException, IOException {
        return qrCodeToBase64(generateQRCode(text, width, height), DEFAULT_IMAGE_FORMAT);
    }

    // ==================== LEITURA / DECODIFICAÇÃO DE QR CODE ====================

    /**
     * Lê o QR Code de um arquivo de imagem.
     *
     * <p>O arquivo é lido por {@link ImageAPI#readImage(String)}: formato reconhecido pelo
     * conteúdo e limite de pixels conferido antes de decodificar.</p>
     *
     * @param imagePath caminho da imagem com o QR Code
     * @return texto do QR Code
     * @throws IOException              se o arquivo não existir, não for uma imagem aceita ou passar
     *                                  do limite de pixels
     * @throws NotFoundException        se nenhum QR Code for encontrado na imagem
     * @throws IllegalArgumentException se o caminho for nulo
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core e javase
     */
    public static String readQRCodeFromFile(String imagePath) throws IOException, NotFoundException {
        requireReader();
        return QrSupport.decode(ImageAPI.readImage(imagePath));
    }

    /**
     * Lê o QR Code de uma imagem em Base64 (com ou sem o prefixo de data URI).
     *
     * <p>A imagem é decodificada por {@link ImageAPI#base64ToImage(String)}, com as mesmas
     * proteções de {@link #readQRCodeFromFile(String)}.</p>
     *
     * @param base64 imagem em Base64
     * @return texto do QR Code
     * @throws IOException              se o Base64 for inválido ou não for uma imagem aceita dentro do
     *                                  limite de pixels
     * @throws NotFoundException        se nenhum QR Code for encontrado
     * @throws IllegalArgumentException se {@code base64} for nulo
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core e javase
     */
    public static String readQRCodeFromBase64(String base64) throws IOException, NotFoundException {
        requireReader();
        return QrSupport.decode(ImageAPI.base64ToImage(base64));
    }

    /**
     * Lê o QR Code de uma imagem já carregada.
     *
     * @param image imagem com o QR Code
     * @return texto do QR Code
     * @throws NotFoundException        se nenhum QR Code for encontrado
     * @throws IllegalArgumentException se a imagem for nula
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core e javase
     */
    public static String decodeQRCode(BufferedImage image) throws NotFoundException {
        requireReader();
        if (image == null) {
            throw new IllegalArgumentException("A imagem não pode ser nula.");
        }
        return QrSupport.decode(image);
    }

    // ==================== UTILITÁRIOS ====================

    /**
     * Gera um QR Code e o grava em arquivo, no formato da extensão do caminho.
     *
     * @param text     conteúdo do QR Code
     * @param filePath caminho de destino (ex.: {@code "qrcode.png"})
     * @param width    largura em pixels (1 a 4096)
     * @param height   altura em pixels (1 a 4096)
     * @throws WriterException          se o ZXing não conseguir codificar o conteúdo
     * @throws IOException              se a gravação falhar
     * @throws IllegalArgumentException se o texto for vazio ou o tamanho estiver fora do limite
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core
     */
    public static void generateAndSaveQRCode(String text, String filePath, int width, int height)
            throws WriterException, IOException {
        saveQRCodeToFile(generateQRCode(text, width, height), filePath);
    }

    /**
     * Gera um QR Code com um logo no centro.
     *
     * <p>Com logo, o código é gerado com correção de erro H (cerca de 30%), porque o logo cobre
     * módulos que o leitor precisa reconstruir. Mantenha o logo em até cerca de 25% da largura do
     * código: acima disso, nem o nível H garante a leitura. Sem logo ({@code null}), o resultado é o
     * de {@link #generateQRCode(String, int, int)}, com correção M.</p>
     *
     * @param text     conteúdo do QR Code
     * @param width    largura em pixels (1 a 4096)
     * @param height   altura em pixels (1 a 4096)
     * @param logo     imagem do logo (pode ser {@code null})
     * @param logoSize lado do logo em pixels (o logo é desenhado quadrado, centralizado)
     * @return imagem do QR Code com o logo
     * @throws WriterException          se o ZXing não conseguir codificar o conteúdo
     * @throws IllegalArgumentException se o texto for vazio, o tamanho estiver fora do limite ou,
     *                                  com logo, {@code logoSize} não for positivo
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o ZXing core
     */
    public static BufferedImage generateQRCodeWithLogo(String text, int width, int height,
                                                        BufferedImage logo, int logoSize) throws WriterException {
        requireGeneration(text, width, height, DEFAULT_QUIET_ZONE_MODULES);
        if (logo == null) {
            return QrSupport.generate(text, width, height, null, DEFAULT_QUIET_ZONE_MODULES, false);
        }
        if (logoSize <= 0) {
            throw new IllegalArgumentException("O tamanho do logo deve ser maior que zero: " + logoSize);
        }
        BufferedImage qr = QrSupport.generate(text, width, height, null, DEFAULT_QUIET_ZONE_MODULES, true);
        Graphics2D g = qr.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int x = (qr.getWidth() - logoSize) / 2;
            int y = (qr.getHeight() - logoSize) / 2;
            g.drawImage(ImageAPI.java2dCompatible(logo), x, y, logoSize, logoSize, null);
        } finally {
            g.dispose();
        }
        return qr;
    }

    // ==================== VALIDAÇÃO ====================

    /** Dependência e argumentos da geração, antes de qualquer tipo do ZXing ser tocado. */
    private static void requireGeneration(String text, int width, int height, int quietZoneModules) {
        Dependencies.require("com.google.zxing.qrcode.QRCodeWriter", ZXING_CORE_COORDINATES, QRCODE_FEATURE);
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("O conteúdo do QR Code não pode ser nulo nem vazio.");
        }
        if (width < 1 || height < 1 || width > MAX_SIZE_PIXELS || height > MAX_SIZE_PIXELS) {
            throw new IllegalArgumentException("A largura e a altura do QR Code devem estar entre 1 e "
                    + MAX_SIZE_PIXELS + " pixels: " + width + " × " + height + ".");
        }
        if (quietZoneModules < 0 || quietZoneModules > MAX_QUIET_ZONE_MODULES) {
            throw new IllegalArgumentException("A margem do QR Code deve estar entre 0 e " + MAX_QUIET_ZONE_MODULES
                    + " módulos: " + quietZoneModules + ".");
        }
    }

    private static void requireReader() {
        Dependencies.require("com.google.zxing.MultiFormatReader", ZXING_CORE_COORDINATES, QRCODE_FEATURE);
        Dependencies.require("com.google.zxing.client.j2se.BufferedImageLuminanceSource", ZXING_JAVASE_COORDINATES, QRCODE_FEATURE);
    }

    // ==================== IMPLEMENTAÇÃO (ZXING — LAZY) ====================

    /**
     * Implementação com o ZXing. Classe separada para manter as referências ao ZXing fora do
     * bytecode da {@link QRCodeAPI}: a classe pública carrega sem o zxing, e o guard exibe a
     * mensagem de instalação antes de esta classe ser tocada. O nível de correção padrão também
     * mora aqui — como campo estático da {@code QRCodeAPI}, ele carregava o ZXing junto com a classe.
     */
    private static final class QrSupport {

        private static final int BLACK = 0x000000;
        private static final int WHITE = 0xFFFFFF;

        private QrSupport() {
        }

        static BufferedImage generate(String text, int width, int height, ErrorCorrectionLevel level,
                int quietZoneModules, boolean forLogo) throws WriterException {
            ErrorCorrectionLevel resolved = level != null ? level
                    : forLogo ? ErrorCorrectionLevel.H : ErrorCorrectionLevel.M;
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, resolved);
            hints.put(EncodeHintType.MARGIN, quietZoneModules);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints);
            return toImage(matrix);
        }

        /**
         * Imagem do tamanho da matriz — que é o pedido, ou maior quando o conteúdo não cabe. Usar o
         * tamanho pedido cortava o código nesse caso. Preenche linha a linha direto no raster, sem
         * uma cópia inteira da imagem em memória.
         */
        private static BufferedImage toImage(BitMatrix matrix) {
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            WritableRaster raster = image.getRaster();
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    row[x] = matrix.get(x, y) ? BLACK : WHITE;
                }
                raster.setDataElements(0, y, width, 1, row);
            }
            return image;
        }

        /** Procura só QR Code: mais rápido e sem falso positivo de código de barras. */
        static String decode(BufferedImage image) throws NotFoundException {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
            return new MultiFormatReader().decode(bitmap, hints).getText();
        }
    }
}
