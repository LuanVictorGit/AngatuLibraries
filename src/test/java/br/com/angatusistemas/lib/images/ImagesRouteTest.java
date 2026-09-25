package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.images.objects.Image;
import io.javalin.http.Context;

/**
 * Rota {@code GET /image}: só tipos raster da lista são servidos para exibição; o resto — inclusive
 * os registros antigos gravados como SVG ou HTML — vai como download, e toda resposta leva
 * {@code nosniff} e a CSP com {@code sandbox}.
 *
 * <p>O servidor não sobe: o handler é chamado direto, com um {@link Context} de mentira que grava o
 * que a rota respondeu, sobre um banco SQLite novo em diretório temporário.</p>
 *
 * @author Angatu Sistemas
 */
class ImagesRouteTest {

    @TempDir
    Path directory;

    @BeforeEach
    void openFreshDatabase() {
        Saveable.shutdown();
        System.setProperty("angatu.db", directory.resolve("imagens.db").toString());
    }

    @AfterEach
    void closeDatabase() {
        Saveable.shutdown();
        System.clearProperty("angatu.db");
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(nullValues = "NULO", value = {
            "image/png, image/png",
            "IMAGE/PNG, image/png",
            "image/jpeg, image/jpeg",
            "image/jpg, image/jpeg",
            "image/pjpeg, image/jpeg",
            "'image/jpeg; charset=binary', image/jpeg",
            "image/gif, image/gif",
            "image/webp, image/webp",
            "image/avif, image/avif",
            "image/bmp, image/bmp",
            "image/x-ms-bmp, image/bmp",
            "image/x-icon, image/x-icon",
            "image/vnd.microsoft.icon, image/x-icon",
            "image/svg+xml, NULO",
            "text/html, NULO",
            "application/xhtml+xml, NULO",
            "image/tiff, NULO",
            "'', NULO",
            "NULO, NULO"})
    @DisplayName("lista de exibição: só tipos raster, normalizados; o resto vira download")
    void inlineTypesAreAllowListed(String stored, String expected) {
        assertEquals(expected, ImagesRoute.inlineContentType(stored));
    }

    @Test
    @DisplayName("tipo gravado com quebra de linha não chega ao cabeçalho: só uma constante da lista sai")
    void storedTypeIsNeverEchoed() {
        assertNull(ImagesRoute.inlineContentType("image/png\r\nSet-Cookie: sessao=roubada"));
        assertNull(ImagesRoute.inlineContentType("image/pngx"));
    }

    @Test
    @DisplayName("imagem PNG é servida para exibição, com nosniff e CSP sandbox")
    void pngIsServedInline() throws Exception {
        byte[] png = ImageTestSupport.encoded(ImageTestSupport.solid(4, 4, BufferedImage.TYPE_INT_RGB, Color.RED), "png");
        ImageAPI.extractToImageObject("logo", png).save();

        Response response = get("logo");

        assertEquals(200, response.status);
        assertEquals("image/png", response.contentType);
        assertArrayEquals(png, response.body);
        assertNull(response.headers.get("Content-Disposition"));
        assertSecurityHeaders(response);
    }

    @ParameterizedTest(name = "registro antigo gravado como {0}")
    @CsvSource({"image/svg+xml", "text/html", "application/javascript"})
    @DisplayName("registro antigo com SVG/HTML é entregue como download, nunca como página da aplicação")
    void legacyDangerousRowsAreDownloads(String storedType) {
        new Image("antigo", storedType, ImageTestSupport.SVG_WITH_SCRIPT).save();

        Response response = get("antigo");

        assertEquals(200, response.status);
        assertEquals(ImagesRoute.DOWNLOAD_TYPE, response.contentType);
        assertEquals("attachment", response.headers.get("Content-Disposition"));
        assertSecurityHeaders(response);
    }

    @Test
    @DisplayName("HTML gravado como image/png sai como image/png com nosniff: o navegador não o executa")
    void htmlStoredAsPngIsNeitherSniffedNorExecuted() {
        new Image("disfarcado", "image/png", ImageTestSupport.HTML_WITH_SCRIPT).save();

        Response response = get("disfarcado");

        assertEquals("image/png", response.contentType);
        assertSecurityHeaders(response);
    }

    @Test
    @DisplayName("sem id: 400 com a mensagem de sempre; id desconhecido: 404; ambos com os cabeçalhos de segurança")
    void missingAndUnknownIds() {
        Response missing = get(null);
        assertEquals(400, missing.status);
        assertEquals("Parâmetro 'id' é obrigatório.", missing.text());
        assertSecurityHeaders(missing);

        Response blank = get("  ");
        assertEquals(400, blank.status);

        Response unknown = get("nao-existe");
        assertEquals(404, unknown.status);
        assertEquals("Imagem não encontrada.", unknown.text());
        assertSecurityHeaders(unknown);
    }

    @Test
    @DisplayName("falha do banco: 500 com mensagem fixa, sem detalhe interno na resposta")
    void databaseFailureDoesNotLeakDetails() {
        Saveable.shutdown();
        System.setProperty("angatu.db", directory.resolve("nao-existe/sub/imagens.db").toString());

        Response response = get("qualquer");

        assertEquals(500, response.status);
        assertEquals("Erro interno ao buscar imagem.", response.text());
        assertFalse(response.text().contains("SQL"));
        assertSecurityHeaders(response);
    }

    private static void assertSecurityHeaders(Response response) {
        assertEquals("nosniff", response.headers.get("X-Content-Type-Options"));
        assertEquals("default-src 'none'; sandbox", response.headers.get("Content-Security-Policy"));
    }

    private static Response get(String id) {
        Response response = new Response();
        if (id != null) {
            response.query.put("id", id);
        }
        Context context = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[] {Context.class}, response);
        ImagesRoute.serve(context);
        return response;
    }

    /** O que a rota respondeu, gravado por um {@link Context} de mentira. */
    private static final class Response implements InvocationHandler {

        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int status = 200;
        String contentType;
        byte[] body;

        String text() {
            return body == null ? null : new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            int arity = args == null ? 0 : args.length;
            if (name.equals("queryParam") && arity == 1) {
                return query.get((String) args[0]);
            }
            if (name.equals("header") && arity == 2) {
                headers.put((String) args[0], (String) args[1]);
                return proxy;
            }
            if (name.equals("status") && arity == 1 && args[0] instanceof Integer code) {
                status = code;
                return proxy;
            }
            if (name.equals("contentType") && arity == 1 && args[0] instanceof String type) {
                contentType = type;
                return proxy;
            }
            if (name.equals("result") && arity == 1 && args[0] instanceof byte[] bytes) {
                body = bytes;
                return proxy;
            }
            if (name.equals("result") && arity == 1 && args[0] instanceof String text) {
                body = text.getBytes(StandardCharsets.UTF_8);
                return proxy;
            }
            if (name.equals("toString") && arity == 0) {
                return "Context de teste";
            }
            if (name.equals("hashCode") && arity == 0) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && arity == 1) {
                return proxy == args[0];
            }
            throw new UnsupportedOperationException("A rota chamou um método não previsto no teste: " + method);
        }
    }
}
