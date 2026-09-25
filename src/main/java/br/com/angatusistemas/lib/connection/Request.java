package br.com.angatusistemas.lib.connection;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

/**
 * Classe utilitária para requisições HTTP (GET, POST, PUT, PATCH, DELETE e qualquer outro método)
 * com token Bearer e corpo JSON — sem dependências externas.
 *
 * <p><strong>Propósito:</strong> cliente HTTP simples para chamadas a APIs REST, montado sobre um
 * único {@link HttpClient} do JDK compartilhado por todas as chamadas: seguro entre threads,
 * reaproveita conexões (keep-alive) e fala HTTP/1.1 e HTTP/2.</p>
 *
 * <p><strong>Quando usar:</strong> chamadas HTTP de ida e volta com corpo de texto (JSON), de
 * qualquer ponto da aplicação, inclusive sob muitas requisições simultâneas.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para download de arquivos grandes, streaming, cabeçalhos
 * customizados, cookies ou multipart — use o {@link HttpClient} do JDK diretamente; para scraping
 * com renderização, use {@link br.com.angatusistemas.lib.browser.BrowserAPI}.</p>
 *
 * <p><strong>Comportamento:</strong></p>
 * <ul>
 *   <li><strong>Tempo:</strong> a conexão tem até 15 s e a chamada inteira — conexão, envio,
 *       espera e leitura do corpo — tem até {@link #getTimeout()} (padrão
 *       {@link #DEFAULT_TIMEOUT}, 15 s). Antes só havia tempo por leitura, e um servidor que
 *       mandava um byte por segundo segurava a chamada indefinidamente.</li>
 *   <li><strong>Tamanho:</strong> o corpo da resposta é limitado a {@link #getMaxBodyBytes()}
 *       (padrão {@link #DEFAULT_MAX_BODY_BYTES}, 10 MiB); acima disso a chamada falha com
 *       {@link ResponseTooLargeException} em vez de esgotar a memória.</li>
 *   <li><strong>Métodos:</strong> qualquer método é enviado como pedido — inclusive
 *       {@code PATCH} e {@code GET} com corpo; o nome é normalizado para maiúsculas.</li>
 *   <li><strong>Redirecionamentos:</strong> seguidos por padrão, até 4 seguidos (limite do JDK),
 *       inclusive de HTTP para HTTPS, mas nunca de HTTPS para HTTP; fora disso, a resposta 3xx
 *       volta como está. Num redirecionamento para outro host, porta ou esquema, o cabeçalho
 *       {@code Authorization} (e {@code Cookie}) <em>não</em> é repassado. Desligue com
 *       {@link #setFollowRedirects(boolean)}.</li>
 *   <li><strong>Texto:</strong> o corpo é decodificado no charset do {@code Content-Type} da
 *       resposta; sem charset declarado, em UTF-8.</li>
 *   <li><strong>Falhas:</strong> nada é lançado por falha de rede ou entrada inválida; a
 *       {@link Response} volta com {@link Response#isNetworkError()} verdadeiro, o motivo em
 *       {@link Response#getError()} e a explicação em português no corpo.</li>
 * </ul>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * // GET simples
 * Response resp = Request.query("GET", "https://api.exemplo.com/users");
 *
 * // POST com JSON e token
 * String json = "{\"nome\":\"João\"}";
 * Response resp2 = Request.query("POST", "https://api.exemplo.com/users", json, "meu-token");
 *
 * if (resp2.isSuccess()) {
 *     System.out.println(resp2.getBody());
 * } else if (resp2.isNetworkError()) {
 *     // sem resposta do servidor: veja resp2.getError()
 * }
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> sempre valide {@code isSuccess()} antes de usar o corpo;
 * use {@code isNetworkError()} para separar "o servidor recusou" de "o servidor nem respondeu";
 * codifique parâmetros de query com {@link java.net.URLEncoder} — espaços e caracteres reservados
 * na URL fazem a chamada falhar (caracteres acentuados são codificados automaticamente em
 * UTF-8). O {@code Content-Type: application/json} é enviado apenas quando há corpo.</p>
 *
 * <p><strong>Limitações:</strong> sem cabeçalhos customizados, cookies, multipart e HTTPS com
 * certificados privados; as configurações ({@link #setTimeout(Duration)},
 * {@link #setMaxBodyBytes(long)}, {@link #setFollowRedirects(boolean)}) valem para o processo
 * inteiro.</p>
 *
 * @author Angatu Sistemas
 * @see Response
 * @see StatusCode
 */
