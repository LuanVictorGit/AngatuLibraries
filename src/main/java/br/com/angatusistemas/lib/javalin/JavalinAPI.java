package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.javalin.classes.*;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.task.Task;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.staticfiles.Location;

/**
 * [PT] API principal para configuração do servidor Javalin com rate limiting
 * avançado e persistência.
 * <p>
 * Fornece proteção contra DDoS, SQL Injection, XSS, rate limiting por
 * rota/configuração global, bloqueios permanentes com persistência em banco de
 * dados e headers de segurança.
 * </p>
 *
 * [EN] Main API for Javalin server configuration with advanced rate limiting
 * and persistence.
 *
 * @author Angatu Sistemas
 * @version 2.0
 */
public final class JavalinAPI {

	// ==================== CONSTANTES ====================
	private static final int DEF_REQ_SEC = 5, DEF_REQ_MIN = 30, PERM_BLOCK_THRESHOLD = 3;
	private static final long DEF_BLOCK_SEC = 300, HEAVY_BLOCK_SEC = 3600;

	// ==================== CONFIGURAÇÕES ====================
	private static final Map<String, RateLimitConfig> RATE_LIMIT_CONFIGS = new ConcurrentHashMap<>();
	private static final Set<String> UNLIMITED_PATHS = new HashSet<>(), IGNORED_PATHS = new HashSet<>();
	private static int globalReqSec = DEF_REQ_SEC, globalReqMin = DEF_REQ_MIN;
	private static long globalBlockSec = DEF_BLOCK_SEC;
	private static boolean rateLimitingEnabled = true;

	// ==================== CACHES ====================
	private static final Map<String, SlidingWindowCounter> SECOND_COUNTERS = new ConcurrentHashMap<>();
	private static final Map<String, SlidingWindowCounter> MINUTE_COUNTERS = new ConcurrentHashMap<>();
	private static final Map<String, Queue<Long>> BURST_TRACKER = new ConcurrentHashMap<>();
	private static final Map<String, BlockInfo> BLOCKED_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, AtomicInteger> VIOLATION_CACHE = new ConcurrentHashMap<>();

	// ==================== HEADERS DE SEGURANÇA ====================
	private static final Map<String, String> SECURITY_HEADERS = new HashMap<>();
	static {
		SECURITY_HEADERS.put("X-Frame-Options", "DENY");
		SECURITY_HEADERS.put("X-Content-Type-Options", "nosniff");
		SECURITY_HEADERS.put("X-XSS-Protection", "1; mode=block");
		SECURITY_HEADERS.put("Referrer-Policy", "strict-origin-when-cross-origin");
		SECURITY_HEADERS.put("Content-Security-Policy",
		        "default-src 'self'; " +
		        "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
		        "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
		        "img-src 'self' data: blob:; " +
		        "font-src 'self' https://cdn.jsdelivr.net; " +
		        "connect-src 'self';"
		);
		SECURITY_HEADERS.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
	}

	private static Javalin javalinInstance;
	private static boolean initialized = false;

	private JavalinAPI() {
	}

	// ==================== MÉTODOS PÚBLICOS ====================

	/** Configura limite de taxa para um path específico */
	public static void configureRateLimit(String pathPattern, RateLimitConfig config) {
		RATE_LIMIT_CONFIGS.put(pathPattern, config);
		new RouteRateLimitConfig(pathPattern, config.requestsPerSecond, config.requestsPerMinute, config.blockSeconds,
				config.perIp).save();
		Console.log("Rate limit configurado para %s: %d req/seg, %d req/min", pathPattern, config.requestsPerSecond,
				config.requestsPerMinute);
	}

	/** Configura limite padrão para APIs (3 req/seg, 20 req/min) */
	public static void configureApiRateLimit(String pathPattern) {
		configureRateLimit(pathPattern, new RateLimitConfig(3, 20, 120));
	}

	/** Configura limite restritivo para login (1 req/seg, 5 req/min) */
	public static void configureLoginRateLimit(String pathPattern) {
		configureRateLimit(pathPattern, new RateLimitConfig(1, 5, 900));
	}

	/** Adiciona path sem limite de taxa */
	public static void addUnlimitedPath(String pathPattern) {
		UNLIMITED_PATHS.add(pathPattern);
		Console.log("Path sem limite: %s", pathPattern);
	}

	/** Configura limite global (fallback) */
	public static void setGlobalRateLimit(int reqSec, int reqMin, long blockSec) {
		globalReqSec = reqSec;
		globalReqMin = reqMin;
		globalBlockSec = blockSec;
	}

	/** Habilita/desabilita rate limiting */
	public static void setRateLimitingEnabled(boolean enabled) {
		rateLimitingEnabled = enabled;
	}

