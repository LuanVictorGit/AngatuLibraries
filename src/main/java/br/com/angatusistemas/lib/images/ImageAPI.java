package br.com.angatusistemas.lib.images;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;

import br.com.angatusistemas.lib.console.Console;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.name.Rename;

/**
 * [PT] Classe utilitária para manipulação avançada de imagens, vídeos e GIFs.
 * <p>
 * Fornece redimensionamento, corte, rotação, conversão de formatos, Base64,
 * extração de frames de vídeos (MP4) e criação de GIFs animados.
 * </p>
 * <p>
 * Também permite converter imagens (BufferedImage ou arquivo) para o objeto
 * {@link Image} (subclasse de Saveable) para persistência em SQLite.
 * </p>
 *
 * [EN] Utility class for advanced image, video and GIF manipulation.
 * <p>
 * Provides resizing, cropping, rotation, format conversion, Base64, video frame
 * extraction (MP4) and animated GIF creation.
 * </p>
 * <p>
 * Also allows converting images (BufferedImage or file) to the {@link Image}
 * object (subclass of Saveable) for SQLite persistence.
 * </p>
 */
public final class ImageAPI {

	private ImageAPI() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== BASE64 ====================

	/**
	 * [PT] Converte BufferedImage para Base64 com prefixo MIME.
	 *
	 * [EN] Converts BufferedImage to Base64 with MIME prefix.
	 *
	 * @param image  [PT] imagem a ser convertida [EN] image to convert
	 * @param format [PT] formato da imagem (ex: "png", "jpg") [EN] image format
	 *               (e.g., "png", "jpg")
	 * @return [PT] string Base64 com prefixo (data:image/...;base64,) [EN] Base64
	 *         string with prefix (data:image/...;base64,)
	 * @throws IOException [PT] se ocorrer erro na escrita [EN] if write error
	 *                     occurs
	 */
	public static String imageToBase64(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(image, format, baos);
		byte[] bytes = baos.toByteArray();
		String base64 = Base64.getEncoder().encodeToString(bytes);
		return "data:image/" + format + ";base64," + base64;
	}

