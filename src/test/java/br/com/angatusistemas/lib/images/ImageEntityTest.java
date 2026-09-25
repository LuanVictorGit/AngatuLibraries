package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.images.objects.Image;

/**
 * Entidade {@link Image}: tem o construtor vazio que o contrato do {@code Saveable} pede e grava no
 * mesmo formato de sempre — os bytes como lista de números no JSON —, então registros de versões
 * anteriores continuam legíveis e vice-versa.
 *
 * @author Angatu Sistemas
 */
class ImageEntityTest {

    @TempDir
    Path directory;

    private Path database;

    @BeforeEach
    void openFreshDatabase() {
        Saveable.shutdown();
        database = directory.resolve("entidade.db");
        System.setProperty("angatu.db", database.toString());
    }

    @AfterEach
    void closeDatabase() {
        Saveable.shutdown();
        System.clearProperty("angatu.db");
    }

    @Test
    @DisplayName("tem construtor vazio protegido, para o Gson, e o construtor completo público de sempre")
    void hasTheConstructorsTheContractAsksFor() throws Exception {
        Constructor<Image> empty = Image.class.getDeclaredConstructor();
        assertTrue(Modifier.isProtected(empty.getModifiers()));
        assertTrue(Modifier.isPublic(Image.class.getConstructor(String.class, String.class, byte[].class).getModifiers()));
    }

    @Test
    @DisplayName("grava e lê de volta com os mesmos bytes e tipo")
    void savesAndReadsBack() throws Exception {
        byte[] png = ImageTestSupport.encoded(ImageTestSupport.solid(3, 3, BufferedImage.TYPE_INT_RGB, Color.RED), "png");
        ImageAPI.extractToImageObject("icone", png).save();

        Image loaded = Saveable.findById(Image.class, "icone");

        assertNotNull(loaded);
        assertEquals("image/png", loaded.getMimeType());
        assertArrayEquals(png, loaded.getBytes());
    }

    @Test
    @DisplayName("o formato gravado não mudou: bytes como lista de números no JSON, sem Base64")
    void storedFormatIsUnchanged() throws Exception {
        new Image("formato", "image/png", new byte[] {-119, 80, 78, 71}).save();

        assertEquals("{\"id\":\"formato\",\"mimeType\":\"image/png\",\"bytes\":[-119,80,78,71]}", rawJson("formato"));
    }

    @Test
    @DisplayName("registro gravado por versão anterior da biblioteca continua legível")
    void rowWrittenByAnOlderVersionIsReadable() throws Exception {
        Saveable.count(Image.class); // cria a tabela no formato de sempre
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO images (id, data) VALUES ('legado', "
                    + "'{\"id\":\"legado\",\"mimeType\":\"image/jpeg\",\"bytes\":[-1,-40,-1,-32]}')");
        }

        Image legacy = Saveable.findById(Image.class, "legado");

        assertEquals("legado", legacy.getId());
        assertEquals("image/jpeg", legacy.getMimeType());
        assertArrayEquals(new byte[] {-1, -40, -1, -32}, legacy.getBytes());
    }

    private String rawJson(String id) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
                PreparedStatement ps = conn.prepareStatement("SELECT data FROM images WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "registro não gravado");
                return rs.getString(1);
            }
        }
    }
}