public final class Request {

    /** Tempo máximo padrão da chamada inteira: 15 segundos, o mesmo número que a versão anterior documentava. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** Limite padrão do corpo da resposta: 10 MiB. */
    public static final long DEFAULT_MAX_BODY_BYTES = 10L * 1024 * 1024;

    /** Maior limite de corpo aceito: o corpo é montado num {@code byte[]}, e esse é o teto de um array na JVM. */
    private static final long MAX_ALLOWED_BODY_BYTES = Integer.MAX_VALUE - 8;

    /** Tempo máximo para estabelecer a conexão TCP (e o TLS). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Código devolvido numa falha sem resposta HTTP. É 500 por compatibilidade: as versões
     * anteriores já devolviam 500 nesse caso, e há código que decide por ele.
     */
    private static final int FAILURE_CODE = 500;

    /** Prefixo do corpo numa falha, mantido das versões anteriores (há quem teste por ele). */
    private static final String ERROR_PREFIX = "Erro: ";

    /**
     * Threads do executor do cliente HTTP. O padrão do JDK é um pool sem limite; este é fixo
     * porque as tarefas são curtas — a espera pela resposta fica na thread de quem chama, e só a
     * resolução do nome (DNS), quando não está no cache da JVM, ocupa uma delas por um instante.
     */
    private static final int WORKER_THREADS = 16;

    private static volatile Duration timeout = DEFAULT_TIMEOUT;
    private static volatile long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private static volatile boolean followRedirects = true;

    /**
     * Guarda a criação dos clientes. Não é um holder estático de propósito: se abrir o seletor de
     * rede falhar (ex.: limite de arquivos abertos), a próxima chamada tenta de novo — num holder,
     * a falha viraria {@code NoClassDefFoundError} até reiniciar a aplicação.
     */
    private static final Object CLIENT_LOCK = new Object();
    private static volatile HttpClient followingClient;
    private static volatile HttpClient nonFollowingClient;
    /** Executor comum aos dois clientes; só acessado sob {@link #CLIENT_LOCK}. */
    private static ExecutorService executor;

    private Request() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== MÉTODOS PÚBLICOS ====================

    /**
     * Executa uma requisição HTTP sem corpo e sem token.
     *
     * @param method Método HTTP (GET, POST, PUT, DELETE, PATCH, etc.)
     * @param urlStr URL absoluta da requisição ({@code http://} ou {@code https://})
     * @return {@link Response} com o corpo e o status; nunca {@code null}
     */
    public static Response query(String method, String urlStr) {
        return query(method, urlStr, null, null);
    }

    /**
     * Executa uma requisição HTTP com corpo (ex: JSON) mas sem token.
     *
     * @param method Método HTTP
     * @param urlStr URL absoluta da requisição
     * @param body   Corpo da requisição (normalmente JSON) — pode ser {@code null}
     * @return {@link Response} com o corpo e o status; nunca {@code null}
     */
    public static Response query(String method, String urlStr, String body) {
        return query(method, urlStr, body, null);
    }

