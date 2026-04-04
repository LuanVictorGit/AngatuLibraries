package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.HttpStatus;
import org.reflections.Reflections;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.task.Task;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.FileRenderer;

public final class JavalinAPI {

	// ==================== CONFIGURAÇÕES DE RATE LIMIT ====================
	private static final int REQUESTS_PER_WINDOW = 0; // Max 150 requisições por janela
	private static final long WINDOW_SECONDS = 1; // Janela de 60 segundos
	private static final long BLOCK_SECONDS = 300; // Bloqueio de 5 minutos

	// NOVA FLAG: se true, o limite é por IP (ignora o path). Se false, mantém por
	// IP+path.
	private static final boolean USE_GLOBAL_RATE_LIMIT = true; // MUDE PARA false SE QUISER POR PATH

	// Para agrupar paths por prefixo (ex: "/assets", "/public") - útil quando
	// USE_GLOBAL_RATE_LIMIT = false
	private static final Set<String> GROUP_PREFIXES = new HashSet<>(Arrays.asList("/assets", "/public", "/uploads"));

	// ==================== CONFIGURAÇÕES GLOBAIS ====================
	private static boolean rateLimitingEnabled = true;
	private static boolean logRequestsEnabled = true;
	private static final Set<String> IGNORED_PATHS = new HashSet<>(Arrays.asList());

	// Estruturas: chave pode ser IP (global) ou "IP|path" ou "IP|categoria"
	private static final Map<String, RequestInfo> REQUESTS = new ConcurrentHashMap<>();
	private static final Map<String, Long> BLOCKED_IPS = new ConcurrentHashMap<>();

	private static Javalin javalinInstance;

	private JavalinAPI() {
	}

	// ==================== CONFIGURAÇÃO ====================
	public static void setRateLimitingEnabled(boolean enabled) {
		rateLimitingEnabled = enabled;
		Console.log("Rate limiting " + (enabled ? "ativado" : "desativado"));
	}

	public static void setLogRequestsEnabled(boolean enabled) {
		logRequestsEnabled = enabled;
		Console.log("Log de requisições " + (enabled ? "ativado" : "desativado"));
	}

	public static void addIgnoredPath(String path) {
		IGNORED_PATHS.add(path);
		Console.log("Path ignorado adicionado: " + path);
	}

	// ==================== SETUP PRINCIPAL ====================
	public static Javalin setup(File folderCerts, int port, boolean localhost, Location locationFiles,
			String packagePath) {
		try {
			Javalin javalin = Javalin.create(config -> {
				config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
				config.staticFiles.add(staticFiles -> {
					staticFiles.hostedPath = "/";
					staticFiles.directory = "/public";
					staticFiles.location = locationFiles;
				});
				if (!localhost) {
					SslPlugin plugin = new SslPlugin(conf -> {
						conf.pemFromPath(folderCerts + "/fullchain.pem", folderCerts + "/privkey.pem");
						conf.secure = true;
						conf.host = null;
						conf.http2 = false;
						conf.securePort = port;
						conf.insecurePort = port + 1;
					});
					config.registerPlugin(plugin);

				}
				config.http.maxRequestSize = 200L * 1024L * 1024L;
			}).start(localhost ? 80 : port);

			javalinInstance = javalin;

			// Handler BEFORE - executado para TODAS as requisições (inclusive static files)
			javalin.unsafe.routes.before(ctx -> {
				// Log incondicional para debug (pode comentar depois)
				Console.log("[BEFORE] Path: %s, IP: %s", ctx.path(), ctx.ip());

				if (AngatuLib.getInstance().getOriginHost() == null) {
					AngatuLib.getInstance().setOriginHost(ctx.scheme() + "://" + ctx.host());
					Console.info("OriginHost definido: " + AngatuLib.getInstance().getOriginHost());
				}

				if (rateLimitingEnabled && !shouldIgnorePath(ctx.path())) {
					String clientIp = getClientIp(ctx);
					String key = buildKey(clientIp, ctx.path());

					if (logRequestsEnabled) {
						Console.log("[REQ] key=%s", key);
					}

					if (isBlocked(key)) {
						if (logRequestsEnabled)
							Console.log("[BLOQUEADO] %s", key);
						sendBlockPage(ctx, ctx.path());
						return;
					}

					if (!checkAndRecordRequest(key)) {
						blockIp(key, ctx.path());
						sendBlockPage(ctx, ctx.path());
						return;
					}
				}
			});

			registerAllRoutes(packagePath);

			Console.log("Javalin iniciado | RateLimit: %s, Modo: %s, Limite: %d req/%ds, Bloqueio: %ds",
					rateLimitingEnabled ? "ON" : "OFF",
					USE_GLOBAL_RATE_LIMIT ? "GLOBAL POR IP" : "POR PATH (com agrupamento)", REQUESTS_PER_WINDOW,
					WINDOW_SECONDS, BLOCK_SECONDS);
			return javalin;

		} catch (Exception e) {
			Console.error("Falha ao iniciar Javalin", e);
			return null;
		}
	}

