package br.com.angatusistemas.lib.images;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RasterFormatException;
import java.awt.image.WritableRaster;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.w3c.dom.Node;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.name.Rename;

/**
 * Utilitário de imagens raster: leitura e gravação seguras, Base64, redimensionamento,
 * miniaturas, corte, rotação, conversão de formato, GIF animado e conversão para a entidade
 * {@link br.com.angatusistemas.lib.images.objects.Image}, que o {@code Saveable} persiste no
 * SQLite e a {@link ImagesRoute} serve em {@code GET /image?id=...}.
 *
 * <h2>Formatos aceitos na leitura</h2>
 * <p>PNG, JPEG, GIF, BMP e TIFF, com os leitores do próprio JDK, e WebP, com o leitor do
 * TwelveMonkeys no classpath ({@code com.twelvemonkeys.imageio:imageio-webp}). O formato é
 * reconhecido pelos <strong>bytes</strong>, por um {@link ImageReader} do ImageIO — nunca pela
 * extensão do arquivo nem pelo tipo MIME que o chamador informa. Todo o resto é recusado com
 * {@link IOException}: SVG (um documento que executa script, não uma imagem), HTML e os formatos
 * raros cujos leitores não foram feitos para receber arquivo de terceiros (WBMP, PCX, PNM, RAW).
 * BMP com JPEG ou PNG embutido também é recusado: para esse caso, o leitor do JDK aloca o tamanho
 * que o cabeçalho declara, sem conferir.</p>
 *
 * <h2>Limite de pixels — bomba de descompressão</h2>
 * <p>Um PNG de 560 KB pode declarar 12000 × 12000 pixels: decodificado, ocupa 549 MB de heap, e
 * algumas requisições assim ao mesmo tempo derrubam o servidor. Por isso toda leitura consulta
 * primeiro o <strong>cabeçalho</strong> e recusa a imagem cuja largura × altura passe de
 * {@link #getMaxPixels()} (padrão {@link #DEFAULT_MAX_PIXELS}, 40 megapixels) antes de alocar
 * qualquer pixel. O mesmo limite vale para o tamanho de <em>saída</em> pedido a {@link #resize},
 * {@link #resizeMaintainAspect} e às miniaturas.</p>
 *
 * <h2>Orientação EXIF</h2>
 * <p>Foto de celular costuma vir "deitada" no arquivo, com uma marca EXIF dizendo como girá-la.
 * {@link #readImage}, {@link #bytesToImage}, {@link #base64ToImage} e tudo o que é construído
 * sobre eles devolvem a imagem já na posição de exibição — como o Thumbnailator sempre fez em
 * {@link #createThumbnail}. Vale para JPEG, o mesmo formato em que o Thumbnailator aplica a marca.</p>
 *
 * <h2>Gravação</h2>
 * <p>Toda gravação falha com {@link IOException} quando nada pode ser escrito. O
 * {@code ImageIO.write} só devolve {@code false} nesse caso, e era assim que um {@code .jpg} com
 * transparência ou um {@code .webp} (não há codificador WebP instalado: o TwelveMonkeys só lê)
 * "gravavam" um arquivo que não existia. Imagem com transparência gravada em formato sem alfa
 * (JPEG, BMP) é achatada sobre fundo branco.</p>
 *
 * <h2>Efeito global: cache em disco do ImageIO desligado</h2>
 * <p>Ao carregar, esta classe chama {@code ImageIO.setUseCache(false)}, que vale para a JVM
 * inteira. Ligado (o padrão do JDK), cada stream do ImageIO cria um arquivo {@code imageio*.tmp}
 * no disco — e um stream esquecido aberto deixa o arquivo para trás. Com os limites acima, manter
 * o stream em memória é mais barato do que ir ao disco a cada imagem.</p>
 *
 * <h2>Dependências</h2>
 * <p>Leitura, gravação, Base64, corte, rotação e GIF usam só o JDK (mais o TwelveMonkeys para ler
 * WebP). {@link #resizeMaintainAspect}, {@link #createThumbnail} e
 * {@link #batchCreateThumbnails} usam o Thumbnailator ({@code net.coobird:thumbnailator:0.4.21}):
 * sem ele no classpath, a chamada exibe a instrução de instalação e lança
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}, e o resto da classe
 * continua funcionando.</p>
 *
 * <h2>Concorrência</h2>
 * <p>Não há estado mutável compartilhado além do limite de pixels ({@code volatile}): os métodos
 * podem ser chamados de várias threads ao mesmo tempo. Nada é guardado em cache.</p>
 *
 * @author Angatu Sistemas
 * @see ImagesRoute
 * @see QRCodeAPI
 */
public final class ImageAPI {

    /**
     * Limite padrão de pixels (largura × altura) de uma imagem lida ou produzida: 40 megapixels.
     *
     * <p>Cobre foto de celular (12 a 24 MP) e de câmera comum (24 a 36 MP). Uma foto de 48 ou 50 MP
     * em resolução máxima passa do limite: aumente-o com {@link #setMaxPixels(long)} se a aplicação
     * realmente recebe esse tipo de arquivo. Na memória, cada pixel ocupa de 1 a 8 bytes conforme o
     * formato — 40 MP em RGBA de 8 bits são 160 MB por imagem.</p>
     */
    public static final long DEFAULT_MAX_PIXELS = 40_000_000L;

    /** Limite em vigor; {@code volatile} porque é trocado em uma thread e lido em todas. */
    private static volatile long maxPixels = DEFAULT_MAX_PIXELS;

    /** Classe, coordenadas e funcionalidade do Thumbnailator, para a mensagem de dependência ausente. */
    private static final String THUMBNAILATOR_CLASS = "net.coobird.thumbnailator.Thumbnails";
    private static final String THUMBNAILATOR_COORDINATES = "net.coobird:thumbnailator:0.4.21";
    private static final String THUMBNAILATOR_FEATURE = "Redimensionamento e miniaturas de imagem (Thumbnailator)";

    /** Formatos de metadado nativos dos plugins JPEG e GIF do JDK. */
    private static final String JPEG_METADATA_FORMAT = "javax_imageio_jpeg_image_1.0";
    private static final String GIF_METADATA_FORMAT = "javax_imageio_gif_image_1.0";

    /** Limites de {@link #imageUrlToBase64(String)}: tamanho, saltos de redirecionamento e tempo. */
    private static final int MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long DOWNLOAD_DEADLINE_MS = 30_000;

    /** Arquivos que o lote de miniaturas processa — sem diferenciar maiúsculas ({@code FOTO.JPG}). */
    private static final Pattern THUMBNAIL_SOURCE = Pattern.compile("(?i).*\\.(jpg|jpeg|png|gif|bmp)");

    /** Arquivos que {@link #listImageFiles(String)} lista — sem diferenciar maiúsculas. */
    private static final Pattern IMAGE_FILE = Pattern.compile("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp|tiff|tif|svg|ico)");

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private static final String UNSUPPORTED_FORMAT =
            "Formato de imagem não reconhecido ou não aceito (aceitos: PNG, JPEG, GIF, WebP, BMP e TIFF).";

    static {
        // Ver "Efeito global" no Javadoc da classe: sem isto, cada stream cria um imageio*.tmp.
        ImageIO.setUseCache(false);
    }

    private ImageAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== LIMITE DE PIXELS ====================

    /**
     * Limite de pixels (largura × altura) em vigor para a leitura e para o tamanho de saída.
     *
     * @return limite atual, em pixels (padrão {@link #DEFAULT_MAX_PIXELS})
     */
    public static long getMaxPixels() {
        return maxPixels;
    }

    /**
     * Troca o limite de pixels (largura × altura) de toda imagem lida ou produzida por esta classe
     * e pela {@link QRCodeAPI}.
     *
     * <p>Vale para o processo inteiro, a partir da próxima chamada: configure na inicialização da
     * aplicação. O custo de memória cresce com a concorrência — N imagens de P pixels decodificadas
     * ao mesmo tempo ocupam até N × P × 4 bytes (8 bytes por pixel em imagem de 16 bits) — então
     * aumente o limite só com heap para isso.</p>
     *
     * @param pixels novo limite, em pixels (ex.: {@code 50_000_000L} para 50 megapixels)
     * @throws IllegalArgumentException se {@code pixels} não for maior que zero
     */
    public static void setMaxPixels(long pixels) {
        if (pixels <= 0) {
            throw new IllegalArgumentException("O limite de pixels deve ser maior que zero: " + pixels);
        }
        maxPixels = pixels;
    }

    // ==================== BASE64 ====================

    /**
     * Converte uma imagem em data URI Base64 ({@code data:image/png;base64,...}) no formato pedido.
     *
     * <p>O tipo MIME do prefixo é o oficial do formato: {@code "jpg"} gera {@code image/jpeg}, nunca
     * o inexistente {@code image/jpg}. Imagem com transparência pedida em formato sem alfa (JPEG,
     * BMP) é achatada sobre branco.</p>
     *
     * @param image  imagem a converter
     * @param format formato de gravação (ex.: {@code "png"}, {@code "jpg"})
     * @return data URI com a imagem codificada
     * @throws IOException              se não houver codificador para o formato ou a gravação falhar
     * @throws IllegalArgumentException se a imagem ou o formato forem nulos
     */
    public static String imageToBase64(BufferedImage image, String format) throws IOException {
        String name = writerFormat(format);
        return dataUri(mimeTypeOfWriterFormat(name), encode(image, name));
    }