    /**
     * Executa uma requisição HTTP completa com corpo e token Bearer.
     *
     * <p>O token é enviado no header {@code Authorization: Bearer <token>}. Se um corpo for
     * fornecido, ele é enviado em UTF-8 com {@code Content-Type: application/json}, no método
     * pedido — um {@code GET} com corpo sai como {@code GET}, e não mais como {@code POST}.</p>
     *
     * <p>Não lança exceção por falha de rede nem por entrada inválida (método ou URL
     * malformados, token com quebra de linha): a resposta volta com
     * {@link Response#isNetworkError()} verdadeiro e a explicação no corpo.</p>
     *
     * @param method Método HTTP (GET, POST, PUT, DELETE, PATCH, etc.); maiúsculas ou minúsculas
     * @param urlStr URL absoluta ({@code http://} ou {@code https://}), com a query já codificada
     * @param body   Corpo da requisição (pode ser {@code null}); vazio equivale a sem corpo
     * @param token  Token Bearer (pode ser {@code null}); vazio equivale a sem token
     * @return {@link Response} com o corpo e o status; nunca {@code null}
     */
    public static Response query(String method, String urlStr, String body, String token) {
        Duration total = timeout;
        long limit = maxBodyBytes;
        HttpRequest request;
        String verb;
        try {
            verb = normalizeMethod(method);
            request = buildRequest(verb, parseUrl(urlStr), body, token, total);
        } catch (InvalidRequestException e) {
            return failure(e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // As validações acima já cobrem o que o JDK recusa; uma recusa inesperada dele pode
            // trazer a URL ou o token na mensagem, e por isso não é repassada.
            InvalidRequestException safe = new InvalidRequestException(
                    "o pedido não pôde ser montado (" + e.getClass().getSimpleName() + ").");
            return failure(safe.getMessage(), safe);
        }

        CompletableFuture<HttpResponse<byte[]>> future;
        try {
            future = client(followRedirects).sendAsync(request, limitedBody(limit, "HEAD".equals(verb)));
        } catch (RuntimeException e) {
            return failure("não foi possível iniciar a requisição (" + technical(e) + ").", e);
        }

        try {
            HttpResponse<byte[]> response = future.get(total.toMillis(), TimeUnit.MILLISECONDS);
            return toResponse(response);
        } catch (TimeoutException e) {
            // Cancelar aborta a troca e fecha a conexão; sem isso o servidor lento continuaria
            // ocupando o socket depois de a chamada já ter desistido.
            future.cancel(true);
            return failure(timeoutMessage(total), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failure("a espera pela resposta foi interrompida.", e);
        } catch (ExecutionException e) {
            return failureFrom(e.getCause(), total);
        }
    }

    // ==================== CONFIGURAÇÃO ====================

    /**
     * Define o tempo máximo da chamada inteira: conexão, envio, espera e leitura do corpo.
     *
     * <p>Vale para todas as chamadas seguintes, no processo inteiro. A conexão continua limitada
     * a 15 s, mesmo que o total seja maior.</p>
     *
     * @param total Tempo máximo, de 1 ms para cima
     * @throws IllegalArgumentException se {@code total} for nulo, zero, negativo ou grande demais
     */
    public static void setTimeout(Duration total) {
        if (total == null || total.isNegative() || total.isZero()) {
            throw new IllegalArgumentException("O tempo máximo da requisição precisa ser positivo: " + total);
        }
        try {
            if (total.toMillis() < 1) {
                throw new IllegalArgumentException("O tempo máximo da requisição precisa ser de pelo menos 1 ms: " + total);
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("O tempo máximo da requisição é grande demais: " + total, e);
        }
        timeout = total;
    }

    /**
     * Retorna o tempo máximo da chamada inteira.
     *
     * @return Tempo máximo em vigor (padrão {@link #DEFAULT_TIMEOUT})
     */
    public static Duration getTimeout() {
        return timeout;
    }

    /**
     * Define o tamanho máximo do corpo da resposta, em bytes.
     *
     * <p>Uma resposta maior falha com {@link ResponseTooLargeException} — de imediato, se o
     * servidor declarar o tamanho em {@code Content-Length}, ou assim que o limite for passado.
     * Vale para todas as chamadas seguintes, no processo inteiro.</p>
     *
     * @param bytes Limite em bytes, de 1 até {@code Integer.MAX_VALUE - 8}
     * @throws IllegalArgumentException se o limite estiver fora dessa faixa
     */
    public static void setMaxBodyBytes(long bytes) {
        if (bytes < 1 || bytes > MAX_ALLOWED_BODY_BYTES) {
            throw new IllegalArgumentException("O limite do corpo da resposta precisa estar entre 1 e "
                    + MAX_ALLOWED_BODY_BYTES + " bytes: " + bytes);
        }
        maxBodyBytes = bytes;
    }

    /**
     * Retorna o tamanho máximo do corpo da resposta, em bytes.
     *
     * @return Limite em vigor (padrão {@link #DEFAULT_MAX_BODY_BYTES})
     */
    public static long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    /**
     * Liga ou desliga o seguimento automático de redirecionamentos (3xx).
     *
     * <p>Ligado (padrão), a chamada segue até 4 redirecionamentos seguidos, inclusive de HTTP para
     * HTTPS, mas nunca de HTTPS para HTTP (nesses casos a resposta 3xx volta como está), e não
     * repassa {@code Authorization} nem {@code Cookie} a outro host, porta ou esquema.
     * Desligado, a resposta 3xx volta como está, com o destino no cabeçalho {@code Location} —
     * útil quando a URL vem do usuário e um redirecionamento não pode levar a chamada para um
     * endereço interno. Vale para todas as chamadas seguintes, no processo inteiro.</p>
     *
     * @param follow {@code true} para seguir redirecionamentos, {@code false} para devolvê-los
     */
    public static void setFollowRedirects(boolean follow) {
        followRedirects = follow;
    }

    /**
     * Indica se os redirecionamentos (3xx) são seguidos automaticamente.
     *
     * @return {@code true} se seguidos (padrão)
     */
    public static boolean isFollowRedirects() {
        return followRedirects;
    }

    // ==================== MONTAGEM DO PEDIDO ====================

    /**
     * Normaliza e valida o nome do método.
     *
     * @throws InvalidRequestException com a explicação em português
     */
    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new InvalidRequestException("informe o método HTTP (GET, POST, PUT, PATCH, DELETE...).");
        }
        // Locale.ROOT: com a JVM em turco, "options".toUpperCase() viraria "OPTİONS", com um I
        // pontuado que nenhum servidor reconhece.
        String verb = method.trim().toUpperCase(Locale.ROOT);
        if (verb.equals("CONNECT")) {
            throw new InvalidRequestException("o método CONNECT não é suportado.");
        }
        if (!isToken(verb)) {
            throw new InvalidRequestException("método HTTP inválido: use um nome como GET, POST, PUT, PATCH ou DELETE.");
        }
        return verb;
    }

    /**
     * Converte e valida a URL. A mensagem de erro nunca repete a URL: ela pode levar chave de
     * API na query, e o corpo da resposta costuma ir para o log.
     *
     * @throws InvalidRequestException com a explicação em português
     */
    private static URI parseUrl(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) {
            throw new InvalidRequestException("informe a URL da requisição.");
        }
        URI uri;
        try {
            uri = new URI(urlStr.trim());
        } catch (URISyntaxException e) {
            String where = e.getIndex() >= 0 ? " na posição " + e.getIndex() : "";
            throw new InvalidRequestException("URL inválida: há um caractere não permitido" + where
                    + ". Codifique espaços e caracteres especiais com URLEncoder antes de chamar o Request.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidRequestException("URL inválida: use um endereço absoluto começando com http:// ou https://.");
        }
        if (uri.getHost() == null) {
            throw new InvalidRequestException("URL inválida: o endereço não tem um servidor (host) válido.");
        }
        // Caracteres fora do ASCII (ex.: "ã") viram %C3%A3, a codificação UTF-8 que os servidores
        // esperam; mandados crus, cada servidor interpretaria de um jeito.
        return URI.create(uri.toASCIIString());
    }

    /** Monta o pedido já validado. */
    private static HttpRequest buildRequest(String verb, URI uri, String body, String token, Duration total) {
        // O timeout do pedido só cobre até os cabeçalhos da resposta; o prazo da chamada inteira
        // é o get(...) em query(). Os dois usam o mesmo valor para que nenhum corte antes do outro.
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(total);
        if (uri.getScheme().equalsIgnoreCase("http")) {
            // Em http:// o JDK tentaria "Upgrade: h2c" no primeiro pedido, e há servidor e proxy
            // que tratam mal esse cabeçalho. HTTP/2 fica para https://, negociado no TLS.
            builder.version(HttpClient.Version.HTTP_1_1);
        }
        if (token != null && !token.isEmpty()) {
            // Validado aqui porque a exceção do JDK para valor inválido traz o valor inteiro —
            // o token — na mensagem.
            if (!isValidHeaderValue(token)) {
                throw new InvalidRequestException("o token contém caracteres que não podem ir num cabeçalho HTTP "
                        + "(quebra de linha ou caractere de controle).");
            }
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null && !body.isEmpty()) {
            builder.header("Content-Type", "application/json");
            builder.method(verb, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(verb, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    /** Verifica se o texto é um nome de método válido ({@code token} da RFC 9110). */
    private static boolean isToken(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean alphaNumeric = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!alphaNumeric && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Mesma regra do JDK para valor de cabeçalho: sem controle (exceto tab) e até U+00FF. */
    private static boolean isValidHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0xFF || c == 0x7F || (c < 0x20 && c != '\t')) {
                return false;
            }
        }
        return true;
    }

    // ==================== EXECUÇÃO ====================

    /**
     * Devolve o cliente compartilhado, criando-o no primeiro uso. O cliente que não segue
     * redirecionamentos só existe se alguém desligá-los.
     */
    private static HttpClient client(boolean follow) {
        HttpClient client = follow ? followingClient : nonFollowingClient;
        if (client != null) {
            return client;
        }
        synchronized (CLIENT_LOCK) {
            client = follow ? followingClient : nonFollowingClient;
            if (client == null) {
                if (executor == null) {
                    executor = newExecutor();
                }
                client = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(follow ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                        .executor(executor)
                        .build();
                if (follow) {
                    followingClient = client;
                } else {
                    nonFollowingClient = client;
                }
            }
            return client;
        }
    }

    /** Executor limitado, de threads daemon nomeadas, que encerram depois de um minuto ociosas. */
    private static ExecutorService newExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "Angatu-Http-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Leitor do corpo com limite. O tamanho declarado é conferido antes de ler — exceto em
     * {@code HEAD}, 204 e 304, em que o {@code Content-Length} descreve um corpo que não vem.
     */
    private static HttpResponse.BodyHandler<byte[]> limitedBody(long limit, boolean head) {
        return info -> {
            int status = info.statusCode();
            long declared = -1;
            if (!head && status != 204 && status != 304 && status >= 200) {
                try {
                    declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
                } catch (NumberFormatException e) {
                    declared = -1;
                }
            }
            return new LimitedBodySubscriber(limit, declared);
        };
    }

    /** Converte a resposta HTTP recebida. */
    private static Response toResponse(HttpResponse<byte[]> response) {
        byte[] bytes = response.body();
        String text = bytes == null || bytes.length == 0 ? "" : new String(bytes, charsetOf(response.headers()));
        return new Response(text, response.statusCode());
    }

    /**
     * Lê o charset do {@code Content-Type}. Antes o corpo era sempre lido como UTF-8, e um
     * servidor em ISO-8859-1 devolvia "S�o Paulo".
     */
    private static Charset charsetOf(HttpHeaders headers) {
        String contentType = headers.firstValue("Content-Type").orElse(null);
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String parameter : contentType.split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String name = trimmed.substring(8).trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) { // nome inválido ou charset não suportado
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    // ==================== FALHAS ====================

    /** Resposta de falha sem resposta HTTP, no formato das versões anteriores. */
    private static Response failure(String message, Exception error) {
        return new Response(ERROR_PREFIX + message, FAILURE_CODE, error);
    }

    /** Converte a causa de uma troca que falhou. Um {@link Error} (ex.: falta de memória) segue adiante. */
    private static Response failureFrom(Throwable cause, Duration total) {
        if (cause instanceof Error error) {
            throw error;
        }
        Exception error = cause instanceof Exception exception ? exception : new IOException(cause);
        return failure(describe(error, total), error);
    }

    /** Explicação em português da falha, sem repetir URL nem cabeçalhos. */
    private static String describe(Exception error, Duration total) {
        ResponseTooLargeException tooLarge = findCause(error, ResponseTooLargeException.class);
        if (tooLarge != null) {
            return tooLarge.getMessage();
        }
        if (findCause(error, HttpConnectTimeoutException.class) != null) {
            return "o servidor não aceitou a conexão em " + CONNECT_TIMEOUT.toSeconds() + " s.";
        }
        if (findCause(error, HttpTimeoutException.class) != null) {
            return timeoutMessage(total);
        }
        if (findCause(error, UnresolvedAddressException.class) != null
                || findCause(error, UnknownHostException.class) != null) {
            return "o nome do servidor não foi encontrado (DNS).";
        }
        if (findCause(error, ConnectException.class) != null) {
            return "não foi possível conectar ao servidor (conexão recusada ou endereço inacessível).";
        }
        if (findCause(error, SSLException.class) != null) {
            return "falha na conexão segura (TLS) com o servidor (" + technical(error) + ").";
        }
        return "falha de comunicação com o servidor (" + technical(error) + ").";
    }

    private static String timeoutMessage(Duration total) {
        return "o servidor não concluiu a resposta dentro do tempo limite de " + formatDuration(total)
                + " (ajuste com Request.setTimeout).";
    }

    /** Procura um tipo na cadeia de causas. */
    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /** Tipo e mensagem da exceção, para o detalhe técnico entre parênteses. */
    private static String technical(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    private static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + " s" : millis + " ms";
    }

    private static String formatBytes(long bytes) {
        long mib = 1024L * 1024;
        if (bytes >= mib && bytes % mib == 0) {
            return (bytes / mib) + " MiB";
        }
        if (bytes >= 1024 && bytes % 1024 == 0) {
            return (bytes / 1024) + " KiB";
        }
        return bytes + " bytes";
    }

    // ==================== TIPOS INTERNOS ====================

    /**
     * Pedido recusado antes de sair: método, URL ou token inválidos. A mensagem, em português, é
     * escrita aqui e nunca repete a URL nem o token; chega a quem chama por
     * {@link Response#getError()}, como um {@link IllegalArgumentException}.
     */
    private static final class InvalidRequestException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        InvalidRequestException(String message) {
            super(message);
        }
    }

    /**
     * Falha de uma resposta cujo corpo passa do limite de {@link #getMaxBodyBytes()}.
     *
     * <p>Aparece em {@link Response#getError()}, com {@link Response#isNetworkError()}
     * verdadeiro. Repetir a chamada não resolve: aumente o limite com
     * {@link #setMaxBodyBytes(long)} se o tamanho for esperado.</p>
     *
     * @author Angatu Sistemas
     */
    public static final class ResponseTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        /** Limite em vigor na chamada, em bytes. */
        private final long limit;
        /** Tamanho declarado pelo servidor, ou {@code -1}. */
        private final long declaredLength;

        private ResponseTooLargeException(long limit, long declaredLength) {
            super(declaredLength > limit
                    ? "a resposta tem " + formatBytes(declaredLength) + " (Content-Length), acima do limite de "
                            + formatBytes(limit) + ". Se o tamanho é esperado, aumente o limite com Request.setMaxBodyBytes."
                    : "a resposta passou do limite de " + formatBytes(limit)
                            + " e a leitura foi interrompida. Se o tamanho é esperado, aumente o limite com Request.setMaxBodyBytes.");
            this.limit = limit;
            this.declaredLength = declaredLength;
        }

        /**
         * Retorna o limite em vigor na chamada.
         *
         * @return Limite em bytes
         */
        public long getLimit() {
            return limit;
        }

        /**
         * Retorna o tamanho que o servidor declarou em {@code Content-Length}.
         *
         * @return Tamanho em bytes, ou {@code -1} se o servidor não declarou (a leitura foi
         *         interrompida ao passar do limite)
         */
        public long getDeclaredLength() {
            return declaredLength;
        }
    }

    /**
     * Acumula o corpo até o limite e cancela a leitura ao passar dele. Os sinais do
     * {@link Flow.Subscriber} chegam em série, por isso o estado não precisa de sincronização —
     * o mesmo desenho do {@code ofByteArray()} do JDK, que também guarda os buffers recebidos.
     */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final long declaredLength;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<ByteBuffer> received = new ArrayList<>();
        private Flow.Subscription subscription;
        private long total;

        LimitedBodySubscriber(long limit, long declaredLength) {
            this.limit = limit;
            this.declaredLength = declaredLength;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            Objects.requireNonNull(subscription);
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            if (declaredLength > limit) {
                // O tamanho declarado já passa do limite: nada é baixado.
                subscription.cancel();
                result.completeExceptionally(new ResponseTooLargeException(limit, declaredLength));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                total += item.remaining();
            }
            if (total > limit) {
                subscription.cancel();
                received.clear();
                result.completeExceptionally(new ResponseTooLargeException(limit, -1));
                return;
            }
            received.addAll(items);
        }

        @Override
        public void onError(Throwable throwable) {
            received.clear();
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (result.isDone()) {
                return;
            }
            byte[] bytes = new byte[(int) total];
            int offset = 0;
            for (ByteBuffer item : received) {
                int length = item.remaining();
                item.get(bytes, offset, length);
                offset += length;
            }
            received.clear();
            result.complete(bytes);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }
}
