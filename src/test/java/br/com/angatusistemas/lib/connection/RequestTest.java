package br.com.angatusistemas.lib.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Request} contra um servidor HTTP falso local (socket bloqueante, sem internet): PATCH e
 * GET com corpo no método pedido, prazo total da chamada, limite de corpo, falha de rede
 * distinguível de um 500 de verdade, códigos sem constante, charset, redirecionamentos e entrada
 * inválida que vira resposta de falha em vez de exceção.
 *
 * @author Angatu Sistemas
 */
class RequestTest {

    private static FakeServer api;
    private static FakeServer otherHost;

    @BeforeAll
    static void startServers() throws IOException {
        otherHost = new FakeServer(request -> Reply.text(200, "auth=" + request.headers().get("authorization")));
        api = new FakeServer(RequestTest::route);
    }

    @AfterAll
    static void stopServers() throws IOException {
        api.close();
        otherHost.close();
    }

    @AfterEach
    void restoreDefaults() {
        Request.setTimeout(Request.DEFAULT_TIMEOUT);
        Request.setMaxBodyBytes(Request.DEFAULT_MAX_BODY_BYTES);
        Request.setFollowRedirects(true);
    }

    private static Reply route(Received request) {
        String path = request.target().split("\\?")[0];
        return switch (path) {
            case "/echo" -> Reply.text(200, request.method() + " ct=" + request.headers().get("content-type")
                    + " auth=" + request.headers().get("authorization")
                    + " body=" + new String(request.body(), StandardCharsets.UTF_8));
            case "/target" -> Reply.text(200, request.target());
            case "/multi-status" -> Reply.text(207, "{}");
            case "/origin-timeout" -> Reply.text(522, "origin timeout");
            case "/boom" -> Reply.text(500, "upstream boom");
            case "/latin1" -> Reply.bytes(200, "{\"cidade\":\"São Paulo\"}".getBytes(StandardCharsets.ISO_8859_1))
                    .header("Content-Type", "application/json; charset=ISO-8859-1");
            case "/big" -> Reply.text(200, "x".repeat(4096));
            case "/big-without-length" -> Reply.raw(out -> {
                out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.write(new byte[8192]);
            });
            case "/slow-drip" -> Reply.raw(out -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 40\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(250);
                    out.write('x');
                    out.flush();
                }
            });
            case "/to-other-host" -> Reply.text(302, "").header("Location", "http://localhost:" + otherHost.port() + "/x");
            case "/to-same-host" -> Reply.text(302, "").header("Location", "/echo");
            default -> Reply.text(200, "ok");
        };
    }

    @Test
    @DisplayName("PATCH chega como PATCH, com corpo JSON e token")
    void patchIsSentAsPatch() {
        Response response = Request.query("PATCH", api.base() + "/echo", "{\"a\":1}", "meu-token");
        assertTrue(response.isSuccess(), response.toString());
        assertEquals("PATCH ct=application/json auth=Bearer meu-token body={\"a\":1}", response.getBody());
    }

    @Test
    @DisplayName("GET com corpo sai como GET, e não mais como POST")
    void getWithBodyKeepsTheMethod() {
        Response response = Request.query("GET", api.base() + "/echo", "{\"filtro\":1}");
        assertTrue(response.getBody().startsWith("GET "), response.getBody());
    }

    @Test
    @DisplayName("o prazo total corta um servidor que manda um byte por vez")
    void totalDeadlineCutsASlowDrippingServer() {
        Request.setTimeout(Duration.ofSeconds(1));
        long start = System.nanoTime();
        Response response = Request.query("GET", api.base() + "/slow-drip");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(response.isNetworkError(), response.toString());
        assertFalse(response.isSuccess());
        assertTrue(elapsedMillis < 5_000, "levou " + elapsedMillis + " ms, com prazo de 1 s e corpo de 10 s");
        assertTrue(response.getBody().startsWith("Erro: ") && response.getBody().contains("1 s"), response.getBody());
    }

    @Test
    @DisplayName("corpo declarado acima do limite falha sem ser baixado")
    void declaredBodyOverTheLimitFails() {
        Request.setMaxBodyBytes(1024);
        Response response = Request.query("GET", api.base() + "/big");
        assertTrue(response.isNetworkError(), response.toString());
        Request.ResponseTooLargeException error = assertInstanceOf(Request.ResponseTooLargeException.class,
                response.getError());
        assertEquals(4096, error.getDeclaredLength());
        assertEquals(1024, error.getLimit());
        assertTrue(response.getBody().contains("Request.setMaxBodyBytes"), response.getBody());
    }

    @Test
    @DisplayName("corpo sem tamanho declarado é interrompido ao passar do limite")
    void undeclaredBodyOverTheLimitIsInterrupted() {
        Request.setMaxBodyBytes(1024);
        Response response = Request.query("GET", api.base() + "/big-without-length");
        Request.ResponseTooLargeException error = assertInstanceOf(Request.ResponseTooLargeException.class,
                response.getError());
        assertEquals(-1, error.getDeclaredLength());
    }

    @Test
    @DisplayName("corpo dentro do limite é lido inteiro")
    void bodyWithinTheLimitIsRead() {
        Response response = Request.query("GET", api.base() + "/big");
        assertTrue(response.isSuccess(), response.toString());
        assertEquals(4096, response.getBody().length());
    }

    @Test
    @DisplayName("conexão recusada é falha de rede; um 500 do servidor não é")
    void networkFailureIsDistinguishableFromServerError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        Response refused = Request.query("POST", "http://127.0.0.1:" + closedPort + "/x", "{}");
        Response serverError = Request.query("POST", api.base() + "/boom", "{}");

        assertTrue(refused.isNetworkError());
        assertNotNull(refused.getError());
        assertEquals(500, refused.getCode(), "500 mantido por compatibilidade");
        assertSame(StatusCode.INTERNAL_SERVER_ERROR, refused.getStatusCode());
        assertTrue(refused.getBody().startsWith("Erro: "), refused.getBody());

        assertFalse(serverError.isNetworkError());
        assertNull(serverError.getError());
        assertEquals(500, serverError.getCode());
        assertEquals("upstream boom", serverError.getBody());
    }

    @Test
    @DisplayName("código sem constante no enum é preservado e 2xx conta como sucesso")
    void unmappedStatusCodesArePreserved() {
        Response multiStatus = Request.query("GET", api.base() + "/multi-status");
        assertEquals(207, multiStatus.getCode());
        assertNull(multiStatus.getStatusCode());
        assertTrue(multiStatus.isSuccess());
        assertFalse(multiStatus.ok());

        Response originTimeout = Request.query("GET", api.base() + "/origin-timeout");
        assertEquals(522, originTimeout.getCode());
        assertFalse(originTimeout.isSuccess());
        assertFalse(originTimeout.isNetworkError());
    }

    @Test
    @DisplayName("o charset do Content-Type é respeitado")
    void contentTypeCharsetIsHonored() {
        assertEquals("{\"cidade\":\"São Paulo\"}", Request.query("GET", api.base() + "/latin1").getBody());
    }

    @Test
    @DisplayName("redirecionamento para outro host não leva o Authorization; para o mesmo host leva")
    void authorizationIsNotForwardedToAnotherHost() {
        Response other = Request.query("GET", api.base() + "/to-other-host", null, "SEGREDO");
        assertEquals("auth=null", other.getBody());
        Response same = Request.query("GET", api.base() + "/to-same-host", null, "SEGREDO");
        assertTrue(same.getBody().contains("auth=Bearer SEGREDO"), same.getBody());
    }

    @Test
    @DisplayName("com redirecionamentos desligados, o 3xx volta como está")
    void redirectsCanBeDisabled() {
        Request.setFollowRedirects(false);
        Response response = Request.query("GET", api.base() + "/to-other-host", null, "SEGREDO");
        assertEquals(302, response.getCode());
        assertFalse(response.isNetworkError());
        assertFalse(Request.isFollowRedirects());
    }

    @Test
    @DisplayName("URL com espaço vira resposta de falha em português, sem repetir a URL")
    void invalidUrlReturnsFailureResponse() {
        Response response = Request.query("GET", api.base() + "/x?cidade=São Paulo&chave=SEGREDO");
        assertTrue(response.isNetworkError());
        assertTrue(response.getBody().startsWith("Erro: URL inválida"), response.getBody());
        assertFalse(response.getBody().contains("SEGREDO"), response.getBody());

        assertTrue(Request.query("GET", "ftp://exemplo.com/arquivo").isNetworkError());
        assertTrue(Request.query("GET", "exemplo.com/sem-esquema").isNetworkError());
        assertTrue(Request.query("GET", null).isNetworkError());
        assertTrue(Request.query(null, api.base()).isNetworkError());
    }

    @Test
    @DisplayName("acentos na URL são codificados em UTF-8")
    void nonAsciiCharactersAreEncoded() {
        assertEquals("/target?cidade=S%C3%A3o", Request.query("GET", api.base() + "/target?cidade=São").getBody());
    }

    @Test
    @DisplayName("token com quebra de linha é recusado sem aparecer na mensagem")
    void tokenWithLineBreakIsRefused() {
        Response response = Request.query("GET", api.base() + "/echo", null, "abc\r\nX-Injetado: 1");
        assertTrue(response.isNetworkError());
        assertFalse(response.getBody().contains("abc"), response.getBody());
        assertFalse(String.valueOf(response.getError().getMessage()).contains("abc"));
    }

    @Test
    @DisplayName("o construtor antigo de Response continua com o mesmo comportamento")
    void legacyResponseConstructorKeepsItsBehavior() {
        Response ok = new Response("x", StatusCode.OK);
        assertEquals(200, ok.getCode());
        assertTrue(ok.ok());
        assertTrue(ok.isSuccess());
        assertFalse(ok.isNetworkError());
        assertSame(StatusCode.OK, ok.getStatus());

        Response unknown = new Response("x", (StatusCode) null);
        assertEquals(-1, unknown.getCode());
        assertFalse(unknown.isSuccess());
    }

    // ==================== SERVIDOR FALSO ====================

    /** Pedido recebido pelo servidor falso. */
    record Received(String method, String target, Map<String, String> headers, byte[] body) {
    }

    /** Escrita crua da resposta, para simular servidores lentos ou sem Content-Length. */
    interface RawWriter {
        void write(OutputStream out) throws Exception;
    }

    /** Resposta do servidor falso. */
    record Reply(int code, Map<String, String> headers, byte[] body, RawWriter raw) {
        static Reply text(int code, String body) {
            return bytes(code, body.getBytes(StandardCharsets.UTF_8));
        }

        static Reply bytes(int code, byte[] body) {
            return new Reply(code, new LinkedHashMap<>(), body, null);
        }

        static Reply raw(RawWriter raw) {
            return new Reply(0, Map.of(), new byte[0], raw);
        }

        Reply header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    /**
     * Servidor HTTP/1.1 mínimo com socket bloqueante e keep-alive: não usa o seletor do NIO nem
     * a internet.
     */
    static final class FakeServer implements AutoCloseable {

        private final ServerSocket socket;

        FakeServer(Function<Received, Reply> handler) throws IOException {
            socket = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        Thread worker = new Thread(() -> serve(client, handler), "fake-http-worker");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake-http-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        String base() {
            return "http://127.0.0.1:" + port();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        private static void serve(Socket client, Function<Received, Reply> handler) {
            try (client) {
                InputStream in = new BufferedInputStream(client.getInputStream());
                OutputStream out = client.getOutputStream();
                while (true) {
                    String requestLine = readLine(in);
                    if (requestLine == null || requestLine.isEmpty()) {
                        return;
                    }
                    String[] parts = requestLine.split(" ");
                    Map<String, String> headers = new LinkedHashMap<>();
                    String line;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                    }
                    byte[] body = in.readNBytes(Integer.parseInt(headers.getOrDefault("content-length", "0")));
                    Reply reply = handler.apply(new Received(parts[0], parts[1], headers, body));
                    if (reply.raw() != null) {
                        reply.raw().write(out);
                        out.flush();
                        return;
                    }
                    StringBuilder head = new StringBuilder("HTTP/1.1 " + reply.code() + " Teste\r\n");
                    reply.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
                    head.append("Content-Length: ").append(reply.body().length).append("\r\n\r\n");
                    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                    if (!"HEAD".equals(parts[0])) {
                        out.write(reply.body());
                    }
                    out.flush();
                }
            } catch (Exception e) {
                // conexão encerrada pelo cliente (ex.: chamada cancelada no prazo): nada a fazer
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (c != '\r') {
                    line.write(c);
                }
            }
            return c == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
