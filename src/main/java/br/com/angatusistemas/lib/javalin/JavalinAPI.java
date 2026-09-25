package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.javalin.classes.BlockInfo;
import br.com.angatusistemas.lib.javalin.classes.DenyNotice;
import br.com.angatusistemas.lib.javalin.classes.PermanentBlock;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.RouteRateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.SlidingWindowCounter;
import br.com.angatusistemas.lib.javalin.classes.SuspectIp;
import br.com.angatusistemas.lib.javalin.html.HtmlRouteAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.task.Task;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.HandlerType;
import io.javalin.http.TooManyRequestsResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.router.ParsedEndpoint;
import io.javalin.websocket.WsHandlerEntry;
import io.javalin.websocket.WsHandlerType;

/**
 * API principal para configuração do servidor Javalin com rate limiting
 * avançado, proteção contra ataques e persistência de bloqueios.
 *
 * <p><strong>Propósito:</strong> encapsular toda a configuração do servidor web
 * (estáticos, segurança, rate limiting) em chamadas estáticas simples.</p>
 *
 * <p><strong>Funcionalidades:</strong></p>
 * <ul>
 *   <li>Recusa de entrada com cara de SQL Injection e XSS (heurística, ver
 *       {@link #hasMaliciousInput(Context)})</li>
 *   <li>Rate limiting por IP e por rota com janela deslizante, também no upgrade de WebSocket</li>
 *   <li>Bloqueios temporários (em memória) e bloqueios longos de 24 h (em memória e no banco,
 *       recarregados na subida)</li>
 *   <li>Headers de segurança HTTP automáticos</li>
 *   <li>Só HTTP: o TLS termina no proxy de borda do Coolify, que entrega a requisição em HTTP</li>
 *   <li>Arquivos estáticos de {@code public/}, com os mesmos cabeçalhos de segurança</li>
 * </ul>
 *
 * <p><strong>Quando usar:</strong> em toda aplicação web da biblioteca — a
 * inicialização é feita automaticamente pelo {@link AngatuLib} (não é preciso
 * chamar {@link #setup} manualmente, exceto para cenários avançados).</p>
 *
 * <p><strong>Ordem:</strong> os métodos de configuração (rate limit, paths, proxy, cabeçalhos,
 * tamanho de corpo) valem desde a primeira requisição quando chamados <strong>antes</strong> de
 * {@code new AngatuLib(...)}. Chamados depois, valem a partir dali — e o
 * {@code bloqByMaxRequisitions} do construtor sobrescreve {@link #setRateLimitingEnabled}.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> em aplicações sem servidor web; não
 * chame {@link #setup} mais de uma vez por processo (retorna a instância já
 * criada).</p>
 *
 * <p><strong>Integração:</strong> {@link AngatuLib} chama {@link #setup} no
 * bootstrap; {@link Route} usa {@link #get()} para registro de rotas;
 * {@link Saveable} persiste bloqueios longos, configurações de rota e o histórico de
 * violações; o IP do cliente vem de {@link IP#get(Context)}.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>Declare o proxy ({@link #setTrustedProxyHops(int)}) e os limites
 *       ({@link #configureRateLimit}, {@link #configureApiRateLimit},
 *       {@link #configureLoginRateLimit}, {@link #addUnlimitedPath}, {@link #addIgnoredPath});</li>
 *   <li>Construa {@code new AngatuLib(...)} (ou chame {@link #setup} diretamente);</li>
 *   <li>Use {@link #get()} para acessar o Javalin em cenários avançados.</li>
 * </ol>
 *
 * <p><strong>Boas práticas:</strong> proteja rotas sensíveis (login/API) com
 * presets de rate limit; use {@code addIgnoredPath} apenas para endpoints
 * realmente públicos (health check); monitore {@link #getActivePermanentBlocks()}.</p>
 *
 * <p><strong>Limitações:</strong> exige as dependências
 * {@code io.javalin:javalin:7.2.3} (web), {@code org.reflections:reflections:0.10.2} (rotas
 * automáticas) e os requisitos do {@link Saveable} (persistência de bloqueios). Dependências
 * ausentes são detectadas com mensagens de instalação claras.</p>
 *
 * @author Angatu Sistemas
 * @version 3.1
 * @see AngatuLib
 * @see Route
 * @see IP
 * @see br.com.angatusistemas.lib.database.Saveable
 */
public final class JavalinAPI {

    // ==================== CONSTANTES ====================

    /** Requisições máximas por segundo (padrão global) */
    private static final int DEFAULT_REQ_SEC = 5;
    /** Requisições máximas por minuto (padrão global) */
    private static final int DEFAULT_REQ_MIN = 30;

    /**
     * Violações necessárias para o bloqueio longo, <b>dentro</b> de
     * {@link #VIOLATION_WINDOW_SEC}.
     *
     * <p>Era 3, e sem janela: o contador só crescia, então três tropeços
     * espalhados por meses somavam igual a três seguidos. Quem abre o site
     * duas vezes por semana chegava lá sozinho, e a punição para isso era
     * 403 em tudo por 30 dias.</p>
     *
     * <p>Abuso de verdade não produz três violações e para — produz dezenas
     * por minuto. Contar dez dentro de uma hora separa os dois casos sem
     * precisar adivinhar intenção.</p>
     */
    private static final int PERM_BLOCK_THRESHOLD = 10;

    /**
     * Janela em que as violações somam (1 hora).
     *
     * <p>Violação precisa prescrever. Sem prazo, o contador vira ficha
     * criminal: a pessoa que esbarrou no limite em janeiro carrega isso para
     * sempre e é banida em março por causa de um pico que não tem relação
     * nenhuma com o primeiro.</p>
     */
    private static final long VIOLATION_WINDOW_SEC = 3600L;

    /** Duração padrão de bloqueio em segundos (5 minutos) */
    private static final long DEFAULT_BLOCK_SEC = 300L;
    /** Duração de bloqueio pesado em segundos para burst attacks (1 hora) */
    private static final long HEAVY_BLOCK_SEC = 3600L;

    /**
     * Máximo de requisições em 1 segundo, por IP, antes de bloquear.
     *
     * <p>Era 10, e 10 é o número de uma tela normal: uma página logada dispara
     * a checagem de sessão, meia dúzia de chamadas de dados e as duas de push
     * ao mesmo tempo — o navegador manda tudo em paralelo, de propósito. Com o
     * limite em 10, abrir o próprio perfil já contava como ataque de rajada.</p>
     *
     * <p>Estático e path sem limite não chegam aqui, então o que sobra é
     * chamada de API. Trinta em um segundo continua sendo muito acima do que
     * qualquer tela precisa, e bem abaixo do que uma ferramenta de abuso faz.</p>
     */
    private static final int BURST_THRESHOLD = 30;