	/** Adiciona path ignorado (sem verificação) */
	public static void addIgnoredPath(String path) {
		IGNORED_PATHS.add(path);
	}

	/** Remove bloqueio permanente de um IP */
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

	/** Retorna lista de bloqueios permanentes ativos */
	public static List<PermanentBlock> getActivePermanentBlocks() {
		List<PermanentBlock> active = new ArrayList<>();
		for (PermanentBlock block : Saveable.findAll(PermanentBlock.class))
			if (!block.isExpired())
				active.add(block);
		return active;
	}

	/** Limpa dados antigos do banco */
	public static void cleanupOldData() {
		long cutoff = Instant.now().getEpochSecond() - (30L * 24 * 60 * 60);
		for (PermanentBlock block : Saveable.findAll(PermanentBlock.class))
			if (block.isExpired())
				block.delete();
		for (SuspectIp suspect : Saveable.findAll(SuspectIp.class))
			if (suspect.getLastViolationAt() < cutoff && !suspect.isPermanentlyBlocked())
				suspect.delete();
	}

	// ==================== SETUP PRINCIPAL ====================

	/**
	 * Inicializa o servidor Javalin com todas as configurações de segurança
	 * 
	 * @param folderCerts      Pasta dos certificados SSL
	 * @param port             Porta do servidor
	 * @param localhost        Modo localhost (sem SSL)
	 * @param enableMaxRequest Habilita rate limiting
	 * @return Instância do Javalin
	 */
	public static Javalin setup(File folderCerts, int port, boolean localhost, boolean enableMaxRequest) {
		if (initialized)
			return javalinInstance;

		rateLimitingEnabled = enableMaxRequest;
		loadPersistedConfigs();
		Task.runTimerWithFixedDelay(JavalinAPI::cleanupOldData, 0, 24 * 60 * 60 * 1000L);

		try {
			Javalin javalin = Javalin.create(config -> {
				config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
				config.staticFiles.add(sf -> {
					sf.hostedPath = "/";
					sf.directory = "/public";
					sf.location = Location.CLASSPATH;
				});
				if (!localhost) {
					SslPlugin plugin = new SslPlugin(conf -> {
						conf.pemFromPath(folderCerts + "/fullchain.pem", folderCerts + "/privkey.pem");
						conf.secure = true;
						conf.securePort = port;
						conf.insecurePort = port + 1;
					});
					config.registerPlugin(plugin);
				}
				config.http.maxRequestSize = 200L * 1024L * 1024L;
			}).start(localhost ? 80 : port);

			javalinInstance = javalin;

			// Handler principal de segurança
			javalin.unsafe.routes.before(ctx -> {
				String path = ctx.path();
				if (path.toLowerCase().endsWith(".html")) {
					ctx.redirect(path.replace(".html", ""));
					return;
				}

				if (AngatuLib.getInstance().getOriginHost() == null)
					AngatuLib.getInstance().setOriginHost(ctx.scheme() + "://" + ctx.host());

				SECURITY_HEADERS.forEach(ctx::header);

				if (hasMaliciousInput(ctx)) {
					registerViolation(getClientIp(ctx));
					ctx.status(StatusCode.FORBIDDEN.code()).result("Acesso negado");
					return;
				}

				if (rateLimitingEnabled && !shouldIgnorePath(path)) {
					String ipHash = hashIp(getClientIp(ctx));

					if (isPermanentlyBlocked(ipHash)) {
						sendPermanentBlockPage(ctx);
						return;
					}
					if (isUnlimitedPath(path))
						return;

					RateLimitConfig cfg = getRateLimitConfig(path);
					String key = buildKey(ipHash, path, cfg);

					if (isBlocked(key)) {
						long remaining = BLOCKED_CACHE.get(key).getUnblockTime() - Instant.now().getEpochSecond();
						sendBlockPage(ctx, remaining);
						return;
					}

					if (cfg.isPerIp() && isBurstAttack(ipHash)) {
						registerViolation(ipHash);
						blockIp(key, path, HEAVY_BLOCK_SEC);
						sendBlockPage(ctx, HEAVY_BLOCK_SEC);
						return;
					}

					if (!checkAndRecordRequest(key, cfg)) {
						int violations = registerViolation(ipHash);
						if (violations >= PERM_BLOCK_THRESHOLD) {
							createPermanentBlock(ipHash, violations);
							sendPermanentBlockPage(ctx);
						} else {
							blockIp(key, path, cfg.getBlockSeconds());
							sendBlockPage(ctx, cfg.getBlockSeconds());
						}
					}
				}
			});

			registerAllRoutes();
			initialized = true;
			return javalin;

		} catch (Exception e) {
			Console.error("Falha ao iniciar Javalin", e);
			return null;
		}
	}

