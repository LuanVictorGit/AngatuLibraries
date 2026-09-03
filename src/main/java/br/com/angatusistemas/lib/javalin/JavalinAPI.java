package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.javalin.classes.BlockInfo;
import br.com.angatusistemas.lib.javalin.classes.PermanentBlock;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.RouteRateLimitConfig;
import br.com.angatusistemas.lib.javalin.classes.SlidingWindowCounter;
import br.com.angatusistemas.lib.javalin.classes.SuspectIp;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.task.Task;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.staticfiles.Location;

/**
 * API principal para configuração do servidor Javalin com rate limiting
 * avançado, proteção contra ataques e persistência de bloqueios.
 *
 * <p><strong>Propósito:</strong> encapsular toda a configuração do servidor web
 * (SSL, estáticos, segurança, rate limiting) em chamadas estáticas simples.</p>
 *
 * <p><strong>Funcionalidades:</strong></p>
 * <ul>
 *   <li>Proteção contra DDoS, SQL Injection e XSS</li>
 *   <li>Rate limiting por IP e por rota com janela deslizante</li>
 *   <li>Bloqueios temporários e permanentes persistidos em banco de dados</li>
 *   <li>Headers de segurança HTTP automáticos</li>
 *   <li>HTTP por padrão (TLS da hospedagem) e HTTPS opcional com redirecionamento</li>
 *   <li>Servir arquivos estáticos corretamente tanto em HTTP quanto HTTPS</li>
 * </ul>
 *
 * <p><strong>Quando usar:</strong> em toda aplicação web da biblioteca — a
 * inicialização é feita automaticamente pelo {@link AngatuLib} (não é preciso
 * chamar {@link #setup} manualmente, exceto para cenários avançados). Use os
 * métodos de configuração (rate limit, paths) logo após a inicialização.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> em aplicações sem servidor web; não
 * chame {@link #setup} mais de uma vez por processo (retorna a instância já
 * criada). Os métodos de configuração devem ser chamados ANTES do servidor
 * receber tráfego para evitar janelas sem proteção.</p>
 *
 * <p><strong>Integração:</strong> {@link AngatuLib} chama {@link #setup} no
 * bootstrap; {@link Route} usa {@link #get()} para registro de rotas;
 * {@link Saveable} persiste bloqueios e configurações (permanentes e suspeitos).</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>Construa {@code new AngatuLib(...)} (ou chame {@link #setup} diretamente);</li>
 *   <li>Configure rate limits ({@link #configureRateLimit},
 *       {@link #configureApiRateLimit}, {@link #configureLoginRateLimit}) e
 *       paths especiais ({@link #addUnlimitedPath}, {@link #addIgnoredPath});</li>
 *   <li>Use {@link #get()} para acessar o Javalin em cenários avançados.</li>
 * </ol>
 *
 * <p><strong>Boas práticas:</strong> proteja rotas sensíveis (login/API) com
 * presets de rate limit; use {@code addIgnoredPath} apenas para endpoints
 * realmente públicos (health check); monitore {@link #getActivePermanentBlocks()}.</p>
 *
 * <p><strong>Limitações:</strong> exige as dependências
 * {@code io.javalin:javalin:7.2.2} (web), {@code io.javalin.community.ssl:javalin-ssl:7.2.2}
 * (apenas no modo HTTPS gerenciado), {@code org.reflections:reflections:0.10.2} (rotas automáticas) e os
 * requisitos do {@link Saveable} (persistência de bloqueios). Dependências
 * ausentes são detectadas com mensagens de instalação claras.</p>
 *
 * <p><strong>Extensões futuras:</strong> novos padrões de segurança podem ser
 * adicionados como métodos estáticos sem quebrar a API; a detecção de padrões
 * maliciosos é extensível via lista de {@code Pattern} pré-compilados.</p>
 *
 * @author Angatu Sistemas
 * @version 3.0
 * @see AngatuLib
 * @see Route
 * @see br.com.angatusistemas.lib.database.Saveable
 */
public final class JavalinAPI {

    // ==================== CONSTANTES ====================

