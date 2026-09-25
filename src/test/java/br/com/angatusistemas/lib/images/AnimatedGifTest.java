package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;

/**
 * GIF animado: o arquivo existe e é válido (antes saía com 0 byte), tem um frame por imagem com a
 * cor certa, o atraso pedido em cada frame e o bloco {@code NETSCAPE2.0} de repetição só quando
 * {@code loop = true}.
 *
 * @author Angatu Sistemas
 */
class AnimatedGifTest {

    private static final byte[] NETSCAPE = "NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path directory;

    @Test
    @DisplayName("GIF com repetição: três frames, cores certas, atraso de 25 centésimos e NETSCAPE2.0 com repetição infinita")
    void loopingGifIsValid() throws Exception {
        Path gif = directory.resolve("animacao.gif");
        List<BufferedImage> frames = List.of(frame(Color.RED), frame(Color.GREEN), frame(Color.BLUE));

        ImageAPI.createAnimatedGif(frames, gif.toString(), 250, true);

        byte[] bytes = Files.readAllBytes(gif);
        assertTrue(bytes.length > 0, "o GIF saiu vazio");
        assertEquals("GIF89a", new String(bytes, 0, 6, StandardCharsets.US_ASCII));
        int netscape = indexOf(bytes, NETSCAPE, 0);
        assertTrue(netscape > 0, "sem o bloco NETSCAPE2.0");
        assertEquals(-1, indexOf(bytes, NETSCAPE, netscape + 1), "o bloco de repetição deve aparecer uma vez só");
        // Sub-bloco de 3 bytes: id 1, repetições 0 (sem fim), terminador.
        assertEquals(3, bytes[netscape + NETSCAPE.length]);
        assertEquals(1, bytes[netscape + NETSCAPE.length + 1]);
        assertEquals(0, bytes[netscape + NETSCAPE.length + 2]);
        assertEquals(0, bytes[netscape + NETSCAPE.length + 3]);

        List<Frame> decoded = read(gif);
        assertEquals(3, decoded.size());
        int[] expected = {Color.RED.getRGB(), Color.GREEN.getRGB(), Color.BLUE.getRGB()};
        for (int i = 0; i < decoded.size(); i++) {
            assertEquals(25, decoded.get(i).delay, "atraso do frame " + i);
            assertEquals(expected[i], decoded.get(i).image.getRGB(8, 8), "cor do frame " + i);
        }
        assertTrue(decoded.get(0).loops, "o primeiro frame leva a repetição");
    }

    @Test
    @DisplayName("GIF sem repetição não leva o bloco NETSCAPE2.0 e toca uma vez")
    void nonLoopingGifHasNoNetscapeBlock() throws Exception {
        Path gif = directory.resolve("uma-vez.gif");

        ImageAPI.createAnimatedGif(List.of(frame(Color.RED), frame(Color.BLUE)), gif.toString(), 100, false);

        byte[] bytes = Files.readAllBytes(gif);
        assertEquals(-1, indexOf(bytes, NETSCAPE, 0));
        List<Frame> decoded = read(gif);
        assertEquals(2, decoded.size());
        assertEquals(10, decoded.get(1).delay);
        assertFalse(decoded.get(0).loops);
    }

    @Test
    @DisplayName("frame com transparência continua transparente no GIF")
    void transparencyIsKept() throws Exception {
        BufferedImage half = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                half.setRGB(x, y, Color.RED.getRGB());
            }
        }
        Path gif = directory.resolve("transparente.gif");

        ImageAPI.createAnimatedGif(List.of(half, half), gif.toString(), 50, true);

        BufferedImage first = read(gif).get(0).image;
        assertEquals(0, ImageTestSupport.alpha(first, 2, 2), "a metade transparente ficou opaca");
        assertEquals(Color.RED.getRGB(), first.getRGB(12, 12));
    }

    @Test
    @DisplayName("frames de tipos diferentes (paleta, cinza, 3 bytes) entram no mesmo GIF, cada um com a sua cor")
    void mixedFrameTypesKeepTheirColors() throws Exception {
        BufferedImage bgr = ImageTestSupport.solid(16, 16, BufferedImage.TYPE_3BYTE_BGR, Color.ORANGE);
        BufferedImage indexed = ImageTestSupport.solid(16, 16, BufferedImage.TYPE_BYTE_INDEXED, Color.BLUE);
        BufferedImage gray = ImageTestSupport.solid(16, 16, BufferedImage.TYPE_BYTE_GRAY, Color.WHITE);
        Path gif = directory.resolve("misto.gif");

        ImageAPI.createAnimatedGif(List.of(bgr, indexed, gray), gif.toString(), 0, true);

        List<Frame> decoded = read(gif);
        assertEquals(3, decoded.size());
        assertEquals(Color.ORANGE.getRGB(), decoded.get(0).image.getRGB(8, 8));
        assertEquals(Color.BLUE.getRGB(), decoded.get(1).image.getRGB(8, 8));
        assertEquals(Color.WHITE.getRGB(), decoded.get(2).image.getRGB(8, 8));
        assertNotEquals(decoded.get(0).image.getRGB(8, 8), decoded.get(1).image.getRGB(8, 8));
    }

    @Test
    @DisplayName("argumentos inválidos falham antes de criar o arquivo")
    void invalidArgumentsAreRejected() {
        Path gif = directory.resolve("invalido.gif");

        assertThrows(IllegalArgumentException.class, () -> ImageAPI.createAnimatedGif(List.of(), gif.toString(), 100, true));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.createAnimatedGif(null, gif.toString(), 100, true));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAPI.createAnimatedGif(Arrays.asList(frame(Color.RED), null), gif.toString(), 100, true));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAPI.createAnimatedGif(List.of(frame(Color.RED)), gif.toString(), -1, true));
        assertFalse(Files.exists(gif));
    }

    private static BufferedImage frame(Color color) {
        return ImageTestSupport.solid(16, 16, BufferedImage.TYPE_INT_RGB, color);
    }

    /** Frame decodificado pelo leitor de GIF do JDK, com o atraso e a repetição do metadado. */
    private record Frame(BufferedImage image, int delay, boolean loops) {
    }

    private static List<Frame> read(Path gif) throws Exception {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(gif.toFile())) {
            reader.setInput(input, false, false);
            List<Frame> frames = new ArrayList<>();
            int count = reader.getNumImages(true);
            for (int i = 0; i < count; i++) {
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                int delay = -1;
                boolean loops = false;
                for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if ("GraphicControlExtension".equals(child.getNodeName())) {
                        delay = Integer.parseInt(((IIOMetadataNode) child).getAttribute("delayTime"));
                    }
                    if ("ApplicationExtensions".equals(child.getNodeName())) {
                        for (Node app = child.getFirstChild(); app != null; app = app.getNextSibling()) {
                            IIOMetadataNode node = (IIOMetadataNode) app;
                            byte[] data = (byte[]) node.getUserObject();
                            loops |= "NETSCAPE".equals(node.getAttribute("applicationID")) && data != null
                                    && data.length == 3 && data[0] == 1 && data[1] == 0 && data[2] == 0;
                        }
                    }
                }
                frames.add(new Frame(reader.read(i), delay, loops));
            }
            return frames;
        } finally {
            reader.dispose();
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