    /**
     * Converte uma imagem em data URI Base64, escolhendo o formato: PNG quando a imagem tem
     * transparência (o JPEG não guarda alfa) e JPEG quando não tem — bem mais compacto para foto.
     *
     * @param image imagem a converter
     * @return data URI ({@code data:image/png;base64,...} ou {@code data:image/jpeg;base64,...})
     * @throws IOException              se a gravação falhar
     * @throws IllegalArgumentException se a imagem for nula
     */
    public static String imageToBase64(BufferedImage image) throws IOException {
        return imageToBase64(image, detectBestFormat(requireImage(image)));
    }

    /**
     * Baixa uma imagem de uma URL e a devolve como data URI Base64, com os bytes originais.
     *
     * <h4>Proteções — a URL costuma vir de quem usa o sistema</h4>
     * <ul>
     *   <li>Só {@code http} e {@code https}; nada de {@code file:}, {@code ftp:} ou {@code jar:}.</li>
     *   <li>O host é resolvido antes da conexão e recusado se <strong>qualquer</strong> endereço dele
     *       for de rede interna, loopback, link-local (inclui o metadado de nuvem
     *       {@code 169.254.169.254}), privada, CGNAT, multicast, reservada ou curinga
     *       ({@code 0.0.0.0}, {@code ::}) — o ataque de SSRF usaria o servidor para ler o que só
     *       ele alcança. A conexão usa a resolução que o cache de DNS da JVM guardou nessa
     *       verificação; com {@code networkaddress.cache.ttl=0} a proteção contra DNS rebinding
     *       deixa de valer.</li>
     *   <li>Redirecionamentos não são seguidos pelo {@link HttpURLConnection}: cada salto (no máximo
     *       5) passa pela mesma verificação.</li>
     *   <li>No máximo 20 MB e 30 s de download; conexão e leitura com prazo de 10 s cada.</li>
     *   <li>O conteúdo precisa ser uma imagem aceita, reconhecida pelos bytes e dentro do limite de
     *       pixels (só o cabeçalho é lido); o tipo MIME do resultado vem dos bytes, nunca do
     *       {@code Content-Type} que o servidor remoto declarou.</li>
     * </ul>
     *
     * @param imageUrl URL da imagem (ex.: {@code "https://exemplo.com/foto.jpg"})
     * @return data URI com o tipo MIME real da imagem
     * @throws IOException              se a URL for recusada, a conexão falhar, a resposta não for
     *                                  {@code 200}, passar dos limites ou não for uma imagem aceita
     * @throws IllegalArgumentException se a URL for nula ou vazia
     */
    public static String imageUrlToBase64(String imageUrl) throws IOException {
        byte[] bytes = download(imageUrl);
        return dataUri(detectMimeType(bytes), bytes);
    }

    /**
     * Converte Base64 em imagem, com ou sem o prefixo de data URI ({@code data:image/...;base64,}).
     *
     * <p>Aceita Base64 quebrado em linhas. A imagem passa pelas mesmas regras de
     * {@link #bytesToImage(byte[])}: formato reconhecido pelos bytes, limite de pixels e orientação
     * EXIF aplicada.</p>
     *
     * @param base64 imagem em Base64
     * @return imagem decodificada, na posição de exibição
     * @throws IOException              se o Base64 for inválido ou vazio, ou se os bytes não forem
     *                                  uma imagem aceita dentro do limite de pixels
     * @throws IllegalArgumentException se {@code base64} for nulo
     */
    public static BufferedImage base64ToImage(String base64) throws IOException {
        return decode(openStream(decodeBase64(base64)));
    }

    /**
     * Decodifica uma imagem em Base64 e a grava em arquivo, no formato da extensão do destino.
     *
     * <p>A imagem é decodificada e codificada de novo (ver {@link #base64ToImage(String)} e
     * {@link #saveImage(BufferedImage, String)}): o arquivo sai no formato da extensão, não
     * necessariamente no do Base64.</p>
     *
     * @param base64   imagem em Base64, com ou sem prefixo
     * @param filePath caminho de destino (ex.: {@code "foto.png"})
     * @throws IOException              se a decodificação ou a gravação falharem
     * @throws IllegalArgumentException se algum argumento for nulo
     */
    public static void saveBase64AsImage(String base64, String filePath) throws IOException {
        saveImage(base64ToImage(base64), filePath);
    }

    // ==================== BYTES ====================

    /**
     * Codifica uma imagem em bytes no formato pedido.
     *
     * <p>Nunca devolve um array vazio: sem codificador para o formato, lança {@link IOException}.
     * Imagem com transparência pedida em formato sem alfa é achatada sobre branco.</p>
     *
     * @param image  imagem a codificar
     * @param format formato (ex.: {@code "png"}, {@code "jpg"})
     * @return bytes do arquivo de imagem
     * @throws IOException              se não houver codificador para o formato ou a gravação falhar
     * @throws IllegalArgumentException se a imagem ou o formato forem nulos
     */
    public static byte[] imageToBytes(BufferedImage image, String format) throws IOException {
        return encode(image, writerFormat(format));
    }

