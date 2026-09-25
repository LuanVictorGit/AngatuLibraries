package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sem o ZXing e sem o Thumbnailator no classpath, as classes carregam e a chamada explica o que
 * falta ({@code MissingDependencyException} com a instrução de instalação), em vez de um
 * {@code NoClassDefFoundError} — que, na {@code QRCodeAPI}, vinha do campo estático do tipo
 * {@code ErrorCorrectionLevel} e deixava a classe inutilizável até para o que não usa o ZXing.
 *
 * <p>O teste monta um class loader que só enxerga as classes da própria biblioteca, sem nenhum jar
 * de terceiros, e chama os métodos por {@link MethodHandles} — que resolvem só o método chamado,
 * como a JVM faz numa chamada normal.</p>
 *
 * @author Angatu Sistemas
 */
class OptionalDependenciesTest {

    private static final String MISSING_DEPENDENCY = "br.com.angatusistemas.lib.dependencies.MissingDependencyException";

    private URLClassLoader libraryOnly;

    @BeforeEach
    void openLibraryOnlyClassLoader() {
        URL classes = ImageAPI.class.getProtectionDomain().getCodeSource().getLocation();
        libraryOnly = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
    }

    @AfterEach
    void closeClassLoader() throws Exception {
        libraryOnly.close();
    }

    @Test
    @DisplayName("sem o ZXing, gerar e ler QR Code explicam a dependência ausente em vez de NoClassDefFoundError")
    void qrCodeWithoutZxingExplainsTheMissingDependency() throws Exception {
        Class<?> qrCode = Class.forName("br.com.angatusistemas.lib.images.QRCodeAPI", true, libraryOnly);
        assertEquals(libraryOnly, qrCode.getClassLoader(), "o teste precisa carregar a classe sem o ZXing");

        Throwable generating = assertThrows(Throwable.class, () -> staticMethod(qrCode, "generateQRCode",
                MethodType.methodType(BufferedImage.class, String.class)).invokeWithArguments("https://angatu.com.br"));
        Throwable decoding = assertThrows(Throwable.class, () -> staticMethod(qrCode, "decodeQRCode",
                MethodType.methodType(String.class, BufferedImage.class)).invokeWithArguments(sample()));

        assertEquals(MISSING_DEPENDENCY, generating.getClass().getName(), String.valueOf(generating));
        assertTrue(generating.getMessage().contains("com.google.zxing:core:3.5.3"), generating.getMessage());
        assertEquals(MISSING_DEPENDENCY, decoding.getClass().getName(), String.valueOf(decoding));
    }

    @Test
    @DisplayName("sem o ZXing, converter uma imagem pronta em Base64 continua funcionando")
    void base64OfAnExistingImageDoesNotNeedZxing() throws Throwable {
        Class<?> qrCode = Class.forName("br.com.angatusistemas.lib.images.QRCodeAPI", true, libraryOnly);

        Object base64 = staticMethod(qrCode, "qrCodeToBase64", MethodType.methodType(String.class, BufferedImage.class, String.class))
                .invokeWithArguments(sample(), "png");

        assertTrue(String.valueOf(base64).startsWith("data:image/png;base64,"));
    }

    @Test
    @DisplayName("sem o Thumbnailator, miniatura explica a dependência ausente e o resto da ImageAPI funciona")
    void imageApiWithoutThumbnailator() throws Throwable {
        Class<?> images = Class.forName("br.com.angatusistemas.lib.images.ImageAPI", true, libraryOnly);

        Throwable thumbnail = assertThrows(Throwable.class, () -> staticMethod(images, "resizeMaintainAspect",
                MethodType.methodType(BufferedImage.class, BufferedImage.class, int.class, int.class))
                .invokeWithArguments(sample(), 5, 5));
        Object bytes = staticMethod(images, "imageToBytes", MethodType.methodType(byte[].class, BufferedImage.class, String.class))
                .invokeWithArguments(sample(), "png");

        assertEquals(MISSING_DEPENDENCY, thumbnail.getClass().getName(), String.valueOf(thumbnail));
        assertTrue(thumbnail.getMessage().contains("net.coobird:thumbnailator:0.4.21"), thumbnail.getMessage());
        assertTrue(((byte[]) bytes).length > 0);
    }

    private static MethodHandle staticMethod(Class<?> owner, String name, MethodType type) throws ReflectiveOperationException {
        return MethodHandles.publicLookup().findStatic(owner, name, type);
    }

    private static BufferedImage sample() {
        return ImageTestSupport.solid(10, 10, BufferedImage.TYPE_INT_RGB, Color.RED);
    }
}