	// ==================== MÉTODOS DE PERSISTÊNCIA ====================

	private static void loadPersistedConfigs() {
		try {
			for (RouteRateLimitConfig c : Saveable.findAll(RouteRateLimitConfig.class))
				if (c.isEnabled())
					RATE_LIMIT_CONFIGS.put(c.getPathPattern(), new RateLimitConfig(c.getRequestsPerSecond(),
							c.getRequestsPerMinute(), c.getBlockSeconds(), c.isPerIp()));

			for (PermanentBlock b : Saveable.findAll(PermanentBlock.class))
				if (!b.isExpired())
					BLOCKED_CACHE.put(b.getIpHash(), new BlockInfo(b.getExpiresAt(), "Permanent"));

			for (SuspectIp s : Saveable.findAll(SuspectIp.class)) {
				VIOLATION_CACHE.put(s.getIpHash(), new AtomicInteger(s.getTotalViolations()));
				if (s.isPermanentlyBlocked())
					BLOCKED_CACHE.put(s.getIpHash(), new BlockInfo(Long.MAX_VALUE, "Permanent"));
			}
		} catch (Exception e) {
			Console.error("Erro ao carregar configurações", e);
		}
	}

	private static void createPermanentBlock(String ipHash, int violations) {
		new PermanentBlock(ipHash, String.format("Bloqueio após %d violações", violations), violations, "System")
				.save();
		BLOCKED_CACHE.put(ipHash, new BlockInfo(Long.MAX_VALUE, "Permanent"));

		List<SuspectIp> suspects = Saveable.query(SuspectIp.class,
				"SELECT data FROM suspectips WHERE json_extract(data, '$.ipHash') = ?", ipHash);
		SuspectIp suspect = suspects.isEmpty() ? new SuspectIp(ipHash) : suspects.get(0);
		suspect.setPermanentlyBlocked(true);
		suspect.save();
	}

	private static boolean isPermanentlyBlocked(String ipHash) {
		BlockInfo block = BLOCKED_CACHE.get(ipHash);
		if (block != null && block.getUnblockTime() == Long.MAX_VALUE) {
			List<PermanentBlock> blocks = Saveable.query(PermanentBlock.class,
					"SELECT data FROM permanentblocks WHERE json_extract(data, '$.ipHash') = ? AND json_extract(data, '$.expiresAt') > ?",
					ipHash, Instant.now().getEpochSecond());
			return !blocks.isEmpty();
		}
		return false;
	}

	// ==================== MÉTODOS DE SEGURANÇA ====================

