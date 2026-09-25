package br.com.angatusistemas.lib.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.RequestLogMode;
import br.com.angatusistemas.lib.javalin.html.HtmlRouteAPI;
import io.javalin.Javalin;

/**
 * O log de requisições contra o servidor real, em processo: o que o terminal recebe é lido de
 * volta, linha por linha.
 *
 * <p>Cada caso usa um IP de cliente próprio, pelo {@code X-Forwarded-For} — aceito porque a
 * conexão do teste vem de loopback, como a de um proxy local —, e acha as linhas dele por esse IP.</p>
 *
 * @author Angatu Sistemas
 */
class RequestLogTest {

    @TempDir
    static Path directory;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
    private static final Pattern TIME = Pattern.compile("TIME=(<?\\d+)ms");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** O "terminal" do teste: tudo o que o Console escreve vem para cá. */
    private static final ByteArrayOutputStream TERMINAL = new ByteArrayOutputStream();
    private static final PrintStream CAPTURE = new PrintStream(TERMINAL, true, StandardCharsets.UTF_8);
    private static PrintStream originalOut;
    private static int port;

    @BeforeAll
    static void startServer() {
        originalOut = System.out;
        System.setOut(CAPTURE);
        System.setProperty("angatu.db", directory.resolve("log.db").toString());
        JavalinAPI.configureRateLimit("/api/log/limited", new RateLimitConfig(1, 1, 60));
        JavalinAPI.addUnlimitedPath("/api/log/load");

        Javalin app = JavalinAPI.setup(0, true, HtmlRouteAPI::registerAllRoutes);
        assertNotNull(app, "o servidor não subiu");
        port = app.port();
    }

    @AfterAll
    static void stopServer() {
        Javalin app = JavalinAPI.get();
        if (app != null) app.stop();
        Saveable.shutdown();
        System.setOut(originalOut);
    }

    @AfterEach
    void restoreDefaults() {
        JavalinAPI.setRequestLogMode(RequestLogMode.ALL);
        RequestLog.capacity = 16_384;
        System.setOut(CAPTURE);
    }

    // ==================== O QUE CADA LINHA TRAZ ====================

    @Test
    @DisplayName("GET com sucesso vira uma linha com horário, método, caminho, IP, status e tempo")
    void successfulGetIsLogged() throws Exception {
        String ip = "203.0.113.20";
        int mark = mark();
        assertEquals(200, get("/api/log/ok", ip).statusCode());

        String line = single(logSince(mark), "IP=" + ip + " ");
        assertTrue(line.matches("^\\[\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}] GET +/api/log/ok +IP=203\\.0\\.113\\.20 "
                + "STATUS=200 TIME=<?\\d+ms$"), line);
    }

    @Test
    @DisplayName("POST com sucesso aparece com o status que a rota devolveu")
    void successfulPostIsLogged() throws Exception {
        String ip = "203.0.113.21";
        int mark = mark();
        assertEquals(201, post("/api/log/items", ip, "{\"nome\":\"x\"}").statusCode());

        String line = single(logSince(mark), "IP=" + ip + " ");
        assertTrue(line.contains("POST    /api/log/items"), line);
        assertTrue(line.contains("STATUS=201"), line);
    }

    @Test
    @DisplayName("caminho sem rota aparece como 404")
    void notFoundIsLogged() throws Exception {
        String ip = "203.0.113.22";
        int mark = mark();
        assertEquals(404, get("/api/log/nao-existe", ip).statusCode());

        String line = single(logSince(mark), "IP=" + ip + " ");
        assertTrue(line.contains("/api/log/nao-existe") && line.contains("STATUS=404"), line);
    }

    @Test
    @DisplayName("exceção que escapa da rota aparece como 500 com o tipo — nunca a mensagem, nem na resposta")
    void internalErrorShowsTheExceptionType() throws Exception {
        String ip = "203.0.113.23";
        int mark = mark();
        HttpResponse<String> response = get("/api/log/boom", ip);
        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("segredo-da-mensagem"), response.body());