    /**
     * Decodifica bytes de imagem.
     *
     * <p>O formato é reconhecido pelos bytes (ver "Formatos aceitos" no Javadoc da classe); o
     * cabeçalho é conferido contra o limite de pixels antes de qualquer alocação, e a orientação
     * EXIF do JPEG é aplicada.</p>
     *
     * @param bytes bytes do arquivo de imagem
     * @return imagem decodificada, na posição de exibição
     * @throws IOException              se os bytes estiverem vazios, não forem uma imagem aceita ou
     *                                  passarem do limite de pixels
     * @throws IllegalArgumentException se {@code bytes} for nulo
     */
    public static BufferedImage bytesToImage(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IllegalArgumentException("Os bytes da imagem não podem ser nulos.");
        }
        if (bytes.length == 0) {
            throw new IOException("Os bytes da imagem estão vazios.");
        }
        return decode(openStream(bytes));
    }

    /**
     * Grava bytes em arquivo exatamente como vieram, sem validar nem converter nada.
     *
     * <p>Para gravar só o que é imagem de verdade, valide antes com {@link #isValidImage(String)}
     * ou decodifique com {@link #bytesToImage(byte[])}.</p>
     *
     * @param bytes    conteúdo a gravar
     * @param filePath caminho de destino (arquivo existente é substituído)
     * @throws IOException              se a gravação falhar
     * @throws IllegalArgumentException se {@code bytes} for nulo
     */
    public static void saveBytesAsImage(byte[] bytes, String filePath) throws IOException {
        if (bytes == null) {
            throw new IllegalArgumentException("Os bytes da imagem não podem ser nulos.");
        }
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            fos.write(bytes);
        }
        Console.debug("Imagem gravada a partir de bytes: " + filePath);
    }

    /**
     * Lê um arquivo inteiro para bytes, sem decodificar nem validar.
     *
     * @param filePath caminho do arquivo
     * @return conteúdo do arquivo
     * @throws IOException se a leitura falhar
     */
    public static byte[] readImageToBytes(String filePath) throws IOException {
        return Files.readAllBytes(Paths.get(filePath));
    }

    // ==================== LEITURA / ESCRITA ====================

    /**
     * Lê uma imagem de arquivo.
     *
     * <p>O formato é reconhecido pelo conteúdo, não pela extensão; o cabeçalho é conferido contra o
     * limite de pixels antes de decodificar, e a orientação EXIF do JPEG é aplicada.</p>
     *
     * @param filePath caminho do arquivo
     * @return imagem decodificada, na posição de exibição
     * @throws IOException              se o arquivo não existir, não for uma imagem aceita ou passar
     *                                  do limite de pixels
     * @throws IllegalArgumentException se {@code filePath} for nulo
     */
    public static BufferedImage readImage(String filePath) throws IOException {
        return decode(openStream(existingFile(filePath)));
    }

    /**
     * Grava uma imagem em arquivo, no formato definido pela extensão do caminho.
     *
     * <p>Falha com {@link IOException} quando nada pode ser gravado — sem extensão, formato sem
     * codificador instalado (ex.: {@code .webp}) — em vez de voltar sem ter gravado. Imagem com
     * transparência gravada em {@code .jpg} ou {@code .bmp} é achatada sobre branco.</p>
     *
     * @param image    imagem a gravar
     * @param filePath caminho de destino (ex.: {@code "foto.png"}); arquivo existente é substituído
     * @throws IOException              se o caminho não tiver extensão, o diretório não existir, não
     *                                  houver codificador para o formato ou a gravação falhar
     * @throws IllegalArgumentException se a imagem ou o caminho forem nulos
     */
    public static void saveImage(BufferedImage image, String filePath) throws IOException {
        requireImage(image);
        File file = targetFile(filePath);
        String extension = extensionOf(file.getName());
        if (extension == null) {
            throw new IOException("O caminho \"" + filePath
                    + "\" não tem extensão, e é ela que define o formato gravado (ex.: foto.png).");
        }
        encode(image, writerFormat(extension), file);
    }

    /**
     * Grava uma imagem em JPEG com qualidade escolhida.
     *
     * <p>Sempre grava JPEG, qualquer que seja a extensão do caminho — use {@code .jpg}. Imagem com
     * transparência é achatada sobre branco (o JPEG não guarda alfa).</p>
     *
     * @param image    imagem a gravar
     * @param filePath caminho de destino; arquivo existente é substituído
     * @param quality  qualidade de {@code 0.0} (menor arquivo) a {@code 1.0} (melhor imagem)
     * @throws IOException              se a gravação falhar
     * @throws IllegalArgumentException se a imagem ou o caminho forem nulos, ou a qualidade estiver
     *                                  fora de {@code 0.0}–{@code 1.0}
     */
    public static void saveImageWithQuality(BufferedImage image, String filePath, float quality) throws IOException {
        requireImage(image);
        if (!(quality >= 0f && quality <= 1f)) {
            throw new IllegalArgumentException("A qualidade deve estar entre 0.0 e 1.0: " + quality);
        }
        File file = targetFile(filePath);
        BufferedImage encodable = encodable(image, "jpeg");
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException(noEncoderMessage("jpeg"));
        }
        ImageWriter writer = writers.next();
        // FileOutputStream trunca o arquivo; o ImageOutputStream direto sobre o File não trunca, e
        // sobrescrever uma foto maior deixava lixo no fim do arquivo novo.
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            if (ios == null) {
                throw new IOException("Não foi possível abrir o destino da imagem: " + filePath);
            }
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(encodable, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ==================== REDIMENSIONAMENTO ====================

    /**
     * Redimensiona para as dimensões exatas pedidas, sem manter a proporção.
     *
     * <p>A imagem devolvida é {@code TYPE_INT_ARGB} quando a original tem transparência e
     * {@code TYPE_INT_RGB} quando não tem — qualquer que seja o tipo da original (antes, imagens
     * cinza com alfa e PNGs de 16 bits falhavam com "Unknown image type 0").</p>
     *
     * @param image  imagem original
     * @param width  nova largura, em pixels
     * @param height nova altura, em pixels
     * @return nova imagem redimensionada
     * @throws IllegalArgumentException se a imagem for nula, uma dimensão não for positiva ou
     *                                  largura × altura passar do limite de pixels
     */
    public static BufferedImage resize(BufferedImage image, int width, int height) {
        requireImage(image);
        requireOutputSize(width, height);
        boolean alpha = image.getColorModel().hasAlpha();
        Image scaled = java2dCompatible(image).getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage resized = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    /**
     * Redimensiona mantendo a proporção, para caber em {@code maxWidth} × {@code maxHeight}.
     *
     * <p>Usa o Thumbnailator direto sobre a imagem (antes ela era recodificada em PNG a cada
     * chamada, cerca de 5 a 20 vezes mais lento). Imagem menor que a caixa é ampliada até
     * encostar nela, como o Thumbnailator faz. A orientação EXIF já vem aplicada por
     * {@link #readImage}, {@link #bytesToImage} e {@link #base64ToImage}.</p>
     *
     * @param image     imagem original
     * @param maxWidth  largura máxima, em pixels
     * @param maxHeight altura máxima, em pixels
     * @return nova imagem redimensionada
     * @throws IOException              se o redimensionamento falhar
     * @throws IllegalArgumentException se a imagem for nula, uma dimensão não for positiva ou o
     *                                  resultado passar do limite de pixels
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o Thumbnailator
     */
    public static BufferedImage resizeMaintainAspect(BufferedImage image, int maxWidth, int maxHeight)
            throws IOException {
        requireThumbnailator();
        requireImage(image);
        requireFittedSize(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
        return Thumbnailing.fit(java2dCompatible(image), maxWidth, maxHeight);
    }

    /**
     * Cria uma miniatura de um arquivo de imagem, mantendo a proporção.
     *
     * <p>A origem é conferida antes pelo cabeçalho (formato aceito e limite de pixels, inclusive o
     * da miniatura). O Thumbnailator aplica a orientação EXIF e grava no formato da extensão do
     * destino.</p>
     *
     * @param sourcePath caminho da imagem original
     * @param targetPath caminho da miniatura
     * @param maxWidth   largura máxima, em pixels
     * @param maxHeight  altura máxima, em pixels
     * @throws IOException              se a origem não existir, não for uma imagem aceita, passar do
     *                                  limite de pixels ou a gravação falhar
     * @throws IllegalArgumentException se uma dimensão não for positiva ou a miniatura passar do
     *                                  limite de pixels
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o Thumbnailator
     */
    public static void createThumbnail(String sourcePath, String targetPath, int maxWidth, int maxHeight)
            throws IOException {
        requireThumbnailator();
        File source = existingFile(sourcePath);
        checkThumbnailSource(source, maxWidth, maxHeight);
        Thumbnailing.toFile(source, targetFile(targetPath), maxWidth, maxHeight);
    }

    /**
     * Cria miniaturas de todas as imagens de um diretório (sem entrar em subdiretórios).
     *
     * <p>Processa {@code .jpg}, {@code .jpeg}, {@code .png}, {@code .gif} e {@code .bmp}, sem
     * diferenciar maiúsculas ({@code FOTO.JPG} entra). As miniaturas saem no diretório de destino
     * com o prefixo {@code thumbnail.} ({@code foto.jpg} → {@code thumbnail.foto.jpg}). Todas as
     * origens são conferidas antes (formato e limite de pixels): uma inválida interrompe o lote
     * antes de qualquer gravação. Diretório sem imagens não é erro — nada é feito.</p>
     *
     * @param sourceDir diretório com as imagens originais
     * @param targetDir diretório das miniaturas (criado se não existir)
     * @param maxWidth  largura máxima, em pixels
     * @param maxHeight altura máxima, em pixels
     * @throws IOException              se o diretório de origem não existir, uma imagem for inválida
     *                                  ou a gravação falhar
     * @throws IllegalArgumentException se uma dimensão não for positiva ou uma miniatura passar do
     *                                  limite de pixels
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException sem o Thumbnailator
     */
    public static void batchCreateThumbnails(String sourceDir, String targetDir, int maxWidth, int maxHeight)
            throws IOException {
        requireThumbnailator();
        File source = new File(sourceDir);
        if (!source.isDirectory()) {
            throw new FileNotFoundException("Diretório de origem não encontrado: " + sourceDir);
        }
        File[] images = source.listFiles(file -> file.isFile() && THUMBNAIL_SOURCE.matcher(file.getName()).matches());
        if (images == null) {
            throw new IOException("Não foi possível listar o diretório: " + sourceDir);
        }
        if (images.length == 0) {
            Console.debug("Nenhuma imagem para gerar miniatura em: " + sourceDir);
            return;
        }
        for (File image : images) {
            checkThumbnailSource(image, maxWidth, maxHeight);
        }
        File target = new File(targetDir);
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Não foi possível criar o diretório de destino: " + targetDir);
        }
        Thumbnailing.toDirectory(images, target, maxWidth, maxHeight);
    }

    // ==================== CORTE E ROTAÇÃO ====================

    /**
     * Recorta uma região da imagem.
     *
     * <p>O recorte é uma <strong>cópia</strong>: alterar um não altera o outro (antes, o
     * {@code getSubimage} dividia os pixels com a original). O tipo da imagem é mantido.</p>
     *
     * @param image  imagem original
     * @param x      coluna inicial
     * @param y      linha inicial
     * @param width  largura do recorte
     * @param height altura do recorte
     * @return nova imagem com a região recortada
     * @throws IllegalArgumentException se a imagem for nula
     * @throws RasterFormatException    se a região sair dos limites da imagem ou for vazia
     */
    public static BufferedImage crop(BufferedImage image, int x, int y, int width, int height) {
        requireImage(image);
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) x + width > image.getWidth() || (long) y + height > image.getHeight()) {
            throw new RasterFormatException(String.format(
                    "Recorte fora da imagem: região (%d, %d) de %d × %d numa imagem de %d × %d pixels.",
                    x, y, width, height, image.getWidth(), image.getHeight()));
        }
        BufferedImage region = image.getSubimage(x, y, width, height);
        ColorModel model = region.getColorModel();
        WritableRaster copy = region.copyData(region.getRaster().createCompatibleWritableRaster(width, height));
        return new BufferedImage(model, copy, model.isAlphaPremultiplied(), null);
    }

    /**
     * Recorta uma região centralizada.
     *
     * @param image  imagem original
     * @param width  largura do recorte (no máximo a da imagem)
     * @param height altura do recorte (no máximo a da imagem)
     * @return nova imagem com a região central
     * @throws IllegalArgumentException se a imagem for nula
     * @throws RasterFormatException    se o recorte for maior que a imagem ou vazio
     */
    public static BufferedImage cropCenter(BufferedImage image, int width, int height) {
        requireImage(image);
        int x = (image.getWidth() - width) / 2;
        int y = (image.getHeight() - height) / 2;
        return crop(image, x, y, width, height);
    }

    /**
     * Rotaciona a imagem em torno do centro.
     *
     * <p>Feita para 90, 180 e 270 graus (e negativos): nesses ângulos a largura e a altura trocam
     * de lugar quando preciso. Em outros ângulos, a imagem gira dentro das dimensões originais e os
     * cantos se perdem. A imagem devolvida é {@code TYPE_INT_ARGB} quando a original tem
     * transparência e {@code TYPE_INT_RGB} quando não tem.</p>
     *
     * @param image imagem original
     * @param angle ângulo em graus, no sentido horário
     * @return nova imagem rotacionada
     * @throws IllegalArgumentException se a imagem for nula
     */
    public static BufferedImage rotate(BufferedImage image, int angle) {
        requireImage(image);
        boolean alpha = image.getColorModel().hasAlpha();
        BufferedImage source = java2dCompatible(image);
        int w = source.getWidth();
        int h = source.getHeight();
        int newW = (angle % 180 == 0) ? w : h;
        int newH = (angle % 180 == 0) ? h : w;
        BufferedImage rotated = new BufferedImage(newW, newH, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        try {
            g.translate((newW - w) / 2.0, (newH - h) / 2.0);
            g.rotate(Math.toRadians(angle), w / 2.0, h / 2.0);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }

    // ==================== CONVERSÃO DE FORMATOS ====================

    /**
     * Converte um arquivo de imagem para o formato da extensão do destino (ex.: PNG → JPG).
     *
     * <p>Lê com {@link #readImage(String)} — formato pelo conteúdo, limite de pixels, orientação
     * EXIF aplicada — e grava com {@link #saveImage(BufferedImage, String)}.</p>
     *
     * @param sourcePath caminho da imagem original
     * @param targetPath caminho de destino (a extensão define o novo formato)
     * @throws IOException              se a leitura ou a gravação falharem
     * @throws IllegalArgumentException se um caminho for nulo
     */
    public static void convertFormat(String sourcePath, String targetPath) throws IOException {
        saveImage(readImage(sourcePath), targetPath);
    }

    /**
     * Converte um arquivo de imagem para PNG com canal de transparência (ARGB).
     *
     * @param sourcePath caminho da imagem original
     * @param targetPath caminho de destino (deve terminar com {@code .png}; outra extensão grava
     *                   aquele formato)
     * @throws IOException              se a leitura ou a gravação falharem
     * @throws IllegalArgumentException se um caminho for nulo
     */
    public static void convertToPng(String sourcePath, String targetPath) throws IOException {
        saveImage(toStandard(readImage(sourcePath), true, null), targetPath);
    }

    // ==================== CONVERSÃO PARA A ENTIDADE IMAGE (SAVEABLE) ====================

    /**
     * Converte uma imagem na entidade {@link br.com.angatusistemas.lib.images.objects.Image}, pronta
     * para {@code save()}.
     *
     * <p>O formato é escolhido como em {@link #imageToBase64(BufferedImage)}: PNG com
     * transparência, JPEG sem. O tipo MIME gravado é o do formato gerado.</p>
     *
     * @param id    identificador da imagem (obrigatório para gravar: os campos da entidade são
     *              {@code final}, e o {@code Saveable} não consegue injetar um UUID neles)
     * @param image imagem a converter
     * @return entidade com os bytes codificados
     * @throws IllegalArgumentException se a imagem for nula
     * @throws IOException              se a codificação falhar
     */
    public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, BufferedImage image)
            throws IOException {
        requireImage(image);
        String format = writerFormat(detectBestFormat(image));
        return new br.com.angatusistemas.lib.images.objects.Image(id, mimeTypeOfWriterFormat(format), encode(image, format));
    }

    /**
     * Converte um arquivo de imagem (inclusive GIF animado, byte a byte) na entidade
     * {@link br.com.angatusistemas.lib.images.objects.Image}.
     *
     * <p>Os bytes são gravados como estão; o tipo MIME vem do <strong>conteúdo</strong>, reconhecido
     * por um leitor de imagem — nunca da extensão. Um HTML renomeado para {@code .png} ou um SVG
     * (que executa script quando aberto pelo navegador) são recusados. Só o cabeçalho é lido, e ele
     * precisa respeitar o limite de pixels.</p>
     *
     * @param id       identificador da imagem (obrigatório para gravar; ver
     *                 {@link #extractToImageObject(String, BufferedImage)})
     * @param filePath caminho do arquivo
     * @return entidade com os bytes originais e o tipo MIME real
     * @throws IOException              se o arquivo não existir, não for uma imagem aceita (PNG,
     *                                  JPEG, GIF, WebP, BMP ou TIFF) ou passar do limite de pixels
     * @throws IllegalArgumentException se {@code filePath} for nulo
     */
    public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, String filePath)
            throws IOException {
        File file = existingFile(filePath);
        byte[] bytes = Files.readAllBytes(file.toPath());
        String mimeType;
        try {
            mimeType = detectMimeType(bytes);
        } catch (IOException e) {
            throw new IOException("O arquivo não é uma imagem aceita: " + filePath + ". " + e.getMessage(), e);
        }
        return new br.com.angatusistemas.lib.images.objects.Image(id, mimeType, bytes);
    }

    /**
     * Converte bytes de imagem na entidade {@link br.com.angatusistemas.lib.images.objects.Image},
     * com o tipo MIME reconhecido pelo conteúdo.
     *
     * <p>Os bytes são gravados como estão; o tipo MIME vem de um leitor de imagem. Conteúdo que não
     * é uma imagem aceita (PNG, JPEG, GIF, WebP, BMP ou TIFF) — SVG, HTML, PDF — é recusado. Só o
     * cabeçalho é lido, e ele precisa respeitar o limite de pixels.</p>
     *
     * @param id    identificador da imagem (obrigatório para gravar; ver
     *              {@link #extractToImageObject(String, BufferedImage)})
     * @param bytes bytes do arquivo de imagem
     * @return entidade com os bytes e o tipo MIME real
     * @throws IllegalArgumentException se os bytes forem nulos, vazios, não forem uma imagem aceita
     *                                  ou passarem do limite de pixels
     */
    public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Os bytes da imagem não podem ser nulos nem vazios.");
        }
        try {
            return new br.com.angatusistemas.lib.images.objects.Image(id, detectMimeType(bytes), bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Os bytes não são uma imagem aceita. " + e.getMessage(), e);
        }
    }

    /**
     * Converte bytes de imagem na entidade {@link br.com.angatusistemas.lib.images.objects.Image}.
     *
     * <p>O {@code mimeType} informado é <strong>ignorado</strong>: o tipo gravado vem do conteúdo,
     * como em {@link #extractToImageObject(String, byte[])}. Aceitar o tipo do chamador permitia
     * gravar um SVG com script como {@code image/svg+xml}, que a rota {@code /image} servia a partir
     * da origem da própria aplicação.</p>
     *
     * @param id       identificador da imagem
     * @param bytes    bytes do arquivo de imagem
     * @param mimeType ignorado; mantido só por compatibilidade
     * @return entidade com os bytes e o tipo MIME real
     * @throws IllegalArgumentException se os bytes forem nulos, vazios, não forem uma imagem aceita
     *                                  ou passarem do limite de pixels
     * @deprecated o tipo MIME passou a ser reconhecido pelo conteúdo e o parâmetro {@code mimeType}
     *             não tem mais efeito. Use {@link #extractToImageObject(String, byte[])}.
     */
    @Deprecated
    public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, byte[] bytes,
            String mimeType) {
        br.com.angatusistemas.lib.images.objects.Image image = extractToImageObject(id, bytes);
        if (mimeType != null && !mimeType.trim().equalsIgnoreCase(image.getMimeType())) {
            Console.debug("extractToImageObject: tipo informado \"%s\" ignorado; o conteúdo é %s", mimeType,
                    image.getMimeType());
        }
        return image;
    }

    // ==================== GIF ANIMADO ====================

    /**
     * Cria um GIF animado a partir de uma lista de frames.
     *
     * <p>O GIF guarda o atraso em centésimos de segundo: {@code delayMs} é dividido por 10 e limitado
     * a 655350 ms; os navegadores tratam atrasos abaixo de 20 ms como 100 ms. Com
     * {@code loop = true}, o arquivo leva o bloco {@code NETSCAPE2.0} de repetição infinita; sem
     * ele, a animação toca uma vez. Frame com transparência a mantém, e o frame seguinte é desenhado
     * sobre o fundo limpo. A tela do GIF tem o tamanho do primeiro frame: use frames do mesmo
     * tamanho.</p>
     *
     * @param frames     frames da animação, na ordem
     * @param outputPath caminho de saída (ex.: {@code "animacao.gif"}); arquivo existente é substituído
     * @param delayMs    atraso entre frames, em milissegundos
     * @param loop       {@code true} para repetir sem parar; {@code false} para tocar uma vez
     * @throws IOException              se a gravação falhar
     * @throws IllegalArgumentException se não houver frames, algum for nulo ou o atraso for negativo
     */
    public static void createAnimatedGif(List<BufferedImage> frames, String outputPath, int delayMs, boolean loop)
            throws IOException {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um frame para o GIF animado.");
        }
        for (BufferedImage frame : frames) {
            if (frame == null) {
                throw new IllegalArgumentException("Nenhum frame do GIF animado pode ser nulo.");
            }
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("O atraso entre frames não pode ser negativo: " + delayMs);
        }
        File file = targetFile(outputPath);
        // A ordem importa: o writer fecha primeiro (termina a sequência e descarrega o ImageIO no
        // stream), e só depois o arquivo fecha. Sem fechar o writer, o GIF saía com 0 byte.
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                GifSequenceWriter writer = new GifSequenceWriter(out, delayMs, loop)) {
            for (BufferedImage frame : frames) {
                writer.writeToSequence(frame);
            }
        }
        Console.log("GIF animado criado: " + outputPath);
    }

    /**
     * Grava os frames de um GIF animado, um por vez, num único stream.
     *
     * <p>Os metadados são montados por frame, a partir do tipo daquele frame: é o que deixa o
     * codificador do JDK gerar a paleta local de cada um (frames RGB com cores diferentes) e manter
     * a transparência de quem a tem.</p>
     */
    private static final class GifSequenceWriter implements Closeable {

        private final ImageWriter writer;
        private final ImageOutputStream output;
        private final ImageWriteParam params;
        /** Atraso em centésimos de segundo, como o GIF guarda (16 bits sem sinal). */
        private final String delay;
        private final boolean loop;
        private boolean firstFrame = true;

        GifSequenceWriter(OutputStream out, int delayMs, boolean loop) throws IOException {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
            if (!writers.hasNext()) {
                throw new IOException(noEncoderMessage("gif"));
            }
            ImageWriter gifWriter = writers.next();
            ImageOutputStream stream = ImageIO.createImageOutputStream(out);
            if (stream == null) {
                gifWriter.dispose();
                throw new IOException("Não foi possível abrir o destino do GIF animado.");
            }
            try {
                gifWriter.setOutput(stream);
                gifWriter.prepareWriteSequence(null);
            } catch (IOException | RuntimeException e) {
                gifWriter.dispose();
                try {
                    stream.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
            this.writer = gifWriter;
            this.output = stream;
            this.params = gifWriter.getDefaultWriteParam();
            this.delay = Integer.toString(Math.min(delayMs / 10, 0xFFFF));
            this.loop = loop;
        }

        void writeToSequence(BufferedImage frame) throws IOException {
            BufferedImage image = java2dCompatible(frame);
            writer.writeToSequence(new IIOImage(image, null, frameMetadata(image)), params);
            firstFrame = false;
        }

        /**
         * Metadado do frame: atraso e descarte no {@code GraphicControlExtension} (filho direto da
         * raiz) e, só no primeiro frame e só com repetição, o bloco {@code NETSCAPE2.0}.
         */
        private IIOMetadata frameMetadata(BufferedImage image) throws IOException {
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), params);
            String format = metadata.getNativeMetadataFormatName();
            if (!GIF_METADATA_FORMAT.equals(format)) {
                throw new IOException("Codificador GIF inesperado: metadado \"" + format + "\".");
            }
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
            IIOMetadataNode control = childOf(root, "GraphicControlExtension");
            // Transparência (transparentColorFlag/Index) fica como o codificador calculou para o frame.
            control.setAttribute("disposalMethod", image.getColorModel().hasAlpha() ? "restoreToBackgroundColor" : "none");
            control.setAttribute("userInputFlag", "FALSE");
            control.setAttribute("delayTime", delay);
            if (firstFrame && loop) {
                IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
                netscape.setAttribute("applicationID", "NETSCAPE");
                netscape.setAttribute("authenticationCode", "2.0");
                netscape.setUserObject(new byte[] {1, 0, 0}); // sub-bloco 1; repetições = 0 (sem fim)
                childOf(root, "ApplicationExtensions").appendChild(netscape);
            }
            metadata.setFromTree(format, root);
            return metadata;
        }

        private static IIOMetadataNode childOf(IIOMetadataNode root, String name) {
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (name.equals(child.getNodeName())) {
                    return (IIOMetadataNode) child;
                }
            }
            IIOMetadataNode node = new IIOMetadataNode(name);
            root.appendChild(node);
            return node;
        }

        @Override
        public void close() throws IOException {
            try {
                writer.endWriteSequence();
            } finally {
                try {
                    output.close(); // descarrega no stream de quem chamou, que segue aberto
                } finally {
                    writer.dispose();
                }
            }
        }
    }

    // ==================== VÍDEO (NÃO IMPLEMENTADO) ====================

    /**
     * Extração de frames de vídeo — <strong>não implementada</strong>.
     *
     * <p>Este método nunca extraiu frame nenhum: com o JCodec no classpath só registrava um aviso e
     * voltava, e quem chamava seguia achando que os frames existiam. Agora falha sempre.</p>
     *
     * @param videoPath     caminho do vídeo
     * @param outputDir     diretório onde os frames seriam gravados
     * @param frameInterval intervalo entre frames extraídos
     * @throws IOException                   nunca; mantido na assinatura por compatibilidade
     * @throws UnsupportedOperationException sempre
     * @deprecated a AngatuLibraries não decodifica vídeo, e este método será removido. Para extrair
     *             frames, use uma biblioteca de vídeo (JCodec, FFmpeg) diretamente e, se quiser um
     *             GIF, passe os frames a {@link #createAnimatedGif(List, String, int, boolean)}.
     */
    @Deprecated(forRemoval = true)
    public static void extractVideoFrames(String videoPath, String outputDir, int frameInterval) throws IOException {
        throw new UnsupportedOperationException("A extração de frames de vídeo não está implementada na "
                + "AngatuLibraries: use uma biblioteca de vídeo (JCodec, FFmpeg) diretamente.");
    }

    /**
     * Criação de vídeo a partir de imagens — <strong>não implementada</strong>.
     *
     * <p>Este método nunca gerou vídeo: com o JCodec no classpath só registrava um aviso e voltava
     * sem criar o arquivo. Agora falha sempre.</p>
     *
     * @param images     frames do vídeo
     * @param outputPath caminho do vídeo
     * @param width      largura
     * @param height     altura
     * @param fps        quadros por segundo
     * @throws IOException                   nunca; mantido na assinatura por compatibilidade
     * @throws UnsupportedOperationException sempre
     * @deprecated a AngatuLibraries não codifica vídeo, e este método será removido. Use uma
     *             biblioteca de vídeo (JCodec, FFmpeg) diretamente.
     */
    @Deprecated(forRemoval = true)
    public static void createVideoFromImages(List<BufferedImage> images, String outputPath, int width, int height,
            int fps) throws IOException {
        throw new UnsupportedOperationException("A criação de vídeo não está implementada na AngatuLibraries: "
                + "use uma biblioteca de vídeo (JCodec, FFmpeg) diretamente.");
    }

    /**
     * Conversão de vídeo em GIF — <strong>não implementada</strong>.
     *
     * <p>Este método nunca converteu nada: só registrava um aviso e voltava sem criar o GIF. Agora
     * falha sempre.</p>
     *
     * @param videoPath caminho do vídeo
     * @param gifPath   caminho do GIF
     * @param fps       quadros por segundo
     * @throws IOException                   nunca; mantido na assinatura por compatibilidade
     * @throws UnsupportedOperationException sempre
     * @deprecated a AngatuLibraries não decodifica vídeo, e este método será removido. Extraia os
     *             frames com uma biblioteca de vídeo e use
     *             {@link #createAnimatedGif(List, String, int, boolean)}.
     */
    @Deprecated(forRemoval = true)
    public static void videoToGif(String videoPath, String gifPath, int fps) throws IOException {
        throw new UnsupportedOperationException("A conversão de vídeo em GIF não está implementada na "
                + "AngatuLibraries: extraia os frames com uma biblioteca de vídeo e use createAnimatedGif.");
    }

    // ==================== UTILITÁRIOS ====================

    /**
     * Diz se o arquivo é uma imagem em formato aceito e dentro do limite de pixels, lendo
     * <strong>só o cabeçalho</strong>.
     *
     * <p>Não decodifica os pixels (antes decodificava a imagem inteira, e uma bomba de 560 KB
     * alocava 549 MB só para responder {@code true}). Um arquivo truncado depois do cabeçalho ainda
     * responde {@code true}: quem acusa o defeito é a leitura completa, {@link #readImage(String)}.</p>
     *
     * @param filePath caminho do arquivo
     * @return {@code true} se o cabeçalho for de uma imagem aceita dentro do limite de pixels
     */
    public static boolean isValidImage(String filePath) {
        try {
            return inspect(openStream(existingFile(filePath)), true, (reader, header) -> Boolean.TRUE);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Largura e altura de exibição de uma imagem, lendo <strong>só o cabeçalho</strong>.
     *
     * <p>Considera a orientação EXIF do JPEG (uma foto "deitada" com marca de 90° devolve as
     * dimensões trocadas, as mesmas de {@link #readImage(String)}). Não aplica o limite de pixels:
     * serve justamente para descobrir o tamanho de uma imagem grande antes de decidir o que fazer.</p>
     *
     * @param filePath caminho do arquivo
     * @return {@code {largura, altura}}, em pixels
     * @throws IOException              se o arquivo não existir ou não for uma imagem aceita
     * @throws IllegalArgumentException se {@code filePath} for nulo
     */
    public static int[] getImageDimensions(String filePath) throws IOException {
        return inspect(openStream(existingFile(filePath)), false,
                (reader, header) -> new int[] {header.displayWidth(), header.displayHeight()});
    }

    /**
     * Lista, recursivamente, os arquivos com extensão de imagem de um diretório.
     *
     * <p>Filtra pela extensão ({@code jpg}, {@code jpeg}, {@code png}, {@code gif}, {@code bmp},
     * {@code webp}, {@code tiff}, {@code tif}, {@code svg}, {@code ico}), sem diferenciar
     * maiúsculas. Não abre os arquivos: estar na lista não garante que o conteúdo seja imagem nem
     * que {@link #readImage(String)} o aceite (SVG e ICO, por exemplo, são recusados na leitura).</p>
     *
     * @param directory diretório raiz
     * @return caminhos encontrados
     * @throws IOException se o diretório não puder ser percorrido
     */
    public static List<String> listImageFiles(String directory) throws IOException {
        try (Stream<Path> walk = Files.walk(Paths.get(directory))) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> IMAGE_FILE.matcher(p.toString()).matches())
                    .map(Path::toString).collect(Collectors.toList());
        }
    }

    // ==================== LEITURA SEGURA (PACOTE) ====================

    /**
     * Cópia da imagem num tipo que o Java2D desenha certo, ou a própria imagem quando já é.
     *
     * <p>{@code TYPE_CUSTOM} (cinza com alfa, PNG de 16 bits) e imagens em cinza passam para
     * {@code TYPE_INT_ARGB}/{@code TYPE_INT_RGB} por {@code toStandard}: o JDK marca o cinza do PNG
     * como linear e, ao desenhar, clareia os meios-tons (128 vira 188).</p>
     */
    static BufferedImage java2dCompatible(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_CUSTOM && !isComponentGray(image)) {
            return image;
        }
        return toStandard(image, image.getColorModel().hasAlpha(), Color.WHITE);
    }

    /** Tipo MIME real de bytes de imagem, pelo cabeçalho (formato aceito e limite de pixels). */
    static String detectMimeType(byte[] bytes) throws IOException {
        return inspect(openStream(bytes), true, (reader, header) -> header.format().mimeType);
    }

    /** Decodifica, conferindo o limite antes e aplicando a orientação EXIF depois. */
    private static BufferedImage decode(ImageInputStream input) throws IOException {
        return inspect(input, true, (reader, header) ->
                applyOrientation(reader.read(0, reader.getDefaultReadParam()), header.orientation()));
    }

    /** Trabalho feito com o leitor já posicionado e o cabeçalho já conferido. */
    @FunctionalInterface
    private interface HeaderTask<T> {
        T run(ImageReader reader, Header header) throws IOException;
    }

    /**
     * O que o cabeçalho diz: formato, dimensões gravadas e orientação EXIF (1 = normal; 5 a 8
     * trocam largura e altura na exibição).
     */
    private record Header(RasterFormat format, int width, int height, int orientation) {

        int displayWidth() {
            return orientation >= 5 ? height : width;
        }

        int displayHeight() {
            return orientation >= 5 ? width : height;
        }
    }

    /**
     * Formatos aceitos na leitura, com o tipo MIME oficial e os nomes com que os leitores do ImageIO
     * se registram.
     */
    private enum RasterFormat {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpeg", "jpg"),
        GIF("image/gif", "gif"),
        BMP("image/bmp", "bmp"),
        WEBP("image/webp", "webp", "wbp"),
        TIFF("image/tiff", "tiff", "tif");

        final String mimeType;
        private final Set<String> names;

        RasterFormat(String mimeType, String... names) {
            this.mimeType = mimeType;
            this.names = Set.of(names);
        }

        /**
         * Formato aceito do leitor, ou {@code null} se ele não ler nenhum. Leitor sem provedor (não
         * veio do registro do ImageIO) não é aceito.
         */
        static RasterFormat of(ImageReader reader) {
            ImageReaderSpi provider = reader.getOriginatingProvider();
            if (provider == null) {
                return null;
            }
            for (String name : provider.getFormatNames()) {
                String normalized = name.toLowerCase(Locale.ROOT);
                for (RasterFormat format : values()) {
                    if (format.names.contains(normalized)) {
                        return format;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Abre o leitor de um formato aceito, lê o cabeçalho, confere o limite de pixels e só então
     * entrega o leitor a {@code task} — que pode ler os pixels ou só usar o cabeçalho.
     *
     * <p>O metadado é ignorado ({@code ignoreMetadata}) em todo formato menos JPEG, onde mora a
     * orientação EXIF: é o que faz o leitor de PNG pular os blocos de texto comprimido, outra forma
     * de bomba de descompressão.</p>
     */
    private static <T> T inspect(ImageInputStream input, boolean enforceLimit, HeaderTask<T> task) throws IOException {
        try (ImageInputStream stream = input) {
            ImageReader reader = allowedReader(stream);
            try {
                RasterFormat format = RasterFormat.of(reader);
                if (format == RasterFormat.BMP) {
                    rejectEmbeddedBmp(stream);
                }
                reader.setInput(stream, true, format != RasterFormat.JPEG);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("A imagem declara dimensões inválidas: " + width + " × " + height + ".");
                }
                if (enforceLimit) {
                    requirePixelLimit(width, height);
                }
                int orientation = format == RasterFormat.JPEG ? exifOrientation(reader) : 1;
                return task.run(reader, new Header(format, width, height, orientation));
            } catch (RuntimeException e) {
                // Leitor que estoura com exceção de runtime em dado corrompido: para quem chama, é
                // arquivo inválido, e quem só trata IOException não é pego de surpresa.
                throw new IOException("Dados de imagem inválidos ou corrompidos.", e);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Primeiro leitor instalado que reconhece os bytes e é de um formato aceito. */
    private static ImageReader allowedReader(ImageInputStream stream) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (RasterFormat.of(reader) != null) {
                return reader;
            }
            reader.dispose();
        }
        throw new IOException(UNSUPPORTED_FORMAT);
    }

    /**
     * Recusa BMP com JPEG ou PNG embutido (compressão 4 e 5): para eles, o leitor do JDK aloca de
     * uma vez o tamanho declarado no cabeçalho, sem conferir contra o arquivo.
     */
    private static void rejectEmbeddedBmp(ImageInputStream stream) throws IOException {
        stream.mark();
        ByteOrder order = stream.getByteOrder();
        try {
            stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            stream.seek(14);
            long headerSize = stream.readUnsignedInt();
            if (headerSize >= 40) {
                stream.seek(30);
                long compression = stream.readUnsignedInt();
                if (compression == 4 || compression == 5) {
                    throw new IOException("BMP com JPEG ou PNG embutido não é aceito.");
                }
            }
        } finally {
            stream.setByteOrder(order);
            stream.reset();
        }
    }

    private static void requirePixelLimit(long width, long height) throws IOException {
        if (width * height > maxPixels) {
            throw new IOException(tooManyPixels(width, height));
        }
    }

    /** Limite de pixels para uma imagem que vai ser <em>criada</em> com essas dimensões. */
    private static void requireOutputSize(long width, long height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Largura e altura devem ser maiores que zero: " + width + " × " + height + ".");
        }
        if (width * height > maxPixels) {
            throw new IllegalArgumentException(tooManyPixels(width, height));
        }
    }

    /** Confere o tamanho que o encaixe proporcional (inclusive ampliando) vai produzir. */
    private static void requireFittedSize(int width, int height, int maxWidth, int maxHeight) {
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException(
                    "Largura e altura máximas devem ser maiores que zero: " + maxWidth + " × " + maxHeight + ".");
        }
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        requireOutputSize(Math.max(1, (long) Math.ceil(width * scale)), Math.max(1, (long) Math.ceil(height * scale)));
    }

    private static String tooManyPixels(long width, long height) {
        return String.format(PT_BR, "A imagem é grande demais para processar: %d × %d pixels (%.1f megapixels); "
                + "o limite é de %.1f megapixels.", width, height, width * height / 1e6, maxPixels / 1e6);
    }

    /** Confere uma origem de miniatura pelo cabeçalho: formato, limite de pixels e tamanho da saída. */
    private static void checkThumbnailSource(File file, int maxWidth, int maxHeight) throws IOException {
        Header header;
        try {
            header = inspect(openStream(file), true, (reader, h) -> h);
        } catch (IOException e) {
            throw new IOException("Imagem recusada: " + file.getPath() + ". " + e.getMessage(), e);
        }
        requireFittedSize(header.displayWidth(), header.displayHeight(), maxWidth, maxHeight);
    }

    private static ImageInputStream openStream(byte[] bytes) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
        if (stream == null) {
            throw new IOException("Não foi possível abrir os dados da imagem.");
        }
        return stream;
    }

    private static ImageInputStream openStream(File file) throws IOException {
        return new FileImageInputStream(file);
    }

    // ==================== ORIENTAÇÃO EXIF ====================

    /**
     * Orientação EXIF (1 a 8) do JPEG, lida do APP1 {@code Exif} no metadado do leitor — o mesmo
     * lugar em que o Thumbnailator procura. Sem marca, ou com metadado ilegível, devolve 1.
     */
    private static int exifOrientation(ImageReader reader) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata == null) {
                return 1;
            }
            Node root = metadata.getAsTree(JPEG_METADATA_FORMAT);
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!"markerSequence".equals(child.getNodeName())) {
                    continue;
                }
                for (Node marker = child.getFirstChild(); marker != null; marker = marker.getNextSibling()) {
                    if (marker instanceof IIOMetadataNode node && node.getUserObject() instanceof byte[] data) {
                        int orientation = orientationFromExif(data);
                        if (orientation > 0) {
                            return orientation;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Metadado ilegível não impede a leitura: a imagem sai como está gravada — o mesmo que o
            // Thumbnailator faz nesse caso.
        }
        return 1;
    }

    /**
     * Orientação no bloco {@code Exif\0\0} + TIFF: tag {@code 0x0112} do IFD0. Devolve 0 quando o
     * bloco não é EXIF (procurar no próximo), 1 a 8 quando é.
     */
    static int orientationFromExif(byte[] data) {
        if (data == null || data.length < 14 || data[0] != 'E' || data[1] != 'x' || data[2] != 'i' || data[3] != 'f'
                || data[4] != 0 || data[5] != 0) {
            return 0;
        }
        final int tiff = 6;
        boolean littleEndian;
        if (data[tiff] == 'I' && data[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiff] == 'M' && data[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }
        if (unsigned16(data, tiff + 2, littleEndian) != 42) {
            return 1;
        }
        long ifd = tiff + unsigned32(data, tiff + 4, littleEndian);
        if (ifd + 2 > data.length) {
            return 1;
        }
        int entries = unsigned16(data, (int) ifd, littleEndian);
        for (int i = 0; i < entries; i++) {
            long entry = ifd + 2 + 12L * i;
            if (entry + 12 > data.length) {
                break;
            }
            int at = (int) entry;
            if (unsigned16(data, at, littleEndian) != 0x0112) {
                continue;
            }
            int type = unsigned16(data, at + 2, littleEndian);
            long value = type == 3 ? unsigned16(data, at + 8, littleEndian)
                    : type == 4 ? unsigned32(data, at + 8, littleEndian) : -1;
            return value >= 1 && value <= 8 ? (int) value : 1;
        }
        return 1;
    }

    private static int unsigned16(byte[] data, int at, boolean littleEndian) {
        int first = data[at] & 0xFF;
        int second = data[at + 1] & 0xFF;
        return littleEndian ? first | second << 8 : first << 8 | second;
    }

    private static long unsigned32(byte[] data, int at, boolean littleEndian) {
        long high = unsigned16(data, littleEndian ? at + 2 : at, littleEndian);
        long low = unsigned16(data, littleEndian ? at : at + 2, littleEndian);
        return high << 16 | low;
    }

    /**
     * Põe a imagem na posição de exibição que a orientação EXIF pede, copiando linha a linha pelo
     * raster — o tipo e a paleta da imagem são mantidos. Linhas viram colunas nas orientações 5 a 8.
     */
    private static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        boolean transposed = orientation >= 5;
        boolean mirrored = orientation == 2 || orientation == 3 || orientation == 7 || orientation == 8;
        WritableRaster source = image.getRaster();
        WritableRaster target = source.createCompatibleWritableRaster(transposed ? height : width, transposed ? width : height);
        int elements = source.getNumDataElements();
        Object row = null;
        Object reversed = null;
        for (int y = 0; y < height; y++) {
            row = source.getDataElements(0, y, width, 1, row);
            Object data = row;
            if (mirrored) {
                reversed = reversePixels(row, width, elements, reversed);
                data = reversed;
            }
            switch (orientation) {
                case 2 -> target.setDataElements(0, y, width, 1, data);
                case 3, 4 -> target.setDataElements(0, height - 1 - y, width, 1, data);
                case 5, 8 -> target.setDataElements(y, 0, 1, width, data);
                default -> target.setDataElements(height - 1 - y, 0, 1, width, data); // 6 e 7
            }
        }
        ColorModel model = image.getColorModel();
        return new BufferedImage(model, target, model.isAlphaPremultiplied(), null);
    }

    private static Object reversePixels(Object row, int width, int elements, Object reuse) {
        Object out = reuse != null ? reuse : Array.newInstance(row.getClass().getComponentType(), Array.getLength(row));
        for (int x = 0; x < width; x++) {
            System.arraycopy(row, x * elements, out, (width - 1 - x) * elements, elements);
        }
        return out;
    }

    // ==================== GRAVAÇÃO ====================

    /** Codifica em memória, falhando alto quando nada foi escrito. */
    private static byte[] encode(BufferedImage image, String format) throws IOException {
        requireImage(image);
        BufferedImage target = encodable(image, format);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(target, format, out)) {
            throw new IOException(noEncoderMessage(format));
        }
        return out.toByteArray();
    }

    /** Codifica direto no arquivo, falhando alto quando nada foi escrito. */
    private static void encode(BufferedImage image, String format, File file) throws IOException {
        BufferedImage target = encodable(image, format);
        if (!ImageIO.write(target, format, file)) {
            throw new IOException(noEncoderMessage(format));
        }
    }

    /**
     * A imagem num tipo que o codificador do formato aceita, convertida só quando preciso: ARGB se
     * o formato guarda transparência, RGB achatado sobre branco se não guarda (JPEG, BMP).
     */
    private static BufferedImage encodable(BufferedImage image, String format) throws IOException {
        if (canEncode(image, format)) {
            return image;
        }
        if (image.getColorModel().hasAlpha() && image.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage argb = toStandard(image, true, null);
            if (canEncode(argb, format)) {
                return argb;
            }
        }
        BufferedImage rgb = toStandard(image, false, Color.WHITE);
        if (canEncode(rgb, format)) {
            return rgb;
        }
        throw new IOException("O codificador \"" + format + "\" não aceita esta imagem, nem convertida para RGB.");
    }

    private static boolean canEncode(BufferedImage image, String format) {
        return ImageIO.getImageWriters(ImageTypeSpecifier.createFromRenderedImage(image), format).hasNext();
    }

    /** Nome do formato normalizado, garantindo que há codificador instalado para ele. */
    private static String writerFormat(String format) throws IOException {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("O formato da imagem deve ser informado (ex.: \"png\", \"jpg\").");
        }
        String name = format.trim().toLowerCase(Locale.ROOT);
        if (!ImageIO.getImageWritersByFormatName(name).hasNext()) {
            throw new IOException(noEncoderMessage(name));
        }
        return name;
    }

    private static String noEncoderMessage(String format) {
        Set<String> available = new TreeSet<>();
        for (String name : ImageIO.getWriterFormatNames()) {
            available.add(name.toLowerCase(Locale.ROOT));
        }
        String hint = "webp".equals(format) ? " O imageio-webp do TwelveMonkeys só lê WebP." : "";
        return "Não há codificador de imagem instalado para o formato \"" + format + "\"." + hint
                + " Formatos disponíveis para gravação: " + String.join(", ", available) + ".";
    }

    private static String mimeTypeOfWriterFormat(String format) {
        return switch (format) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "wbmp" -> "image/vnd.wap.wbmp";
            case "tif", "tiff" -> "image/tiff";
            case "webp" -> "image/webp";
            default -> "image/" + format;
        };
    }

    private static String dataUri(String mimeType, byte[] bytes) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** Transparência exige PNG (o JPEG não guarda alfa); sem ela, JPEG é bem mais compacto para foto. */
    private static String detectBestFormat(BufferedImage image) {
        return image.getColorModel().hasAlpha() ? "png" : "jpg";
    }

    // ==================== CONVERSÃO DE TIPO ====================

    /**
     * Cópia da imagem em {@code TYPE_INT_ARGB} ({@code keepAlpha}) ou {@code TYPE_INT_RGB}, com a
     * transparência composta sobre {@code background} (branco quando {@code null}).
     *
     * <p>Imagem em cinza é copiada amostra a amostra, tratando o cinza como já codificado para
     * exibição — como o navegador faz. O Java2D a trataria como cinza linear e clarearia os
     * meios-tons.</p>
     */
    private static BufferedImage toStandard(BufferedImage source, boolean keepAlpha, Color background) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target = new BufferedImage(width, height, keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Color fill = background == null ? Color.WHITE : background;
        if (isComponentGray(source)) {
            copyGray(source, target, keepAlpha, fill);
            return target;
        }
        Graphics2D g = target.createGraphics();
        try {
            if (!keepAlpha) {
                g.setColor(fill);
                g.fillRect(0, 0, width, height);
            }
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** Cinza por componentes (com ou sem alfa), inteiro de até 16 bits: o caso que o Java2D clareia. */
    private static boolean isComponentGray(BufferedImage image) {
        ColorModel model = image.getColorModel();
        return model instanceof ComponentColorModel
                && model.getColorSpace().getType() == ColorSpace.TYPE_GRAY
                && model.getNumColorComponents() == 1
                && model.getTransferType() != DataBuffer.TYPE_FLOAT
                && model.getTransferType() != DataBuffer.TYPE_DOUBLE
                && model.getComponentSize(0) <= 16
                && (!model.hasAlpha() || model.getComponentSize(1) <= 16);
    }

    private static void copyGray(BufferedImage source, BufferedImage target, boolean keepAlpha, Color background) {
        ColorModel model = source.getColorModel();
        Raster raster = source.getRaster();
        WritableRaster out = target.getRaster();
        int width = source.getWidth();
        boolean alpha = model.hasAlpha();
        int grayMax = (1 << model.getComponentSize(0)) - 1;
        int alphaMax = alpha ? (1 << model.getComponentSize(1)) - 1 : 255;
        boolean premultiplied = alpha && model.isAlphaPremultiplied();
        int red = background.getRed();
        int green = background.getGreen();
        int blue = background.getBlue();
        int[] grays = new int[width];
        int[] alphas = alpha ? new int[width] : null;
        int[] pixels = new int[width];
        for (int y = 0; y < source.getHeight(); y++) {
            raster.getSamples(0, y, width, 1, 0, grays);
            if (alpha) {
                raster.getSamples(0, y, width, 1, 1, alphas);
            }
            for (int x = 0; x < width; x++) {
                int a = alpha ? to8Bits(alphas[x], alphaMax) : 255;
                int v = to8Bits(grays[x], grayMax);
                if (premultiplied && a > 0 && a < 255) {
                    v = Math.min(255, (v * 255 + a / 2) / a);
                }
                pixels[x] = keepAlpha ? a << 24 | v << 16 | v << 8 | v
                        : blend(v, red, a) << 16 | blend(v, green, a) << 8 | blend(v, blue, a);
            }
            out.setDataElements(0, y, width, 1, pixels);
        }
    }

    private static int to8Bits(int value, int max) {
        return max == 255 ? value : (int) ((value * 255L + max / 2) / max);
    }

    private static int blend(int color, int background, int alpha) {
        return (color * alpha + background * (255 - alpha) + 127) / 255;
    }

    // ==================== DOWNLOAD (imageUrlToBase64) ====================

    /** Baixa seguindo redirecionamentos à mão, com a verificação de destino a cada salto. */
    private static byte[] download(String imageUrl) throws IOException {
        URI uri = httpUri(imageUrl);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DOWNLOAD_DEADLINE_MS);
        for (int redirects = 0;; redirects++) {
            requirePublicDestination(uri);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setRequestProperty("Accept", "image/*");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400 && status != HttpURLConnection.HTTP_NOT_MODIFIED) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("A URL redirecionou (HTTP " + status + ") sem informar o destino.");
                    }
                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("Redirecionamentos demais: mais de " + MAX_REDIRECTS + " saltos.");
                    }
                    uri = httpUri(resolveRedirect(uri, location.trim()));
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("A URL respondeu HTTP " + status + " em vez da imagem.");
                }
                if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) {
                    throw new IOException(downloadTooLarge());
                }
                try (InputStream in = connection.getInputStream()) {
                    return readLimited(in, deadline);
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String resolveRedirect(URI current, String location) throws IOException {
        try {
            return current.resolve(new URI(location)).toString();
        } catch (URISyntaxException e) {
            throw new IOException("A URL redirecionou para um endereço inválido.", e);
        }
    }

    /** URL {@code http}/{@code https} com host; qualquer outra coisa é recusada. */
    private static URI httpUri(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A URL da imagem deve ser informada.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IOException("URL de imagem inválida.", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Só são aceitas URLs http ou https.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("A URL da imagem não tem um host válido.");
        }
        return uri;
    }

    /** Recusa o host se qualquer endereço dele for de rede interna, local ou reservada. */
    private static void requirePublicDestination(URI uri) throws IOException {
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1); // literal IPv6: [::1]
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Host da URL não encontrado: " + host, e);
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                throw new IOException("Endereço não permitido: a URL aponta para uma rede interna, local ou reservada.");
            }
        }
    }

    /**
     * O endereço é de rede interna, loopback, link-local, privada, CGNAT, multicast, reservada,
     * de documentação ou curinga — destino que uma URL vinda de fora não pode alcançar pelo servidor?
     * Endereços IPv6 que embutem um IPv4 (mapeado, NAT64, 6to4) são julgados pelo IPv4 embutido.
     */
    static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] ip = address.getAddress();
        if (ip.length == 4) {
            return isForbiddenIpv4(ip, 0);
        }
        int b0 = ip[0] & 0xFF;
        int b1 = ip[1] & 0xFF;
        if ((b0 & 0xFE) == 0xFC) {
            return true; // fc00::/7 — rede local única (inclui o metadado IPv6 da AWS, fd00:ec2::254)
        }
        if (isZeroed(ip, 0, 10) && (ip[10] & 0xFF) == 0xFF && (ip[11] & 0xFF) == 0xFF) {
            return isForbiddenIpv4(ip, 12); // ::ffff:a.b.c.d — IPv4 mapeado
        }
        if (isZeroed(ip, 0, 12)) {
            return true; // ::a.b.c.d — IPv4 compatível, obsoleto
        }
        if (b0 == 0x00 && b1 == 0x64 && (ip[2] & 0xFF) == 0xFF && (ip[3] & 0xFF) == 0x9B && isZeroed(ip, 4, 12)) {
            return isForbiddenIpv4(ip, 12); // 64:ff9b::/96 — NAT64
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return isForbiddenIpv4(ip, 2); // 2002::/16 — 6to4
        }
        if (b0 == 0x20 && b1 == 0x01 && (ip[2] & 0xFF) == 0x0D && (ip[3] & 0xFF) == 0xB8) {
            return true; // 2001:db8::/32 — documentação
        }
        return b0 == 0x01 && b1 == 0x00 && isZeroed(ip, 2, 8); // 100::/64 — descarte
    }

    private static boolean isForbiddenIpv4(byte[] ip, int at) {
        int a = ip[at] & 0xFF;
        int b = ip[at + 1] & 0xFF;
        int c = ip[at + 2] & 0xFF;
        return a == 0                                // 0.0.0.0/8 — "esta rede"
                || a == 10                           // 10.0.0.0/8 — privada
                || a == 127                          // 127.0.0.0/8 — loopback
                || (a == 100 && (b & 0xC0) == 64)    // 100.64.0.0/10 — CGNAT
                || (a == 169 && b == 254)            // 169.254.0.0/16 — link-local, metadado de nuvem
                || (a == 172 && (b & 0xF0) == 16)    // 172.16.0.0/12 — privada
                || (a == 192 && b == 168)            // 192.168.0.0/16 — privada
                || (a == 192 && b == 0 && c == 0)    // 192.0.0.0/24 — protocolos do IETF
                || (a == 192 && b == 0 && c == 2)    // 192.0.2.0/24 — documentação
                || (a == 198 && (b & 0xFE) == 18)    // 198.18.0.0/15 — testes de desempenho
                || (a == 198 && b == 51 && c == 100) // 198.51.100.0/24 — documentação
                || (a == 203 && b == 0 && c == 113)  // 203.0.113.0/24 — documentação
                || a >= 224;                         // multicast, reservado e broadcast
    }

    private static boolean isZeroed(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readLimited(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IOException(downloadTooLarge());
            }
            if (System.nanoTime() - deadline > 0) {
                throw new IOException("O download da imagem passou do tempo limite de "
                        + TimeUnit.MILLISECONDS.toSeconds(DOWNLOAD_DEADLINE_MS) + " s.");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String downloadTooLarge() {
        return "A imagem da URL passa do limite de " + MAX_DOWNLOAD_BYTES / (1024 * 1024) + " MB.";
    }

    // ==================== ARGUMENTOS ====================

    private static BufferedImage requireImage(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("A imagem não pode ser nula.");
        }
        return image;
    }

    private static File existingFile(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("O caminho do arquivo não pode ser nulo.");
        }
        File file = new File(filePath);
        if (!file.isFile()) {
            throw new FileNotFoundException("Arquivo de imagem não encontrado: " + filePath);
        }
        return file;
    }

    private static File targetFile(String filePath) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("O caminho de destino deve ser informado.");
        }
        File file = new File(filePath);
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            throw new FileNotFoundException("O diretório de destino não existe: " + parent.getPath());
        }
        return file;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : null;
    }

    private static byte[] decodeBase64(String base64) throws IOException {
        if (base64 == null) {
            throw new IllegalArgumentException("A string Base64 não pode ser nula.");
        }
        int comma = base64.indexOf(',');
        String payload = withoutWhitespace(comma >= 0 ? base64.substring(comma + 1) : base64);
        if (payload.isEmpty()) {
            throw new IOException("A string Base64 da imagem está vazia.");
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IOException("Conteúdo Base64 inválido.", e);
        }
    }

    /** Base64 quebrado em linhas (padrão MIME, 76 colunas) é comum; o decodificador básico o recusa. */
    private static String withoutWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                StringBuilder clean = new StringBuilder(text.length());
                for (int j = 0; j < text.length(); j++) {
                    char c = text.charAt(j);
                    if (!Character.isWhitespace(c)) {
                        clean.append(c);
                    }
                }
                return clean.toString();
            }
        }
        return text;
    }

    private static void requireThumbnailator() {
        Dependencies.require(THUMBNAILATOR_CLASS, THUMBNAILATOR_COORDINATES, THUMBNAILATOR_FEATURE);
    }

    // ==================== THUMBNAILATOR (LAZY) ====================

    /**
     * Toda referência ao Thumbnailator mora aqui: a {@link ImageAPI} carrega sem o jar dele, e o
     * {@link Dependencies#require} de cada método público explica o que falta antes de esta classe
     * ser tocada.
     */
    private static final class Thumbnailing {

        private Thumbnailing() {
        }

        static BufferedImage fit(BufferedImage image, int maxWidth, int maxHeight) throws IOException {
            return Thumbnails.of(image).size(maxWidth, maxHeight).keepAspectRatio(true).asBufferedImage();
        }

        static void toFile(File source, File target, int maxWidth, int maxHeight) throws IOException {
            Thumbnails.of(source).size(maxWidth, maxHeight).toFile(target);
        }

        static void toDirectory(File[] sources, File targetDir, int maxWidth, int maxHeight) throws IOException {
            Thumbnails.of(sources).size(maxWidth, maxHeight).toFiles(targetDir, Rename.PREFIX_DOT_THUMBNAIL);
        }
    }
}
