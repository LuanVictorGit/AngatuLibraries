package br.com.angatusistemas.lib.images;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.imageio.ImageIO;

/**
 * Imagens de teste geradas em memória: bomba de descompressão, PNG cinza com alfa e de 16 bits,
 * JPEG com marca de orientação EXIF, BMP com PNG embutido, SVG e HTML disfarçados.
 *
 * <p>Nada vem da rede nem de arquivo do repositório: cada caso monta os bytes que precisa.</p>
 *
 * @author Angatu Sistemas
 */
final class ImageTestSupport {

    static final Color RED = new Color(220, 20, 20);
    static final Color GREEN = new Color(20, 200, 20);
    static final Color BLUE = new Color(20, 20, 220);
    static final Color YELLOW = new Color(230, 230, 20);

    static final byte[] SVG_WITH_SCRIPT = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
            + "<script>alert(document.cookie)</script></svg>").getBytes(StandardCharsets.UTF_8);

    static final byte[] HTML_WITH_SCRIPT = "<!DOCTYPE html><html><body><script>alert(document.cookie)</script></body></html>"
            .getBytes(StandardCharsets.UTF_8);

    private ImageTestSupport() {
    }

    /** Imagem de uma cor só. */
    static BufferedImage solid(int width, int height, int type, Color color) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    /** Bytes de uma imagem codificada pelo próprio ImageIO. */
    static byte[] encoded(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IOException("sem codificador para " + format);
        }
        return out.toByteArray();
    }

    /**
     * PNG RGBA de 8 bits que declara {@code width × height} com todos os pixels zerados: poucos
     * centos de KB no disco, {@code width × height × 4} bytes decodificado.
     */
    static byte[] pngBomb(int width, int height) throws IOException {
        byte[] row = new byte[1 + width * 4];
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int y = 0; y < height; y++) {
            deflater.setInput(row);
            while (!deflater.needsInput()) {
                idat.write(buffer, 0, deflater.deflate(buffer));
            }
        }
        deflater.finish();
        while (!deflater.finished()) {
            idat.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return png(width, height, 8, 6, idat.toByteArray());
    }

    /** PNG cinza com alfa (tipo de cor 4, 8 bits): decodifica como {@code TYPE_CUSTOM}. */
    static byte[] grayAlphaPng(int width, int height, int gray, int alpha) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            raw.write(0);
            for (int x = 0; x < width; x++) {
                raw.write(gray);
                raw.write(alpha);
            }
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            idat.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return png(width, height, 8, 4, idat.toByteArray());
    }

    /** PNG RGB de 16 bits por canal: também decodifica como {@code TYPE_CUSTOM}. */
    static byte[] rgb16Png(int width, int height, Color color) throws IOException {
        ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, color.getRed() * 257);
                raster.setSample(x, y, 1, color.getGreen() * 257);
                raster.setSample(x, y, 2, color.getBlue() * 257);
            }
        }
        return encoded(new BufferedImage(model, raster, false, null), "png");
    }

    private static byte[] png(int width, int height, int bitDepth, int colorType, byte[] idat) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(header);
        data.writeInt(width);
        data.writeInt(height);
        data.writeByte(bitDepth);
        data.writeByte(colorType);
        data.writeByte(0);
        data.writeByte(0);
        data.writeByte(0);
        chunk(out, "IHDR", header.toByteArray());
        chunk(out, "IDAT", idat);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(out);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        stream.writeInt(data.length);
        stream.write(name);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
    }

    /**
     * JPEG gravado em quatro quadrantes (vermelho, verde / azul, amarelo) com APP1 {@code Exif} de
     * orientação {@code orientation}, inserido logo depois do APP0 JFIF — como a câmera grava.
     */
    static byte[] quadrantJpeg(int width, int height, int orientation, boolean bigEndian) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(RED);
        g.fillRect(0, 0, width / 2, height / 2);
        g.setColor(GREEN);
        g.fillRect(width / 2, 0, width - width / 2, height / 2);
        g.setColor(BLUE);
        g.fillRect(0, height / 2, width / 2, height - height / 2);
        g.setColor(YELLOW);
        g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
        g.dispose();
        byte[] jpeg = encoded(image, "jpg");
        int app0Length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
        int insertAt = 2 + 2 + app0Length;
        byte[] exif = exifOrientation(orientation, bigEndian);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, insertAt);
        out.write(0xFF);
        out.write(0xE1);
        out.write(((exif.length + 2) >> 8) & 0xFF);
        out.write((exif.length + 2) & 0xFF);
        out.write(exif);
        out.write(jpeg, insertAt, jpeg.length - insertAt);
        return out.toByteArray();
    }

    /** Payload do APP1: {@code Exif\0\0} + TIFF com IFD0 de uma entrada, a orientação (0x0112). */
    static byte[] exifOrientation(int orientation, boolean bigEndian) {
        ByteBuffer buffer = ByteBuffer.allocate(6 + 8 + 2 + 12 + 4).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        buffer.put("Exif".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 0);
        buffer.put(bigEndian ? "MM".getBytes(StandardCharsets.US_ASCII) : "II".getBytes(StandardCharsets.US_ASCII));
        buffer.putShort((short) 42).putInt(8);
        buffer.putShort((short) 1);
        buffer.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        buffer.putInt(0);
        return buffer.array();
    }

    /** BMP cujo cabeçalho diz "PNG embutido" (compressão 5) com 2 GB de dados — que não existem. */
    static byte[] bmpWithEmbeddedPng() {
        ByteBuffer buffer = ByteBuffer.allocate(14 + 40 + 16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'B').put((byte) 'M').putInt(70).putInt(0).putInt(54);
        buffer.putInt(40).putInt(1).putInt(1).putShort((short) 1).putShort((short) 0).putInt(5)
                .putInt(0x7FFF_FFF0).putInt(0).putInt(0).putInt(0).putInt(0);
        return buffer.array();
    }

    /** Cor mais próxima entre as quatro dos quadrantes — a compressão do JPEG altera um pouco o tom. */
    static String nearestQuadrantColor(int rgb) {
        Color[] colors = {RED, GREEN, BLUE, YELLOW};
        String[] names = {"vermelho", "verde", "azul", "amarelo"};
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < colors.length; i++) {
            long dr = r - colors[i].getRed();
            long dg = g - colors[i].getGreen();
            long db = b - colors[i].getBlue();
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return names[best];
    }

    /** Cores dos quatro quadrantes, lidas no centro de cada um: superior esquerdo, superior direito, inferior esquerdo, inferior direito. */
    static String quadrants(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return nearestQuadrantColor(image.getRGB(w / 4, h / 4)) + "," + nearestQuadrantColor(image.getRGB(3 * w / 4, h / 4))
                + "/" + nearestQuadrantColor(image.getRGB(w / 4, 3 * h / 4)) + ","
                + nearestQuadrantColor(image.getRGB(3 * w / 4, 3 * h / 4));
    }

    /** Canal vermelho de um pixel (em imagem cinza, o próprio nível de cinza). */
    static int red(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >> 16) & 0xFF;
    }

    /** Alfa de um pixel. */
    static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }
}