	private static String hashIp(String ip) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(ip.getBytes());
			StringBuilder hex = new StringBuilder();
			for (byte b : hash)
				hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (Exception e) {
			return ip.replaceAll("[^a-zA-Z0-9]", "");
		}
	}

	private static boolean hasMaliciousInput(Context ctx) {
		for (List<String> params : ctx.queryParamMap().values())
			for (String p : params)
				if (containsMaliciousPattern(p))
					return true;

		if (Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH).contains(ctx.method()))
			if (ctx.body() != null && containsMaliciousPattern(ctx.body()))
				return true;

		for (String h : ctx.headerMap().values())
			if (containsMaliciousPattern(h))
				return true;

		return false;
	}

	private static boolean containsMaliciousPattern(String input) {
		if (input == null)
			return false;
		String lower = input.toLowerCase();
		String[] patterns = { "select.*from", "insert.*into", "update.*set", "delete.*from", "drop.*table",
				"union.*select", "exec\\(", "execute\\(", "<script", "javascript:", "onload=", "eval\\(", "alert\\(" };
		for (String p : patterns)
			if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(lower).find() || lower.contains(p))
				return true;
		return false;
	}

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

	private static boolean isBurstAttack(String ipHash) {
		long now = System.currentTimeMillis();
		Queue<Long> timestamps = BURST_TRACKER.computeIfAbsent(ipHash, k -> new LinkedList<>());
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && timestamps.peek() < now - 1000)
				timestamps.poll();
			timestamps.add(now);
			return timestamps.size() > 10;
		}
	}

	// ==================== RATE LIMIT ====================

	private static RateLimitConfig getRateLimitConfig(String path) {
		if (RATE_LIMIT_CONFIGS.containsKey(path))
			return RATE_LIMIT_CONFIGS.get(path);
		for (Map.Entry<String, RateLimitConfig> e : RATE_LIMIT_CONFIGS.entrySet())
			if (matchesPathPattern(path, e.getKey()))
				return e.getValue();
		return new RateLimitConfig(globalReqSec, globalReqMin, globalBlockSec);
	}

	private static boolean matchesPathPattern(String path, String pattern) {
		if (pattern.endsWith("/*"))
			return path.startsWith(pattern.substring(0, pattern.length() - 2));
		return Pattern.matches(pattern.replaceAll("\\{[^}]+\\}", "[^/]+"), path);
	}

	private static boolean isUnlimitedPath(String path) {
		return UNLIMITED_PATHS.stream().anyMatch(p -> matchesPathPattern(path, p));
	}

	private static boolean shouldIgnorePath(String path) {
		return IGNORED_PATHS.stream().anyMatch(p -> path.toLowerCase().startsWith(p.toLowerCase()));
	}

	private static String getClientIp(Context ctx) {
		for (String h : new String[] { "X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP" }) {
			String v = ctx.header(h);
			if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v))
				return v.split(",")[0].trim();
		}
		return ctx.ip();
	}

	private static String buildKey(String ipHash, String path, RateLimitConfig cfg) {
		if (!cfg.isPerIp())
			return path;
		String cat = path.startsWith("/assets") || path.startsWith("/public") ? "/static"
				: path.startsWith("/api") ? "/api" : path;
		return ipHash + "|" + cat;
	}

	private static boolean isBlocked(String key) {
		BlockInfo info = BLOCKED_CACHE.get(key);
		if (info == null)
			return false;
		if (Instant.now().getEpochSecond() >= info.getUnblockTime()) {
			BLOCKED_CACHE.remove(key);
			return false;
		}
		return true;
	}

	private static boolean checkAndRecordRequest(String key, RateLimitConfig cfg) {
		long now = Instant.now().getEpochSecond();
		SlidingWindowCounter sec = SECOND_COUNTERS.computeIfAbsent(key, k -> new SlidingWindowCounter(1));
		if (!sec.checkAndIncrement(cfg.getRequestsPerSecond(), now))
			return false;
		SlidingWindowCounter min = MINUTE_COUNTERS.computeIfAbsent(key, k -> new SlidingWindowCounter(60));
		return min.checkAndIncrement(cfg.getRequestsPerMinute(), now);
	}

	private static void blockIp(String key, String path, long seconds) {
		long unblockAt = Instant.now().getEpochSecond() + seconds;
		BLOCKED_CACHE.put(key, new BlockInfo(unblockAt, path));
		SECOND_COUNTERS.remove(key);
		MINUTE_COUNTERS.remove(key);
		Task.runLater(() -> {
			BlockInfo info = BLOCKED_CACHE.get(key);
			if (info != null && info.getUnblockTime() == unblockAt)
				BLOCKED_CACHE.remove(key);
		}, seconds * 1000L);
	}

	// ==================== PÁGINAS DE BLOQUEIO ====================

	private static void sendBlockPage(Context ctx, long seconds) {
		ctx.html(String.format(
				"""
						<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Bloqueado</title>
						<link href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.3/dist/tailwind.min.css" rel="stylesheet">
						</head><body class="bg-gray-100 flex items-center justify-center min-h-screen">
						<div class="bg-white rounded-lg p-8 text-center"><h1 class="text-2xl font-bold text-red-600 mb-4">Acesso Bloqueado</h1>
						<p class="text-gray-600">Muitas requisições. Aguarde %d segundos.</p></div></body></html>""",
				seconds)).status(StatusCode.TOO_MANY_REQUESTS.code());
	}

	private static void sendPermanentBlockPage(Context ctx) {
		ctx.html(
				"""
						<!DOCTYPE html><html><head><meta charset="UTF-8"><title>Bloqueio Permanente</title>
						<link href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.3/dist/tailwind.min.css" rel="stylesheet">
						</head><body class="bg-gray-100 flex items-center justify-center min-h-screen">
						<div class="bg-white rounded-lg p-8 text-center"><h1 class="text-2xl font-bold text-red-700 mb-4">Acesso Permanentemente Bloqueado</h1>
						<p class="text-gray-600">Contate o suporte.</p></div></body></html>""")
				.status(StatusCode.FORBIDDEN.code());
	}

	// ==================== MÉTODOS AUXILIARES ====================

	private static void registerAllRoutes() {
		Reflections reflections = new Reflections(new org.reflections.util.ConfigurationBuilder()
				.setUrls(org.reflections.util.ClasspathHelper.forJavaClassPath()).setScanners(Scanners.SubTypes));
		for (Class<? extends Route> rc : reflections.getSubTypesOf(Route.class)) {
			try {
				if (!java.lang.reflect.Modifier.isAbstract(rc.getModifiers()) && !rc.isInterface())
					rc.getDeclaredConstructor().newInstance().register();
			} catch (Exception e) {
				Console.error("Erro ao carregar rota: %s", e, rc.getName());
			}
		}
	}

	/** Retorna instância do Javalin */
	public static Javalin get() {
		return javalinInstance;
	}
}