package br.com.angatusistemas.lib.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.RouteRateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.SuspectIp;
import br.com.angatusistemas.lib.javalin.html.HtmlRouteAPI;
import io.javalin.Javalin;

/**
 * O filtro de segurança do {@link JavalinAPI} contra o servidor real, em processo.
 *
 * <p>Cada caso usa um IP de cliente próprio, pelo {@code X-Forwarded-For} — que a biblioteca
 * aceita porque a conexão do teste vem de loopback, como a de um proxy local. Assim um caso
 * nunca herda os contadores de outro.</p>
 *
 * @author Angatu Sistemas
 */
class JavalinSecurityTest {

    @TempDir
    static Path directory;

    private static int port;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    static void startServer() {
        System.setProperty("angatu.db", directory.resolve("web.db").toString());
        JavalinAPI.configureLoginRateLimit("/api/login");
        JavalinAPI.configureRateLimit("/api/items/*", new RateLimitConfig(2, 2, 60));
        JavalinAPI.configureRateLimit("/api/v/*", new RateLimitConfig(1, 1, 1));
        JavalinAPI.configureRateLimit("/api/six", new RateLimitConfig(1, 1, 60));
        JavalinAPI.addIgnoredPath("/health");
        JavalinAPI.setSecurityHeader("Cache-Control", "no-store");

        Javalin app = JavalinAPI.setup(0, true, HtmlRouteAPI::registerAllRoutes);
        assertNotNull(app, "o servidor não subiu");
        port = app.port();
    }

    @AfterAll
    static void stopServer() {
        Javalin app = JavalinAPI.get();
        if (app != null) app.stop();
        Saveable.shutdown();
    }

    // ==================== LIMITE ====================

    @Test
    @DisplayName("variações de barra no caminho dividem o mesmo limite de login")
    void slashVariantsShareTheLoginLimit() throws Exception {
        String ip = "203.0.113.10";
        String[] variants = {"/api/login", "/api//login", "/api/login/", "//api/login", "/api///login//", "/api/login"};
        int refused = 0;
        for (String path : variants) {
            if (post(path, ip, "application/json", "{}").statusCode() == 429) refused++;
        }
        assertTrue(refused >= 1, "nenhuma variação foi limitada: cada uma tinha o próprio contador");
        assertEquals(429, post("/api/login", ip, "application/json", "{}").statusCode());
    }

    @Test
    @DisplayName("rota com extensão de estático no caminho continua limitada")
    void staticExtensionOnARouteIsStillLimited() throws Exception {
        String ip = "203.0.113.11";
        assertEquals(200, get("/api/items/a.css", ip).statusCode());
        assertEquals(200, get("/api/items/a.css", ip).statusCode());
        assertEquals(429, get("/api/items/a.css", ip).statusCode());
    }