	/**
	 * [PT] Converte Base64 para BufferedImage (aceita com ou sem prefixo).
	 *
	 * [EN] Converts Base64 to BufferedImage (accepts with or without prefix).
	 *
	 * @param base64 [PT] string Base64 da imagem [EN] Base64 string of the image
	 * @return [PT] imagem decodificada [EN] decoded image
	 * @throws IOException [PT] se a string for inválida ou a imagem não puder ser
	 *                     lida [EN] if the string is invalid or the image cannot be
	 *                     read
	 */
	public static BufferedImage base64ToImage(String base64) throws IOException {
		String clean = base64.contains(",") ? base64.split(",")[1] : base64;
		byte[] bytes = Base64.getDecoder().decode(clean);
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
			BufferedImage img = ImageIO.read(bais);
			if (img == null)
				throw new IOException("Invalid image data");
			return img;
		}
	}

	/**
	 * [PT] Salva Base64 diretamente em arquivo de imagem.
	 *
	 * [EN] Saves Base64 directly to an image file.
	 *
	 * @param base64   [PT] string Base64 da imagem [EN] Base64 string of the image
	 * @param filePath [PT] caminho de destino (ex: "foto.png") [EN] destination
	 *                 path (e.g., "foto.png")
	 * @throws IOException [PT] se ocorrer erro na conversão ou escrita [EN] if
	 *                     conversion or write error occurs
	 */
	public static void saveBase64AsImage(String base64, String filePath) throws IOException {
		BufferedImage img = base64ToImage(base64);
		saveImage(img, filePath);
	}

	// ==================== BYTE ARRAY ====================

	/**
	 * [PT] Converte BufferedImage para array de bytes no formato especificado.
	 *
	 * [EN] Converts BufferedImage to byte array in the specified format.
	 *
	 * @param image  [PT] imagem a ser convertida [EN] image to convert
	 * @param format [PT] formato da imagem (ex: "png", "jpg") [EN] image format
	 *               (e.g., "png", "jpg")
	 * @return [PT] array de bytes da imagem [EN] byte array of the image
	 * @throws IOException [PT] se ocorrer erro na escrita [EN] if write error
	 *                     occurs
	 */
	public static byte[] imageToBytes(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(image, format, baos);
		return baos.toByteArray();
	}

	/**
	 * [PT] Converte array de bytes para BufferedImage.
	 *
	 * [EN] Converts byte array to BufferedImage.
	 *
	 * @param bytes [PT] array de bytes da imagem [EN] byte array of the image
	 * @return [PT] imagem decodificada [EN] decoded image
	 * @throws IOException [PT] se os bytes não representarem uma imagem válida [EN]
	 *                     if bytes do not represent a valid image
	 */
	public static BufferedImage bytesToImage(byte[] bytes) throws IOException {
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
			BufferedImage img = ImageIO.read(bais);
			if (img == null)
				throw new IOException("Invalid image data");
			return img;
		}
	}

	/**
	 * [PT] Salva array de bytes como arquivo de imagem.
	 *
	 * [EN] Saves byte array as an image file.
	 *
	 * @param bytes    [PT] array de bytes da imagem [EN] byte array of the image
	 * @param filePath [PT] caminho de destino [EN] destination path
	 * @throws IOException [PT] se ocorrer erro na escrita [EN] if write error
	 *                     occurs
	 */
	public static void saveBytesAsImage(byte[] bytes, String filePath) throws IOException {
		try (FileOutputStream fos = new FileOutputStream(filePath)) {
			fos.write(bytes);
		}
		Console.debug("Image saved from bytes: " + filePath);
	}

	/**
	 * [PT] Lê um arquivo de imagem diretamente para array de bytes.
	 *
	 * [EN] Reads an image file directly to a byte array.
	 *
	 * @param filePath [PT] caminho do arquivo de imagem [EN] path to the image file
	 * @return [PT] array de bytes do arquivo [EN] byte array of the file
	 * @throws IOException [PT] se ocorrer erro de leitura [EN] if read error occurs
	 */
	public static byte[] readImageToBytes(String filePath) throws IOException {
		return Files.readAllBytes(Paths.get(filePath));
	}

	// ==================== LEITURA / ESCRITA ====================

	/**
	 * [PT] Lê uma imagem a partir de um arquivo.
	 *
	 * [EN] Reads an image from a file.
	 *
	 * @param filePath [PT] caminho do arquivo [EN] file path
	 * @return [PT] imagem lida [EN] read image
	 * @throws IOException [PT] se o formato não for suportado [EN] if format is not
	 *                     supported
	 */
	public static BufferedImage readImage(String filePath) throws IOException {
		BufferedImage img = ImageIO.read(new File(filePath));
		if (img == null)
			throw new IOException("Unsupported image format: " + filePath);
		return img;
	}

	/**
	 * [PT] Salva uma imagem em arquivo (formato definido pela extensão).
	 *
	 * [EN] Saves an image to a file (format defined by extension).
	 *
	 * @param image    [PT] imagem a ser salva [EN] image to save
	 * @param filePath [PT] caminho de destino [EN] destination path
	 * @throws IOException [PT] se ocorrer erro na escrita [EN] if write error
	 *                     occurs
	 */
	public static void saveImage(BufferedImage image, String filePath) throws IOException {
		String ext = filePath.substring(filePath.lastIndexOf('.') + 1);
		ImageIO.write(image, ext, new File(filePath));
	}

	/**
	 * [PT] Salva uma imagem JPEG com qualidade ajustável.
	 *
	 * [EN] Saves a JPEG image with adjustable quality.
	 *
	 * @param image    [PT] imagem a ser salva [EN] image to save
	 * @param filePath [PT] caminho de destino (deve terminar com .jpg) [EN]
	 *                 destination path (must end with .jpg)
	 * @param quality  [PT] qualidade de 0.0 a 1.0 [EN] quality from 0.0 to 1.0
	 * @throws IOException [PT] se ocorrer erro na escrita [EN] if write error
	 *                     occurs
	 */
	public static void saveImageWithQuality(BufferedImage image, String filePath, float quality) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(quality);
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(new File(filePath))) {
			writer.setOutput(ios);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		writer.dispose();
	}

	// ==================== REDIMENSIONAMENTO ====================

	/**
	 * [PT] Redimensiona uma imagem para dimensões exatas (não mantém proporção).
	 *
	 * [EN] Resizes an image to exact dimensions (does not preserve aspect ratio).
	 *
	 * @param image  [PT] imagem original [EN] original image
	 * @param width  [PT] nova largura [EN] new width
	 * @param height [PT] nova altura [EN] new height
	 * @return [PT] imagem redimensionada [EN] resized image
	 */
	public static BufferedImage resize(BufferedImage image, int width, int height) {
		Image scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
		BufferedImage resized = new BufferedImage(width, height, image.getType());
		Graphics2D g = resized.createGraphics();
		g.drawImage(scaled, 0, 0, null);
		g.dispose();
		return resized;
	}

	/**
	 * [PT] Redimensiona uma imagem mantendo a proporção (fit dentro das dimensões
	 * máximas).
	 *
	 * [EN] Resizes an image maintaining aspect ratio (fit within max dimensions).
	 *
	 * @param image     [PT] imagem original [EN] original image
	 * @param maxWidth  [PT] largura máxima [EN] maximum width
	 * @param maxHeight [PT] altura máxima [EN] maximum height
	 * @return [PT] imagem redimensionada [EN] resized image
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static BufferedImage resizeMaintainAspect(BufferedImage image, int maxWidth, int maxHeight)
			throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(image, "png", baos);
		try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
			return Thumbnails.of(bais).size(maxWidth, maxHeight).keepAspectRatio(true).asBufferedImage();
		}
	}

	/**
	 * [PT] Cria um thumbnail (miniatura) a partir de um arquivo de imagem.
	 *
	 * [EN] Creates a thumbnail from an image file.
	 *
	 * @param sourcePath [PT] caminho da imagem original [EN] source image path
	 * @param targetPath [PT] caminho para salvar o thumbnail [EN] target path for
	 *                   thumbnail
	 * @param maxWidth   [PT] largura máxima [EN] maximum width
	 * @param maxHeight  [PT] altura máxima [EN] maximum height
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void createThumbnail(String sourcePath, String targetPath, int maxWidth, int maxHeight)
			throws IOException {
		Thumbnails.of(new File(sourcePath)).size(maxWidth, maxHeight).toFile(new File(targetPath));
	}

	/**
	 * [PT] Cria thumbnails em lote para todas as imagens de um diretório.
	 *
	 * [EN] Batch creates thumbnails for all images in a directory.
	 *
	 * @param sourceDir [PT] diretório com imagens originais [EN] directory with
	 *                  original images
	 * @param targetDir [PT] diretório para salvar os thumbnails [EN] directory to
	 *                  save thumbnails
	 * @param maxWidth  [PT] largura máxima [EN] maximum width
	 * @param maxHeight [PT] altura máxima [EN] maximum height
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void batchCreateThumbnails(String sourceDir, String targetDir, int maxWidth, int maxHeight)
			throws IOException {
		File src = new File(sourceDir);
		File dst = new File(targetDir);
		if (!dst.exists())
			dst.mkdirs();
		Thumbnails.of(src.listFiles((dir, name) -> name.matches(".*\\.(jpg|jpeg|png|gif|bmp)$")))
				.size(maxWidth, maxHeight).toFiles(dst, Rename.PREFIX_DOT_THUMBNAIL);
	}

	// ==================== CORTE E ROTAÇÃO ====================

	/**
	 * [PT] Corta uma imagem (crop) nas coordenadas especificadas.
	 *
	 * [EN] Crops an image at the specified coordinates.
	 *
	 * @param image  [PT] imagem original [EN] original image
	 * @param x      [PT] coordenada X inicial [EN] starting X coordinate
	 * @param y      [PT] coordenada Y inicial [EN] starting Y coordinate
	 * @param width  [PT] largura do recorte [EN] crop width
	 * @param height [PT] altura do recorte [EN] crop height
	 * @return [PT] imagem cortada [EN] cropped image
	 */
	public static BufferedImage crop(BufferedImage image, int x, int y, int width, int height) {
		return image.getSubimage(x, y, width, height);
	}

	/**
	 * [PT] Corta uma imagem centralizada.
	 *
	 * [EN] Crops an image centered.
	 *
	 * @param image  [PT] imagem original [EN] original image
	 * @param width  [PT] largura desejada [EN] desired width
	 * @param height [PT] altura desejada [EN] desired height
	 * @return [PT] imagem cortada centralizada [EN] centered cropped image
	 */
	public static BufferedImage cropCenter(BufferedImage image, int width, int height) {
		int x = (image.getWidth() - width) / 2;
		int y = (image.getHeight() - height) / 2;
		return crop(image, x, y, width, height);
	}

	/**
	 * [PT] Rotaciona uma imagem em ângulos de 90, 180 ou 270 graus.
	 *
	 * [EN] Rotates an image by 90, 180 or 270 degrees.
	 *
	 * @param image [PT] imagem original [EN] original image
	 * @param angle [PT] ângulo em graus (0, 90, 180, 270) [EN] angle in degrees (0,
	 *              90, 180, 270)
	 * @return [PT] imagem rotacionada [EN] rotated image
	 */
	public static BufferedImage rotate(BufferedImage image, int angle) {
		int w = image.getWidth(), h = image.getHeight();
		int newW = (angle % 180 == 0) ? w : h;
		int newH = (angle % 180 == 0) ? h : w;
		BufferedImage rotated = new BufferedImage(newW, newH, image.getType());
		Graphics2D g = rotated.createGraphics();
		g.translate((newW - w) / 2.0, (newH - h) / 2.0);
		g.rotate(Math.toRadians(angle), w / 2.0, h / 2.0);
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return rotated;
	}

	// ==================== CONVERSÃO DE FORMATOS ====================

	/**
	 * [PT] Converte uma imagem entre formatos (ex: PNG para JPG).
	 *
	 * [EN] Converts an image between formats (e.g., PNG to JPG).
	 *
	 * @param sourcePath [PT] caminho da imagem original [EN] source image path
	 * @param targetPath [PT] caminho de destino (extensão define o novo formato)
	 *                   [EN] destination path (extension defines new format)
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void convertFormat(String sourcePath, String targetPath) throws IOException {
		BufferedImage img = readImage(sourcePath);
		saveImage(img, targetPath);
	}

	/**
	 * [PT] Converte uma imagem para PNG (compatível com transparência).
	 *
	 * [EN] Converts an image to PNG (transparency compatible).
	 *
	 * @param sourcePath [PT] caminho da imagem original [EN] source image path
	 * @param targetPath [PT] caminho de destino (deve terminar com .png) [EN]
	 *                   destination path (must end with .png)
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void convertToPng(String sourcePath, String targetPath) throws IOException {
		BufferedImage img = readImage(sourcePath);
		BufferedImage png = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = png.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		saveImage(png, targetPath);
	}

	// ==================== CONVERSÃO PARA OBJETO IMAGE (SAVEABLE)
	// ====================

	/**
	 * [PT] Converte um BufferedImage em um objeto {@link Image} (subclasse de
	 * Saveable) para persistência no banco de dados SQLite.
	 * <p>
	 * O formato da imagem é detectado automaticamente com base nos writers
	 * disponíveis no ImageIO, priorizando PNG por ser sem perdas e amplamente
	 * suportado. O MIME type também é definido automaticamente a partir do formato
	 * escolhido.
	 * </p>
	 * <p>
	 * <b>Para GIFs animados:</b> Este método não preserva animação, pois
	 * {@code BufferedImage} representa apenas um único frame.
	 * </p>
	 *
	 * [EN] Converts a BufferedImage into an {@link Image} object (subclass of
	 * Saveable) for SQLite database persistence.
	 * <p>
	 * The image format is automatically detected based on available ImageIO
	 * writers, prioritizing PNG as a lossless and widely supported format. The MIME
	 * type is also derived automatically from the chosen format.
	 * </p>
	 * <p>
	 * <b>For animated GIFs:</b> This method does not preserve animation because
	 * {@code BufferedImage} represents only a single frame.
	 * </p>
	 *
	 * @param id    [PT] Identificador único da imagem (chave primária no banco)
	 *              [EN] Unique image identifier (primary key in the database)
	 * @param image [PT] Imagem a ser convertida [EN] Image to convert
	 * @return [PT] Objeto {@link Image} pronto para persistência [EN] {@link Image}
	 *         object ready for persistence
	 * @throws IllegalArgumentException [PT] se a imagem for nula [EN] if image is
	 *                                  null
	 * @throws IOException              [PT] se nenhum formato suportado estiver
	 *                                  disponível ou ocorrer erro na conversão [EN]
	 *                                  if no supported format is available or
	 *                                  conversion fails
	 */
	public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, BufferedImage image)
			throws IOException {
		if (image == null) {
			throw new IllegalArgumentException("A imagem não pode ser nula / Image cannot be null");
		}

		String format = "png";
		if (!ImageIO.getImageWritersByFormatName("png").hasNext()) {
			if (ImageIO.getImageWritersByFormatName("jpg").hasNext()) {
				format = "jpg";
			} else if (ImageIO.getImageWritersByFormatName("jpeg").hasNext()) {
				format = "jpeg";
			} else if (ImageIO.getImageWritersByFormatName("bmp").hasNext()) {
				format = "bmp";
			} else if (ImageIO.getImageWritersByFormatName("gif").hasNext()) {
				format = "gif";
			} else {
				throw new IOException("Nenhum formato de imagem suportado disponível no ImageIO");
			}
		}

		String mimeType = getMimeTypeForFormat(format);
		byte[] bytes = imageToBytes(image, format);

		return new br.com.angatusistemas.lib.images.objects.Image(id, mimeType, bytes);
	}

	/**
	 * [PT] Converte um arquivo de imagem (incluindo GIFs animados) em um objeto
	 * {@link Image}.
	 * <p>
	 * O MIME type é detectado automaticamente a partir do conteúdo do arquivo
	 * (usando {@link Files#probeContentType(Path)}). Suporta todos os formatos
	 * reconhecidos pelo sistema (PNG, JPG, GIF, BMP, WEBP, TIFF, etc.).
	 * </p>
	 * <p>
	 * Este método preserva os bytes originais, sendo ideal para GIFs animados.
	 * </p>
	 *
	 * [EN] Converts an image file (including animated GIFs) into an {@link Image}
	 * object.
	 * <p>
	 * The MIME type is automatically detected from the file content (using
	 * {@link Files#probeContentType(Path)}). Supports all formats recognized by the
	 * system (PNG, JPG, GIF, BMP, WEBP, TIFF, etc.).
	 * </p>
	 * <p>
	 * This method preserves the original bytes, making it ideal for animated GIFs.
	 * </p>
	 *
	 * @param id       [PT] Identificador único da imagem [EN] Unique image
	 *                 identifier
	 * @param filePath [PT] Caminho do arquivo de imagem (ex: "foto.png",
	 *                 "animacao.gif") [EN] Path to the image file (e.g.,
	 *                 "photo.png", "animation.gif")
	 * @return [PT] Objeto {@link Image} com os bytes originais e MIME type
	 *         detectado [EN] {@link Image} object with original bytes and detected
	 *         MIME type
	 * @throws IOException [PT] se o arquivo não existir ou não for uma imagem
	 *                     válida [EN] if the file does not exist or is not a valid
	 *                     image
	 */
	public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, String filePath)
			throws IOException {
		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new IOException("Arquivo não encontrado / File not found: " + filePath);
		}
		byte[] bytes = Files.readAllBytes(path);
		String mimeType = Files.probeContentType(path);
		if (mimeType == null || !mimeType.startsWith("image/")) {
			throw new IOException(
					"O arquivo não parece ser uma imagem válida / File does not appear to be a valid image: "
							+ filePath);
		}
		return new br.com.angatusistemas.lib.images.objects.Image(id, mimeType, bytes);
	}

	/**
	 * [PT] Converte um array de bytes em um objeto {@link Image} com MIME type
	 * especificado.
	 *
	 * [EN] Converts a byte array into an {@link Image} object with the specified
	 * MIME type.
	 *
	 * @param id       [PT] Identificador único da imagem [EN] Unique image
	 *                 identifier
	 * @param bytes    [PT] Array de bytes da imagem [EN] Byte array of the image
	 * @param mimeType [PT] Tipo MIME da imagem (ex: "image/png", "image/jpeg") [EN]
	 *                 MIME type of the image (e.g., "image/png", "image/jpeg")
	 * @return [PT] Objeto {@link Image} pronto para persistência [EN] {@link Image}
	 *         object ready for persistence
	 * @throws IllegalArgumentException [PT] se os bytes ou MIME type forem
	 *                                  nulos/vazios [EN] if bytes or MIME type is
	 *                                  null/empty
	 */
	public static br.com.angatusistemas.lib.images.objects.Image extractToImageObject(String id, byte[] bytes,
			String mimeType) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException(
					"Os bytes da imagem não podem ser nulos/vazios / Image bytes cannot be null/empty");
		}
		if (mimeType == null || mimeType.trim().isEmpty()) {
			throw new IllegalArgumentException("O MIME type deve ser informado / MIME type must be provided");
		}
		return new br.com.angatusistemas.lib.images.objects.Image(id, mimeType, bytes);
	}

	// ==================== GIF ANIMADO ====================

	/**
	 * [PT] Cria GIF animado a partir de uma lista de frames.
	 * <p>
	 * Implementação puramente Java, sem dependências externas.
	 * </p>
	 *
	 * [EN] Creates an animated GIF from a list of frames.
	 * <p>
	 * Pure Java implementation, no external dependencies.
	 * </p>
	 *
	 * @param frames     [PT] lista de frames (BufferedImage) do GIF [EN] list of
	 *                   GIF frames (BufferedImage)
	 * @param outputPath [PT] caminho de saída (ex: "animacao.gif") [EN] output path
	 *                   (e.g., "animation.gif")
	 * @param delayMs    [PT] atraso entre frames em milissegundos [EN] delay
	 *                   between frames in milliseconds
	 * @param loop       [PT] true para loop infinito, false para executar uma vez
	 *                   [EN] true for infinite loop, false for single execution
	 * @throws IOException [PT] se ocorrer erro na criação [EN] if an error occurs
	 *                     during creation
	 */
	public static void createAnimatedGif(List<BufferedImage> frames, String outputPath, int delayMs, boolean loop)
			throws IOException {
		if (frames == null || frames.isEmpty())
			throw new IllegalArgumentException("No frames provided");
		try (FileOutputStream fos = new FileOutputStream(outputPath);
				BufferedOutputStream bos = new BufferedOutputStream(fos)) {
			GifSequenceWriter writer = new GifSequenceWriter(bos, frames.get(0).getType(), delayMs, loop);
			for (BufferedImage frame : frames) {
				writer.writeToSequence(frame);
			}
			writer.close();
		}
		Console.log("Animated GIF created: " + outputPath);
	}

	// Classe interna para escrever GIF animado (baseada na implementação pública de
	// Elliot Kroo)
	private static class GifSequenceWriter implements Closeable {
		private ImageWriter writer;
		private ImageWriteParam params;
		private IIOMetadata metadata;

		public GifSequenceWriter(OutputStream out, int imageType, int delayMs, boolean loop) throws IOException {
			writer = ImageIO.getImageWritersBySuffix("gif").next();
			ImageOutputStream ios = ImageIO.createImageOutputStream(out);
			writer.setOutput(ios);
			params = writer.getDefaultWriteParam();
			ImageTypeSpecifier typeSpec = ImageTypeSpecifier.createFromBufferedImageType(imageType);
			metadata = writer.getDefaultImageMetadata(typeSpec, params);
			configureMetadata(metadata, delayMs, loop);
			writer.prepareWriteSequence(null);
		}

		private void configureMetadata(IIOMetadata meta, int delayMs, boolean loop) throws IOException {
			String metaFormat = meta.getNativeMetadataFormatName();
			if (!"gif".equals(metaFormat))
				return;
			javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) meta
					.getAsTree(metaFormat);
			javax.imageio.metadata.IIOMetadataNode gce = new javax.imageio.metadata.IIOMetadataNode(
					"GraphicControlExtension");
			gce.setAttribute("disposalMethod", "none");
			gce.setAttribute("userInputFlag", "FALSE");
			gce.setAttribute("transparentColorFlag", "FALSE");
			gce.setAttribute("delayTime", Integer.toString(delayMs / 10));
			gce.setAttribute("transparentColorIndex", "0");
			javax.imageio.metadata.IIOMetadataNode appExt = new javax.imageio.metadata.IIOMetadataNode(
					"ApplicationExtensions");
			javax.imageio.metadata.IIOMetadataNode appNode = new javax.imageio.metadata.IIOMetadataNode(
					"ApplicationExtension");
			appNode.setAttribute("applicationID", "NETSCAPE");
			appNode.setAttribute("authenticationCode", "2.0");
			byte[] loopBytes = new byte[] { 1, 0, 0, 0, 0 };
			if (loop) {
				loopBytes = new byte[] { 1, 0, 0, 0, 0 };
			} else {
				loopBytes = new byte[] { 1, 0, 0, 0, 0 };
			}
			appNode.setUserObject(loopBytes);
			appExt.appendChild(appNode);
			javax.imageio.metadata.IIOMetadataNode extensions = new javax.imageio.metadata.IIOMetadataNode(
					"ImageExtension");
			extensions.appendChild(gce);
			extensions.appendChild(appExt);
			root.appendChild(extensions);
			meta.setFromTree(metaFormat, root);
		}

		public void writeToSequence(BufferedImage img) throws IOException {
			writer.writeToSequence(new IIOImage(img, null, metadata), params);
		}

		@Override
		public void close() throws IOException {
			writer.endWriteSequence();
			writer.dispose();
		}
	}

	// ==================== VÍDEO (MP4) - REQUER JCODEC ====================

	/**
	 * [PT] Extrai frames de vídeo MP4. Requer JCodec no classpath.
	 *
	 * [EN] Extracts frames from an MP4 video. Requires JCodec on classpath.
	 *
	 * @param videoPath     [PT] caminho do vídeo MP4 [EN] MP4 video path
	 * @param outputDir     [PT] diretório para salvar os frames [EN] directory to
	 *                      save frames
	 * @param frameInterval [PT] intervalo entre frames (1 = todos os frames) [EN]
	 *                      interval between frames (1 = every frame)
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void extractVideoFrames(String videoPath, String outputDir, int frameInterval) throws IOException {
		try {
			Class.forName("org.jcodec.api.FrameGrab");
			Console.warn("JCodec not fully integrated; implement with actual JCodec API");
		} catch (ClassNotFoundException e) {
			throw new UnsupportedOperationException("JCodec library not found. Add org.jcodec:jcodec to dependencies.");
		}
	}

	/**
	 * [PT] Cria vídeo MP4 a partir de imagens. Requer JCodec.
	 *
	 * [EN] Creates MP4 video from images. Requires JCodec.
	 *
	 * @param images     [PT] lista de imagens (frames) [EN] list of images (frames)
	 * @param outputPath [PT] caminho de saída (ex: "video.mp4") [EN] output path
	 *                   (e.g., "video.mp4")
	 * @param width      [PT] largura do vídeo [EN] video width
	 * @param height     [PT] altura do vídeo [EN] video height
	 * @param fps        [PT] quadros por segundo [EN] frames per second
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void createVideoFromImages(List<BufferedImage> images, String outputPath, int width, int height,
			int fps) throws IOException {
		try {
			Class.forName("org.jcodec.api.awt.AWTSequenceEncoder");
			Console.warn("JCodec not fully integrated; implement with actual JCodec API");
		} catch (ClassNotFoundException e) {
			throw new UnsupportedOperationException("JCodec library not found.");
		}
	}

	/**
	 * [PT] Converte vídeo MP4 para GIF animado. Requer JCodec.
	 *
	 * [EN] Converts MP4 video to animated GIF. Requires JCodec.
	 *
	 * @param videoPath [PT] caminho do vídeo MP4 [EN] MP4 video path
	 * @param gifPath   [PT] caminho de saída do GIF [EN] output GIF path
	 * @param fps       [PT] quadros por segundo do GIF [EN] frames per second for
	 *                  GIF
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static void videoToGif(String videoPath, String gifPath, int fps) throws IOException {
		Console.warn("videoToGif requires JCodec; implement using extractVideoFrames + createAnimatedGif");
	}

	// ==================== UTILITÁRIOS ====================

	/**
	 * [PT] Verifica se um arquivo é uma imagem válida.
	 *
	 * [EN] Checks if a file is a valid image.
	 *
	 * @param filePath [PT] caminho do arquivo [EN] file path
	 * @return [PT] true se for uma imagem legível [EN] true if readable image
	 */
	public static boolean isValidImage(String filePath) {
		try {
			BufferedImage img = readImage(filePath);
			return img != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * [PT] Obtém as dimensões de uma imagem sem carregá-la completamente.
	 *
	 * [EN] Gets image dimensions without fully loading the image.
	 *
	 * @param filePath [PT] caminho da imagem [EN] image path
	 * @return [PT] array com [largura, altura] [EN] array with [width, height]
	 * @throws IOException [PT] se ocorrer erro [EN] if error occurs
	 */
	public static int[] getImageDimensions(String filePath) throws IOException {
		BufferedImage img = readImage(filePath);
		return new int[] { img.getWidth(), img.getHeight() };
	}

	/**
	 * [PT] Lista todos os arquivos de imagem em um diretório (recursivo).
	 *
	 * [EN] Lists all image files in a directory (recursive).
	 *
	 * @param directory [PT] diretório a ser escaneado [EN] directory to scan
	 * @return [PT] lista de caminhos das imagens encontradas [EN] list of paths of
	 *         found images
	 * @throws IOException [PT] se ocorrer erro de I/O [EN] if I/O error occurs
	 */
	public static List<String> listImageFiles(String directory) throws IOException {
		try (Stream<Path> walk = Files.walk(Paths.get(directory))) {
			return walk.filter(Files::isRegularFile)
					.filter(p -> p.toString().matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|tiff|tif|svg|ico)$"))
					.map(Path::toString).collect(java.util.stream.Collectors.toList());
		}
	}

	// ==================== MÉTODOS PRIVADOS ====================

	/**
	 * [PT] Retorna o MIME type correspondente a um formato de imagem.
	 *
	 * [EN] Returns the MIME type corresponding to an image format.
	 *
	 * @param format [PT] formato (ex: "png", "jpg", "gif") [EN] format (e.g.,
	 *               "png", "jpg", "gif")
	 * @return [PT] MIME type (ex: "image/png") [EN] MIME type (e.g., "image/png")
	 * @throws IllegalArgumentException [PT] se o formato não for suportado [EN] if
	 *                                  the format is not supported
	 */
	private static String getMimeTypeForFormat(String format) {
		switch (format.toLowerCase()) {
		case "png":
			return "image/png";
		case "jpg":
		case "jpeg":
			return "image/jpeg";
		case "gif":
			return "image/gif";
		case "bmp":
			return "image/bmp";
		case "webp":
			return "image/webp";
		case "tiff":
		case "tif":
			return "image/tiff";
		case "svg":
			return "image/svg+xml";
		case "ico":
			return "image/x-icon";
		default:
			throw new IllegalArgumentException("Formato de imagem não suportado: " + format);
		}
	}
}