        String line = single(logSince(mark), "IP=" + ip + " ");
        assertTrue(line.contains("STATUS=500") && line.endsWith("ERROR=NullPointerException"), line);
        assertFalse(line.contains("segredo-da-mensagem"), line);
    }

    @Test
    @DisplayName("rota que captura a exceção e responde sozinha marca o tipo com markRequestError")
    void handledErrorIsMarked() throws Exception {
        String ip = "203.0.113.24";
        int mark = mark();
        HttpResponse<String> response = get("/api/log/handled", ip);
        assertEquals(500, response.statusCode());
        assertEquals("falhou", response.body());

        String line = single(logSince(mark), "IP=" + ip + " ");
        assertTrue(line.endsWith("ERROR=IllegalStateException"), line);
        assertFalse(line.contains("detalhe interno"), line);
    }

    @Test
    @DisplayName("parâmetros aparecem; valor de segredo ou dado pessoal sai como ***")
    void parametersAreLoggedAndSecretsMasked() throws Exception {
        String ip = "203.0.113.25";
        int mark = mark();
        get("/api/log/ok?page=2&q=camisa&token=q-SEGREDO-1&api_key=q-SEGREDO-2&senha=q-SEGREDO-3"
                + "&email=fulano%40exemplo.com&%74oken=q-SEGREDO-4", ip);

        String line = single(logSince(mark), "IP=" + ip + " ");
        assertTrue(line.contains("PARAMS=page=2&q=camisa&token=***&api_key=***&senha=***&email=***&%74oken=***"),
                line);
        assertFalse(line.contains("SEGREDO") || line.contains("fulano"), line);
    }

    @Test
    @DisplayName("corpo, cabeçalhos, cookies e tokens nunca entram no log")
    void bodyHeadersAndCookiesNeverAppear() throws Exception {
        String ip = "203.0.113.26";
        int mark = mark();
        HttpRequest request = HttpRequest.newBuilder(uri("/api/log/items"))
                .header("X-Forwarded-For", ip)
                .header("Authorization", "Bearer h-SEGREDO-1")
                .header("Cookie", "sessao=h-SEGREDO-2")
                .header("X-Api-Key", "h-SEGREDO-3")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"senha\":\"b-SEGREDO-4\",\"cpf\":\"123.456.789-00\"}"))
                .build();
        assertEquals(201, HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());

        String log = logSince(mark);
        single(log, "IP=" + ip + " ");
        assertFalse(log.contains("SEGREDO") || log.contains("123.456.789-00"), log);
    }

    @Test
    @DisplayName("o IP é o da regra de proxy: o item que o cliente escreveu no X-Forwarded-For não vale")
    void ipFollowsTheProxyRule() throws Exception {
        int mark = mark();
        HttpRequest request = HttpRequest.newBuilder(uri("/api/ip"))
                .header("X-Forwarded-For", "198.51.100.66, 203.0.113.27") // o primeiro o cliente forjou
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals("203.0.113.27", response.body(), "a rota e o log usam o mesmo IP.get");

        String log = logSince(mark);
        String line = single(log, "/api/ip");
        assertTrue(line.contains("IP=203.0.113.27 "), line);
        assertFalse(log.contains("198.51.100.66"), log);
    }

    @Test
    @DisplayName("o tempo registrado é o da requisição inteira")
    void processingTimeIsMeasured() throws Exception {
        String ip = "203.0.113.28";
        int mark = mark();
        get("/api/log/slow", ip);

        String line = single(logSince(mark), "IP=" + ip + " ");
        Matcher time = TIME.matcher(line);
        assertTrue(time.find(), line);
        assertTrue(Integer.parseInt(time.group(1)) >= 140, "a rota demora 150 ms: " + line);
    }

    @Test
    @DisplayName("recusa do filtro de segurança traz o motivo")
    void deniedRequestsCarryTheReason() throws Exception {
        String ip = "203.0.113.29";
        int mark = mark();
        assertEquals(200, get("/api/log/limited", ip).statusCode());
        assertEquals(429, get("/api/log/limited", ip).statusCode());
        assertEquals(403, get("/api/log/ok?q=%3Cscript%3Ealert(1)%3C/script%3E", ip).statusCode());

        List<String> lines = lines(logSince(mark), "IP=" + ip + " ");
        assertEquals(3, lines.size(), String.join("\n", lines));
        assertFalse(lines.get(0).contains("DENIED"), lines.get(0));
        assertTrue(lines.get(1).contains("STATUS=429") && lines.get(1).endsWith("DENIED=too_many_requests"), lines.get(1));
        assertTrue(lines.get(2).contains("STATUS=403") && lines.get(2).endsWith("DENIED=rejected"), lines.get(2));
    }

    @Test
    @DisplayName("página, arquivo estático e upgrade de WebSocket também têm linha")
    void pagesStaticFilesAndWebSocketsAreLogged() throws Exception {
        String ip = "203.0.113.30";
        int mark = mark();
        assertEquals(200, get("/sobre", ip).statusCode());
        assertEquals(200, get("/css/site.css", ip).statusCode());
        WebSocket socket = HTTP.newWebSocketBuilder().header("X-Forwarded-For", ip)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"), new WebSocket.Listener() {
                })
                .get(5, TimeUnit.SECONDS);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim").get(5, TimeUnit.SECONDS);

        String log = logSince(mark);
        assertTrue(single(log, "/sobre ").contains("STATUS=200"), log);
        assertTrue(single(log, "/css/site.css").contains("STATUS=200"), log);
        assertTrue(single(log, "/ws/echo").contains("STATUS=101"), log);
    }

    @Test
    @DisplayName("requisições simultâneas: uma linha para cada, sem mistura nem duplicata")
    void concurrentRequestsAreEachLoggedOnce() throws Exception {
        int requests = 64;
        int mark = mark();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int n = i;
                results.add(pool.submit(() -> get("/api/log/ok?n=" + n, "198.18.0." + n).statusCode()));
            }
            for (Future<Integer> result : results) assertEquals(200, result.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<String> lines = lines(logSince(mark), "/api/log/ok");
        for (int n = 0; n < requests; n++) {
            String suffix = "PARAMS=n=" + n;
            List<String> own = lines.stream().filter(line -> line.endsWith(suffix)).toList();
            assertEquals(1, own.size(), "esperava uma linha para n=" + n + ": " + own);
            assertTrue(own.get(0).contains("IP=198.18.0." + n + " "), "a linha de n=" + n + " tem outro IP: " + own);
        }
    }

    // ==================== COMPORTAMENTO E DESEMPENHO ====================

    @Test
    @DisplayName("com ou sem log, as respostas são idênticas")
    void loggingDoesNotChangeResponses() throws Exception {
        JavalinAPI.setRequestLogMode(RequestLogMode.OFF);
        List<String> withoutLog = snapshot("203.0.113.31");
        JavalinAPI.setRequestLogMode(RequestLogMode.ALL);
        List<String> withLog = snapshot("203.0.113.32");
        assertEquals(withoutLog, withLog);
    }

    @Test
    @DisplayName("modo ERRORS escreve só as falhas; OFF não escreve nada")
    void modesReduceOrSilenceTheLog() throws Exception {
        String ip = "203.0.113.33";
        JavalinAPI.setRequestLogMode(RequestLogMode.ERRORS);
        int mark = mark();
        get("/api/log/ok", ip);
        get("/api/log/sumiu", ip);
        List<String> lines = lines(logSince(mark), "IP=" + ip + " ");
        assertEquals(1, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).contains("STATUS=404"), lines.get(0));

        JavalinAPI.setRequestLogMode(RequestLogMode.OFF);
        mark = mark();
        get("/api/log/sumiu-de-novo", ip);
        get("/api/log/boom", ip);
        assertTrue(lines(logSince(mark), "IP=" + ip + " ").isEmpty());
        assertEquals(RequestLogMode.OFF, JavalinAPI.getRequestLogMode());
    }

    @Test
    @DisplayName("terminal lento não atrasa a requisição: a escrita é de outra thread")
    void slowTerminalNeverSlowsRequests() throws Exception {
        System.setOut(new PrintStream(new SlowStream(200), true, StandardCharsets.UTF_8));
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) assertEquals(200, get("/api/log/load?slow=" + i, "203.0.113.34").statusCode());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.setOut(CAPTURE);
        assertTrue(RequestLog.flush(20_000), "a fila não foi escrita");
        // Síncrono, cada requisição esperaria ao menos 200 ms pelo terminal: 4 s no total.
        assertTrue(elapsedMs < 2_000, "as requisições esperaram pelo terminal: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("fila cheia descarta e avisa, em vez de segurar a requisição")
    void fullQueueDropsInsteadOfBlocking() throws Exception {
        RequestLog.capacity = 5;
        System.setOut(new PrintStream(new SlowStream(1_000), true, StandardCharsets.UTF_8));
        int mark = TERMINAL.size();
        long start = System.nanoTime();
        for (int i = 0; i < 40; i++) assertEquals(200, get("/api/log/load?drop=" + i, "203.0.113.35").statusCode());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.setOut(CAPTURE);
        assertTrue(elapsedMs < 2_000, "as requisições esperaram pela fila: " + elapsedMs + " ms");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String log = "";
        while (System.nanoTime() < deadline && !log.contains("descartada")) {
            Thread.sleep(50);
            log = text(mark);
        }
        assertTrue(log.contains("linha(s) descartada(s)"), log);
    }

    @Test
    @DisplayName("carga: o log ligado não vira gargalo")
    void loadWithLoggingStaysClose() throws Exception {
        int threads = 8;
        int perThread = 300;
        load(threads, 100, "warm"); // aquece o JIT nos dois caminhos
        JavalinAPI.setRequestLogMode(RequestLogMode.OFF);
        load(threads, 100, "warm");

        JavalinAPI.setRequestLogMode(RequestLogMode.OFF);
        long off = Math.min(load(threads, perThread, "off1"), load(threads, perThread, "off2"));
        JavalinAPI.setRequestLogMode(RequestLogMode.ALL);
        int mark = mark();
        long on = Math.min(load(threads, perThread, "on1"), load(threads, perThread, "on2"));
        String log = logSince(mark);

        int total = threads * perThread;
        originalOut.printf("Carga do log de requisições: %d requisições em %d threads — sem log %d ms, com log %d ms "
                + "(%.0f req/s contra %.0f req/s)%n", total, threads, off, on, total * 1000.0 / on, total * 1000.0 / off);
        assertEquals(2 * total, lines(log, "PARAMS=phase=on").size(), "linha perdida ou duplicada na carga");
        assertTrue(on <= off * 1.5 + 500, "o log custou demais: sem log " + off + " ms, com log " + on + " ms");
    }

    // ==================== APOIO ====================

    /** Esvazia a fila e devolve a posição atual do terminal. */
    private static int mark() {
        assertTrue(RequestLog.flush(10_000), "a fila do log não foi escrita a tempo");
        return TERMINAL.size();
    }

    /** O que o terminal recebeu desde a marca, sem as cores — depois de a fila ser escrita. */
    private static String logSince(int mark) {
        assertTrue(RequestLog.flush(10_000), "a fila do log não foi escrita a tempo");
        return text(mark);
    }

    private static String text(int mark) {
        byte[] all = TERMINAL.toByteArray();
        return ANSI.matcher(new String(all, mark, all.length - mark, StandardCharsets.UTF_8)).replaceAll("");
    }

    private static List<String> lines(String log, String marker) {
        List<String> found = new ArrayList<>();
        for (String line : log.split("\\R")) {
            if (line.contains(marker)) found.add(line.stripTrailing());
        }
        return found;
    }

    private static String single(String log, String marker) {
        List<String> found = lines(log, marker);
        assertEquals(1, found.size(), "esperava uma linha com '" + marker + "':\n" + log);
        return found.get(0);
    }

    /** Status, corpo e cabeçalhos (menos a data) de um conjunto de requisições. */
    private static List<String> snapshot(String ip) throws Exception {
        List<String> result = new ArrayList<>();
        List<HttpResponse<String>> responses = List.of(get("/api/log/ok?page=1", ip),
                post("/api/log/items", ip, "{}"), get("/api/log/nada", ip), get("/api/log/boom", ip),
                get("/sobre", ip), get("/css/site.css", ip));
        for (HttpResponse<String> response : responses) {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(response.headers().map());
            headers.remove("date");
            result.add(response.statusCode() + " " + headers + " " + response.body());
        }
        return result;
    }

    /** Dispara {@code threads} x {@code perThread} requisições e devolve o tempo total, em ms. */
    private static long load(int threads, int perThread, String phase) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String path = "/api/log/load?phase=" + phase.replaceAll("\\d", "") + "&t=" + thread + "&i=" + i;
                        if (get(path, "203.0.113.40").statusCode() != 200) throw new IllegalStateException(path);
                    }
                    return null;
                });
            }
            long start = System.nanoTime();
            for (Future<Void> done : pool.invokeAll(tasks)) done.get();
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } finally {
            pool.shutdownNow();
        }
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> get(String path, String ip) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("X-Forwarded-For", ip).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String ip, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("X-Forwarded-For", ip)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Um terminal que demora a cada escrita. */
    private static final class SlowStream extends OutputStream {
        private final long delayMs;

        SlowStream(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