    @Test
    @DisplayName("cliente de API recebe a recusa em JSON, com Retry-After")
    void apiClientGetsJsonWithRetryAfter() throws Exception {
        String ip = "203.0.113.12";
        get("/api/items/1", ip);
        get("/api/items/1", ip);
        HttpResponse<String> refused = get("/api/items/1", ip, "Accept", "application/json");
        assertEquals(429, refused.statusCode());
        assertTrue(refused.body().contains("\"error\":\"too_many_requests\""), refused.body());
        assertTrue(refused.headers().firstValue("Retry-After").isPresent());
        assertTrue(refused.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    @DisplayName("IPv6 trocando de endereço dentro do mesmo /64 continua no mesmo limite")
    void ipv6RotationInsideTheSameSlash64SharesTheLimit() throws Exception {
        assertEquals(200, get("/api/six", "2001:db8:1:2::1").statusCode());
        assertEquals(429, get("/api/six", "2001:db8:1:2::2").statusCode());
        assertEquals(200, get("/api/six", "2001:db8:1:3::1").statusCode(), "outro /64 é outro cliente");
    }

    @Test
    @DisplayName("bloqueio longo: rota limitada recusa, página pública abre, WebSocket recusa, desbloqueio libera")
    void longBlockCoversApiAndSocketsButNotPublicPages() throws Exception {
        String ip = "203.0.113.50";
        HttpResponse<String> last = null;
        // Dez violações na mesma hora: estoura o limite (1/s), espera o bloqueio de 1 s vencer, repete.
        for (int n = 0; n < 10; n++) {
            if (n > 0) Thread.sleep(1_100);
            get("/api/v/" + n, ip);
            last = get("/api/v/" + n, ip, "Accept", "application/json");
        }
        assertEquals(403, last.statusCode(), "a décima violação vira bloqueio longo");
        assertTrue(last.body().contains("\"error\":\"blocked\""), last.body());

        assertEquals(403, get("/api/items/9", ip).statusCode());
        HttpResponse<String> page = get("/sobre", ip);
        assertEquals(200, page.statusCode(), "página pública continua abrindo");
        assertTrue(page.body().contains("Sobre a empresa"));

        assertEquals(403, openSocket(ip), "upgrade de WebSocket também respeita o bloqueio");
        assertEquals(101, openSocket("203.0.113.51"));

        JavalinAPI.sweepRateLimitState();
        assertTrue(Saveable.count(SuspectIp.class) >= 1, "o histórico de violações foi gravado em lote");

        assertTrue(JavalinAPI.unblockAll() >= 0);
        assertEquals(200, get("/api/items/9", ip).statusCode(), "desbloqueio vale na hora");
    }

    @Test
    @DisplayName("caminhos inventados dividem um contador só: a memória não cresce com o caminho")
    void inventedPathsShareOneCounterAndMemoryDoesNotGrow() throws Exception {
        String ip = "203.0.113.60";
        int keysBefore = rateLimitKeys();
        String padding = "x".repeat(2_000);
        int refused = 0;
        for (int n = 0; n < 60; n++) {
            if (get("/sem-rota/" + n + "/" + padding, ip).statusCode() == 429) refused++;
        }
        assertTrue(refused >= 50, "caminho inventado não era limitado: " + refused + " de 60 recusados");
        assertTrue(rateLimitKeys() - keysBefore <= 3,
                "cada caminho abriu entrada própria nos mapas: " + (rateLimitKeys() - keysBefore));
    }

    @Test
    @DisplayName("limite com curinga vale para a rota: trocar o id não zera o contador")
    void wildcardLimitCountsTheRouteNotEachUrl() throws Exception {
        String ip = "203.0.113.61";
        int accepted = 0;
        for (int n = 0; n < 10; n++) {
            if (get("/api/items/cupom-" + n, ip).statusCode() == 200) accepted++;
        }
        assertEquals(2, accepted, "cada id tinha o próprio contador");
    }

    @Test
    @DisplayName("preflight de CORS não gasta o limite de login, e o duplo clique passa")
    void corsPreflightDoesNotSpendTheLoginLimit() throws Exception {
        String ip = "203.0.113.62";
        HttpRequest preflight = HttpRequest.newBuilder(uri("/api/login"))
                .timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-For", ip)
                .header("Origin", "https://loja.example.com")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> answer = HTTP.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertTrue(answer.statusCode() < 300, "preflight respondeu " + answer.statusCode());

        assertEquals(200, post("/api/login", ip, "application/json", "{}").statusCode(), "o preflight gastou o limite");
        assertEquals(200, post("/api/login", ip, "application/json", "{}").statusCode(), "o duplo clique levou 429");
    }

    @Test
    @DisplayName("IPv6 trocando de /64 dentro do mesmo /48 esbarra no freio da rede, num limite configurado")
    void ipv6RotationAcrossSlash64sHitsTheSlash48Brake() throws Exception {
        int accepted = 0;
        for (int n = 0; n < 20; n++) {
            if (get("/api/six", "2001:db8:9:" + Integer.toHexString(n) + "::1").statusCode() == 200) accepted++;
        }
        assertEquals(16, accepted, "o /48 inteiro devia somar 16 vezes o limite de um cliente");
        assertEquals(200, get("/api/six", "2001:db8:a:1::1").statusCode(), "outro /48 é outra rede");
    }

    @Test
    @DisplayName("pedido recusado pelo conteúdo entra na conta do limite")
    void contentRejectionsCountTowardsTheLimit() throws Exception {
        String ip = "203.0.113.63";
        assertEquals(403, get("/api/items/1?q=%3Cscript%3E", ip).statusCode());
        assertEquals(403, get("/api/items/1?q=%3Cscript%3E", ip).statusCode());
        assertEquals(429, get("/api/items/1?q=%3Cscript%3E", ip).statusCode(),
                "a recusa por conteúdo não contava: o limite nunca chegava para quem só mandava ataque");
    }

    @Test
    @DisplayName("limite removido sai da memória e do banco")
    void removedRateLimitLeavesMemoryAndDatabase() {
        JavalinAPI.configureRateLimit("/api/tmp", new RateLimitConfig(1, 1, 60));
        assertEquals(1, Saveable.findByField(RouteRateLimitConfig.class, "pathPattern", "/api/tmp").size());
        assertTrue(JavalinAPI.removeRateLimit("/api/tmp"));
        assertFalse(JavalinAPI.removeRateLimit("/api/tmp"));
        assertEquals(0, Saveable.findByField(RouteRateLimitConfig.class, "pathPattern", "/api/tmp").size());
    }

    // ==================== CONTEÚDO ====================

    @Test
    @DisplayName("SQL na query é recusado; JSON comum com updatedAt, setor, offset e from passa")
    void maliciousQueryIsRefusedButOrdinaryJsonPasses() throws Exception {
        String ip = "203.0.113.20";
        HttpResponse<String> attack = get("/api/ip?q=1%20union%20select%20password%20from%20users", ip);
        assertEquals(403, attack.statusCode());

        String json = "{\"updatedAt\":\"2026-01-01\",\"setor\":\"vendas\",\"selectedIds\":[1,2],"
                + "\"from\":\"x\",\"action\":\"update\",\"offset\":0}";
        HttpResponse<String> ordinary = post("/api/json", ip, "application/json", json);
        assertEquals(200, ordinary.statusCode(), "JSON comum foi recusado: " + ordinary.body());
        assertEquals(String.valueOf(json.length()), ordinary.body(), "a rota leu o corpo inteiro depois da varredura");

        assertEquals(403, post("/api/json", ip, "application/json", "{\"q\":\"x'; DROP TABLE users; --\"}").statusCode());
    }

    @Test
    @DisplayName("nome de parâmetro malicioso é recusado; codificação inválida não derruba o filtro")
    void maliciousParameterNameIsRefusedAndBadEncodingDoesNotBreakTheFilter() throws Exception {
        String ip = "203.0.113.21";
        assertEquals(403, get("/api/ip?%3Cscript%3E=1", ip).statusCode());

        // O cliente HTTP do Java nem monta essa URL: vai por socket cru. O Javalin decodifica
        // com tolerância (o "%zz" fica como texto); o que não pode é o filtro virar erro 500.
        int status = rawGetStatus("/api/ip?q=%zz", ip);
        assertTrue(status < 500, "codificação inválida respondeu " + status);
    }

    @Test
    @DisplayName("formulário e JSON são varridos decodificados; formulário comum passa")
    void formAndJsonAreScannedDecoded() throws Exception {
        String ip = "203.0.113.25";
        String form = "application/x-www-form-urlencoded";
        assertEquals(403, post("/api/json", ip, form, "q=%3Cscript%3Ealert(1)%3C%2Fscript%3E").statusCode(),
                "o <script> de um formulário comum passava, codificado pelo navegador");
        assertEquals(403, post("/api/json", ip, "application/json", "{\"q\":\"\\u003cscript\\u003e\"}").statusCode(),
                "o <script> escapado no JSON passava");
        assertEquals(200, post("/api/json", ip, form, "nome=Jo%C3%A3o&cidade=S%C3%A3o+Paulo").statusCode());
    }

    @Test
    @DisplayName("corpo grande não é lido pela varredura: chega inteiro à rota")
    void largeBodyIsNotScannedAndReachesTheRoute() throws Exception {
        String ip = "203.0.113.22";
        String body = "{\"texto\":\"" + "a".repeat(100_000) + "<script>\"}";
        HttpResponse<String> response = post("/api/json", ip, "application/json", body);
        assertEquals(200, response.statusCode());
        assertEquals(String.valueOf(body.length()), response.body());
    }

    @Test
    @DisplayName("página de recusa do projeto substitui a padrão; se ela falhar, vale a padrão, com o crédito")
    void projectDenyPageReplacesTheDefaultAndFallsBackWhenItFails() throws Exception {
        String ip = "203.0.113.24";
        String attack = "/api/ip?q=%3Cscript%3E";
        try {
            JavalinAPI.setDenyPage(notice -> "<!DOCTYPE html><title>Loja</title><p>"
                    + notice.status() + " " + notice.code() + " " + notice.title() + "</p>");
            HttpResponse<String> custom = get(attack, ip, "Accept", "text/html");
            assertEquals(403, custom.statusCode());
            assertTrue(custom.body().contains("403 rejected Pedido recusado"), custom.body());

            JavalinAPI.setDenyPage(notice -> {
                throw new IllegalStateException("template quebrado");
            });
            HttpResponse<String> fallback = get(attack, ip, "Accept", "text/html");
            assertEquals(403, fallback.statusCode());
            assertTrue(fallback.body().contains("<h1>Pedido recusado</h1>"), fallback.body());
            assertTrue(fallback.body().contains("Angatu Sistemas"), fallback.body());

            HttpResponse<String> api = get(attack, ip, "Accept", "application/json");
            assertEquals(403, api.statusCode());
            assertTrue(api.body().contains("\"error\":\"rejected\""), "a API continua recebendo JSON: " + api.body());

            // Exceção verificada (como a do @SneakyThrows) e estouro de pilha também caem na padrão.
            JavalinAPI.setDenyPage(notice -> sneakyThrow(new java.io.IOException("template não encontrado")));
            HttpResponse<String> checked = get(attack, "203.0.113.26", "Accept", "text/html");
            assertEquals(403, checked.statusCode());
            assertTrue(checked.body().contains("<h1>Pedido recusado</h1>"), checked.body());

            JavalinAPI.setDenyPage(notice -> {
                throw new StackOverflowError();
            });
            HttpResponse<String> overflow = get(attack, "203.0.113.27", "Accept", "text/html");
            assertEquals(403, overflow.statusCode());
            assertTrue(overflow.body().contains("<h1>Pedido recusado</h1>"), overflow.body());
        } finally {
            JavalinAPI.setDenyPage(null);
        }
    }

    @Test
    @DisplayName("caminho ignorado vale por segmento: /health sim, /healthcare não")
    void ignoredPathMatchesWholeSegmentsOnly() throws Exception {
        String ip = "203.0.113.23";
        assertEquals(200, get("/health?q=%3Cscript%3E", ip).statusCode());
        assertEquals(403, get("/healthcare?q=%3Cscript%3E", ip).statusCode());
    }

    // ==================== REDIRECIONAMENTO, IP, CABEÇALHOS ====================

    @Test
    @DisplayName("redirecionamento de .html fica no próprio site, sem laço, com a query")
    void htmlRedirectStaysOnSiteWithoutLoopKeepingTheQuery() throws Exception {
        String ip = "203.0.113.30";
        assertLocation("/evil.com/x", get("//evil.com/x.html", ip));
        assertLocation("/sobre?a=1", get("/sobre.html?a=1", ip));
        assertLocation("/sobre", get("/Sobre.HTML", ip));
        assertLocation("/", get("/index.html", ip));
    }

    @Test
    @DisplayName("IP do cliente: último item do X-Forwarded-For atrás de proxy local; lixo é ignorado")
    void clientIpComesFromTheLastForwardedHopAndIgnoresGarbage() throws Exception {
        assertEquals("198.51.100.7", get("/api/ip", "198.51.100.7").body());
        assertEquals("198.51.100.8", get("/api/ip", "10.0.0.1, 198.51.100.8").body());
        String fallback = get("/api/ip", "not-an-ip").body();
        assertFalse(fallback.contains("not-an-ip"), fallback);
    }

    @Test
    @DisplayName("X-Forwarded-For em duas linhas: vale o fim da lista inteira, não a linha do cliente")
    void forwardedForSplitAcrossLinesIsReadWhole() throws Exception {
        // Proxy que acrescenta a própria linha (HAProxy com option forwardfor) em vez de emendar.
        // Socket cru: garante duas linhas de cabeçalho, sem o cliente HTTP juntar os valores.
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            String request = "GET /api/ip HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nX-Forwarded-For: 6.6.6.6\r\nX-Forwarded-For: 198.51.100.9\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            String body = response.substring(response.indexOf("\r\n\r\n") + 4); // pode vir em chunks
            assertTrue(body.contains("198.51.100.9") && !body.contains("6.6.6.6"), response);
        }
    }

    @Test
    @DisplayName("resposta leva os cabeçalhos de segurança, inclusive nosniff")
    void responsesCarrySecurityHeaders() throws Exception {
        HttpResponse<String> page = get("/sobre", "203.0.113.31");
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("SAMEORIGIN", page.headers().firstValue("X-Frame-Options").orElse(null));
    }

    @Test
    @DisplayName("Cache-Control declarado antes da subida vale para página e para arquivo estático")
    void declaredCacheControlReachesPagesAndStaticFiles() throws Exception {
        HttpResponse<String> page = get("/sobre", "203.0.113.32");
        assertEquals(List.of("no-store"), page.headers().allValues("Cache-Control"));

        HttpResponse<String> stylesheet = get("/css/site.css", "203.0.113.32");
        assertEquals(200, stylesheet.statusCode());
        assertEquals(List.of("no-store"), stylesheet.headers().allValues("Cache-Control"),
                "o servidor de estáticos escrevia o próprio max-age=0 por cima");
    }

    // ==================== APOIO ====================

    /** Quantas chaves os mapas de contagem e de bloqueio temporário guardam agora. Lido por reflexão. */
    private static int rateLimitKeys() throws ReflectiveOperationException {
        int total = 0;
        for (String name : new String[] {"SECOND_COUNTERS", "MINUTE_COUNTERS", "BLOCKED_CACHE"}) {
            java.lang.reflect.Field field = JavalinAPI.class.getDeclaredField(name);
            field.setAccessible(true);
            total += ((java.util.Map<?, ?>) field.get(null)).size();
        }
        return total;
    }

    /** Lança uma exceção verificada sem declará-la — o que o {@code @SneakyThrows} do Lombok faz. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> String sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    private static void assertLocation(String expected, HttpResponse<String> response) {
        assertTrue(response.statusCode() / 100 == 3, "esperado redirecionamento, veio " + response.statusCode());
        assertEquals(expected, response.headers().firstValue("Location").orElse(null));
    }

    private static HttpResponse<String> get(String path, String clientIp, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-For", clientIp)
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String clientIp, String contentType, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-For", clientIp)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Abre um WebSocket e devolve o status do upgrade (101 quando aceito). */
    private static int openSocket(String clientIp) throws Exception {
        try {
            WebSocket socket = HTTP.newWebSocketBuilder()
                    .header("X-Forwarded-For", clientIp)
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/echo"), new WebSocket.Listener() { })
                    .get(10, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim").get(5, TimeUnit.SECONDS);
            return 101;
        } catch (java.util.concurrent.ExecutionException | CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException handshake) {
                return handshake.getResponse().statusCode();
            }
            throw e;
        }
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** GET por socket cru, para mandar o que o cliente HTTP do Java se recusa a montar. */
    private static int rawGetStatus(String rawPath, String clientIp) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            String request = "GET " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nX-Forwarded-For: " + clientIp + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
            String statusLine = reader.readLine(); // HTTP/1.1 403 Forbidden
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
