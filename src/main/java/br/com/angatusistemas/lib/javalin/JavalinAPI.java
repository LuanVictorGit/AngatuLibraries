package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
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
 * <p>Funcionalidades:</p>
 * <ul>
 *   <li>Proteção contra DDoS, SQL Injection e XSS</li>
 *   <li>Rate limiting por IP e por rota com janela deslizante</li>
 *   <li>Bloqueios temporários e permanentes persistidos em banco de dados</li>
 *   <li>Headers de segurança HTTP automáticos</li>
 *   <li>Suporte a SSL/TLS com redirecionamento automático</li>
 *   <li>Servir arquivos estáticos corretamente tanto em HTTP quanto HTTPS</li>
 * </ul>
 *
 * @author Angatu Sistemas
 * @version 3.0
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
        SECURITY_HEADERS.put("X-Frame-Options", "DENY");
        SECURITY_HEADERS.put("X-Content-Type-Options", "nosniff");
        SECURITY_HEADERS.put("X-XSS-Protection", "1; mode=block");
        SECURITY_HEADERS.put("Referrer-Policy", "strict-origin-when-cross-origin");
        SECURITY_HEADERS.put("Content-Security-Policy",
                "default-src 'self' data: blob:; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net; " +
                "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                "img-src * data: blob:; " +
                "font-src 'self' https://cdn.jsdelivr.net; " +
                "connect-src *;"
        );
        SECURITY_HEADERS.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }

    // ==================== PADRÕES MALICIOSOS ====================

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

    private JavalinAPI() {}

    // ==================== API PÚBLICA ====================

    /**
     * Inicializa o servidor Javalin com todas as configurações de segurança.
     *
     * <p>Em modo HTTPS, o SSL é configurado automaticamente com os certificados
     * fornecidos. Os arquivos estáticos são servidos tanto em HTTP quanto HTTPS.</p>
     *
     * @param folderCerts Pasta contendo {@code fullchain.pem} e {@code privkey.pem}
     * @param port        Porta principal (HTTPS usa esta porta; HTTP usa {@code port + 1})
     * @param localhost   {@code true} para modo local sem SSL (porta 80)
     * @param enableRateLimit {@code true} para habilitar rate limiting
     * @return Instância configurada do Javalin, ou {@code null} em caso de falha
     */
    public static Javalin setup(File folderCerts, int port, boolean localhost, boolean enableRateLimit) {
        if (initialized) return javalinInstance;

        rateLimitingEnabled = enableRateLimit;
        loadPersistedConfigs();

        // Limpa dados antigos diariamente
        Task.runTimerWithFixedDelay(JavalinAPI::cleanupOldData, 0, 24 * 60 * 60 * 1000L);

        try {
            Javalin javalin = Javalin.create(config -> {
                // CORS: permite qualquer origem
                config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));

                // Arquivos estáticos — configuração robusta para HTTP e HTTPS
                // IMPORTANTE: Em modo HTTPS (SslPlugin), o Javalin serve dois servidores
                // (seguro e inseguro). É necessário registrar os arquivos estáticos
                // ANTES de iniciar o servidor para que ambos os canais os ensirvam.
                config.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/public";
                    sf.location = Location.CLASSPATH;
                });

                // Tamanho máximo do body (1 GB)
                config.http.maxRequestSize = 1_000L * 1_024L * 1_024L;

                // Configuração SSL para produção
                if (!localhost) {
                    Console.log("Javalin iniciado em modo HTTPS (porta %d)", port);
                    SslPlugin sslPlugin = new SslPlugin(ssl -> {
                        ssl.pemFromPath(folderCerts + "/fullchain.pem", folderCerts + "/privkey.pem");
                        ssl.secure = true;
                        ssl.insecure = false;
                        ssl.redirect = true;
                        ssl.securePort = port;
                        ssl.insecurePort = port + 1;
                    });
                    config.registerPlugin(sslPlugin);
                } else {
                    Console.log("Javalin iniciado em modo HTTP local (porta 80)");
                }
            });

            if (localhost) {
                javalin.start(80);
            } else {
                javalin.start();
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
                "SELECT data FROM permanentblocks WHERE json_extract(data, '$.ipHash') = ?", ipHash);
        for (PermanentBlock block : blocks) {
            if (block.delete()) {
                BLOCKED_CACHE.remove(ipHash);
                return true;
            }
        }
        return false;
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
        return javalinInstance;
    }

    // ==================== HANDLER DE SEGURANÇA ====================

    /**
     * Registra o before-handler principal que aplica headers de segurança,
     * proteção contra inputs maliciosos e rate limiting em todas as rotas.
     */
    private static void registerSecurityHandler(Javalin javalin) {
        javalin.before(ctx -> {
            String path = ctx.path();

            // Redireciona URLs com extensão .html para a versão sem extensão
            if (path.toLowerCase().endsWith(".html")) {
                ctx.redirect(path.replace(".html", ""));
                return;
            }

            // Captura o host de origem na primeira requisição
            if (AngatuLib.getInstance().getOriginHost() == null)
                AngatuLib.getInstance().setOriginHost(ctx.scheme() + "://" + ctx.host());

            // Aplica headers de segurança em todas as respostas
            SECURITY_HEADERS.forEach(ctx::header);

            // Ignora paths configurados (ex: health check)
            if (shouldIgnorePath(path)) return;

            // Bloqueia inputs maliciosos (SQLi/XSS)
            if (hasMaliciousInput(ctx)) {
                registerViolation(hashIp(getClientIp(ctx)));
                ctx.status(StatusCode.FORBIDDEN.code()).result("Acesso negado");
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

            // Paths sem limite não passam pela verificação de rate limit
            if (isUnlimitedPath(path)) return;

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
                int violations = registerViolation(ipHash);
                if (violations >= PERM_BLOCK_THRESHOLD) {
                    createPermanentBlock(ipHash, violations);
                    sendPermanentBlockPage(ctx);
                } else {
                    blockKey(key, HEAVY_BLOCK_SEC);
                    sendBlockPage(ctx, HEAVY_BLOCK_SEC);
                }
                return;
            }

            // Verifica e registra a requisição nas janelas deslizantes
            if (!checkAndRecordRequest(key, cfg)) {
                int violations = registerViolation(ipHash);
                if (violations >= PERM_BLOCK_THRESHOLD) {
                    createPermanentBlock(ipHash, violations);
                    sendPermanentBlockPage(ctx);
                } else {
                    blockKey(key, cfg.getBlockSeconds());
                    sendBlockPage(ctx, cfg.getBlockSeconds());
                }
            }
        });
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
                    "SELECT data FROM suspectips WHERE json_extract(data, '$.ipHash') = ?", ipHash);
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
                "SELECT data FROM permanentblocks WHERE json_extract(data, '$.ipHash') = ? AND json_extract(data, '$.expiresAt') > ?",
                ipHash, Instant.now().getEpochSecond()).isEmpty();
    }

    // ==================== SEGURANÇA ====================

    /**
     * Gera o hash SHA-256 de um endereço IP para anonimização nos logs/banco.
     *
     * @param ip Endereço IP original
     * @return Hash hexadecimal SHA-256, ou IP sanitizado em caso de erro
     */
    private static String hashIp(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(ip.getBytes());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
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

        if (Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH).contains(ctx.method()))
            if (containsMaliciousPattern(ctx.body())) return true;

        for (String h : ctx.headerMap().values())
            if (containsMaliciousPattern(h)) return true;

        return false;
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
                    "SELECT data FROM suspectips WHERE json_extract(data, '$.ipHash') = ?", ipHash);
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
    private static boolean matchesPathPattern(String path, String pattern) {
        if (pattern.endsWith("/*"))
            return path.startsWith(pattern.substring(0, pattern.length() - 2));
        String regex = pattern.replaceAll("\\{[^}]+}", "[^/]+");
        return Pattern.matches(regex, path);
    }

    /**
     * Verifica se o path está na lista de paths sem limite.
     */
    private static boolean isUnlimitedPath(String path) {
        return UNLIMITED_PATHS.stream().anyMatch(p -> matchesPathPattern(path, p));
    }

    /**
     * Verifica se o path deve ser completamente ignorado pela verificação de segurança.
     */
    private static boolean shouldIgnorePath(String path) {
        return IGNORED_PATHS.stream().anyMatch(p -> path.toLowerCase().startsWith(p.toLowerCase()));
    }

    /**
     * Extrai o IP real do cliente, considerando proxies reversos e CDNs comuns.
     * Prioriza os headers: {@code X-Forwarded-For}, {@code X-Real-IP},
     * {@code CF-Connecting-IP} (Cloudflare), {@code True-Client-IP}.
     *
     * @param ctx Contexto da requisição
     * @return IP real do cliente
     */
    private static String getClientIp(Context ctx) {
        for (String header : new String[]{"X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP"}) {
            String value = ctx.header(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value))
                return value.split(",")[0].trim();
        }
        return ctx.ip();
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
     * Envia uma página HTML de bloqueio temporário com o tempo restante.
     *
     * @param ctx     Contexto da requisição
     * @param seconds Segundos restantes até o desbloqueio
     */
    private static void sendBlockPage(Context ctx, long seconds) {
        ctx.html(String.format("""
                <!DOCTYPE html><html><head><meta charset="UTF-8"><title>Bloqueado</title>
                <link href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.3/dist/tailwind.min.css" rel="stylesheet">
                </head><body class="bg-gray-100 flex items-center justify-center min-h-screen">
                <div class="bg-white rounded-lg p-8 text-center shadow">
                <h1 class="text-2xl font-bold text-red-600 mb-4">Acesso Bloqueado</h1>
                <p class="text-gray-600">Muitas requisições. Aguarde <strong>%d segundos</strong>.</p>
                </div></body></html>""", seconds))
                .status(StatusCode.TOO_MANY_REQUESTS.code());
    }

    /**
     * Envia uma página HTML de bloqueio permanente.
     *
     * @param ctx Contexto da requisição
     */
    private static void sendPermanentBlockPage(Context ctx) {
        ctx.html("""
                <!DOCTYPE html><html><head><meta charset="UTF-8"><title>Bloqueio Permanente</title>
                <link href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.3/dist/tailwind.min.css" rel="stylesheet">
                </head><body class="bg-gray-100 flex items-center justify-center min-h-screen">
                <div class="bg-white rounded-lg p-8 text-center shadow">
                <h1 class="text-2xl font-bold text-red-700 mb-4">Acesso Permanentemente Bloqueado</h1>
                <p class="text-gray-600">Entre em contato com o suporte para regularizar o acesso.</p>
                </div></body></html>""")
                .status(StatusCode.FORBIDDEN.code());
    }

    // ==================== REGISTRO DE ROTAS ====================

    /**
     * Descobre e registra automaticamente todas as implementações de {@link Route}
     * no classpath usando reflection, ignorando classes abstratas e interfaces.
     */
    private static void registerAllRoutes() {
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
                Console.error("Erro ao registrar rota: %s", e, routeClass.getName());
            }
        }
    }
}