	// ==================== NOVO: CONSTRUÇÃO DA CHAVE ====================
	private static String buildKey(String ip, String path) {
		if (USE_GLOBAL_RATE_LIMIT) {
			return ip; // Apenas IP → limite global
		}
		// Agrupamento por prefixo: /assets/imagem.png vira /assets
		String category = getPathCategory(path.toLowerCase());
		return ip + "|" + category;
	}

	private static String getPathCategory(String path) {
		for (String prefix : GROUP_PREFIXES) {
			if (path.startsWith(prefix)) {
				return prefix;
			}
		}
		return path; // sem agrupamento, usa o path completo
	}

	private static boolean shouldIgnorePath(String path) {
		String lowerPath = path.toLowerCase();
		for (String ignored : IGNORED_PATHS) {
			if (lowerPath.startsWith(ignored.toLowerCase()))
				return true;
		}
		return false;
	}

	private static String getClientIp(Context ctx) {
		String xff = ctx.header("X-Forwarded-For");
		if (xff != null && !xff.isEmpty()) {
			return xff.split(",")[0].trim();
		}
		return ctx.ip();
	}

	private static boolean isBlocked(String key) {
		Long unblockTime = BLOCKED_IPS.get(key);
		if (unblockTime == null)
			return false;
		if (Instant.now().getEpochSecond() >= unblockTime) {
			BLOCKED_IPS.remove(key);
			return false;
		}
		return true;
	}

	private static boolean checkAndRecordRequest(String key) {
		long now = Instant.now().getEpochSecond();
		RequestInfo info = REQUESTS.computeIfAbsent(key, k -> new RequestInfo());

		synchronized (info) {
			// Remove timestamps fora da janela
			info.timestamps.removeIf(ts -> ts < now - WINDOW_SECONDS);

			if (info.timestamps.size() >= REQUESTS_PER_WINDOW) {
				if (logRequestsEnabled)
					Console.log("[LIMITE EXCEDIDO] %s (%d requisições)", key, info.timestamps.size());
				return false;
			}
			info.timestamps.add(now);
			return true;
		}
	}

	private static void blockIp(String key, String path) {
		long now = Instant.now().getEpochSecond();
		long unblockAt = now + BLOCK_SECONDS;
		BLOCKED_IPS.put(key, unblockAt);
		REQUESTS.remove(key);

		Task.runLater(() -> {
			if (BLOCKED_IPS.getOrDefault(key, 0L) == unblockAt) {
				BLOCKED_IPS.remove(key);
				Console.log("Chave %s desbloqueada após %d segundos.", key, BLOCK_SECONDS);
			}
		}, BLOCK_SECONDS * 1000L);

		Console.warn("Chave %s bloqueada por %d segundos (excesso em %s)", key, BLOCK_SECONDS, path);
	}

	// ==================== PÁGINA DE BLOQUEIO (sem dependência circular)
	// ====================
	private static void sendBlockPage(Context ctx, String path) {
		String html = loadBlockPageSafe(path);
		ctx.html(html).status(StatusCode.TOO_MANY_REQUESTS.code());
	}

	private static String loadBlockPageSafe(String path) {
		String html = AssetsAPI.readAssetAsString("/utils/error/error.html");
		return html;
	}

	private static void registerAllRoutes(String packagePath) {
		Reflections reflections = new Reflections(packagePath);
		Set<Class<? extends Route>> routeClasses = reflections.getSubTypesOf(Route.class);
		for (Class<? extends Route> routeClass : routeClasses) {
			try {
				Route route = routeClass.getDeclaredConstructor().newInstance();
				route.register();
				Console.log("Rota registrada: %s", routeClass.getName());
			} catch (Exception e) {
				Console.error("Erro ao carregar rota: %s", e, routeClass.getName());
			}
		}
	}

	public static Javalin get() {
		return javalinInstance;
	}

	private static class RequestInfo {
		private final List<Long> timestamps = new ArrayList<>();
	}
}