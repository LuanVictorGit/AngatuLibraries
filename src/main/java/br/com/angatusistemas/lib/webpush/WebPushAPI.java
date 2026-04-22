package br.com.angatusistemas.lib.webpush;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.task.Task;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;

public final class WebPushAPI {

	// ==================== CONSTANTES ====================

	private static final int DEFAULT_TTL = 3600;
	private static final Urgency DEFAULT_URGENCY = Urgency.NORMAL;

	// Nome da curva elíptica usada pelo protocolo Web Push (VAPID)
	private static final String EC_CURVE = "prime256v1"; // alias: secp256r1 / P-256

	static {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
			Console.debug("BouncyCastleProvider registrado com sucesso");
		}
	}

	private static PushService pushService;
	private static boolean initialized = false;
	private static String vapidPublicKey;
	private static String vapidPrivateKey;

	private WebPushAPI() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== INICIALIZAÇÃO ====================

	public static synchronized boolean initialize() {
		if (initialized) return true;

		try {
			Key key = Saveable.findById(Key.class, "key");

			String pubKey  = key.getPublicKey();
			String privKey = key.getPrivateKey();
			String subject = PushBootstrap.DEFAULT_SUBJECT;

			if (isBlank(pubKey) || isBlank(privKey) || isBlank(subject)) {
				Console.warn("Chaves VAPID não configuradas. Use WebPushAPI.generateVapidKeys() para criá-las.");
				return false;
			}

			return initializeInternal(pubKey, privKey, subject);
		} catch (Exception e) {
			Console.error("Falha ao inicializar WebPushAPI: {}", e);
			return false;
		}
	}

	public static synchronized boolean initialize(String publicKey, String privateKey, String subject) {
		return initializeInternal(publicKey, privateKey, subject);
	}

	private static synchronized boolean initializeInternal(String publicKey, String privateKey, String subject) {
		try {
			publicKey  = cleanBase64Key(publicKey);
			privateKey = cleanBase64Key(privateKey);

			if (!isValidBase64Url(publicKey) || !isValidBase64Url(privateKey)) {
				Console.error("Chaves VAPID em formato inválido (Base64URL). Gere novas chaves com WebPushAPI.generateVapidKeys().");
				return false;
			}

			// Validar tamanho da chave pública: deve ser 65 bytes (04 || X || Y) → 87 chars Base64URL sem padding
			byte[] pubBytes = Base64.getUrlDecoder().decode(padBase64(publicKey));
			if (pubBytes.length != 65 || pubBytes[0] != 0x04) {
				Console.error("Chave pública VAPID inválida: esperado 65 bytes no formato uncompressed (04|X|Y), recebido {} bytes.", pubBytes.length);
				return false;
			}

			// Validar tamanho da chave privada: deve ser 32 bytes → 43 chars Base64URL sem padding
			byte[] privBytes = Base64.getUrlDecoder().decode(padBase64(privateKey));
			if (privBytes.length != 32) {
				Console.error("Chave privada VAPID inválida: esperado 32 bytes, recebido {} bytes.", privBytes.length);
				return false;
			}

			// A biblioteca nl.martijndwars:web-push:5.1.2 aceita as chaves como strings Base64URL
			// e internamente usa BouncyCastle para reconstruir os objetos ECPublicKey / ECPrivateKey.
			pushService = new PushService(publicKey, privateKey, subject);

			vapidPublicKey  = publicKey;
			vapidPrivateKey = privateKey;
			initialized     = true;

			Console.log("WebPushAPI inicializado com sucesso. Subject: {}", subject);
			Console.debug("Public Key (primeiros 20 chars): {}",
					vapidPublicKey.substring(0, Math.min(20, vapidPublicKey.length())));

			return true;
		} catch (Exception e) {
			Console.error("Falha ao inicializar WebPushAPI: {}", e);
			initialized = false;
			pushService  = null;
			return false;
		}
	}

	// ==================== RESET / STATUS ====================

	public static synchronized void reset() {
		pushService     = null;
		vapidPublicKey  = null;
		vapidPrivateKey = null;
		initialized     = false;
		Console.debug("WebPushAPI resetado");
	}

	public static boolean isInitialized() {
		return initialized;
	}

	public static String getVapidPublicKey() {
		return vapidPublicKey;
	}

	// ==================== GERAÇÃO DE CHAVES VAPID ====================

	/**
	 * Gera um par de chaves VAPID no formato correto para uso com Web Push.
	 *
	 * <p>A chave pública é codificada em Base64URL <b>sem padding</b> no formato
	 * uncompressed da curva P-256: {@code 0x04 || X (32 bytes) || Y (32 bytes)} = 65 bytes → 87 chars.</p>
	 *
	 * <p>A chave privada é o escalar S da curva, 32 bytes → 43 chars Base64URL sem padding.</p>
	 */
	public static VapidKeys generateVapidKeys() {
		try {
			// 1. Gerar par de chaves EC com BouncyCastle (garante compatibilidade com a lib web-push)
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			kpg.initialize(new ECGenParameterSpec(EC_CURVE));
			KeyPair kp = kpg.generateKeyPair();

			// 2. Montar chave pública no formato uncompressed: 0x04 || X (32) || Y (32)
			ECPublicKey pub    = (ECPublicKey) kp.getPublic();
			ECPoint     point  = pub.getW();
			byte[]      x      = toExact32Bytes(point.getAffineX().toByteArray());
			byte[]      y      = toExact32Bytes(point.getAffineY().toByteArray());

			byte[] pubBytes = new byte[65];
			pubBytes[0] = 0x04;
			System.arraycopy(x, 0, pubBytes, 1,  32);
			System.arraycopy(y, 0, pubBytes, 33, 32);

			// 3. Extrair escalar privado S em exatamente 32 bytes
			ECPrivateKey priv      = (ECPrivateKey) kp.getPrivate();
			byte[]       privBytes = toExact32Bytes(priv.getS().toByteArray());

			// 4. Codificar em Base64URL SEM padding (obrigatório para VAPID)
			Base64.Encoder enc     = Base64.getUrlEncoder().withoutPadding();
			String         pubKey  = enc.encodeToString(pubBytes);  // deve ter 87 chars
			String         privKey = enc.encodeToString(privBytes); // deve ter 43 chars

			Console.debug("Chaves VAPID geradas — pub.length={}, priv.length={}", pubKey.length(), privKey.length());
			validateGeneratedKeys(pubBytes, privBytes); // log de sanidade

			return new VapidKeys(pubKey, privKey);

		} catch (Exception e) {
			throw new RuntimeException("Erro ao gerar chaves VAPID", e);
		}
	}

	/**
	 * Ajusta um array de bytes para exatamente 32 bytes.
	 * <ul>
	 *   <li>BigInteger.toByteArray() pode retornar 33 bytes com 0x00 de sign-bit no início.</li>
	 *   <li>Valores pequenos podem ter menos de 32 bytes e precisam de zero-padding à esquerda.</li>
	 * </ul>
	 */
	private static byte[] toExact32Bytes(byte[] src) {
		byte[] dst = new byte[32];
		if (src.length == 32) {
			return src;
		} else if (src.length > 32) {
			// Remove bytes extras de sign (ex.: 33 bytes com 0x00 no início)
			System.arraycopy(src, src.length - 32, dst, 0, 32);
		} else {
			// Menos de 32 bytes → zero-pad à esquerda
			System.arraycopy(src, 0, dst, 32 - src.length, src.length);
		}
		return dst;
	}

	private static void validateGeneratedKeys(byte[] pubBytes, byte[] privBytes) {
		if (pubBytes.length != 65 || pubBytes[0] != 0x04) {
			Console.warn("AVISO: chave pública gerada não tem 65 bytes ou não começa com 0x04!");
		}
		if (privBytes.length != 32) {
			Console.warn("AVISO: chave privada gerada não tem 32 bytes!");
		}
	}

	// ==================== DIAGNÓSTICO ====================

	public static boolean testConfiguration() {
		if (!initialized) {
			Console.error("WebPushAPI não está inicializado");
			return false;
		}

		Console.log("=== TESTE DE CONFIGURAÇÃO WebPushAPI ===");
		Console.log("Initialized: {}", initialized);
		Console.log("PushService: {}", pushService != null ? "OK" : "NULL");

		if (vapidPublicKey != null) {
			Console.log("Public Key length (chars): {}", vapidPublicKey.length()); // esperado: 87
			byte[] pub = Base64.getUrlDecoder().decode(padBase64(vapidPublicKey));
			Console.log("Public Key length (bytes): {}", pub.length);              // esperado: 65
			Console.log("Public Key starts with 0x04: {}", pub.length > 0 && pub[0] == 0x04);
		}

		if (vapidPrivateKey != null) {
			Console.log("Private Key length (chars): {}", vapidPrivateKey.length()); // esperado: 43
			byte[] priv = Base64.getUrlDecoder().decode(padBase64(vapidPrivateKey));
			Console.log("Private Key length (bytes): {}", priv.length);              // esperado: 32
		}

		Console.log("=========================================");
		return vapidPublicKey != null && vapidPrivateKey != null && pushService != null;
	}

	// ==================== ENVIO DE NOTIFICAÇÕES ====================

	public static void sendNotification(Subscription subscription, String title, String body, String iconUrl) {
		sendNotification(subscription, title, body, iconUrl, null, null);
	}

	public static void sendNotification(Subscription subscription, String title, String body, String iconUrl,
			String clickUrl, Map<String, Object> extraData) {
		checkInitialized();
		String payload = buildPayload(title, body, iconUrl, clickUrl, extraData);
		sendRawNotificationAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY, null);
	}

	public static CompletableFuture<SendResult> sendNotificationAsync(Subscription subscription, String title,
			String body, String iconUrl) {
		checkInitialized();
		String payload = buildPayload(title, body, iconUrl, null, null);
		return doSendAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY);
	}

	public static void sendRawNotification(Subscription subscription, String jsonPayload) {
		sendRawNotificationAsync(subscription, jsonPayload, DEFAULT_TTL, DEFAULT_URGENCY, null);
	}

	public static void sendRawNotificationAsync(Subscription subscription, String jsonPayload, int ttl,
			Urgency urgency, Consumer<SendResult> onResult) {
		checkInitialized();
		CompletableFuture<SendResult> future = doSendAsync(subscription, jsonPayload, ttl, urgency);
		if (onResult != null) {
			future.thenAccept(onResult);
		}
	}

	public static List<CompletableFuture<SendResult>> sendBatchNotifications(List<Subscription> subscriptions,
			String title, String body, String iconUrl) {
		checkInitialized();
		String payload = buildPayload(title, body, iconUrl, null, null);
		List<CompletableFuture<SendResult>> futures = new ArrayList<>(subscriptions.size());
		for (Subscription subscription : subscriptions) {
			futures.add(doSendAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY));
		}
		return futures;
	}

	// ==================== MÉTODOS DE ASSINATURA ====================

	public static Subscription createSubscription(String endpoint, String p256dh, String auth) {
		Objects.requireNonNull(endpoint, "endpoint não pode ser null");
		Objects.requireNonNull(p256dh,   "p256dh não pode ser null");
		Objects.requireNonNull(auth,     "auth não pode ser null");
		return new Subscription(endpoint, new Subscription.Keys(p256dh, auth));
	}

	public static Subscription parseSubscriptionFromJson(String json) {
		JsonObject obj      = JsonParser.parseString(json).getAsJsonObject();
		String     endpoint = obj.get("endpoint").getAsString();
		JsonObject keys     = obj.getAsJsonObject("keys");
		String     p256dh   = keys.get("p256dh").getAsString();
		String     auth     = keys.get("auth").getAsString();
		return createSubscription(endpoint, p256dh, auth);
	}

	public static String subscriptionToJson(Subscription subscription) {
		JsonObject keys = new JsonObject();
		keys.addProperty("p256dh", subscription.keys.p256dh);
		keys.addProperty("auth",   subscription.keys.auth);
		JsonObject obj = new JsonObject();
		obj.addProperty("endpoint", subscription.endpoint);
		obj.add("keys", keys);
		return GsonAPI.get().toJson(obj);
	}

	// ==================== IMPLEMENTAÇÃO INTERNA ====================

	private static CompletableFuture<SendResult> doSendAsync(Subscription subscription, String payload,
			int ttl, Urgency urgency) {

		CompletableFuture<SendResult> future = new CompletableFuture<>();

		Task.runAsync(() -> {
			try {
				Console.debug("Enviando notificação para endpoint: {}", truncateEndpoint(subscription.endpoint));

				// Criar Notification — o construtor com TTL correto na versão 5.1.2
				Notification notification = new Notification(
						subscription.endpoint,
						subscription.keys.p256dh,
						subscription.keys.auth,
						payload.getBytes(StandardCharsets.UTF_8),
						ttl
				);

				HttpResponse response   = pushService.send(notification);
				int          statusCode = response.getStatusLine().getStatusCode();
				String       reason     = response.getStatusLine().getReasonPhrase();

				// Ler body da resposta
				String responseBody = "";
				if (response.getEntity() != null) {
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
						responseBody = reader.lines().collect(Collectors.joining("\n"));
					}
				}

				// Header Retry-After (presente em 429 / 503)
				String retryAfter = null;
				if (response.getFirstHeader("Retry-After") != null) {
					retryAfter = response.getFirstHeader("Retry-After").getValue();
				}

				String baseLog = "Status=" + statusCode
						+ " | Reason=" + reason
						+ " | Body=" + responseBody
						+ " | Retry-After=" + retryAfter;

				if (statusCode >= 200 && statusCode < 300) {
					Console.debug("Notificação enviada com sucesso | {} | Endpoint: {}",
							baseLog, truncateEndpoint(subscription.endpoint));
					future.complete(SendResult.success(statusCode));

				} else if (statusCode == 410 || statusCode == 404) {
					// Assinatura expirada / inválida — deve ser removida do banco
					String msg = "Assinatura inválida/expirada (HTTP " + statusCode + ")";
					Console.warn("{} | {} | Subscription: {}", msg, baseLog, GsonAPI.get().toJson(subscription));
					future.complete(SendResult.expired(statusCode, msg + " | " + reason));

				} else if (statusCode == 403) {
					// Autenticação VAPID rejeitada pelo push service
					String msg = "Erro de autenticação VAPID (HTTP 403)";
					Console.error("{} | {}", msg, baseLog);

					if (responseBody.contains("crypto-key")) {
						Console.error("CAUSA PROVÁVEL: formato incorreto do header Crypto-Key / Authorization VAPID.");
						Console.error("Verifique se a chave pública tem 65 bytes (87 chars Base64URL) e começa com 0x04.");
						Console.error("Chave pública atual ({} chars): {}",
								vapidPublicKey != null ? vapidPublicKey.length() : 0,
								vapidPublicKey != null ? vapidPublicKey.substring(0, Math.min(30, vapidPublicKey.length())) : "null");
					}

					future.complete(SendResult.failure(statusCode, msg + " | " + reason));

				} else if (statusCode == 429) {
					String msg = "Rate limit atingido (HTTP 429). Retry-After: " + retryAfter;
					Console.warn("{} | {}", msg, baseLog);
					future.complete(SendResult.failure(statusCode, msg));

				} else {
					String msg = "Falha ao enviar notificação (HTTP " + statusCode + ")";
					Console.error("{} | {} | Subscription: {}",
							msg, baseLog, GsonAPI.get().toJson(subscription));
					future.complete(SendResult.failure(statusCode, msg + " | " + reason));
				}

			} catch (Exception e) {
				Console.error("Exceção ao enviar notificação | Subscription: {} | Erro: {}",
						GsonAPI.get().toJson(subscription), e.toString());
				e.printStackTrace();
				future.completeExceptionally(e);
			}
		});

		return future;
	}

	// ==================== UTILITÁRIOS PRIVADOS ====================

	private static String truncateEndpoint(String endpoint) {
		if (endpoint == null) return "null";
		if (endpoint.length() <= 60) return endpoint;
		return endpoint.substring(0, 30) + "..." + endpoint.substring(endpoint.length() - 30);
	}

	private static String buildPayload(String title, String body, String iconUrl,
			String clickUrl, Map<String, Object> extraData) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("title",     title);
		payload.put("body",      body);
		payload.put("timestamp", System.currentTimeMillis());
		if (!isBlank(iconUrl))   payload.put("icon",         iconUrl);
		if (!isBlank(clickUrl))  payload.put("click_action", clickUrl);
		if (extraData != null)   payload.putAll(extraData);
		return new Gson().toJson(payload);
	}

	private static void checkInitialized() {
		if (!initialized) {
			throw new IllegalStateException("WebPushAPI não inicializado. Chame WebPushAPI.initialize() primeiro.");
		}
	}

	/** Remove padding '=' e espaços/quebras de linha que corrompem Base64URL. */
	private static String cleanBase64Key(String key) {
		if (key == null) return null;
		return key.replace("=",  "")
				  .replace("\n", "")
				  .replace("\r", "")
				  .replace(" ",  "")
				  .trim();
	}

	/** Verifica que a string contém apenas caracteres válidos de Base64URL. */
	private static boolean isValidBase64Url(String key) {
		if (key == null || key.isEmpty()) return false;
		return key.matches("^[A-Za-z0-9_-]+$");
	}

	/**
	 * Adiciona padding '=' ao final de uma string Base64URL para que
	 * {@link Base64#getUrlDecoder()} consiga decodificá-la corretamente.
	 */
	private static String padBase64(String b64) {
		int mod = b64.length() % 4;
		if (mod == 0) return b64;
		if (mod == 2) return b64 + "==";
		if (mod == 3) return b64 + "=";
		return b64; // mod == 1 → inválido, mas deixa o decoder rejeitar
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	// ==================== CLASSES DE SUPORTE ====================

	public static final class VapidKeys {
		public final String publicKey;
		public final String privateKey;

		public VapidKeys(String publicKey, String privateKey) {
			this.publicKey  = Objects.requireNonNull(publicKey,  "publicKey não pode ser null");
			this.privateKey = Objects.requireNonNull(privateKey, "privateKey não pode ser null");
		}

		@Override
		public String toString() {
			return "VapidKeys{publicKey='" + publicKey + "', privateKey='[PROTECTED]'}";
		}
	}

	public static final class SendResult {
		private final boolean success;
		private final boolean expired;
		private final int     statusCode;
		private final String  error;

		private SendResult(boolean success, boolean expired, int statusCode, String error) {
			this.success    = success;
			this.expired    = expired;
			this.statusCode = statusCode;
			this.error      = error;
		}

		static SendResult success(int statusCode)               { return new SendResult(true,  false, statusCode, null);  }
		static SendResult failure(int statusCode, String error) { return new SendResult(false, false, statusCode, error); }
		static SendResult expired(int statusCode, String error) { return new SendResult(false, true,  statusCode, error); }

		public boolean isSuccess()    { return success;    }
		public boolean isExpired()    { return expired;    }
		public int     getStatusCode(){ return statusCode; }
		public String  getError()     { return error;      }

		@Override
		public String toString() {
			return "SendResult{success=" + success
					+ ", expired=" + expired
					+ ", statusCode=" + statusCode
					+ (error != null ? ", error='" + error + "'" : "")
					+ "}";
		}
	}

	public enum Urgency {
		VERY_LOW, LOW, NORMAL, HIGH
	}
}