    /**
     * Extensões de recurso estático que <b>não</b> entram no rate limit.
     *
     * <p>Uma página real carrega CSS, fontes, scripts e dezenas de imagens em
     * paralelo — o navegador dispara tudo de uma vez, e isso é o comportamento
     * normal, não um ataque. Contando esses pedidos, uma vitrine com 15 fotos
     * estourava o {@link #BURST_THRESHOLD} logo no primeiro acesso e, em três
     * recargas, o visitante levava bloqueio <b>permanente</b> por
     * {@link #PERM_BLOCK_THRESHOLD}.</p>
     *
     * <p>Arquivo estático não é superfície de ataque: não tem query, não toca
     * o banco e não muda estado. O que precisa de limite é rota — login,
     * pagamento, escrita. É lá que o contador continua valendo. Por isso a
     * isenção vale só para GET/HEAD de um caminho que <b>não</b> é rota
     * (ver {@link #isStaticRequest(Context, String)}).</p>
     *
     * <p>{@code .json} e {@code .wasm} estão na lista porque arquivo fora dela cai no balde único
     * dos caminhos que não são rota ({@link #NO_ROUTE}): o {@code manifest.json} de um PWA, pedido a
     * cada página, dividia o balde com os 404.</p>
     */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".css", ".js", ".mjs", ".map", ".json", ".wasm",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg", ".ico", ".bmp",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".mp4", ".webm", ".ogg", ".mp3", ".wav",
            ".webmanifest", ".txt", ".xml", ".pdf");

    /**
     * Tamanho máximo padrão de um corpo lido em memória (16 MiB).
     *
     * <p>Era 1 GB. Toda rota que lê o corpo ({@code ctx.body()}, {@code bodyAsClass}) aceitava
     * um pedido desse tamanho — e ler 1 GB custa perto de 3 GB de heap. Uma única requisição
     * derrubava o contêiner. Upload multipart não passa por este limite (o Javalin grava as
     * partes em disco); quem precisa de corpo maior chama {@link #setMaxRequestSize(long)}.</p>
     */
    private static final long DEFAULT_MAX_REQUEST_SIZE = 16L * 1024 * 1024;

    /** Maior corpo que a varredura de conteúdo malicioso lê (64 KiB). */
    private static final int MAX_SCANNED_BODY_BYTES = 64 * 1024;

    /**
     * O que o limite conta para todo caminho que não é rota: um balde só por IP (ver
     * {@link #resolveLimit(Context, String, boolean)}).
     */
    private static final String NO_ROUTE = "(sem rota)";

    /**
     * Folga do freio de rede do IPv6: o {@code /48} inteiro pode somar este múltiplo do limite
     * de um cliente, num limite <strong>configurado</strong>.
     *
     * <h4>Por que existe</h4>
     * <p>O limite enxerga o IPv6 por {@code /64}, que é o que um cliente recebe. Mas um
     * {@code /48} sai de graça num serviço de túnel, e tem 65.536 {@code /64} dentro: trocando de
     * {@code /64} a cada pedido, o limite de login de 5 por minuto virava 327 mil — medido, 300
     * tentativas de 300 {@code /64} do mesmo {@code /48} passaram todas em 710 ms.</p>
     *
     * <h4>Por que só nos limites configurados, e sem bloqueio longo</h4>
     * <p>Um {@code /48} também pode ser uma operadora móvel inteira, com milhares de clientes
     * legítimos dentro. O freio fica onde tentar e errar em massa compensa — login, cupom, API
     * sensível, configurados com {@link #configureRateLimit} —, com folga de 16 vezes o limite de
     * um cliente, e nunca registra violação: quem divide o {@code /48} com um atacante esbarra no
     * freio por alguns minutos, mas não é banido por 24 horas pelo que não fez. O limite global
     * continua por {@code /64}.</p>
     */
    private static final int IPV6_NETWORK_FACTOR = 16;

    /** Quantas linhas de histórico de violação vão por transação, na gravação em lote. */
    private static final int VIOLATION_FLUSH_BATCH = 200;


    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("/{2,}");

    // ==================== CONFIGURAÇÕES DE RATE LIMIT ====================

    /** Configurações de rate limit por padrão de path (chave: padrão canônico). */
    private static final Map<String, RateLimitConfig> RATE_LIMIT_CONFIGS = new ConcurrentHashMap<>();
    /** Os padrões com curinga das configurações, compilados, do mais específico ao menos. */
    private static volatile List<PathRule> rateLimitRules = List.of();
    /** Paths sem nenhum limite de requisição (padrões canônicos). */
    private static final Set<String> UNLIMITED_PATHS = ConcurrentHashMap.newKeySet();
    /** Os padrões com curinga dos paths sem limite, compilados. */
    private static volatile List<PathRule> unlimitedRules = List.of();
    /** Prefixos de path completamente ignorados pela verificação de segurança (minúsculos). */
    private static final Set<String> IGNORED_PATHS = ConcurrentHashMap.newKeySet();

    /** Limite global aplicado a path sem configuração própria. */
    private static volatile RateLimitConfig globalConfig =
            new RateLimitConfig(DEFAULT_REQ_SEC, DEFAULT_REQ_MIN, DEFAULT_BLOCK_SEC);
    /** Flag para habilitar/desabilitar rate limiting */
    private static volatile boolean rateLimitingEnabled = true;
    /** Maior corpo lido em memória; ver {@link #DEFAULT_MAX_REQUEST_SIZE}. */
    private static volatile long maxRequestSize = DEFAULT_MAX_REQUEST_SIZE;

    // ==================== ESTADO EM MEMÓRIA ====================

    /**
     * Intervalo da varredura que devolve memória dos mapas de rate limiting.
     *
     * <p>Um minuto: o custo é percorrer mapas que, entre duas varreduras, só podem ter crescido
     * o que o tráfego de um minuto produziu.</p>
     */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    /**
     * Tempo parado depois do qual uma entrada sai do mapa, por mapa.
     *
     * <p>Cada número é a janela do próprio mapa com folga, e é isso que torna a remoção
     * <strong>sem efeito sobre a decisão</strong>: o que é removido aqui é exatamente o que o
     * código descartaria sozinho no próximo toque daquela chave. Reduzir qualquer um deles
     * abaixo da janela correspondente enfraqueceria a proteção — o de violações, em especial,
     * precisa cobrir {@link #VIOLATION_WINDOW_SEC} inteiro.</p>
     */
    private static final long IDLE_COUNTER_SEC = 120L;
    private static final long IDLE_MINUTE_SEC = 300L;
    private static final long IDLE_BURST_MS = 120_000L;
    private static final long IDLE_VIOLATION_SEC = VIOLATION_WINDOW_SEC + 300L;

    /**
     * Teto declarado de chaves por mapa. Passar disto não é tráfego, é ataque ou defeito.
     *
     * <p>Nada é apagado à força ao ultrapassá-lo — apagar bloqueio ativo soltaria justamente
     * quem está atacando. O teto existe para <strong>aparecer no console</strong>: sem essa
     * linha, o sintoma chegaria como "o contêiner morreu de madrugada" e não como "um cliente
     * abriu cem mil rotas distintas".</p>
     */
    private static final int MAX_KEYS_PER_MAP = 50_000;

    /** Contador de janela deslizante por segundo, chaveado por IP+path */
    private static final Map<String, SlidingWindowCounter> SECOND_COUNTERS = new ConcurrentHashMap<>();
    /** Contador de janela deslizante por minuto, chaveado por IP+path */
    private static final Map<String, SlidingWindowCounter> MINUTE_COUNTERS = new ConcurrentHashMap<>();
    /**
     * Timestamps recentes para detecção de burst attack, chaveado por IP hash.
     *
     * <p>É {@link Deque} e não {@link Queue} para a varredura periódica conseguir ler o
     * <strong>último</strong> instante sem consumir a fila — é assim que ela sabe que a entrada
     * está parada e pode sair do mapa.</p>
     */
    private static final Map<String, Deque<Long>> BURST_TRACKER = new ConcurrentHashMap<>();
    /** Chaves (IP+path, ou path) temporariamente bloqueadas, com o instante do desbloqueio. */
    private static final Map<String, BlockInfo> BLOCKED_CACHE = new ConcurrentHashMap<>();

    /**
     * Bloqueios longos ativos: hash do IP → fim do bloqueio (epoch, segundos).
     *
     * <h4>Por que em memória</h4>
     * <p>Antes, um IP com bloqueio longo custava <strong>uma consulta ao SQLite por
     * requisição</strong>, para sempre — e sem índice, varrendo a tabela. Um atacante bloqueado
     * que continuasse mandando pedidos transformava cada um deles em carga no banco, disputando
     * conexão com o resto do site. Agora a decisão é um acesso a mapa. O banco continua sendo o
     * registro durável: a linha em {@code permanentblocks} é gravada fora da requisição e
     * recarregada na subida.</p>
     */
    private static final Map<String, Long> PERMANENT_BLOCKS = new ConcurrentHashMap<>();

    /**
     * Instantes das violações recentes de cada IP, em segundos.
     *
     * <p>Guarda os momentos, e não um total, porque a decisão de bloquear
     * depende de <i>quando</i> as violações aconteceram. Era um contador que
     * só subia, e por isso o total de uma vida inteira decidia o banimento de
     * hoje. A fila é podada por {@link #VIOLATION_WINDOW_SEC} a cada
     * violação.</p>
     */
    private static final Map<String, Deque<Long>> VIOLATION_CACHE = new ConcurrentHashMap<>();

    /**
     * Violações ainda não gravadas no histórico ({@link SuspectIp}), por hash de IP.
     *
     * <p>Cada violação disparava uma leitura e uma gravação no banco, em tarefa separada. Sob
     * ataque, isso era uma fila de milhares de transações disputando a vez de gravar com a
     * própria aplicação. Agora elas somam aqui e vão ao banco em lote, na varredura de cada
     * minuto. O histórico é para quem administra; a decisão de bloquear nunca dependeu dele.</p>
     */
    private static final Map<String, PendingViolations> PENDING_VIOLATIONS = new ConcurrentHashMap<>();

    // ==================== HEADERS DE SEGURANÇA ====================

    private static final Map<String, String> SECURITY_HEADERS = new ConcurrentHashMap<>();

    static {
        SECURITY_HEADERS.put("X-Frame-Options", "SAMEORIGIN");
        SECURITY_HEADERS.put("X-XSS-Protection", "1; mode=block");
        SECURITY_HEADERS.put("Referrer-Policy", "strict-origin-when-cross-origin");
        // Sem ele, o navegador "adivinha" o tipo de um arquivo pelo conteúdo — e um upload que
        // diz ser imagem, mas contém HTML com script, podia ser executado como página.
        SECURITY_HEADERS.put("X-Content-Type-Options", "nosniff");

        SECURITY_HEADERS.put("Content-Security-Policy",
                "default-src * data: blob: 'unsafe-inline' 'unsafe-eval'; " +
                "script-src * data: blob: 'unsafe-inline' 'unsafe-eval'; " +
                "style-src * data: blob: 'unsafe-inline'; " +
                "img-src * data: blob:; " +
                "font-src * data: blob:; " +
                "connect-src * data: blob:; " +
                "frame-src * data: blob:; " +
                "media-src * data: blob:; " +
                "object-src * data: blob:;"
        );
    }

    /**
     * Substitui um header de segurança padrão.
     *
     * <p>Existe por causa da {@code Content-Security-Policy}: o valor padrão
     * daqui libera {@code *} em toda diretiva, o que faz o header existir sem
     * proteger de nada — um script injetado apontando para fora passaria
     * igual. Não dá para apertar isso na biblioteca sem quebrar aplicação que
     * carrega recurso de terceiro, e a lista de origens só a aplicação
     * conhece. Então a aplicação declara a sua.</p>
     *
     * <p>Chame <b>antes</b> de {@code new AngatuLib(...)} para valer desde a
     * primeira requisição. Os cabeçalhos de cache ({@code Cache-Control}, {@code Pragma} e
     * {@code Expires}) declarados antes da subida valem também para os arquivos estáticos de
     * {@code public/}, que sem isso sairiam com o {@code Cache-Control: max-age=0} do
     * Javalin.</p>
     *
     * @param name  nome do header (ex: {@code "Content-Security-Policy"})
     * @param value valor a enviar; {@code null} remove o header
     */
    public static void setSecurityHeader(String name, String value) {
        if (name == null || name.isBlank()) return;
        if (value == null) SECURITY_HEADERS.remove(name);
        else SECURITY_HEADERS.put(name, value);
    }

    /** Cabeçalhos de cache que o projeto declara e que também valem para os arquivos estáticos. */
    private static final List<String> CACHE_HEADERS = List.of("Cache-Control", "Pragma", "Expires");

    /**
     * Cabeçalhos dos arquivos estáticos: os do Javalin, com os de cache trocados pelos que o
     * projeto declarou em {@link #setSecurityHeader(String, String)}.
     *
     * <p>O servidor de estáticos escreve os próprios cabeçalhos <em>depois</em> do filtro de
     * segurança, por cima do que ele pôs: sem isto, um {@code Cache-Control: no-store} valia para
     * páginas e rotas, mas não para CSS, JS e imagens. O contorno — um {@code after} registrado
     * depois da subida — disputava a lista de manipuladores do Javalin com as requisições em
     * andamento.</p>
     */
    private static Map<String, String> staticFileHeaders(Map<String, String> javalinDefaults) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (javalinDefaults != null) headers.putAll(javalinDefaults);
        for (String cacheHeader : CACHE_HEADERS) {
            for (Map.Entry<String, String> declared : SECURITY_HEADERS.entrySet()) {
                if (declared.getKey().equalsIgnoreCase(cacheHeader)) {
                    headers.keySet().removeIf(name -> name.equalsIgnoreCase(cacheHeader));
                    headers.put(cacheHeader, declared.getValue());
                }
            }
        }
        return headers;
    }

    /** Coordenadas Maven do Javalin (versão alvo da biblioteca). */
    private static final String JAVALIN_COORDINATES = "io.javalin:javalin:7.2.3";
    /** Coordenadas Maven da Reflections (scan de rotas). */
    private static final String REFLECTIONS_COORDINATES = "org.reflections:reflections:0.10.2";

    // ==================== PADRÕES MALICIOSOS ====================

    /** Métodos HTTP que podem carregar corpo malicioso no request. */
    private static final Set<HandlerType> BODY_METHODS = Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH);

    /**
     * Padrões de SQL Injection e XSS.
     *
     * <h4>Palavra inteira, distância limitada</h4>
     * <p>Os padrões eram {@code select.+from}, {@code update.+set} e parecidos, sem limite de
     * palavra. Dois defeitos saíam daí:</p>
     * <ul>
     *   <li><strong>recusavam JSON comum</strong>: {@code {"updatedAt":…,"setor":"vendas"}} casa
     *       com {@code update.+set}, {@code {"selectedIds":[…],"from":…}} com
     *       {@code select.+from}, {@code {"action":"update","offset":0}} de novo com
     *       {@code update.+set}. A tela de configurações recebia 403;</li>
     *   <li><strong>custo quadrático</strong>: o {@code .+} voltava sobre o texto inteiro a cada
     *       ocorrência da primeira palavra. Um corpo de 128 KB de {@code select} repetido levava
     *       10 s de CPU, e o filtro rodava antes de qualquer limite.</li>
     * </ul>
     * <p>Agora cada par exige as duas palavras inteiras ({@code \b}) a no máximo 100 caracteres
     * uma da outra, atravessando quebra de linha — linear no tamanho do texto, e sem casar com
     * nome de campo.</p>
     */
    private static final Pattern[] MALICIOUS_PATTERNS = {
            keywordPair("select", "from"),
            keywordPair("insert", "into"),
            keywordPair("update", "set"),
            keywordPair("delete", "from"),
            keywordPair("drop", "table"),
            keywordPair("union", "select"),
            Pattern.compile("\\bexec(?:ute)?\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\s*script\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjavascript\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bonload\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\beval\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balert\\s*\\(", Pattern.CASE_INSENSITIVE)
    };

    /** Escape <code>&#92;uXXXX</code> de uma string JSON. */
    private static final Pattern JSON_UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private static Pattern keywordPair(String first, String second) {
        return Pattern.compile("\\b" + first + "\\b.{0,100}?\\b" + second + "\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    // ==================== ESTADO DO SERVIDOR ====================

    private static volatile Javalin javalinInstance;
    private static volatile boolean initialized = false;

    /** Página de recusa do projeto; {@code null} usa a padrão. */
    private static volatile Function<DenyNotice, String> denyPage;
    /** A primeira falha da página do projeto vai para o log; as seguintes, sob ataque, não. */
    private static final AtomicBoolean DENY_PAGE_FAILURE_LOGGED = new AtomicBoolean();

    private JavalinAPI() {}

    /**
     * Declara quantos proxies reversos confiáveis existem na frente da aplicação.
     *
     * <p>Chame com {@code 1} no Coolify, ou com um nginx (ou Caddy, ou Apache) na frente; com
     * {@code 2} quando houver uma CDN (Cloudflare) na frente do proxy. Com {@code 0}, o
     * {@code X-Forwarded-For} é ignorado e vale o IP do socket — que é o único valor que o
     * cliente não consegue forjar.</p>
     *
     * <p>Sem esta chamada, vale a regra automática de {@link IP}: um proxy só é presumido
     * quando a conexão vem de rede privada. Declarar deixa a regra explícita.</p>
     *
     * <p>Errar para mais é pior que errar para menos: cada salto declarado a
     * mais devolve o controle do IP para quem faz a requisição.</p>
     *
     * @param hops Número de proxies confiáveis (negativo é tratado como zero)
     * @see IP#get(Context)
     */
    public static void setTrustedProxyHops(int hops) {
        IP.setTrustedProxyHops(hops);
    }

    /**
     * Declara um cabeçalho que já traz o IP do cliente, escrito por quem está na frente —
     * {@code "CF-Connecting-IP"} com a Cloudflare, {@code "True-Client-IP"} com a Akamai.
     *
     * <p>Só declare se <strong>todo</strong> o tráfego passa por esse intermediário: se a origem
     * aceitar conexão direta, o cliente escreve o cabeçalho e escolhe o próprio IP.</p>
     *
     * @param header Nome do cabeçalho; {@code null} volta a ignorar cabeçalhos desse tipo
     */
    public static void setClientIpHeader(String header) {
        IP.setTrustedHeader(header);
    }

    /**
     * Define o maior corpo de requisição lido em memória ({@code ctx.body()},
     * {@code bodyAsClass}). Corpo maior recebe 413.
     *
     * <p>Padrão: 16 MiB. Upload multipart não passa por este limite. Chame <b>antes</b> de
     * {@code new AngatuLib(...)}: o valor é aplicado quando o servidor é criado.</p>
     *
     * @param bytes Tamanho máximo em bytes (mínimo 1 KiB)
     */
    public static void setMaxRequestSize(long bytes) {
        maxRequestSize = Math.max(1024L, bytes);
    }

    /**
     * Troca por uma página do projeto a recusa mostrada ao navegador — a do rate limit (429), a
     * do bloqueio longo e a de conteúdo recusado (403).
     *
     * <p>É uma tela que uma pessoa de verdade lê: quem esbarra no limite quase sempre é um cliente
     * comum, que clicou duas vezes ou recarregou a página. Com esta chamada, a recusa segue o
     * sistema de design do projeto e leva a marca da Angatu Sistemas, como qualquer outra tela.</p>
     *
     * <p>A página precisa se bastar: estilo embutido, sem script e sem depender de outra
     * requisição — quem a recebe já está sendo limitado. O status, o {@code Retry-After} e o JSON
     * das chamadas de API continuam da biblioteca. Se a função lançar exceção ou devolver texto
     * vazio, vale a página padrão.</p>
     *
     * <pre>
     * JavalinAPI.setDenyPage(notice -&gt; DenyPageTemplate.render(notice.title(), notice.message()));
     * </pre>
     *
     * @param renderer Monta o HTML completo a partir da recusa; {@code null} volta à página padrão
     */
    public static void setDenyPage(Function<DenyNotice, String> renderer) {
        denyPage = renderer;
        DENY_PAGE_FAILURE_LOGGED.set(false);
    }

    // ==================== API PÚBLICA ====================

    /**
     * Inicializa o servidor Javalin em HTTP com todas as configurações de segurança — a forma
     * usada pelos projetos, todos hospedados no Coolify.
     *
     * <p>O servidor escuta em {@code 0.0.0.0:port}, só HTTP: o certificado, a renovação e o
     * redirecionamento para HTTPS são do proxy de borda do Coolify.</p>
     *
     * @param port            Porta HTTP em que o servidor escuta
     * @param enableRateLimit {@code true} para habilitar rate limiting
     * @return Instância configurada do Javalin, ou {@code null} em caso de falha
     */
    public static Javalin setup(int port, boolean enableRateLimit) {
        return setup(port, enableRateLimit, (Consumer<Javalin>) null);
    }

    /**
     * Como a biblioteca subia com HTTPS gerenciado pelo próprio servidor.
     *
     * @param port            Porta HTTP em que o servidor escuta
     * @param enableRateLimit {@code true} para habilitar rate limiting
     * @param manageSsl       Só {@code false} é aceito
     * @param folderCerts     Ignorado
     * @return Instância configurada do Javalin, ou {@code null} em caso de falha
     * @throws UnsupportedOperationException se {@code manageSsl} for {@code true}
     * @deprecated A biblioteca roda só atrás do Coolify, que termina o TLS: não existe mais HTTPS
     *             gerenciado nem pasta de certificados. Use {@link #setup(int, boolean)}.
     */
    @Deprecated(forRemoval = true)
    public static Javalin setup(int port, boolean enableRateLimit, boolean manageSsl, File folderCerts) {
        refuseManagedSsl(manageSsl);
        return setup(port, enableRateLimit);
    }

    /**
     * Recusa o pedido de HTTPS gerenciado, que saiu da biblioteca.
     *
     * <p>Falha alto, na subida: servir HTTP em silêncio a quem pediu HTTPS deixaria a porta 443
     * falando texto puro, e o site quebrado sem uma linha no log.</p>
     */
    private static void refuseManagedSsl(boolean manageSsl) {
        if (manageSsl) {
            throw new UnsupportedOperationException("HTTPS gerenciado saiu da biblioteca: ela roda só atrás do "
                    + "Coolify, que termina o TLS. Suba em HTTP com JavalinAPI.setup(porta, rateLimit) ou "
                    + "new AngatuLib(host, porta, rateLimit).");
        }
    }

    /**
     * Igual a {@link #setup(int, boolean)}, com um passo antes de o servidor aceitar conexões.
     *
     * <p>Tudo o que precisa existir desde a primeira requisição — o filtro de segurança, as
     * rotas descobertas, as páginas — é registrado <strong>antes</strong> do {@code start()}. A
     * ordem inversa deixava uma janela em que a requisição chegava sem filtro, recebia 404 de
     * uma rota ainda não registrada, ou esbarrava no registro em andamento: as listas de rota
     * do Javalin não aceitam inclusão concorrente.</p>
     *
     * @param port            Porta HTTP em que o servidor escuta
     * @param enableRateLimit {@code true} para habilitar rate limiting
     * @param beforeStart     Registro adicional a fazer antes de subir (ex.: páginas HTML);
     *                        pode ser {@code null}
     * @return Instância configurada do Javalin, ou {@code null} em caso de falha
     */
    public static synchronized Javalin setup(int port, boolean enableRateLimit, Consumer<Javalin> beforeStart) {
        Dependencies.require("io.javalin.Javalin", JAVALIN_COORDINATES, "Web Server (Javalin)");
        if (initialized) return javalinInstance;

        rateLimitingEnabled = enableRateLimit;
        loadPersistedConfigs();
        // A galeria da própria biblioteca (GET /image?id=…) é imagem de vitrine, como um .png:
        // todas as fotos de uma página dividiam o contador de /image, e uma vitrine com mais de
        // trinta fotos levava o visitante a um bloqueio de uma hora.
        addUnlimitedPath("/image");

        Javalin javalin = null;
        try {
            javalin = Javalin.create(config -> {
                config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
                config.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/public";
                    sf.location = Location.CLASSPATH;
                    sf.headers = staticFileHeaders(sf.headers);
                });

                config.router.contextPath = "/";
                config.router.ignoreTrailingSlashes = true;
                config.router.treatMultipleSlashesAsSingleSlash = true;
                config.http.maxRequestSize = maxRequestSize;
            });

            javalinInstance = javalin; // Route exige a instância para ser construída
            registerSecurityHandler(javalin);
            registerAllRoutes();
            if (beforeStart != null) beforeStart.accept(javalin);

            javalin.start(port);
            Console.log("Javalin no ar em HTTP (porta %d) — o HTTPS é do Coolify", port);
        } catch (Exception e) {
            Console.error("Falha ao iniciar Javalin", e);
            javalinInstance = null;
            if (javalin != null) {
                try {
                    javalin.stop();
                } catch (RuntimeException ignored) {
                    // o servidor nem chegou a subir
                }
            }
            return null;
        }

        // Agendado só depois de subir: numa falha, uma segunda tentativa não duplica os timers.
        Task.runTimerWithFixedDelay(JavalinAPI::cleanupOldData, 0, 24 * 60 * 60 * 1000L);
        Task.runTimerWithFixedDelay(JavalinAPI::sweepRateLimitState, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS);

        initialized = true;
        return javalin;
    }

    /**
     * Configura um limite de taxa personalizado para um padrão de path.
     *
     * <p><strong>Padrões:</strong> caminho exato ({@code /login}); prefixo com {@code /*}
     * ({@code /api/*} vale para {@code /api} e tudo abaixo, mas não para {@code /apiary});
     * segmento variável com {@code {id}}, {@code <id>} ou {@code *} no meio
     * ({@code /api/pedidos/{id}}). Quando mais de um padrão casa, vence o exato e, depois dele,
     * o mais específico. O padrão fica gravado e é recarregado na subida — use
     * {@link #removeRateLimit(String)} para desfazer.</p>
     *
     * @param pathPattern Padrão de path (ex: {@code /api/*}, {@code /login})
     * @param config      Configuração de limite a aplicar
     */
    public static void configureRateLimit(String pathPattern, RateLimitConfig config) {
        String pattern = canonicalPath(pathPattern);
        putRateLimit(pattern, config);

        /* Atualiza a linha existente em vez de inserir outra. Como isto é
           chamado na subida, cada deploy gravava um registro novo (UUID novo)
           para o mesmo path — a tabela crescia sem parar e loadPersistedConfigs
           relia tudo aquilo toda vez. A comparação é pela forma canônica: uma
           linha antiga gravada com barra dupla ou barra no fim é o mesmo padrão. */
        List<RouteRateLimitConfig> existing = persistedRowsFor(pattern);

        if (existing.isEmpty()) {
            new RouteRateLimitConfig(
                    pattern,
                    config.requestsPerSecond,
                    config.requestsPerMinute,
                    config.blockSeconds,
                    config.perIp
            ).save();
        } else {
            RouteRateLimitConfig current = existing.get(0);
            current.setPathPattern(pattern);
            current.setRequestsPerSecond(config.requestsPerSecond);
            current.setRequestsPerMinute(config.requestsPerMinute);
            current.setBlockSeconds(config.blockSeconds);
            current.setPerIp(config.perIp);
            current.setEnabled(true);
            current.save();

            // Duplicatas deixadas pelas subidas anteriores
            for (int i = 1; i < existing.size(); i++) existing.get(i).delete();
        }

        Console.log("Rate limit configurado: %s → %d req/s, %d req/min", pattern,
                config.requestsPerSecond, config.requestsPerMinute);
    }

    /**
     * Aplica configuração padrão de API: 3 req/s, 20 req/min, bloqueio de 2 minutos.
     *
     * @param pathPattern Padrão de path
     */
    public static void configureApiRateLimit(String pathPattern) {
        configureRateLimit(pathPattern, new RateLimitConfig(3, 20, 120));
    }

    /**
     * Aplica configuração restritiva para login: 2 req/s, 5 req/min, bloqueio de 15 minutos.
     *
     * <p>Eram 1 por segundo: o duplo clique no botão de entrar mandava o segundo pedido no mesmo
     * segundo, e a pessoa levava 429 e 15 minutos de bloqueio sem ter errado a senha uma vez. O
     * que segura a força bruta é o limite por minuto, que continua em 5.</p>
     *
     * @param pathPattern Padrão de path
     */
    public static void configureLoginRateLimit(String pathPattern) {
        configureRateLimit(pathPattern, new RateLimitConfig(2, 5, 900));
    }

    /**
     * Remove um limite configurado por {@link #configureRateLimit}, da memória e do banco.
     *
     * <p>A configuração é gravada e volta a cada subida; sem este método, um limite apagado do
     * código continuava valendo para sempre. O path passa a seguir o limite global.</p>
     *
     * @param pathPattern Padrão exatamente como foi configurado
     * @return {@code true} se havia um limite para o padrão
     */
    public static boolean removeRateLimit(String pathPattern) {
        String pattern = canonicalPath(pathPattern);
        boolean removed;
        synchronized (RATE_LIMIT_CONFIGS) {
            removed = RATE_LIMIT_CONFIGS.remove(pattern) != null;
            rateLimitRules = compileRules(RATE_LIMIT_CONFIGS.keySet());
        }
        for (RouteRateLimitConfig row : persistedRowsFor(pattern)) {
            if (row.delete()) removed = true;
        }
        return removed;
    }

    /** Linhas gravadas cujo padrão, na forma canônica, é o informado. A tabela tem poucas linhas. */
    private static List<RouteRateLimitConfig> persistedRowsFor(String canonicalPattern) {
        List<RouteRateLimitConfig> rows = new ArrayList<>();
        for (RouteRateLimitConfig row : Saveable.findAll(RouteRateLimitConfig.class)) {
            if (row.getPathPattern() != null && canonicalPattern.equals(canonicalPath(row.getPathPattern()))) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Adiciona um path sem nenhum limite de requisições (ex: arquivos estáticos grandes).
     * Aceita os mesmos padrões de {@link #configureRateLimit}.
     *
     * @param pathPattern Padrão de path
     */
    public static void addUnlimitedPath(String pathPattern) {
        synchronized (UNLIMITED_PATHS) {
            UNLIMITED_PATHS.add(canonicalPath(pathPattern));
            unlimitedRules = compileRules(UNLIMITED_PATHS);
        }
    }

    /**
     * Adiciona um path completamente ignorado pela verificação de segurança.
     *
     * <p>Vale para o próprio path e para tudo abaixo dele, por segmento: {@code /health} ignora
     * {@code /health} e {@code /health/db}, mas não {@code /healthcare}.</p>
     *
     * @param path Prefixo de path a ignorar (ex: {@code /health})
     */
    public static void addIgnoredPath(String path) {
        IGNORED_PATHS.add(canonicalPath(path).toLowerCase(Locale.ROOT));
    }

    /**
     * Define o limite global de requisições (usado como fallback para paths sem configuração específica).
     *
     * @param reqSec   Requisições máximas por segundo
     * @param reqMin   Requisições máximas por minuto
     * @param blockSec Duração do bloqueio em segundos
     */
    public static void setGlobalRateLimit(int reqSec, int reqMin, long blockSec) {
        globalConfig = new RateLimitConfig(reqSec, reqMin, blockSec);
    }

    /**
     * Habilita ou desabilita o rate limiting globalmente.
     *
     * @param enabled {@code true} para habilitar
     */
    public static void setRateLimitingEnabled(boolean enabled) {
        rateLimitingEnabled = enabled;
    }

    /**
     * Remove o bloqueio longo de um IP pelo hash dele.
     *
     * <p>O hash é o SHA-256 do IPv4 inteiro ou, para IPv6, do prefixo {@code /64} (ex.:
     * {@code "2804:14c:1:2::/64"}) — ver {@link #rateLimitSubject(String)}. Desbloqueia de
     * verdade: apaga o bloqueio longo, os bloqueios temporários daquele IP e a janela de
     * violações.</p>
     *
     * @param ipHash Hash SHA-256 do IP
     * @return {@code true} se havia bloqueio longo
     */
    public static boolean unblockPermanently(String ipHash) {
        boolean removed = PERMANENT_BLOCKS.remove(ipHash) != null;
        for (PermanentBlock block : Saveable.findByField(PermanentBlock.class, "ipHash", ipHash))
            if (block.delete()) removed = true;

        if (!removed) return false;

        /* Desbloquear tem que desbloquear de verdade. Apagando só a linha de
           permanentblocks, a janela de violações continuava cheia e a próxima
           requisição fora do limite bloqueava de novo na hora — e a flag em
           SuspectIp seguia ligada, marcando como banido quem acabou de ser
           perdoado. Os bloqueios temporários (chave ipHash|path) também saem. */
        String prefix = ipHash + "|";
        BLOCKED_CACHE.keySet().removeIf(key -> key.startsWith(prefix));
        VIOLATION_CACHE.remove(ipHash);
        BURST_TRACKER.remove(ipHash);
        PENDING_VIOLATIONS.remove(ipHash);
        for (SuspectIp s : Saveable.findByField(SuspectIp.class, "ipHash", ipHash)) {
            s.setPermanentlyBlocked(false);
            s.save();
        }
        return true;
    }

    /**
     * Remove <b>todos</b> os bloqueios (longos e temporários) e zera as violações.
     *
     * <p>Operação de manutenção. Existe porque {@link #unblockPermanently(String)}
     * exige o hash do IP, e quem administra normalmente não tem o IP de quem foi bloqueado.
     * Sem isto, um bloqueio indevido não tinha como ser desfeito a não ser apagando o banco.</p>
     *
     * @return Quantidade de bloqueios longos removidos do banco
     */
    public static int unblockAll() {
        int removed = Saveable.deleteAll(PermanentBlock.class);
        Saveable.deleteAll(SuspectIp.class);
        PERMANENT_BLOCKS.clear();
        BLOCKED_CACHE.clear();
        BURST_TRACKER.clear();
        PENDING_VIOLATIONS.clear();
        /* Sem isto o perdão durava um pedido: a janela de violações continuava
           cheia e o primeiro tropeço recriava o bloqueio. */
        VIOLATION_CACHE.clear();
        return removed;
    }

    /**
     * Retorna todos os bloqueios longos ativos (não expirados), lidos do banco.
     *
     * @return Lista de {@link PermanentBlock} ativos
     */
    public static List<PermanentBlock> getActivePermanentBlocks() {
        return Saveable.query(PermanentBlock.class, "SELECT data FROM " + Saveable.tableName(PermanentBlock.class)
                + " WHERE json_extract(data, '$.expiresAt') > ?", nowSeconds());
    }

    /**
     * Devolve a memória dos mapas em memória do rate limiting e grava o histórico de violações
     * acumulado. Roda a cada minuto.
     *
     * <h4>O que estava acontecendo</h4>
     * <p>As estruturas de rate limiting ganhavam uma entrada por
     * {@code computeIfAbsent} a <strong>cada requisição</strong>, e só perdiam entrada quando a
     * chave era efetivamente <strong>bloqueada</strong>. Tráfego bem-comportado — que é a
     * imensa maioria — nunca saía. As chaves dos contadores são {@code ipHash + "|" + path},
     * então uma pessoa navegando por trinta telas deixava sessenta entradas permanentes, e um
     * varredor de URLs pedindo quinhentos endereços deixava mil.</p>
     *
     * <p>Num processo que fica meses no ar, isso é um vazamento: cresce com o tráfego total
     * acumulado, nunca recua, e não aparece como defeito em lugar nenhum — só como um consumo
     * de memória que sobe devagar até o contêiner ser morto pelo sistema.</p>
     *
     * <h4>Por que remover não enfraquece a proteção</h4>
     * <p>Toda estrutura aqui é uma <strong>janela</strong>: o contador de segundo guarda 1 s, o
     * de minuto 60 s, o de burst 1 s e o de violações {@link #VIOLATION_WINDOW_SEC}. Passada a
     * janela, o próprio código joga os instantes fora no toque seguinte daquela chave. Esta
     * varredura remove apenas entradas cuja janela <strong>já está inteiramente vencida</strong>,
     * com folga: recriar a entrada do zero produz exatamente o mesmo estado. Bloqueio, temporário
     * ou longo, só sai do mapa depois de expirar.</p>
     */
    public static void sweepRateLimitState() {
        try {
            long nowSec = nowSeconds();
            long nowMs = System.currentTimeMillis();

            SECOND_COUNTERS.values().removeIf(c -> c.lastSeenSeconds() < nowSec - IDLE_COUNTER_SEC);
            MINUTE_COUNTERS.values().removeIf(c -> c.lastSeenSeconds() < nowSec - IDLE_MINUTE_SEC);
            BURST_TRACKER.values().removeIf(q -> lastOf(q) < nowMs - IDLE_BURST_MS);
            VIOLATION_CACHE.values().removeIf(q -> lastOf(q) < nowSec - IDLE_VIOLATION_SEC);
            BLOCKED_CACHE.values().removeIf(b -> b.getUnblockTime() <= nowSec);
            PERMANENT_BLOCKS.values().removeIf(until -> until <= nowSec);

            warnIfHuge("contadores por segundo", SECOND_COUNTERS.size());
            warnIfHuge("contadores por minuto", MINUTE_COUNTERS.size());
            warnIfHuge("rastreio de burst", BURST_TRACKER.size());
            warnIfHuge("violações recentes", VIOLATION_CACHE.size());
            warnIfHuge("bloqueios ativos", BLOCKED_CACHE.size());

            flushViolations();
        } catch (Throwable t) {
            // Uma varredura que falha não pode derrubar o agendador: ela roda de novo em 1 min.
            Console.warn("[JavalinAPI] varredura do rate limit: %s", t.getMessage());
        }
    }

    /**
     * Último instante da fila, lido sob a mesma trava que quem escreve nela usa.
     *
     * <p>Fila vazia devolve {@link Long#MAX_VALUE}: é uma entrada recém-criada, e removê-la no
     * intervalo entre o {@code computeIfAbsent} e o primeiro registro seria uma corrida.</p>
     */
    private static long lastOf(Deque<Long> queue) {
        synchronized (queue) {
            Long last = queue.peekLast();
            return last == null ? Long.MAX_VALUE : last.longValue();
        }
    }

    private static void warnIfHuge(String name, int size) {
        if (size > MAX_KEYS_PER_MAP) {
            Console.warn("[JavalinAPI] rate limit: %s com %d chaves (teto declarado: %d). "
                    + "Isso não é tráfego normal — investigue a origem.",
                    name, Integer.valueOf(size), Integer.valueOf(MAX_KEYS_PER_MAP));
        }
    }

    /**
     * Remove do banco os bloqueios longos vencidos e o histórico de IPs sem violação há 30
     * dias. Executado automaticamente a cada 24 horas.
     *
     * <p>Duas exclusões por SQL, em vez de ler as tabelas inteiras e apagar linha a linha —
     * uma transação por linha, numa tabela que um ataque faz crescer. A marca
     * {@code isPermanentlyBlocked} do histórico não impede a exclusão: é estado derivado, e as
     * linhas com ela presa nunca eram apagadas. Uma falha aqui não interrompe o agendamento.</p>
     */
    public static void cleanupOldData() {
        try {
            long now = nowSeconds();
            long cutoff = now - (30L * 24 * 60 * 60);
            Saveable.query(PermanentBlock.class, "DELETE FROM " + Saveable.tableName(PermanentBlock.class)
                    + " WHERE json_extract(data, '$.expiresAt') <= ?", now);
            Saveable.query(SuspectIp.class, "DELETE FROM " + Saveable.tableName(SuspectIp.class)
                    + " WHERE json_extract(data, '$.lastViolationAt') < ?", cutoff);
        } catch (Throwable t) {
            Console.error("Limpeza diária dos bloqueios falhou; ela roda de novo amanhã", t);
        }
    }

    /**
     * Retorna a instância ativa do Javalin.
     *
     * @return Instância do {@link Javalin}, ou {@code null} se não inicializado
     */
    public static Javalin get() {
        Dependencies.require("io.javalin.Javalin", JAVALIN_COORDINATES, "Web Server (Javalin)");
        return javalinInstance;
    }

    // ==================== HANDLER DE SEGURANÇA ====================

    /**
     * Registra os filtros de segurança: o {@code before} de toda requisição HTTP e o do upgrade
     * de WebSocket.
     *
     * <p>O upgrade de WebSocket não passa pelo {@code before}: o Javalin o desvia antes. Sem o
     * segundo filtro, um IP bloqueado conectava do mesmo jeito e uma rajada de conexões não
     * tinha limite nenhum. A sessão continua sendo conferida dentro da rota WebSocket.</p>
     */
    private static void registerSecurityHandler(Javalin javalin) {
        javalin.unsafe.routes.before(JavalinAPI::screenHttpRequest);
        javalin.unsafe.routes.wsBeforeUpgrade(JavalinAPI::screenWebSocketUpgrade);
    }

    /** Filtro de toda requisição HTTP: headers, bloqueio e limite, e conteúdo malicioso. */
    private static void screenHttpRequest(Context ctx) {
        String path = canonicalPath(ctx.path());

        // Redireciona URLs com extensão .html para a versão sem extensão
        if (isHtmlAlias(ctx, path)) {
            ctx.redirect(withoutHtmlExtension(ctx, path));
            return;
        }

        // Aplica headers de segurança em todas as respostas
        SECURITY_HEADERS.forEach(ctx::header);

        // Ignora paths configurados (ex: health check)
        if (shouldIgnorePath(path)) return;

        // Preflight de CORS não é pedido do cliente: é o navegador perguntando se pode. O plugin
        // de CORS responde sem rodar rota nenhuma. Contado, ele gastava o limite da chamada que
        // vinha logo atrás: com o limite de login, o POST de uma tela em outro domínio levava 429
        // e 15 minutos de bloqueio na primeira tentativa.
        if (isCorsPreflight(ctx)) return;

        // Bloqueio e limite ANTES da varredura de conteúdo. A varredura lê e percorre até 64 KiB
        // de corpo; com ela na frente, um IP já bloqueado continuava gastando CPU a cada pedido
        // — e o pedido recusado por ela nunca entrava na conta do limite.
        //
        // Recurso estático e paths sem limite não passam pelo rate limit: um <img> a mais numa
        // vitrine não pode virar bloqueio de cliente. E essa isenção vem antes do bloqueio, e a
        // ordem é o conserto de um estrago real: com ela depois, um IP bloqueado levava 403 no
        // CSS, no JS e na página pública. Quem estava na lista não via "acesso bloqueado" — via
        // o site quebrado, e o robô do buscador que caísse ali tirava o site inteiro do índice.
        if (rateLimitingEnabled && !isStaticRequest(ctx, path) && !isUnlimitedPath(path)) {
            Denial denial = checkAccess(ctx, path, isPublicPage(ctx, path), false);
            if (denial != null) {
                sendDenial(ctx, path, denial);
                return;
            }
        }

        // Recusa inputs com cara de SQLi/XSS.
        //
        // Isto NÃO conta violação. O filtro é uma heurística de texto: um
        // Referer de anúncio, ou uma busca digitada na loja, casa com os
        // padrões sem que ninguém esteja atacando. Deixar essa heurística
        // alimentar o contador de bloqueio longo significa banir quem
        // escreveu a frase errada no campo de busca. Recusa-se a requisição —
        // que é o que protege — e pronto.
        if (hasMaliciousInput(ctx)) sendDeniedPage(ctx, path);
    }

    /**
     * Filtro do upgrade de WebSocket. A recusa é por exceção: é o que interrompe o upgrade no
     * Javalin e devolve o código HTTP ao cliente.
     */
    private static void screenWebSocketUpgrade(Context ctx) {
        String path = canonicalPath(ctx.path());
        if (shouldIgnorePath(path)) return;
        if (rateLimitingEnabled && !isUnlimitedPath(path)) {
            Denial denial = checkAccess(ctx, path, false, true);
            if (denial != null) {
                if (denial.permanent()) throw new ForbiddenResponse("Acesso bloqueado");
                throw new TooManyRequestsResponse("Muitos acessos seguidos");
            }
        }
        if (hasMaliciousInput(ctx)) throw new ForbiddenResponse("Pedido recusado");
    }

    /**
     * Preflight de CORS: {@code OPTIONS} com {@code Access-Control-Request-Method}. É o
     * navegador perguntando antes da chamada de verdade, e o plugin de CORS o responde sem rodar
     * rota — por isso não passa pelo limite (ver {@link #screenHttpRequest(Context)}).
     */
    private static boolean isCorsPreflight(Context ctx) {
        return ctx.method() == HandlerType.OPTIONS && ctx.header("Access-Control-Request-Method") != null;
    }

    /** Recusa decidida pelo limite: longa (403) ou temporária (429, com os segundos restantes). */
    private record Denial(boolean permanent, long seconds) {
    }

    /**
     * Aplica bloqueio e limite a uma requisição (HTTP ou upgrade de WebSocket).
     *
     * <p>O contador é do IP (IPv6: do {@code /64}) naquela <strong>rota</strong> — o molde que vai
     * atender o pedido, não o caminho (ver {@link #resolveLimit(Context, String, boolean)}). Num
     * limite configurado, o IPv6 passa também pelo freio do {@code /48} (ver
     * {@link #IPV6_NETWORK_FACTOR}).</p>
     *
     * @param publicContent {@code true} para página pública: o bloqueio longo não vale nela —
     *                      é o que a página de bloqueio promete, e o que impede uma rede inteira
     *                      atrás do mesmo IP de ver o site "fora do ar" por 24 horas
     * @param webSocket     {@code true} no upgrade de WebSocket, cujas rotas ficam num roteador à parte
     * @return {@code null} se passa; a recusa, se não
     */
    private static Denial checkAccess(Context ctx, String path, boolean publicContent, boolean webSocket) {
        String ip = IP.get(ctx);
        String ipHash = hashIp(rateLimitSubject(ip));

        if (!publicContent && isPermanentlyBlocked(ipHash)) return new Denial(true, 0);

        Limit limit = resolveLimit(ctx, path, webSocket);
        RateLimitConfig cfg = limit.config();
        String key = cfg.isPerIp() ? ipHash + "|" + limit.subject() : limit.subject();

        BlockInfo block = activeBlock(key);
        if (block != null) return new Denial(false, block.getUnblockTime() - nowSeconds());

        String networkKey = limit.configured() && cfg.isPerIp() ? ipv6NetworkKey(ip, limit.subject()) : null;
        if (networkKey != null) {
            BlockInfo networkBlock = activeBlock(networkKey);
            if (networkBlock != null) return new Denial(false, networkBlock.getUnblockTime() - nowSeconds());
        }

        // Detecta burst attack (muitas requisições em < 1 segundo)
        if (cfg.isPerIp() && isBurstAttack(ipHash)) return punish(ip, ipHash, key, HEAVY_BLOCK_SEC, true);

        // Verifica e registra a requisição nas janelas deslizantes
        if (!checkAndRecordRequest(key, cfg.getRequestsPerSecond(), cfg.getRequestsPerMinute())) {
            return punish(ip, ipHash, key, cfg.getBlockSeconds(), cfg.isPerIp());
        }

        // O /48 inteiro: sem violação nem bloqueio longo — ver IPV6_NETWORK_FACTOR
        if (networkKey != null && !checkAndRecordRequest(networkKey,
                networkLimit(cfg.getRequestsPerSecond()), networkLimit(cfg.getRequestsPerMinute()))) {
            blockKey(networkKey, cfg.getBlockSeconds());
            return new Denial(false, cfg.getBlockSeconds());
        }
        return null;
    }

    /**
     * Bloqueia a chave e decide se o caso já virou bloqueio longo.
     *
     * <p>O bloqueio longo é a punição mais cara, então exige duas coisas:
     * {@link #PERM_BLOCK_THRESHOLD} violações <b>dentro</b> de
     * {@link #VIOLATION_WINDOW_SEC} — repetição concentrada, não soma de uma
     * vida — e um IP em que dê para confiar. Se o valor resolvido é loopback ou rede privada,
     * o proxy não está repassando a origem e todo mundo chega com o mesmo IP: bloquear derruba
     * a aplicação inteira. Continua valendo o bloqueio temporário, que contém o abuso e
     * expira.</p>
     *
     * <p>Limite compartilhado ({@code perIp = false}) estoura pela soma de todos os clientes:
     * quem completou a conta não fez nada de errado, e a violação não é registrada contra
     * ele.</p>
     *
     * @return A recusa a enviar
     */
    private static Denial punish(String ip, String ipHash, String key, long seconds, boolean perIp) {
        if (perIp) {
            int violations = registerViolation(ipHash);
            if (violations >= PERM_BLOCK_THRESHOLD && !IP.isPrivateOrLocal(ip)) {
                createPermanentBlock(ipHash, violations);
                return new Denial(true, 0);
            }
        }
        blockKey(key, seconds);
        return new Denial(false, seconds);
    }

    // ==================== PERSISTÊNCIA ====================

    /**
     * Carrega configurações de rate limit e bloqueios longos do banco para a memória, ao
     * iniciar o servidor, e cria os índices das consultas por IP.
     */
    private static void loadPersistedConfigs() {
        try {
            Saveable.createIndex(PermanentBlock.class, "ipHash");
            Saveable.createIndex(SuspectIp.class, "ipHash");

            for (RouteRateLimitConfig c : Saveable.findAll(RouteRateLimitConfig.class))
                if (c.isEnabled())
                    putRateLimit(canonicalPath(c.getPathPattern()), new RateLimitConfig(
                            c.getRequestsPerSecond(), c.getRequestsPerMinute(),
                            c.getBlockSeconds(), c.isPerIp()));

            for (PermanentBlock b : Saveable.findAll(PermanentBlock.class))
                if (!b.isExpired())
                    PERMANENT_BLOCKS.merge(b.getIpHash(), b.getExpiresAt(), Math::max);

            /* O total de violações de SuspectIp NÃO volta para o cache. Ele é
               o acumulado de sempre; o cache conta só a janela de
               VIOLATION_WINDOW_SEC. Semeando um com o outro, quem tivesse
               histórico antigo já subia o servidor acima do limiar e era
               banido na primeira requisição depois do deploy.

               SuspectIp.isPermanentlyBlocked também não entra: é estado
               derivado, fica velho quando o bloqueio expira ou é desfeito, e
               ressuscitava bloqueio já removido a cada reinicialização. */

            /* Bloqueio longo é invisível para quem administra: quem foi
             * banido simplesmente não volta para reclamar, e quem olha o site
             * de outro lugar vê tudo funcionando. Dizer o número toda subida é
             * o que transforma "o site caiu para alguns" numa pista. */
            if (!PERMANENT_BLOCKS.isEmpty())
                Console.warn("Há %d IP(s) com bloqueio longo. Quem estiver na lista recebe 403 "
                        + "em rota limitada (API, login, escrita); página pública e arquivo "
                        + "estático continuam abrindo. Para zerar: JavalinAPI.unblockAll().",
                        PERMANENT_BLOCKS.size());
        } catch (Exception e) {
            Console.error("Erro ao carregar configurações persistidas", e);
        }
    }

    /**
     * Cria o bloqueio longo do IP: vale na hora, em memória, e é gravado no banco fora da
     * requisição.
     *
     * <p>Várias requisições do mesmo IP cruzam o limiar ao mesmo tempo; só a primeira grava —
     * antes, cada uma criava a sua linha em {@code permanentblocks}, na própria thread da
     * requisição, esperando a vez de gravar.</p>
     *
     * @param ipHash     Hash SHA-256 do IP
     * @param violations Número de violações que causaram o bloqueio
     */
    private static void createPermanentBlock(String ipHash, int violations) {
        long now = nowSeconds();
        long expiresAt = now + PermanentBlock.DEFAULT_DURATION_SEC;
        // Só cria se não houver bloqueio VÁLIDO: um vencido que a varredura ainda não tirou do
        // mapa é substituído, e não confundido com um bloqueio em vigor.
        boolean[] created = {false};
        PERMANENT_BLOCKS.compute(ipHash, (key, until) -> {
            if (until != null && until > now) return until;
            created[0] = true;
            return expiresAt;
        });
        if (!created[0]) return;

        Task.runAsync(() -> {
            try {
                Saveable.transaction(() -> {
                    new PermanentBlock(ipHash, String.format("Bloqueio após %d violações", violations),
                            violations, "System").save();
                    SuspectIp suspect = suspectFor(ipHash);
                    suspect.setPermanentlyBlocked(true);
                    suspect.save();
                });
            } catch (RuntimeException e) {
                Console.error("Falha ao gravar o bloqueio longo (ele segue valendo em memória)", e);
            }
        });
    }

    /**
     * O IP tem bloqueio longo ativo? Decidido em memória — ver {@link #PERMANENT_BLOCKS}.
     *
     * @param ipHash Hash SHA-256 do IP
     * @return {@code true} se o bloqueio ainda vale
     */
    private static boolean isPermanentlyBlocked(String ipHash) {
        Long until = PERMANENT_BLOCKS.get(ipHash);
        if (until == null) return false;
        if (nowSeconds() < until) return true;
        PERMANENT_BLOCKS.remove(ipHash, until);
        return false;
    }

    /** Histórico de violações do IP: a linha existente, ou uma nova. */
    private static SuspectIp suspectFor(String ipHash) {
        SuspectIp suspect = Saveable.findFirstByField(SuspectIp.class, "ipHash", ipHash);
        return suspect != null ? suspect : new SuspectIp(ipHash);
    }

    /** Violações acumuladas de um IP entre duas gravações do histórico. */
    private static final class PendingViolations {
        final LongAdder count = new LongAdder();
        volatile long lastAt;
    }

    /**
     * Grava no histórico ({@link SuspectIp}) as violações acumuladas desde a última varredura,
     * em transações de {@link #VIOLATION_FLUSH_BATCH} linhas.
     */
    private static void flushViolations() {
        if (PENDING_VIOLATIONS.isEmpty()) return;
        List<Map.Entry<String, PendingViolations>> drained = new ArrayList<>();
        for (String ipHash : PENDING_VIOLATIONS.keySet()) {
            PendingViolations pending = PENDING_VIOLATIONS.remove(ipHash);
            if (pending != null) drained.add(Map.entry(ipHash, pending));
        }
        for (int from = 0; from < drained.size(); from += VIOLATION_FLUSH_BATCH) {
            List<Map.Entry<String, PendingViolations>> batch =
                    drained.subList(from, Math.min(drained.size(), from + VIOLATION_FLUSH_BATCH));
            Saveable.transaction(() -> {
                for (Map.Entry<String, PendingViolations> entry : batch) {
                    SuspectIp suspect = suspectFor(entry.getKey());
                    suspect.registerViolations((int) entry.getValue().count.sum(), entry.getValue().lastAt);
                    suspect.save();
                }
            });
        }
    }

    // ==================== SEGURANÇA ====================

    /** Tabela hexadecimal para conversão de bytes sem alocação de formatadores. */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Gera o hash SHA-256 de um endereço IP, usado como chave de limite e nos registros de
     * bloqueio.
     *
     * <p>É pseudonimização, não segredo: o espaço IPv4 é pequeno o bastante para ser
     * percorrido, e quem tem o IP calcula o hash dele direto.</p>
     *
     * @param ip Endereço IP (ou prefixo IPv6, ver {@link #rateLimitSubject(String)})
     * @return Hash hexadecimal SHA-256
     */
    private static String hashIp(String ip) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(HEX_DIGITS[(b >>> 4) & 0xF]);
                hex.append(HEX_DIGITS[b & 0xF]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e); // obrigatório na plataforma Java
        }
    }

    /**
     * Quem o limite enxerga: o IPv4 inteiro, ou o prefixo {@code /64} do IPv6.
     *
     * <p>Qualquer conexão doméstica ou VPS recebe um {@code /64} inteiro — são 2<sup>64</sup>
     * endereços do mesmo cliente. Contando endereço por endereço, trocar de IP a cada pedido
     * zerava todos os contadores, não havia limite nenhum, e cada pedido ainda deixava três
     * entradas novas nos mapas.</p>
     *
     * @param ip Endereço literal devolvido por {@link IP#get(Context)}
     * @return O próprio IPv4, ou {@code "<prefixo>::/64"} para IPv6
     */
    static String rateLimitSubject(String ip) {
        if (ip == null || ip.indexOf(':') < 0) return ip;
        try {
            // Literal garantido por IP.get: com ':' no texto, o Java só interpreta o endereço —
            // nunca resolve nome.
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (address.length != 16) return InetAddress.getByAddress(address).getHostAddress(); // IPv4 embutido
            StringBuilder prefix = new StringBuilder(24);
            for (int i = 0; i < 8; i += 2) {
                if (i > 0) prefix.append(':');
                prefix.append(Integer.toHexString(((address[i] & 0xFF) << 8) | (address[i + 1] & 0xFF)));
            }
            return prefix.append("::/64").toString();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /**
     * O prefixo {@code /48} de um IPv6, para o freio de rede dos limites configurados (ver
     * {@link #IPV6_NETWORK_FACTOR}).
     *
     * @param ip Endereço literal devolvido por {@link IP#get(Context)}
     * @return {@code "<prefixo>::/48"}, ou {@code null} para IPv4 (inclusive o embutido em IPv6)
     */
    static String ipv6Network(String ip) {
        if (ip == null || ip.indexOf(':') < 0) return null;
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (address.length != 16) return null;
            StringBuilder prefix = new StringBuilder(20);
            for (int i = 0; i < 6; i += 2) {
                if (i > 0) prefix.append(':');
                prefix.append(Integer.toHexString(((address[i] & 0xFF) << 8) | (address[i + 1] & 0xFF)));
            }
            return prefix.append("::/48").toString();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Chave do freio de rede do {@code /48} para a rota; {@code null} fora de IPv6. */
    private static String ipv6NetworkKey(String ip, String subject) {
        String network = ipv6Network(ip);
        return network == null ? null : hashIp(network) + "|" + subject + "|48";
    }

    /** O limite do {@code /48}: o do cliente vezes {@link #IPV6_NETWORK_FACTOR}, sem estourar o {@code int}. */
    private static int networkLimit(int perClient) {
        return (int) Math.min(Integer.MAX_VALUE, (long) perClient * IPV6_NETWORK_FACTOR);
    }

    /**
     * Verifica se a requisição traz padrões de SQL Injection ou XSS em nome ou valor de
     * parâmetro, em cabeçalho ou no corpo.
     *
     * <p><strong>O corpo só é lido quando é texto e pequeno</strong> — JSON, formulário, XML ou
     * {@code text/*}, com {@code Content-Length} declarado de até 64 KiB. Antes, qualquer corpo
     * sem tipo binário era lido inteiro, até o limite do servidor (1 GB), antes de qualquer rate
     * limit: um pedido grande derrubava o contêiner por memória. Multipart e binário nunca são
     * lidos — {@code ctx.body()} consome o corpo, e depois dele {@code ctx.uploadedFiles()}
     * lança exceção.</p>
     *
     * <p>Um corpo varrido fica em cache no Javalin: {@code ctx.body()}, {@code bodyAsBytes()} e
     * {@code bodyAsClass()} continuam funcionando na rota. Quem lê em fluxo
     * ({@code bodyInputStream()}) recebe o fluxo já consumido — nesse caso, use
     * {@code bodyAsBytes()}.</p>
     *
     * <h4>O texto é varrido decodificado</h4>
     * <p>O navegador codifica {@code <}, {@code :}, {@code =} e {@code (} num formulário, e o JSON
     * pode trazer <code>&#92;u003c</code> no lugar de {@code <}. Varrido cru, o {@code <script>} de
     * um formulário comum passava, enquanto o mesmo valor na query era recusado. Agora o formulário
     * é varrido campo a campo, já decodificado, e o JSON com os escapes <code>&#92;uXXXX</code>
     * resolvidos.</p>
     *
     * @param ctx Contexto da requisição
     * @return {@code true} se input malicioso detectado, ou codificação percentual malformada
     */
    private static boolean hasMaliciousInput(Context ctx) {
        try {
            if (hasMaliciousParameter(ctx.queryParamMap())) return true;
        } catch (IllegalArgumentException malformed) {
            return true; // codificação percentual inválida: nenhum navegador manda isso
        }

        for (String header : ctx.headerMap().values())
            if (containsMaliciousPattern(header)) return true;

        if (!BODY_METHODS.contains(ctx.method()) || !isScannableBody(ctx)) return false;
        String body = ctx.body();
        if (containsMaliciousPattern(body)) return true;
        String type = ctx.header("Content-Type").toLowerCase(Locale.ROOT); // isScannableBody garante o cabeçalho
        if (type.startsWith("application/x-www-form-urlencoded")) {
            try {
                return hasMaliciousParameter(ctx.formParamMap());
            } catch (IllegalArgumentException malformed) {
                return true;
            }
        }
        return (type.startsWith("application/json") || type.contains("+json")) && body.contains("\\u")
                && containsMaliciousPattern(unescapeJsonUnicode(body));
    }

    /** Algum nome ou valor de parâmetro (query ou formulário, já decodificados) é malicioso? */
    private static boolean hasMaliciousParameter(Map<String, List<String>> parameters) {
        for (Map.Entry<String, List<String>> param : parameters.entrySet()) {
            if (containsMaliciousPattern(param.getKey())) return true;
            for (String value : param.getValue())
                if (containsMaliciousPattern(value)) return true;
        }
        return false;
    }

    /** O texto com os escapes <code>&#92;uXXXX</code> do JSON trocados pelo caractere. */
    private static String unescapeJsonUnicode(String json) {
        Matcher escape = JSON_UNICODE_ESCAPE.matcher(json);
        StringBuilder decoded = new StringBuilder(json.length());
        while (escape.find()) {
            char c = (char) Integer.parseInt(escape.group(1), 16);
            escape.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(c)));
        }
        escape.appendTail(decoded);
        return decoded.toString();
    }

    /** O corpo é texto, com tamanho declarado e pequeno o bastante para a varredura? */
    private static boolean isScannableBody(Context ctx) {
        int length = ctx.contentLength();
        if (length <= 0 || length > MAX_SCANNED_BODY_BYTES) return false;
        String type = ctx.header("Content-Type");
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.startsWith("application/json") || t.startsWith("application/x-www-form-urlencoded")
                || t.startsWith("text/") || t.startsWith("application/xml")
                || t.contains("+json") || t.contains("+xml");
    }

    /**
     * Verifica se uma string corresponde a algum padrão malicioso pré-compilado.
     *
     * @param input String a verificar
     * @return {@code true} se padrão malicioso detectado
     */
    private static boolean containsMaliciousPattern(String input) {
        if (input == null || input.isEmpty()) return false;
        for (Pattern p : MALICIOUS_PATTERNS)
            if (p.matcher(input).find()) return true;
        return false;
    }

    /**
     * Registra uma violação e devolve quantas houve na janela recente.
     *
     * <p>O retorno é o que decide o bloqueio longo, e por isso conta apenas o
     * que está dentro de {@link #VIOLATION_WINDOW_SEC}. O total de sempre
     * continua sendo gravado no {@link SuspectIp}, em lote (ver
     * {@link #PENDING_VIOLATIONS}), como histórico para quem administra — não como
     * sentença.</p>
     *
     * @param ipHash Hash SHA-256 do IP
     * @return Violações dentro da janela de {@link #VIOLATION_WINDOW_SEC}
     */
    private static int registerViolation(String ipHash) {
        long now = nowSeconds();
        Deque<Long> recent = VIOLATION_CACHE.computeIfAbsent(ipHash, k -> new ArrayDeque<>());

        int inWindow;
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst() <= now - VIOLATION_WINDOW_SEC)
                recent.pollFirst();
            recent.addLast(now);
            inWindow = recent.size();
        }

        PendingViolations pending = PENDING_VIOLATIONS.computeIfAbsent(ipHash, k -> new PendingViolations());
        pending.count.increment();
        pending.lastAt = now;
        return inWindow;
    }

    /**
     * Detecta ataque de burst: mais de {@link #BURST_THRESHOLD} requisições em 1 segundo.
     *
     * @param ipHash Hash SHA-256 do IP
     * @return {@code true} se burst attack detectado
     */
    private static boolean isBurstAttack(String ipHash) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = BURST_TRACKER.computeIfAbsent(ipHash, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            // Remove timestamps fora da janela de 1 segundo
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= now - 1_000) timestamps.pollFirst();
            timestamps.addLast(now);
            return timestamps.size() > BURST_THRESHOLD;
        }
    }

    // ==================== RATE LIMITING ====================

    /**
     * O limite que vale para a requisição e o que o contador dele conta.
     *
     * @param config     Configuração aplicável
     * @param subject    O que o contador conta, além do IP: o molde da rota, ou {@link #NO_ROUTE}
     * @param configured {@code true} quando a configuração veio de {@link #configureRateLimit},
     *                   não do limite global
     */
    private record Limit(RateLimitConfig config, String subject, boolean configured) {
    }

    /**
     * Resolve o limite da requisição.
     *
     * <p>A configuração vem do caminho, em ordem de precedência: correspondência exata, o padrão
     * mais específico que casa (ver {@link #configureRateLimit}), e a configuração global.</p>
     *
     * <h4>O contador é da rota, não do caminho</h4>
     * <p>O contador era chaveado pelo caminho exato. Cada caminho diferente abria uma entrada nova
     * nos mapas, que ficava cinco minutos — e o caminho é o cliente quem escolhe: um IP pedindo
     * {@code /x/1}, {@code /x/2}… com 7 KB de texto cada, a 30 por segundo, segurava 70 MB sem ser
     * recusado uma vez sequer, e quinze IPs derrubavam o contêiner por falta de memória. O mesmo
     * defeito esvaziava o limite configurado com curinga: em {@code /api/cupom/*}, cada código
     * tentado ganhava o próprio contador, e o limite de 5 por minuto não segurava tentativa
     * nenhuma.</p>
     * <p>Agora o contador é do <strong>molde</strong> da rota que vai atender o pedido
     * ({@code /api/cupom/{codigo}}): o número de moldes é o número de rotas da aplicação, e todos
     * os códigos dividem o limite, como ele quer dizer. Caminho que não é rota — 404, varredura,
     * arquivo com extensão fora da lista de estáticos — cai num balde só por IP
     * ({@link #NO_ROUTE}). Um limite configurado para {@code /api/*} continua valendo por rota,
     * e não para a pasta inteira.</p>
     *
     * @param ctx       Contexto da requisição
     * @param path      Path canônico
     * @param webSocket {@code true} no upgrade de WebSocket
     * @return O limite e o que ele conta
     */
    private static Limit resolveLimit(Context ctx, String path, boolean webSocket) {
        String subject = webSocket ? webSocketTemplate(path) : routeTemplate(ctx, path);
        RateLimitConfig exact = RATE_LIMIT_CONFIGS.get(path);
        if (exact != null) return new Limit(exact, subject, true);
        for (PathRule rule : rateLimitRules) {
            if (!rule.matches(path)) continue;
            RateLimitConfig config = RATE_LIMIT_CONFIGS.get(rule.pattern());
            if (config != null) return new Limit(config, subject, true);
        }
        return new Limit(globalConfig, subject, false);
    }

    /**
     * Molde da rota HTTP que vai atender o pedido ({@code /api/pedidos/{id}}), ou
     * {@link #NO_ROUTE}. {@code HEAD} sem rota própria é atendido pela de {@code GET}.
     */
    private static String routeTemplate(Context ctx, String path) {
        Javalin app = javalinInstance;
        if (app == null) return NO_ROUTE;
        HandlerType method = ctx.method();
        ParsedEndpoint route = app.unsafe.internalRouter.findFirstHttpHandlerEntry(method, path);
        if (route == null && method == HandlerType.HEAD) {
            route = app.unsafe.internalRouter.findFirstHttpHandlerEntry(HandlerType.GET, path);
        }
        return route == null ? NO_ROUTE : route.endpoint.path;
    }

    /** Molde da rota WebSocket que vai atender o upgrade, ou {@link #NO_ROUTE}. */
    private static String webSocketTemplate(String path) {
        Javalin app = javalinInstance;
        if (app == null) return NO_ROUTE;
        for (WsHandlerEntry entry : app.unsafe.internalRouter.allWsHandlers()) {
            if (entry.getType() == WsHandlerType.WEBSOCKET && entry.matches(path)) return entry.getPath();
        }
        return NO_ROUTE;
    }

    /** Guarda a configuração e recompila os padrões com curinga. */
    private static void putRateLimit(String pattern, RateLimitConfig config) {
        synchronized (RATE_LIMIT_CONFIGS) {
            RATE_LIMIT_CONFIGS.put(pattern, config);
            rateLimitRules = compileRules(RATE_LIMIT_CONFIGS.keySet());
        }
    }

    /** Os padrões com curinga do conjunto, compilados, do mais específico ao menos. */
    private static List<PathRule> compileRules(Set<String> patterns) {
        List<PathRule> rules = new ArrayList<>();
        for (String pattern : patterns) {
            PathRule rule = PathRule.compile(pattern);
            if (rule.wildcard()) rules.add(rule);
        }
        rules.sort(Comparator.comparingInt(PathRule::specificity).reversed());
        return List.copyOf(rules);
    }

    /**
     * Padrão de path compilado <strong>uma vez</strong>, quando é configurado.
     *
     * <p>Antes, o padrão virava regex a cada requisição, e o texto entrava cru: um
     * {@code addUnlimitedPath("*")} lançava {@code PatternSyntaxException} em toda requisição
     * (o site inteiro em 500), o {@code .} casava com qualquer caractere, e {@code /api/*}
     * valia também para {@code /apiary}. Agora cada trecho literal é citado, o curinga final
     * respeita a divisão de segmento, e vence o padrão mais específico — não o primeiro que o
     * mapa devolvesse.</p>
     *
     * @param pattern     Padrão canônico, como configurado
     * @param regex       Expressão compilada
     * @param specificity Caracteres literais do padrão: quanto mais, mais específico
     * @param wildcard    {@code false} para caminho exato (resolvido por busca direta)
     */
    private record PathRule(String pattern, Pattern regex, int specificity, boolean wildcard) {

        static PathRule compile(String pattern) {
            String[] segments = pattern.split("/", -1);
            StringBuilder regex = new StringBuilder();
            int literal = 0;
            boolean wildcard = false;
            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i];
                boolean last = i == segments.length - 1;
                if (last && (segment.equals("*") || segment.equals("**"))) {
                    regex.append("(?:/.*)?"); // o prefixo e tudo abaixo dele
                    wildcard = true;
                } else if (segment.equals("*") || isParameter(segment, '{', '}')) {
                    regex.append("/[^/]+");
                    wildcard = true;
                } else if (isParameter(segment, '<', '>')) {
                    regex.append("/.+"); // como no Javalin: <param> aceita barras
                    wildcard = true;
                } else {
                    regex.append('/').append(Pattern.quote(segment));
                    literal += segment.length();
                }
            }
            if (regex.isEmpty()) regex.append('/');
            return new PathRule(pattern, Pattern.compile(regex.toString()), literal, wildcard);
        }

        private static boolean isParameter(String segment, char open, char close) {
            return segment.length() > 2 && segment.charAt(0) == open && segment.charAt(segment.length() - 1) == close;
        }

        boolean matches(String path) {
            return regex.matcher(path).matches();
        }
    }

    /**
     * Forma canônica do path: barras repetidas viram uma, e a barra final sai.
     *
     * <p>É a mesma normalização que o roteador do Javalin aplica ({@code ignoreTrailingSlashes}
     * e {@code treatMultipleSlashesAsSingleSlash}). Sem ela, {@code /api//login},
     * {@code /api/login/} e {@code //api/login} chegavam à rota de login, mas cada um com os
     * próprios contadores e sem casar com a configuração de {@code /api/login}: girando entre
     * sessenta variações, o limite de 5 tentativas por minuto virava 1.700.</p>
     *
     * @param raw Path como veio na requisição
     * @return Path canônico
     */
    static String canonicalPath(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        String path = raw.contains("//") ? MULTIPLE_SLASHES.matcher(raw).replaceAll("/") : raw;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path.charAt(0) == '/' ? path : "/" + path;
    }

    /**
     * Verifica se o path está na lista de paths sem limite.
     */
    private static boolean isUnlimitedPath(String path) {
        if (UNLIMITED_PATHS.contains(path)) return true;
        for (PathRule rule : unlimitedRules)
            if (rule.matches(path)) return true;
        return false;
    }

    /**
     * A requisição é de arquivo estático?
     *
     * <p>Decidido pela extensão, e não por prefixo de pasta: projetos servem
     * estático de lugares diferentes ({@code /assets}, {@code /public},
     * {@code /styles}, a raiz), e uma lista de pastas erra em todos eles.</p>
     *
     * <p>E só para GET/HEAD de um caminho que <strong>não é rota</strong>. Decidido só pela
     * extensão, a isenção valia para qualquer método e para rota de verdade: um
     * {@code /sitemap.xml} dinâmico, ou uma rota com parâmetro chamada como
     * {@code /api/busca/x.css}, passava por fora de todo limite e bloqueio.</p>
     *
     * @param ctx  Contexto da requisição
     * @param path Path canônico
     * @return {@code true} se for arquivo estático
     */
    private static boolean isStaticRequest(Context ctx, String path) {
        HandlerType method = ctx.method();
        if (method != HandlerType.GET && method != HandlerType.HEAD) return false;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) return false; // ponto no meio do caminho, não é extensão
        if (!STATIC_EXTENSIONS.contains(path.substring(dot).toLowerCase(Locale.ROOT))) return false;
        Javalin app = javalinInstance;
        return app == null || !app.unsafe.internalRouter.hasHttpHandlerEntry(HandlerType.GET, path);
    }

    /** GET/HEAD de uma página registrada pelo {@link HtmlRouteAPI}? */
    private static boolean isPublicPage(Context ctx, String path) {
        HandlerType method = ctx.method();
        return (method == HandlerType.GET || method == HandlerType.HEAD) && HtmlRouteAPI.isPage(path);
    }

    /**
     * Verifica se o path deve ser completamente ignorado pela verificação de segurança: o
     * prefixo configurado, ou algo abaixo dele — nunca um nome que só começa igual.
     */
    private static boolean shouldIgnorePath(String path) {
        if (IGNORED_PATHS.isEmpty()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String prefix : IGNORED_PATHS)
            if (lower.equals(prefix) || lower.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) return true;
        return false;
    }

    /**
     * O bloqueio temporário ativo da chave, lido uma vez só. Remove do cache o que já venceu.
     *
     * <p>Uma leitura só: conferir "está bloqueado?" e depois buscar o bloqueio de novo para
     * calcular o tempo restante devolvia {@code null} na segunda leitura quando o bloqueio
     * vencia no meio — e a requisição terminava em 500.</p>
     *
     * @param key Chave de rate limiting
     * @return O bloqueio ainda válido, ou {@code null}
     */
    private static BlockInfo activeBlock(String key) {
        BlockInfo info = BLOCKED_CACHE.get(key);
        if (info == null) return null;
        if (nowSeconds() >= info.getUnblockTime()) {
            BLOCKED_CACHE.remove(key, info);
            return null;
        }
        return info;
    }

    /**
     * Verifica e registra a requisição nas janelas deslizantes de segundo e minuto.
     *
     * @param key           Chave de rate limiting
     * @param perSecondMax  Limite por segundo
     * @param perMinuteMax  Limite por minuto
     * @return {@code true} se a requisição está dentro dos limites; {@code false} se excedeu
     */
    private static boolean checkAndRecordRequest(String key, int perSecondMax, int perMinuteMax) {
        long now = nowSeconds();
        SlidingWindowCounter perSecond = SECOND_COUNTERS.computeIfAbsent(key, k -> new SlidingWindowCounter(1));
        if (!perSecond.checkAndIncrement(perSecondMax, now)) return false;
        SlidingWindowCounter perMinute = MINUTE_COUNTERS.computeIfAbsent(key, k -> new SlidingWindowCounter(60));
        return perMinute.checkAndIncrement(perMinuteMax, now);
    }

    /**
     * Bloqueia uma chave por um número de segundos e limpa seus contadores.
     *
     * <p>A remoção do bloqueio vencido fica com a leitura ({@link #activeBlock(String)}) e com a
     * varredura de cada minuto. Antes, cada bloqueio agendava uma tarefa própria para daqui a
     * até uma hora: sob ataque, eram milhares de tarefas pendentes segurando memória.</p>
     *
     * @param key     Chave de rate limiting a bloquear
     * @param seconds Duração do bloqueio em segundos
     */
    private static void blockKey(String key, long seconds) {
        BLOCKED_CACHE.put(key, new BlockInfo(nowSeconds() + seconds, key));
        SECOND_COUNTERS.remove(key);
        MINUTE_COUNTERS.remove(key);
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    // ==================== REDIRECIONAMENTO .html ====================

    /** GET/HEAD de um caminho terminado em {@code .html}, em qualquer caixa? */
    private static boolean isHtmlAlias(Context ctx, String path) {
        HandlerType method = ctx.method();
        return (method == HandlerType.GET || method == HandlerType.HEAD)
                && path.length() > 5 && path.regionMatches(true, path.length() - 5, ".html", 0, 5);
    }

    /**
     * Destino do redirecionamento de {@code /pagina.html}: a rota da página.
     *
     * <p>O {@code replace(".html", "")} de antes tinha quatro defeitos: {@code //site.com/x.html}
     * virava {@code Location: //site.com/x} e mandava o visitante para outro domínio;
     * {@code /Pagina.HTML} redirecionava para si mesma para sempre; a query string se perdia; e
     * {@code /index.html} ia para {@code /index}, que não existe. Agora o destino começa sempre
     * por uma barra só, tira os cinco últimos caracteres (qualquer caixa), usa a rota da página
     * registrada quando houver ({@code /blog/post.html} → {@code /post}) e mantém a query.</p>
     */
    private static String withoutHtmlExtension(Context ctx, String path) {
        String target = path.substring(0, path.length() - 5);
        String name = target.substring(target.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("index")) target = "/";
        else if (HtmlRouteAPI.isPage("/" + name)) target = "/" + name;
        target = "/" + target.replaceFirst("^/+", "");

        String query = ctx.queryString();
        return query == null || query.isEmpty() ? target : target + "?" + query;
    }

    // ==================== PÁGINAS DE RESPOSTA ====================

    /**
     * Estilo das páginas de recusa. Embutido de propósito.
     *
     * <p>Estas páginas aparecem exatamente quando a requisição do cliente foi
     * recusada. Buscar CSS num CDN aqui é pedir para a página de erro depender
     * de uma terceira rede — e, sob uma CSP restritiva, o pedido é bloqueado e
     * o visitante recebe texto cru. Não há nada aqui que justifique um
     * framework: são quatro linhas de texto.</p>
     */
    private static final String DENY_STYLE =
            "*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;flex-direction:column;"
            + "gap:20px;align-items:center;justify-content:center;padding:24px;background:#F4F4F5;"
            + "color:#18181B;font:16px/1.55 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}"
            + "main{max-width:26rem;background:#fff;border-radius:14px;padding:32px;"
            + "box-shadow:0 1px 3px rgba(0,0,0,.1),0 8px 24px rgba(0,0,0,.06)}"
            + "h1{margin:0 0 12px;font-size:1.3rem;line-height:1.3}"
            + "p{margin:0;color:#52525B}strong{color:#18181B}footer{font-size:.8rem;color:#71717A}"
            + "@media(prefers-color-scheme:dark){body{background:#18181B;color:#FAFAFA}"
            + "main{background:#27272A;box-shadow:none}p{color:#A1A1AA}strong{color:#FAFAFA}"
            + "footer{color:#A1A1AA}}";

    /** Envia a recusa decidida pelo limite. */
    private static void sendDenial(Context ctx, String path, Denial denial) {
        if (denial.permanent()) sendPermanentBlockPage(ctx, path);
        else sendBlockPage(ctx, path, denial.seconds());
    }

    /**
     * Monta a recusa e <b>encerra a requisição</b>: página para o navegador, JSON para quem
     * chama a API.
     *
     * <p>O {@code skipRemainingHandlers()} é o ponto crítico, e não um detalhe.
     * Um {@code before} do Javalin não interrompe nada ao retornar: o servlet
     * continua para a task HTTP, que casa a rota ou entrega o arquivo estático
     * por cima do que foi escrito aqui. Sem este corte, um bloqueio vira
     * decoração — o conteúdo é servido do mesmo jeito e só o código de status
     * fica errado, o que quebra o site (o navegador recusa CSS e JS com 4xx)
     * sem proteger coisa alguma.</p>
     *
     * <p>A chamada de API ({@code fetch} de uma tela) recebe
     * {@code {"error": …, "message": …}}: com HTML, o {@code res.json()} da tela quebrava e a
     * pessoa via um erro genérico no lugar da explicação.</p>
     *
     * @param ctx        Contexto da requisição
     * @param path       Path canônico
     * @param status     Código HTTP da recusa
     * @param code       Código curto da recusa, para quem trata o JSON
     * @param title      Título curto, em linguagem comum
     * @param message    Explicação em uma frase, sem HTML
     * @param detail     A mesma explicação em HTML (já escapado)
     * @param retryAfter Segundos até a liberação, ou 0 sem prazo exato
     */
    private static void sendDenyPage(Context ctx, String path, int status, String code, String title,
            String message, String detail, long retryAfter) {
        if (wantsJson(ctx, path)) {
            ctx.status(status).contentType("application/json; charset=utf-8")
                    .result("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
        } else {
            ctx.html(denyPageHtml(new DenyNotice(status, code, title, message, retryAfter), detail)).status(status);
        }
        ctx.skipRemainingHandlers();
    }

    /**
     * HTML da recusa: a página do projeto ({@link #setDenyPage}), quando existe e funciona; senão,
     * a padrão, com o crédito da Angatu Sistemas.
     */
    private static String denyPageHtml(DenyNotice notice, String detail) {
        Function<DenyNotice, String> custom = denyPage;
        if (custom != null) {
            try {
                String html = custom.apply(notice);
                if (html != null && !html.isBlank()) return html;
                if (DENY_PAGE_FAILURE_LOGGED.compareAndSet(false, true)) {
                    Console.warn("A página de recusa do projeto devolveu texto vazio; usando a padrão.");
                }
            } catch (Exception | StackOverflowError | LinkageError e) {
                // Não só RuntimeException: um template que lança exceção verificada (por
                // @SneakyThrows ou Kotlin) ou estoura a pilha virava 500 — com Retry-After de 15
                // minutos, e sem página nenhuma.
                if (DENY_PAGE_FAILURE_LOGGED.compareAndSet(false, true)) {
                    Console.error("A página de recusa do projeto falhou; usando a padrão.", e);
                }
            }
        }
        return "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\">"
                + "<title>" + notice.title() + "</title><style>" + DENY_STYLE + "</style></head>"
                + "<body><main><h1>" + notice.title() + "</h1><p>" + detail + "</p></main>"
                + "<footer>Desenvolvido por Angatu Sistemas</footer></body></html>";
    }

    /** Quem pediu espera JSON? Navegação de página nunca; chamada de API sim. */
    private static boolean wantsJson(Context ctx, String path) {
        String accept = ctx.header("Accept");
        if (accept != null && accept.contains("text/html")) return false;
        if (accept != null && accept.contains("application/json")) return true;
        String type = ctx.header("Content-Type");
        return (type != null && type.toLowerCase(Locale.ROOT).contains("json")) || path.startsWith("/api/");
    }

    /**
     * Envia a recusa de bloqueio temporário, com o tempo restante e o cabeçalho
     * {@code Retry-After}.
     *
     * @param ctx     Contexto da requisição
     * @param path    Path canônico
     * @param seconds Segundos restantes até o desbloqueio
     */
    private static void sendBlockPage(Context ctx, String path, long seconds) {
        long remaining = Math.max(1, seconds);
        long minutes = Math.max(1, Math.round(remaining / 60.0));
        String wait = remaining < 90
                ? remaining + (remaining == 1 ? " segundo" : " segundos")
                : minutes + (minutes == 1 ? " minuto" : " minutos");
        ctx.header("Retry-After", Long.toString(remaining));
        sendDenyPage(ctx, path, StatusCode.TOO_MANY_REQUESTS.code(), "too_many_requests", "Muitos acessos seguidos",
                "Chegaram pedidos demais deste aparelho em pouco tempo. Espere " + wait + " e tente de novo.",
                "Chegaram pedidos demais deste aparelho em pouco tempo. "
                + "Espere <strong>" + wait + "</strong> e tente de novo.", remaining);
    }

    /**
     * Envia a recusa de bloqueio longo.
     *
     * @param ctx  Contexto da requisição
     * @param path Path canônico
     */
    private static void sendPermanentBlockPage(Context ctx, String path) {
        /* Dizer que passa em 24 horas não é detalhe de texto: sem prazo, a
           página soa definitiva e quem foi bloqueado por engano simplesmente
           desiste do site — nunca aparece para reclamar, e o erro nunca é
           descoberto. */
        sendDenyPage(ctx, path, StatusCode.FORBIDDEN.code(), "blocked", "Acesso bloqueado",
                "Este acesso foi bloqueado por atividade fora do padrão e será liberado automaticamente "
                + "em até 24 horas. Se você acha que houve engano, fale com o suporte.",
                "Este acesso foi bloqueado por atividade fora do padrão e será liberado automaticamente "
                + "em até <strong>24 horas</strong>. Se você acha que houve engano, fale com o suporte.", 0);
    }

    /**
     * Envia a recusa de conteúdo suspeito (SQLi/XSS detectado na requisição).
     *
     * @param ctx  Contexto da requisição
     * @param path Path canônico
     */
    private static void sendDeniedPage(Context ctx, String path) {
        String message = "O conteúdo enviado tem trechos que o sistema não aceita. "
                + "Refaça o pedido sem símbolos ou comandos.";
        sendDenyPage(ctx, path, StatusCode.FORBIDDEN.code(), "rejected", "Pedido recusado", message, message, 0);
    }

    // ==================== REGISTRO DE ROTAS ====================

    /**
     * Descobre e registra automaticamente todas as implementações de {@link Route}
     * no classpath usando reflection, ignorando classes abstratas e interfaces.
     */
    private static void registerAllRoutes() {
        Dependencies.require("org.reflections.Reflections", REFLECTIONS_COORDINATES, "Descoberta automática de rotas");
        // Delegado a uma classe helper: mantém o bytecode desta classe livre de
        // referências à Reflections, permitindo o link sem a dependência e o
        // guard acima imprimir a mensagem antes de qualquer falha
        RouteDiscovery.scanAndRegister();
    }

    // ==================== HELPERS (CARREGAMENTO LAZY) ====================

    /**
     * Descobre e registra todas as implementações de {@link Route} no classpath.
     * Classe separada para manter as referências à Reflections fora do bytecode
     * da {@link JavalinAPI} (link sem a dependência + guard com mensagem clara).
     */
    private static final class RouteDiscovery {

        private RouteDiscovery() {
        }

        /**
         * Onde procurar rotas: o {@code java.class.path}, os JARs listados no manifesto dele e
         * os carregadores de classe da aplicação.
         *
         * <p>Só o {@code java.class.path} não bastava. No {@code mvn exec:java} ele é o
         * classpath do próprio Maven — as classes do projeto estão num carregador à parte — e no
         * JAR que só aponta para as dependências pelo manifesto ele é esse JAR sozinho. Nos dois
         * casos a busca não achava rota nenhuma, e o servidor subia respondendo 404 a tudo.</p>
         */
        static java.util.Set<java.net.URL> classpathUrls() {
            java.util.Collection<java.net.URL> javaClassPath = org.reflections.util.ClasspathHelper.forJavaClassPath();
            java.util.Set<java.net.URL> urls = new java.util.LinkedHashSet<>(javaClassPath);
            urls.addAll(org.reflections.util.ClasspathHelper.forManifest(javaClassPath));
            urls.addAll(org.reflections.util.ClasspathHelper.forClassLoader());
            return urls;
        }

        static void scanAndRegister() {
            Reflections reflections = new Reflections(
                    new org.reflections.util.ConfigurationBuilder()
                            .setUrls(classpathUrls())
                            .setScanners(Scanners.SubTypes)
            );
            for (Class<? extends Route> routeClass : reflections.getSubTypesOf(Route.class)) {
                try {
                    if (!java.lang.reflect.Modifier.isAbstract(routeClass.getModifiers()) && !routeClass.isInterface())
                        routeClass.getDeclaredConstructor().newInstance().register();
                } catch (Exception e) {
                    Console.error("Erro ao registrar rota: %s", routeClass.getName(), e);
                }
            }
        }
    }
}