    /** Requisições máximas por segundo (padrão global) */
    private static final int DEFAULT_REQ_SEC = 5;
    /** Requisições máximas por minuto (padrão global) */
    private static final int DEFAULT_REQ_MIN = 30;
    /** Número de violações antes do bloqueio permanente */
    private static final int PERM_BLOCK_THRESHOLD = 3;
    /** Duração padrão de bloqueio em segundos (5 minutos) */
    private static final long DEFAULT_BLOCK_SEC = 300L;
    /** Duração de bloqueio pesado em segundos para burst attacks (1 hora) */
    private static final long HEAVY_BLOCK_SEC = 3600L;
    /** Máximo de requisições em burst (1 segundo) antes de bloquear */
    private static final int BURST_THRESHOLD = 10;

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
     * pagamento, escrita. É lá que o contador continua valendo.</p>
     */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".css", ".js", ".mjs", ".map",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg", ".ico", ".bmp",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".mp4", ".webm", ".ogg", ".mp3", ".wav",
            ".webmanifest", ".txt", ".xml", ".pdf");

    // ==================== CONFIGURAÇÕES DE RATE LIMIT ====================

    /** Configurações de rate limit por padrão de path */
    private static final Map<String, RateLimitConfig> RATE_LIMIT_CONFIGS = new ConcurrentHashMap<>();
    /** Paths sem nenhum limite de requisição */
    private static final Set<String> UNLIMITED_PATHS = new HashSet<>();
    /** Paths completamente ignorados pela verificação de segurança */
    private static final Set<String> IGNORED_PATHS = new HashSet<>();

    /** Limite global de requisições por segundo (fallback) */
    private static int globalReqSec = DEFAULT_REQ_SEC;
    /** Limite global de requisições por minuto (fallback) */
    private static int globalReqMin = DEFAULT_REQ_MIN;
    /** Duração global de bloqueio em segundos (fallback) */
    private static long globalBlockSec = DEFAULT_BLOCK_SEC;
    /** Flag para habilitar/desabilitar rate limiting */
    private static boolean rateLimitingEnabled = true;

    // ==================== CACHES IN-MEMORY ====================

    /** Contador de janela deslizante por segundo, chaveado por IP+path */
    private static final Map<String, SlidingWindowCounter> SECOND_COUNTERS = new ConcurrentHashMap<>();
    /** Contador de janela deslizante por minuto, chaveado por IP+path */
    private static final Map<String, SlidingWindowCounter> MINUTE_COUNTERS = new ConcurrentHashMap<>();
    /** Timestamps recentes para detecção de burst attack, chaveado por IP hash */
    private static final Map<String, Queue<Long>> BURST_TRACKER = new ConcurrentHashMap<>();
    /** IPs/chaves atualmente bloqueados com tempo de desbloqueio */
    private static final Map<String, BlockInfo> BLOCKED_CACHE = new ConcurrentHashMap<>();
    /** Contagem de violações por IP hash */
    private static final Map<String, AtomicInteger> VIOLATION_CACHE = new ConcurrentHashMap<>();

    // ==================== HEADERS DE SEGURANÇA ====================

    private static final Map<String, String> SECURITY_HEADERS = new HashMap<>();

    static {
        SECURITY_HEADERS.put("X-Frame-Options", "SAMEORIGIN");
        SECURITY_HEADERS.put("X-XSS-Protection", "1; mode=block");
        SECURITY_HEADERS.put("Referrer-Policy", "strict-origin-when-cross-origin");

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
     * primeira requisição.</p>
     *
     * @param nome  nome do header (ex: {@code "Content-Security-Policy"})
     * @param valor valor a enviar; {@code null} remove o header
     */
    public static void setSecurityHeader(String nome, String valor) {
        if (nome == null || nome.isBlank()) return;
        if (valor == null) SECURITY_HEADERS.remove(nome);
        else SECURITY_HEADERS.put(nome, valor);
    }

    /** Coordenadas Maven do Javalin (versão alvo da biblioteca). */
    private static final String JAVALIN_COORDINATES = "io.javalin:javalin:7.2.2";
    /** Coordenadas Maven do plugin SSL (modo HTTPS). */
    private static final String JAVALIN_SSL_COORDINATES = "io.javalin.community.ssl:javalin-ssl:7.2.2";
    /** Coordenadas Maven da Reflections (scan de rotas). */
    private static final String REFLECTIONS_COORDINATES = "org.reflections:reflections:0.10.2";

    // ==================== PADRÕES MALICIOSOS ====================

    /** Métodos HTTP que podem carregar corpo malicioso no request. */
    private static final Set<HandlerType> BODY_METHODS = Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH);

    /** Padrões compilados de SQL Injection e XSS para detecção */
    private static final Pattern[] MALICIOUS_PATTERNS = {
            Pattern.compile("select.+from", Pattern.CASE_INSENSITIVE),
            Pattern.compile("insert.+into", Pattern.CASE_INSENSITIVE),
            Pattern.compile("update.+set", Pattern.CASE_INSENSITIVE),
            Pattern.compile("delete.+from", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drop.+table", Pattern.CASE_INSENSITIVE),
            Pattern.compile("union.+select", Pattern.CASE_INSENSITIVE),
            Pattern.compile("exec\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("execute\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<script", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("onload\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("eval\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("alert\\s*\\(", Pattern.CASE_INSENSITIVE)
    };

    // ==================== ESTADO DO SERVIDOR ====================

    private static Javalin javalinInstance;
    private static boolean initialized = false;

    /**
     * Quantos proxies reversos confiáveis existem na frente da aplicação.
     * Zero (padrão) = nenhum; cabeçalho de proxy é ignorado.
     */
    private static int trustedProxyHops = 0;

    private JavalinAPI() {}

    /**
     * Declara quantos proxies reversos confiáveis existem na frente da aplicação.
     *
     * <p>Chame com {@code 1} quando houver um nginx (ou Caddy, ou Apache) na
     * frente; com {@code 2} quando houver nginx atrás de uma CDN. Enquanto for
     * zero, {@code X-Forwarded-For} é ignorado e vale o IP do socket — que é o
     * único valor que o cliente não consegue forjar.</p>
     *
     * <p>Errar para mais é pior que errar para menos: cada salto declarado a
     * mais devolve o controle do IP para quem faz a requisição.</p>
     *
     * @param hops Número de proxies confiáveis (negativo é tratado como zero)
     */
    public static void setTrustedProxyHops(int hops) {
        trustedProxyHops = Math.max(0, hops);
    }

    // ==================== API PÚBLICA ====================

    /**
     * Inicializa o servidor Javalin em HTTP com todas as configurações de
     * segurança — a forma usada pelos projetos hospedados no Coolify.
     *
     * @param port            Porta HTTP em que o servidor escuta
     * @param enableRateLimit {@code true} para habilitar rate limiting
     * @return Instância configurada do Javalin, ou {@code null} em caso de falha
     */
    public static Javalin setup(int port, boolean enableRateLimit) {
        return setup(port, enableRateLimit, false, null);
    }

    /**
     * Inicializa o servidor Javalin com todas as configurações de segurança,
     * escolhendo explicitamente quem gerencia o certificado SSL.
     *
     * <p>O padrão é <strong>HTTP</strong>: o servidor escuta em
     * {@code 0.0.0.0:port} e o TLS fica com a hospedagem (Coolify, nginx ou
     * outro proxy reverso). O modo HTTPS só é ligado quando
     * {@code manageSsl} é {@code true} — aí o plugin javalin-ssl assume os
     * certificados, escuta HTTPS na porta informada e mantém {@code port + 1}
     * apenas para redirecionar o HTTP.</p>
     *
     * @param port            Porta principal (HTTP; ou HTTPS quando {@code manageSsl})
     * @param enableRateLimit {@code true} para habilitar rate limiting
     * @param manageSsl       {@code true} para o Javalin gerenciar o certificado SSL
     * @param folderCerts     Pasta com {@code fullchain.pem} e {@code privkey.pem};
     *                        usada somente quando {@code manageSsl} é {@code true}
     * @return Instância configurada do Javalin, ou {@code null} em caso de falha
     */
    public static Javalin setup(int port, boolean enableRateLimit, boolean manageSsl, File folderCerts) {
        Dependencies.require("io.javalin.Javalin", JAVALIN_COORDINATES, "Web Server (Javalin)");
        if (initialized) return javalinInstance;

        rateLimitingEnabled = enableRateLimit;
        loadPersistedConfigs();

        // Limpa dados antigos diariamente
        Task.runTimerWithFixedDelay(JavalinAPI::cleanupOldData, 0, 24 * 60 * 60 * 1000L);

        try {
            Javalin javalin = Javalin.create(config -> {
                config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
                config.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/public";
                    sf.location = Location.CLASSPATH;
                });
                
                config.router.contextPath = "/";
                config.router.ignoreTrailingSlashes = true;
                config.router.treatMultipleSlashesAsSingleSlash = true;

                // Tamanho máximo do body (1 GB)
                config.http.maxRequestSize = 1_000L * 1_024L * 1_024L;

                // HTTPS só quando pedido explicitamente: no Coolify o TLS
                // termina no proxy de borda e o contêiner recebe HTTP
                if (manageSsl) {
                    Console.log("Javalin iniciado em modo HTTPS gerenciado (porta %d, HTTP em %d apenas para redirecionar)",
                            port, port + 1);
                    Dependencies.require("io.javalin.community.ssl.SslPlugin", JAVALIN_SSL_COORDINATES,
                            "Web Server (Javalin) — HTTPS gerenciado");
                    SslSetup.configure(config, folderCerts, port);
                } else {
                    Console.log("Javalin iniciado em modo HTTP (porta %d) — HTTPS a cargo da hospedagem", port);
                }
            });

            // No modo HTTPS as portas são definidas pelo plugin SSL
            if (manageSsl) {
                javalin.start();
            } else {
                javalin.start(port);
            }

            javalinInstance = javalin;

            registerSecurityHandler(javalin);
            registerAllRoutes();

            initialized = true;
            return javalin;

        } catch (Exception e) {
            Console.error("Falha ao iniciar Javalin", e);
            return null;
        }
    }

    /**
     * Configura um limite de taxa personalizado para um padrão de path.
     *
     * @param pathPattern Padrão de path (ex: {@code /api/*}, {@code /login})
     * @param config      Configuração de limite a aplicar
     */
    public static void configureRateLimit(String pathPattern, RateLimitConfig config) {
        RATE_LIMIT_CONFIGS.put(pathPattern, config);
        new RouteRateLimitConfig(
                pathPattern,
                config.requestsPerSecond,
                config.requestsPerMinute,
                config.blockSeconds,
                config.perIp
        ).save();
        Console.log("Rate limit configurado: %s → %d req/s, %d req/min", pathPattern,
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
     * Aplica configuração restritiva para login: 1 req/s, 5 req/min, bloqueio de 15 minutos.
     *
     * @param pathPattern Padrão de path
     */
    public static void configureLoginRateLimit(String pathPattern) {
        configureRateLimit(pathPattern, new RateLimitConfig(1, 5, 900));
    }

    /**
     * Adiciona um path sem nenhum limite de requisições (ex: arquivos estáticos grandes).
     *
     * @param pathPattern Padrão de path
     */
    public static void addUnlimitedPath(String pathPattern) {
        UNLIMITED_PATHS.add(pathPattern);
    }

    /**
     * Adiciona um path completamente ignorado pela verificação de segurança.
     *
     * @param path Prefixo de path a ignorar (ex: {@code /health})
     */
    public static void addIgnoredPath(String path) {
        IGNORED_PATHS.add(path);
    }

    /**
     * Define o limite global de requisições (usado como fallback para paths sem configuração específica).
     *
     * @param reqSec   Requisições máximas por segundo
     * @param reqMin   Requisições máximas por minuto
     * @param blockSec Duração do bloqueio em segundos
     */
    public static void setGlobalRateLimit(int reqSec, int reqMin, long blockSec) {
        globalReqSec = reqSec;
        globalReqMin = reqMin;
        globalBlockSec = blockSec;
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
     * Remove o bloqueio permanente de um IP pelo seu hash SHA-256.
     *
     * @param ipHash Hash SHA-256 do IP
     * @return {@code true} se o bloqueio foi removido com sucesso
     */
    public static boolean unblockPermanently(String ipHash) {
        List<PermanentBlock> blocks = Saveable.query(PermanentBlock.class,
                "SELECT data, version FROM permanentblocks WHERE json_extract(data, '$.ipHash') = ?", ipHash);
        for (PermanentBlock block : blocks) {
            if (block.delete()) {
                BLOCKED_CACHE.remove(ipHash);
                return true;
            }
        }
        return false;
    }

    /**
     * Remove <b>todos</b> os bloqueios permanentes e zera as violações.
     *
     * <p>Operação de manutenção. Existe porque {@link #unblockPermanently(String)}
     * exige o hash SHA-256 do IP — que, por desenho, ninguém consegue derivar de
     * volta. Sem isto, um bloqueio indevido não tinha como ser desfeito a não ser
     * apagando o banco.</p>
     *
     * @return Quantidade de bloqueios removidos
     */
    public static int unblockAll() {
        int n = 0;
        for (PermanentBlock block : Saveable.findAll(PermanentBlock.class))
            if (block.delete()) n++;
        for (SuspectIp s : Saveable.findAll(SuspectIp.class)) s.delete();
        BLOCKED_CACHE.clear();
        BURST_TRACKER.clear();
        return n;
    }

    /**
     * Retorna todos os bloqueios permanentes ativos (não expirados).
     *
     * @return Lista de {@link PermanentBlock} ativos
     */
    public static List<PermanentBlock> getActivePermanentBlocks() {
        List<PermanentBlock> active = new ArrayList<>();
        for (PermanentBlock block : Saveable.findAll(PermanentBlock.class))
            if (!block.isExpired()) active.add(block);
        return active;
    }

    /**
     * Remove registros antigos do banco de dados (bloqueios expirados e suspeitos inativos há 30 dias).
     * Executado automaticamente a cada 24 horas.
     */
    public static void cleanupOldData() {
        long cutoff = Instant.now().getEpochSecond() - (30L * 24 * 60 * 60);
        Saveable.findAll(PermanentBlock.class).stream()
                .filter(PermanentBlock::isExpired)
                .forEach(PermanentBlock::delete);
        Saveable.findAll(SuspectIp.class).stream()
                .filter(s -> s.getLastViolationAt() < cutoff && !s.isPermanentlyBlocked())
                .forEach(SuspectIp::delete);
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
     * Registra o before-handler principal que aplica headers de segurança,
     * proteção contra inputs maliciosos e rate limiting em todas as rotas.
     */
    private static void registerSecurityHandler(Javalin javalin) {
        javalin.unsafe.routes.before(ctx -> {
            String path = ctx.path();

            // Redireciona URLs com extensão .html para a versão sem extensão
            if (path.toLowerCase().endsWith(".html")) {
                ctx.redirect(path.replace(".html", ""));
                return;
            }

            // Aplica headers de segurança em todas as respostas
            SECURITY_HEADERS.forEach(ctx::header);

            // Ignora paths configurados (ex: health check)
            if (shouldIgnorePath(path)) return;

            // Bloqueia inputs maliciosos (SQLi/XSS).
            //
            // Isto NÃO conta violação. O filtro é uma heurística de texto: um
            // Referer de anúncio, ou uma busca digitada na loja, casa com os
            // padrões sem que ninguém esteja atacando. Deixar essa heurística
            // alimentar o contador de bloqueio permanente significa banir para
            // sempre quem escreveu a frase errada no campo de busca. Recusa-se
            // a requisição — que é o que protege — e pronto.
            if (hasMaliciousInput(ctx)) {
                sendDeniedPage(ctx);
                return;
            }

            if (!rateLimitingEnabled) return;

            String ip = getClientIp(ctx);
            String ipHash = hashIp(ip);

            // Verifica bloqueio permanente
            if (isPermanentlyBlocked(ipHash)) {
                sendPermanentBlockPage(ctx);
                return;
            }

            // Recurso estático e paths sem limite não passam pelo rate limit.
            // Um <img> a mais numa vitrine não pode virar bloqueio de cliente.
            if (isStaticResource(path) || isUnlimitedPath(path)) return;

            RateLimitConfig cfg = getRateLimitConfig(path);

            // Chave única por IP + path exato para granularidade máxima
            String key = buildRateLimitKey(ipHash, path, cfg);

            // Verifica se o IP/chave está temporariamente bloqueado
            if (isBlocked(key)) {
                long remaining = BLOCKED_CACHE.get(key).getUnblockTime() - Instant.now().getEpochSecond();
                sendBlockPage(ctx, remaining);
                return;
            }

            // Detecta burst attack (muitas requisições em < 1 segundo)
            if (cfg.isPerIp() && isBurstAttack(ipHash)) {
                punir(ctx, ip, ipHash, key, HEAVY_BLOCK_SEC);
                return;
            }

            // Verifica e registra a requisição nas janelas deslizantes
            if (!checkAndRecordRequest(key, cfg))
                punir(ctx, ip, ipHash, key, cfg.getBlockSeconds());
        });
    }

    /**
     * Bloqueia a chave e decide se o caso já virou bloqueio permanente.
     *
     * <p>Estava escrito duas vezes, igual, em dois pontos do handler — e é o
     * trecho que mais dói errar: cada cópia é uma chance de uma delas esquecer
     * de encerrar a requisição ou de escalar cedo demais.</p>
     *
     * <p>O bloqueio permanente é a única punição que não passa sozinha, então
     * exige um IP em que dê para confiar. Se o valor resolvido é loopback ou
     * rede privada, ou o proxy não está repassando a origem — e aí todo mundo
     * chega com o mesmo IP — ou é tráfego interno. Banir para sempre nesse
     * caso derruba a aplicação inteira. Continua valendo o bloqueio temporário,
     * que contém o abuso e expira.</p>
     *
     * @param ctx     Contexto da requisição
     * @param ip      IP resolvido do cliente
     * @param ipHash  Hash SHA-256 do IP
     * @param key     Chave de rate limit a bloquear
     * @param seconds Duração do bloqueio temporário, em segundos
     */
    private static void punir(Context ctx, String ip, String ipHash, String key, long seconds) {
        int violations = registerViolation(ipHash);

        if (violations >= PERM_BLOCK_THRESHOLD && !isSharedOrLocalIp(ip)) {
            createPermanentBlock(ipHash, violations);
            sendPermanentBlockPage(ctx);
            return;
        }

        blockKey(key, seconds);
        sendBlockPage(ctx, seconds);
    }

    // ==================== PERSISTÊNCIA ====================

    /**
     * Carrega configurações de rate limit, bloqueios permanentes e suspeitos do banco de dados
     * para os caches in-memory ao iniciar o servidor.
     */
    private static void loadPersistedConfigs() {
        try {
            for (RouteRateLimitConfig c : Saveable.findAll(RouteRateLimitConfig.class))
                if (c.isEnabled())
                    RATE_LIMIT_CONFIGS.put(c.getPathPattern(), new RateLimitConfig(
                            c.getRequestsPerSecond(), c.getRequestsPerMinute(),
                            c.getBlockSeconds(), c.isPerIp()));

            for (PermanentBlock b : Saveable.findAll(PermanentBlock.class))
                if (!b.isExpired())
                    BLOCKED_CACHE.put(b.getIpHash(), new BlockInfo(b.getExpiresAt(), "Permanent"));

            for (SuspectIp s : Saveable.findAll(SuspectIp.class)) {
                VIOLATION_CACHE.put(s.getIpHash(), new AtomicInteger(s.getTotalViolations()));
                if (s.isPermanentlyBlocked())
                    BLOCKED_CACHE.put(s.getIpHash(), new BlockInfo(Long.MAX_VALUE, "Permanent"));
            }

            /* Bloqueio permanente é invisível para quem administra: quem foi
             * banido simplesmente não volta para reclamar, e quem olha o site
             * de outro lugar vê tudo funcionando. Dizer o número toda subida é
             * o que transforma "o site caiu para alguns" numa pista. */
            long permanentes = BLOCKED_CACHE.values().stream()
                    .filter(b -> b.getUnblockTime() == Long.MAX_VALUE).count();
            if (permanentes > 0)
                Console.warn("Há %d IP(s) com bloqueio PERMANENTE. Quem estiver na lista "
                        + "recebe 403 em tudo. Para zerar: JavalinAPI.unblockAll().", permanentes);
        } catch (Exception e) {
            Console.error("Erro ao carregar configurações persistidas", e);
        }
    }

    /**
     * Cria um bloqueio permanente para o IP informado e atualiza o banco de dados.
     *
     * @param ipHash     Hash SHA-256 do IP
     * @param violations Número de violações que causaram o bloqueio
     */
    private static void createPermanentBlock(String ipHash, int violations) {
        new PermanentBlock(ipHash, String.format("Bloqueio após %d violações", violations), violations, "System").save();
        BLOCKED_CACHE.put(ipHash, new BlockInfo(Long.MAX_VALUE, "Permanent"));

        Task.runLater(() -> {
            List<SuspectIp> suspects = Saveable.query(SuspectIp.class,
                    "SELECT data, version FROM suspectips WHERE json_extract(data, '$.ipHash') = ?", ipHash);
            SuspectIp suspect = suspects.isEmpty() ? new SuspectIp(ipHash) : suspects.get(0);
            suspect.setPermanentlyBlocked(true);
            suspect.save();
        }, 0);
    }

    /**
     * Verifica se um IP hash possui bloqueio permanente ativo no banco de dados.
     *
     * @param ipHash Hash SHA-256 do IP
     * @return {@code true} se o IP está permanentemente bloqueado
     */
    private static boolean isPermanentlyBlocked(String ipHash) {
        BlockInfo block = BLOCKED_CACHE.get(ipHash);
        if (block == null || block.getUnblockTime() != Long.MAX_VALUE) return false;
        return !Saveable.query(PermanentBlock.class,
                "SELECT data, version FROM permanentblocks WHERE json_extract(data, '$.ipHash') = ? AND json_extract(data, '$.expiresAt') > ?",
                ipHash, Instant.now().getEpochSecond()).isEmpty();
    }

    // ==================== SEGURANÇA ====================

    /**
     * Gera o hash SHA-256 de um endereço IP para anonimização nos logs/banco.
     *
     * @param ip Endereço IP original
     * @return Hash hexadecimal SHA-256, ou IP sanitizado em caso de erro
     */
    /** Tabela hexadecimal para conversão de bytes sem alocação de formatadores. */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static String hashIp(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(ip.getBytes());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(HEX_DIGITS[(b >>> 4) & 0xF]);
                hex.append(HEX_DIGITS[b & 0xF]);
            }
            return hex.toString();
        } catch (Exception e) {
            return ip.replaceAll("[^a-zA-Z0-9]", "");
        }
    }

    /**
     * Verifica se algum parâmetro, corpo ou header da requisição contém padrões maliciosos
     * (SQL Injection ou XSS).
     *
     * @param ctx Contexto da requisição
     * @return {@code true} se input malicioso detectado
     */
    private static boolean hasMaliciousInput(Context ctx) {
        for (List<String> params : ctx.queryParamMap().values())
            for (String p : params)
                if (containsMaliciousPattern(p)) return true;

        if (BODY_METHODS.contains(ctx.method()) && corpoEhTexto(ctx))
            if (containsMaliciousPattern(ctx.body())) return true;

        for (String h : ctx.headerMap().values())
            if (containsMaliciousPattern(h)) return true;

        return false;
    }

    /**
     * O corpo desta requisição pode ser lido para varredura?
     *
     * <p><b>{@code ctx.body()} CONSOME o corpo.</b> Depois dele,
     * {@code ctx.uploadedFiles()} lança {@code BodyAlreadyReadException} e todo
     * upload multipart do consumidor morre — a rota recebe o corpo já gasto e
     * não consegue mais separar as partes. Era o que derrubava as três telas de
     * foto da Ele &amp; Ela: capa da home, capa de categoria e foto de produto.</p>
     *
     * <p>E procurar {@code <script} dentro dos bytes de um JPEG não protege
     * nada: gasta megabytes virando String a cada foto enviada e ainda pode
     * casar por acaso, recusando a foto sem que ninguém esteja atacando.</p>
     *
     * <p>Sem {@code Content-Type} a varredura continua acontecendo — é o
     * comportamento antigo, e sem cabeçalho não existe multipart para quebrar.
     * Query params e headers seguem varridos em qualquer caso.</p>
     *
     * @param ctx Contexto da requisição
     * @return {@code false} para multipart e binário, {@code true} para o resto
     */
    private static boolean corpoEhTexto(Context ctx) {
        String tipo = ctx.header("Content-Type");
        if (tipo == null || tipo.isBlank()) return true;
        String t = tipo.toLowerCase(Locale.ROOT);
        if (t.startsWith("multipart/")) return false;
        return !t.startsWith("image/")
                && !t.startsWith("video/")
                && !t.startsWith("audio/")
                && !t.startsWith("application/octet-stream");
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
     * Registra uma violação de segurança para o IP e persiste de forma assíncrona.
     *
     * @param ipHash Hash SHA-256 do IP
     * @return Número total de violações acumuladas
     */
    private static int registerViolation(String ipHash) {
        int violations = VIOLATION_CACHE.computeIfAbsent(ipHash, k -> new AtomicInteger(0)).incrementAndGet();
        Task.runLater(() -> {
            List<SuspectIp> suspects = Saveable.query(SuspectIp.class,
                    "SELECT data, version FROM suspectips WHERE json_extract(data, '$.ipHash') = ?", ipHash);
            SuspectIp suspect = suspects.isEmpty() ? new SuspectIp(ipHash) : suspects.get(0);
            suspect.setTotalViolations(violations);
            suspect.save();
        }, 0);
        return violations;
    }

    /**
     * Detecta ataque de burst: mais de {@link #BURST_THRESHOLD} requisições em 1 segundo.
     *
     * @param ipHash Hash SHA-256 do IP
     * @return {@code true} se burst attack detectado
     */
    private static boolean isBurstAttack(String ipHash) {
        long now = System.currentTimeMillis();
        Queue<Long> timestamps = BURST_TRACKER.computeIfAbsent(ipHash, k -> new LinkedList<>());
        synchronized (timestamps) {
            // Remove timestamps fora da janela de 1 segundo
            while (!timestamps.isEmpty() && timestamps.peek() < now - 1_000) timestamps.poll();
            timestamps.add(now);
            return timestamps.size() > BURST_THRESHOLD;
        }
    }

    // ==================== RATE LIMITING ====================

    /**
     * Retorna a configuração de rate limit aplicável ao path, em ordem de precedência:
     * <ol>
     *   <li>Correspondência exata do path</li>
     *   <li>Correspondência por padrão (curinga ou regex)</li>
     *   <li>Configuração global (fallback)</li>
     * </ol>
     *
     * @param path Path da requisição
     * @return Configuração de rate limit aplicável
     */
    private static RateLimitConfig getRateLimitConfig(String path) {
        RateLimitConfig exact = RATE_LIMIT_CONFIGS.get(path);
        if (exact != null) return exact;
        for (Map.Entry<String, RateLimitConfig> entry : RATE_LIMIT_CONFIGS.entrySet())
            if (matchesPathPattern(path, entry.getKey())) return entry.getValue();
        return new RateLimitConfig(globalReqSec, globalReqMin, globalBlockSec);
    }

    /**
     * Verifica se um path corresponde a um padrão (suporta curingas {@code /*} e segmentos variáveis {@code {param}}).
     *
     * @param path    Path real da requisição
     * @param pattern Padrão de configuração
     * @return {@code true} se o path corresponde ao padrão
     */
    /** Cache de regex compiladas para padrões de path com parâmetros ({@code {id}}). */
    private static final Map<String, Pattern> PATTERN_REGEX_CACHE = new ConcurrentHashMap<>();

    private static boolean matchesPathPattern(String path, String pattern) {
        if (pattern.endsWith("/*"))
            return path.startsWith(pattern.substring(0, pattern.length() - 2));
        Pattern regex = PATTERN_REGEX_CACHE.computeIfAbsent(pattern, p ->
                Pattern.compile(p.replaceAll("\\{[^}]+}", "[^/]+")));
        return regex.matcher(path).matches();
    }

    /**
     * Verifica se o path está na lista de paths sem limite.
     */
    private static boolean isUnlimitedPath(String path) {
        return UNLIMITED_PATHS.stream().anyMatch(p -> matchesPathPattern(path, p));
    }

    /**
     * Verifica se o path aponta para um recurso estático.
     *
     * <p>Decidido pela extensão, e não por prefixo de pasta: projetos servem
     * estático de lugares diferentes ({@code /assets}, {@code /public},
     * {@code /styles}, a raiz), e uma lista de pastas erra em todos eles.</p>
     *
     * @param path Path da requisição
     * @return {@code true} se for arquivo estático
     */
    private static boolean isStaticResource(String path) {
        int ponto = path.lastIndexOf('.');
        if (ponto < 0) return false;
        int barra = path.lastIndexOf('/');
        if (ponto < barra) return false;                  // ponto no meio do caminho, não é extensão
        return STATIC_EXTENSIONS.contains(path.substring(ponto).toLowerCase());
    }

    /**
     * Verifica se o path deve ser completamente ignorado pela verificação de segurança.
     */
    private static boolean shouldIgnorePath(String path) {
        return IGNORED_PATHS.stream().anyMatch(p -> path.toLowerCase().startsWith(p.toLowerCase()));
    }

    /**
     * Extrai o IP do cliente.
     *
     * <p><b>Cabeçalho de proxy só vale se houver proxy declarado.</b> A versão
     * anterior lia {@code X-Forwarded-For} sempre, e pegava o <i>primeiro</i>
     * item da lista. Os dois pontos estavam errados:</p>
     *
     * <ul>
     *   <li>Sem proxy na frente, o cabeçalho vem do cliente. Mandar
     *       {@code X-Forwarded-For: 1.2.3.4} contornava rate limit e bloqueio
     *       permanente de uma vez — e, escolhendo o IP de outra pessoa,
     *       permitia fazer <i>ela</i> ser bloqueada;</li>
     *   <li>o primeiro item é justamente a parte que o cliente escreve. Com
     *       {@code $proxy_add_x_forwarded_for} no nginx, a lista fica
     *       {@code <o que o cliente mandou>, <o que o proxy viu>} — o valor
     *       confiável é o do <b>fim</b>.</li>
     * </ul>
     *
     * <p>Por isso a contagem é a partir da direita: {@link #trustedProxyHops}
     * diz quantos proxies seus existem, e pula-se exatamente esse tanto. O
     * padrão é zero — sem configuração, vale só o IP do socket.</p>
     *
     * @param ctx Contexto da requisição
     * @return IP do cliente
     * @see #setTrustedProxyHops(int)
     */
    private static String getClientIp(Context ctx) {
        if (trustedProxyHops <= 0) return ctx.ip();

        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] cadeia = forwarded.split(",");
            // length - hops: pula os proxies confiáveis, contando do fim.
            int i = Math.max(0, cadeia.length - trustedProxyHops);
            String ip = cadeia[i].trim();
            if (!ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) return ip;
        }

        // Cabeçalhos de valor único: o proxy sobrescreve, então não há cadeia
        // para percorrer. Só entram porque um proxy foi declarado.
        for (String header : new String[]{"CF-Connecting-IP", "True-Client-IP", "X-Real-IP"}) {
            String value = ctx.header(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value))
                return value.split(",")[0].trim();
        }
        return ctx.ip();
    }

    /**
     * Verifica se o IP resolvido é local ou de rede privada.
     *
     * <p>Serve de rede de segurança para proxy mal configurado: se o nginx não
     * repassa o IP de origem, <b>todo mundo</b> chega como {@code 127.0.0.1} e
     * passa a dividir o mesmo contador. Aí o primeiro visitante a esbarrar no
     * limite bloqueia a loja inteira, para sempre. Um IP assim continua sendo
     * limitado por rajada, mas nunca vira bloqueio permanente.</p>
     *
     * @param ip IP resolvido por {@link #getClientIp(Context)}
     * @return {@code true} se for loopback ou rede privada
     */
    private static boolean isSharedOrLocalIp(String ip) {
        if (ip == null || ip.isBlank()) return true;
        String v = ip.trim().toLowerCase();
        if (v.startsWith("[")) v = v.substring(1);
        return v.startsWith("127.") || v.equals("::1") || v.startsWith("0:0:0:0:0:0:0:1")
                || v.startsWith("10.") || v.startsWith("192.168.")
                || v.startsWith("169.254.") || v.startsWith("fc") || v.startsWith("fd")
                || v.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /**
     * Constrói a chave de rate limiting.
     *
     * <p>Quando {@code perIp = true}, a chave é {@code <ipHash>|<path>}, garantindo
     * granularidade máxima: cada IP é limitado individualmente por path exato.</p>
     * <p>Quando {@code perIp = false}, a chave é apenas o path, compartilhado entre todos os IPs.</p>
     *
     * @param ipHash Hash SHA-256 do IP
     * @param path   Path exato da requisição
     * @param cfg    Configuração de rate limit
     * @return Chave de rate limiting
     */
    private static String buildRateLimitKey(String ipHash, String path, RateLimitConfig cfg) {
        return cfg.isPerIp() ? (ipHash + "|" + path) : path;
    }

    /**
     * Verifica se uma chave (IP ou path) está atualmente bloqueada.
     * Remove automaticamente do cache se o bloqueio já expirou.
     *
     * @param key Chave de rate limiting
     * @return {@code true} se ainda bloqueada
     */
    private static boolean isBlocked(String key) {
        BlockInfo info = BLOCKED_CACHE.get(key);
        if (info == null) return false;
        if (Instant.now().getEpochSecond() >= info.getUnblockTime()) {
            BLOCKED_CACHE.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Verifica e registra a requisição nas janelas deslizantes de segundo e minuto.
     *
     * @param key Chave de rate limiting
     * @param cfg Configuração com os limites a aplicar
     * @return {@code true} se a requisição está dentro dos limites; {@code false} se excedeu
     */
    private static boolean checkAndRecordRequest(String key, RateLimitConfig cfg) {
        long now = Instant.now().getEpochSecond();
        SlidingWindowCounter perSecond = SECOND_COUNTERS.computeIfAbsent(key, k -> new SlidingWindowCounter(1));
        if (!perSecond.checkAndIncrement(cfg.getRequestsPerSecond(), now)) return false;
        SlidingWindowCounter perMinute = MINUTE_COUNTERS.computeIfAbsent(key, k -> new SlidingWindowCounter(60));
        return perMinute.checkAndIncrement(cfg.getRequestsPerMinute(), now);
    }

    /**
     * Bloqueia uma chave por um número de segundos, limpa seus contadores
     * e agenda remoção automática do cache.
     *
     * @param key     Chave de rate limiting a bloquear
     * @param seconds Duração do bloqueio em segundos
     */
    private static void blockKey(String key, long seconds) {
        long unblockAt = Instant.now().getEpochSecond() + seconds;
        BLOCKED_CACHE.put(key, new BlockInfo(unblockAt, key));
        SECOND_COUNTERS.remove(key);
        MINUTE_COUNTERS.remove(key);
        // Remove do cache após expiração para evitar acúmulo de memória
        Task.runLater(() -> {
            BlockInfo info = BLOCKED_CACHE.get(key);
            if (info != null && info.getUnblockTime() == unblockAt)
                BLOCKED_CACHE.remove(key);
        }, seconds * 1_000L);
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
            "*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;"
            + "align-items:center;justify-content:center;padding:24px;background:#F4F4F5;"
            + "color:#18181B;font:16px/1.55 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}"
            + "main{max-width:26rem;background:#fff;border-radius:14px;padding:32px;"
            + "box-shadow:0 1px 3px rgba(0,0,0,.1),0 8px 24px rgba(0,0,0,.06)}"
            + "h1{margin:0 0 12px;font-size:1.3rem;line-height:1.3}"
            + "p{margin:0;color:#52525B}strong{color:#18181B}"
            + "@media(prefers-color-scheme:dark){body{background:#18181B;color:#FAFAFA}"
            + "main{background:#27272A;box-shadow:none}p{color:#A1A1AA}strong{color:#FAFAFA}}";

    /**
     * Monta uma página de recusa e <b>encerra a requisição</b>.
     *
     * <p>O {@code skipRemainingHandlers()} é o ponto crítico, e não um detalhe.
     * Um {@code before} do Javalin não interrompe nada ao retornar: o servlet
     * continua para a task HTTP, que casa a rota ou entrega o arquivo estático
     * por cima do que foi escrito aqui. Sem este corte, um bloqueio vira
     * decoração — o conteúdo é servido do mesmo jeito e só o código de status
     * fica errado, o que quebra o site (o navegador recusa CSS e JS com 4xx)
     * sem proteger coisa alguma.</p>
     *
     * @param ctx    Contexto da requisição
     * @param status Código HTTP da recusa
     * @param titulo Título curto, em linguagem comum
     * @param corpo  Explicação em uma frase (HTML já escapado pelo chamador)
     */
    private static void sendDenyPage(Context ctx, int status, String titulo, String corpo) {
        ctx.html("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\">"
                + "<title>" + titulo + "</title><style>" + DENY_STYLE + "</style></head>"
                + "<body><main><h1>" + titulo + "</h1><p>" + corpo + "</p></main></body></html>")
                .status(status);
        ctx.skipRemainingHandlers();
    }

    /**
     * Envia uma página de bloqueio temporário com o tempo restante.
     *
     * @param ctx     Contexto da requisição
     * @param seconds Segundos restantes até o desbloqueio
     */
    private static void sendBlockPage(Context ctx, long seconds) {
        long minutos = Math.max(1, Math.round(seconds / 60.0));
        String espera = seconds < 90
                ? "<strong>" + Math.max(1, seconds) + " segundos</strong>"
                : "<strong>" + minutos + (minutos == 1 ? " minuto" : " minutos") + "</strong>";
        sendDenyPage(ctx, StatusCode.TOO_MANY_REQUESTS.code(), "Muitos acessos seguidos",
                "Chegaram pedidos demais deste aparelho em pouco tempo. "
                + "Espere " + espera + " e tente de novo.");
    }

    /**
     * Envia uma página de bloqueio permanente.
     *
     * @param ctx Contexto da requisição
     */
    private static void sendPermanentBlockPage(Context ctx) {
        sendDenyPage(ctx, StatusCode.FORBIDDEN.code(), "Acesso bloqueado",
                "Este acesso foi bloqueado por atividade fora do normal. "
                + "Se você acha que houve engano, fale com o suporte.");
    }

    /**
     * Envia a recusa de conteúdo suspeito (SQLi/XSS detectado na requisição).
     *
     * @param ctx Contexto da requisição
     */
    private static void sendDeniedPage(Context ctx) {
        sendDenyPage(ctx, StatusCode.FORBIDDEN.code(), "Pedido recusado",
                "O conteúdo enviado tem trechos que o sistema não aceita. "
                + "Refaça o pedido sem símbolos ou comandos.");
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
     * Configura o plugin SSL/TLS do Javalin (javalin-ssl), usado somente quando
     * a aplicação pede HTTPS gerenciado na inicialização. Classe separada para
     * que a {@link JavalinAPI} possa ser vinculada sem a dependência do plugin —
     * o guard de dependência roda antes deste helper ser tocado.
     */
    private static final class SslSetup {

        private SslSetup() {
        }

        static void configure(io.javalin.config.JavalinConfig config, File folderCerts, int port) {
            SslPlugin sslPlugin = new SslPlugin(ssl -> {
                ssl.pemFromPath(folderCerts + "/fullchain.pem", folderCerts + "/privkey.pem");
                ssl.secure = true;
                ssl.insecure = false;
                ssl.redirect = true;
                ssl.securePort = port;
                ssl.insecurePort = port + 1;
            });
            config.registerPlugin(sslPlugin);
        }
    }

    /**
     * Descobre e registra todas as implementações de {@link Route} no classpath.
     * Classe separada para manter as referências à Reflections fora do bytecode
     * da {@link JavalinAPI} (link sem a dependência + guard com mensagem clara).
     */
    private static final class RouteDiscovery {

        private RouteDiscovery() {
        }

        static void scanAndRegister() {
            Reflections reflections = new Reflections(
                    new org.reflections.util.ConfigurationBuilder()
                            .setUrls(org.reflections.util.ClasspathHelper.forJavaClassPath())
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