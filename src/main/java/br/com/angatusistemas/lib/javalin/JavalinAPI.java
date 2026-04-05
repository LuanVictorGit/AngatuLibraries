package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.task.Task;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

public final class JavalinAPI {

	// ==================== CONFIGURAÇÕES DE RATE LIMIT ====================
	private static final int REQUESTS_PER_WINDOW = 10; // Max 150 requisições por janela
	private static final long WINDOW_SECONDS = 1; // Janela de 60 segundos
	private static final long BLOCK_SECONDS = 120; // Bloqueio de 5 minutos

	// NOVA FLAG: se true, o limite é por IP (ignora o path). Se false, mantém por
	// IP+path.
	private static final boolean USE_GLOBAL_RATE_LIMIT = true; // MUDE PARA false SE QUISER POR PATH

	// Para agrupar paths por prefixo (ex: "/assets", "/public") - útil quando
	// USE_GLOBAL_RATE_LIMIT = false
	private static final Set<String> GROUP_PREFIXES = new HashSet<>(Arrays.asList("/assets", "/public", "/uploads"));

	// ==================== CONFIGURAÇÕES GLOBAIS ====================
	private static boolean rateLimitingEnabled = true;
	private static boolean logRequestsEnabled = false;
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
	public static Javalin setup(File folderCerts, int port, boolean localhost, boolean enableMaxRequest) {
		rateLimitingEnabled = enableMaxRequest;
		try {
			Javalin javalin = Javalin.create(config -> {
				config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
				config.staticFiles.add(staticFiles -> {
					staticFiles.hostedPath = "/";
					staticFiles.directory = "/public";
					staticFiles.location = Location.CLASSPATH;
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

				String path = ctx.path();
				if (path.toLowerCase().endsWith(".html")) {
					ctx.redirect(path.replace(".html", new String()));
					return;
				}

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

			javalin.unsafe.routes.after(ctx -> {
				if (rateLimitingEnabled && !shouldIgnorePath(ctx.path())) {
					String clientIp = getClientIp(ctx);
					String key = buildKey(clientIp, ctx.path());

					if (isBlocked(key)) {
						sendBlockPage(ctx, ctx.path());
						return;
					}

				}
			});

			registerAllRoutes();

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
		String html = """
								<!DOCTYPE html>
				<html lang="pt-BR">
				<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
				<title>Acesso temporariamente bloqueado - Angatu Sistemas</title>
				<link href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.3/dist/tailwind.min.css" rel="stylesheet">
				<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0-beta3/css/all.min.css">
				</head>
				<body class="bg-[#f5f7fc] flex items-center justify-center min-h-screen p-6 font-inter">
				<div class="max-w-md w-full bg-white rounded-3xl shadow-lg overflow-hidden">
				  <div class="flex items-center gap-4 p-8 pb-4 border-b border-gray-200">
				    <img src="https://angatusistemas.com.br/img/iconangatu.png" class="h-12 object-contain" alt="Angatu Sistemas">
				    <div class="ml-auto bg-red-100 text-red-700 text-xs font-semibold px-3 py-1 rounded-full">Segurança</div>
				  </div>
				  <div class="p-7 pb-9">
				    <div class="font-mono font-semibold text-gray-500 text-xs mb-6 bg-gray-100 inline-block px-3 py-1 rounded-full">HTTP 429 · Muitas requisições</div>
				    <h1 class="text-2xl font-bold text-[#0a1c2f] mb-3">Acesso temporariamente bloqueado</h1>
				    <p class="text-gray-600 text-sm mb-7">
				      O sistema detectou um volume excessivo de solicitações vindas do seu endereço de rede. Por questões de segurança e estabilidade, o acesso foi bloqueado por alguns minutos.
				    </p>
				    <div class="bg-[#f8fafd] border border-gray-200 rounded-2xl p-5 mb-7">
				      <div class="flex items-center gap-2 text-gray-500 uppercase text-xs font-medium mb-3">
				        <i class="fas fa-hourglass-half text-green-700"></i> Tempo de bloqueio
				      </div>
				      <div id="countdownDisplay" class="font-mono text-3xl font-bold text-[#1a344d] bg-white inline-block px-6 py-2 rounded-full shadow border border-gray-200">2 minutos</div>
				      <div class="text-gray-500 text-[0.65rem] mt-3 flex items-center gap-1">
				        <i class="fas fa-info-circle"></i> Recarregamentos frequentes (F5) podem prolongar o bloqueio.
				      </div>
				    </div>
				    <ul class="mb-5 space-y-4 text-gray-600 text-sm">
				      <li class="flex items-center gap-3"><i class="fas fa-shield-alt text-blue-600 w-5"></i> Proteção automática contra sobrecarga do servidor</li>
				      <li class="flex items-center gap-3"><i class="fas fa-clock text-blue-600 w-5"></i> O bloqueio será removido automaticamente após o tempo indicado</li>
				      <li class="flex items-center gap-3"><i class="fas fa-envelope text-blue-600 w-5"></i> Se o problema persistir, entre em contato com o suporte técnico</li>
				    </ul>
				    <div class="text-right mb-8">
				      <button id="manualRefreshBtn" class="inline-flex items-center gap-2 px-5 py-2 text-sm font-medium text-blue-700 bg-white border border-blue-200 rounded-full hover:bg-blue-50 hover:border-blue-300 transition">
				        <i class="fas fa-sync-alt"></i> Verificar novamente
				      </button>
				    </div>
				    <div class="text-center text-gray-400 text-[0.65rem] border-t border-gray-100 pt-5">
				      <i class="far fa-copyright"></i> Angatu Sistemas · Plataforma de serviços digitais
				      <br><a href="https://angatusistemas.com.br" target="_blank" class="text-blue-700 hover:underline">angatusistemas.com.br</a>
				    </div>
				  </div>
				</div>
				</body>
				</html>
								""";
		ctx.html(html).status(StatusCode.TOO_MANY_REQUESTS.code());
	}

	private static void registerAllRoutes() {
		Reflections reflections = new Reflections(new org.reflections.util.ConfigurationBuilder()
				.setUrls(org.reflections.util.ClasspathHelper.forJavaClassPath()).setScanners(Scanners.SubTypes));

		Set<Class<? extends Route>> routeClasses = reflections.getSubTypesOf(Route.class);

		for (Class<? extends Route> routeClass : routeClasses) {
			try {
				// Evita classes abstratas ou interfaces
				if (java.lang.reflect.Modifier.isAbstract(routeClass.getModifiers()) || routeClass.isInterface()) {
					continue;
				}

				Route route = routeClass.getDeclaredConstructor().newInstance();
				route.register();

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