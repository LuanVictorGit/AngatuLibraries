package br.com.angatusistemas.lib.webpush;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.util.io.pem.PemWriter;

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

	static {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
			Console.debug("BouncyCastleProvider registrado com sucesso");
		}
	}

	private static PushService pushService;
	private static boolean initialized = false;
	private static String vapidPublicKey;

	private WebPushAPI() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== INICIALIZAÇÃO ====================

	public static synchronized boolean initialize() {
		if (initialized) return true;

		try {
			
			Key key = Saveable.findById(Key.class, "key");
			
			String pubKey = key.getPublicKey();
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

	private static boolean initializeInternal(String publicKey, String privateKey, String subject) {
		try {
			pushService = new PushService(publicKey, privateKey, subject);
			vapidPublicKey = publicKey;
			initialized = true;
			Console.log("WebPushAPI inicializado com sucesso. Subject: {}", subject);
			return true;
		} catch (Exception e) {
			Console.error("Falha ao inicializar WebPushAPI: {}", e);
			initialized = false;
			return false;
		}
	}

	public static synchronized void reset() {
		pushService = null;
		vapidPublicKey = null;
		initialized = false;
		Console.debug("WebPushAPI resetado");
	}

	public static boolean isInitialized() {
		return initialized;
	}

	public static String getVapidPublicKey() {
		return vapidPublicKey;
	}

	// ==================== GERAÇÃO DE CHAVES VAPID ====================

	public static VapidKeys generateVapidKeys() {
		try {
			KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			keyPairGen.initialize(new java.security.spec.ECGenParameterSpec("P-256"));
			KeyPair keyPair = keyPairGen.generateKeyPair();

			org.bouncycastle.jce.interfaces.ECPublicKey bcPublicKey =
				(org.bouncycastle.jce.interfaces.ECPublicKey) keyPair.getPublic();
			ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();
			byte[] publicKeyBytes = bcPublicKey.getQ().getEncoded(false);
			byte[] sBytes = privateKey.getS().toByteArray();
			byte[] privateKeyBytes = new byte[32];
			if (sBytes.length >= 32) {
				System.arraycopy(sBytes, sBytes.length - 32, privateKeyBytes, 0, 32);
			} else {
				System.arraycopy(sBytes, 0, privateKeyBytes, 32 - sBytes.length, sBytes.length);
			}
			
			String publicKeyBase64  = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes);
			String privateKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKeyBytes);
			if (publicKeyBase64.length() < 80 || privateKeyBase64.length() < 40) {
				Console.error("Chaves VAPID geradas com tamanho inválido. pub={} priv={}",
					publicKeyBase64.length(), privateKeyBase64.length());
				return null;
			}

			Console.log("Chaves VAPID geradas. pub={} chars, priv={} chars",
				publicKeyBase64.length(), privateKeyBase64.length());

			return new VapidKeys(publicKeyBase64, privateKeyBase64);
		} catch (Exception e) {
			Console.error("Falha ao gerar chaves VAPID: {}", e);
			return null;
		}
	}

	public static VapidKeys loadVapidKeysFromFile(String filePath) throws IOException {
		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new IOException("Arquivo não encontrado: " + filePath);
		}

		String content = Files.readString(path, StandardCharsets.UTF_8);
		String publicKey = null;
		String privateKey = null;

		try (PemReader pemReader = new PemReader(new StringReader(content))) {
			PemObject pemObject;
			while ((pemObject = pemReader.readPemObject()) != null) {
				switch (pemObject.getType()) {
				case "PUBLIC KEY":
					publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pemObject.getContent());
					break;
				case "PRIVATE KEY":
					privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pemObject.getContent());
					break;
				default:
					Console.warn("Tipo PEM desconhecido ignorado: {}", pemObject.getType());
				}
			}
		}

		if (publicKey == null || privateKey == null) {
			throw new IOException("Arquivo PEM não contém par de chaves válido (PUBLIC KEY + PRIVATE KEY)");
		}

		return new VapidKeys(publicKey, privateKey);
	}

	public static void saveVapidKeys(VapidKeys keys, String filePath) throws IOException {
		Objects.requireNonNull(keys, "VapidKeys não pode ser null");
		try (PemWriter pemWriter = new PemWriter(new FileWriter(filePath, StandardCharsets.UTF_8))) {
			pemWriter.writeObject(new PemObject("PUBLIC KEY", Base64.getUrlDecoder().decode(keys.publicKey)));
			pemWriter.writeObject(new PemObject("PRIVATE KEY", Base64.getUrlDecoder().decode(keys.privateKey)));
		}
		Console.debug("Chaves VAPID salvas em: {}", filePath);
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

	public static void sendRawNotificationAsync(Subscription subscription, String jsonPayload, int ttl, Urgency urgency,
			Consumer<SendResult> onResult) {
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
		Objects.requireNonNull(p256dh, "p256dh não pode ser null");
		Objects.requireNonNull(auth, "auth não pode ser null");
		return new Subscription(endpoint, new Subscription.Keys(p256dh, auth));
	}

	public static Subscription parseSubscriptionFromJson(String json) {
		JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
		String endpoint = obj.get("endpoint").getAsString();
		JsonObject keys = obj.getAsJsonObject("keys");
		String p256dh = keys.get("p256dh").getAsString();
		String auth = keys.get("auth").getAsString();
		return createSubscription(endpoint, p256dh, auth);
	}

	public static String subscriptionToJson(Subscription subscription) {
		JsonObject keys = new JsonObject();
		keys.addProperty("p256dh", subscription.keys.p256dh);
		keys.addProperty("auth", subscription.keys.auth);
		JsonObject obj = new JsonObject();
		obj.addProperty("endpoint", subscription.endpoint);
		obj.add("keys", keys);
		return GsonAPI.get().toJson(obj);
	}

	// ==================== IMPLEMENTAÇÃO INTERNA ====================

	private static CompletableFuture<SendResult> doSendAsync(Subscription subscription, String payload, int ttl,
			Urgency urgency) {
		CompletableFuture<SendResult> future = new CompletableFuture<>();

		Task.runAsync(() -> {
			try {
				Notification notification = new Notification(
						subscription.endpoint,
						subscription.keys.p256dh,
						subscription.keys.auth,
						payload.getBytes(StandardCharsets.UTF_8),
						ttl);

				HttpResponse response = pushService.send(notification);
				int statusCode = response.getStatusLine().getStatusCode();

				if (statusCode >= 200 && statusCode < 300) {
					Console.debug("Notificação enviada. Status: {} | Endpoint: {}", statusCode, subscription.endpoint);
					future.complete(SendResult.success(statusCode));
				} else if (statusCode == 410 || statusCode == 404) {
					String msg = "Assinatura inválida/expirada (HTTP " + statusCode + "). Remova do banco.";
					Console.warn(msg + " Endpoint: {}", subscription.endpoint);
					future.complete(SendResult.expired(statusCode, msg));
				} else {
					String msg = "Falha ao enviar notificação. HTTP " + statusCode;
					Console.error(msg + " | Endpoint: {}", subscription.endpoint);
					future.complete(SendResult.failure(statusCode, msg));
				}
			} catch (Exception e) {
				Console.error("Exceção ao enviar notificação para " + GsonAPI.get().toJson(subscription) + " -> "
						+ e.getMessage());
				future.completeExceptionally(e);
			}
		});

		return future;
	}

	private static String buildPayload(String title, String body, String iconUrl, String clickUrl,
			Map<String, Object> extraData) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("title", title);
		payload.put("body", body);
		payload.put("timestamp", System.currentTimeMillis());

		if (!isBlank(iconUrl))
			payload.put("icon", iconUrl);
		if (!isBlank(clickUrl))
			payload.put("click_action", clickUrl);
		if (extraData != null)
			payload.putAll(extraData);

		return new Gson().toJson(payload);
	}

	private static void checkInitialized() {
		if (!initialized) {
			throw new IllegalStateException("WebPushAPI não inicializado. Chame WebPushAPI.initialize() primeiro.");
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	// ==================== CLASSES DE SUPORTE ====================

	public static final class VapidKeys {
		public final String publicKey;
		public final String privateKey;

		public VapidKeys(String publicKey, String privateKey) {
			this.publicKey = Objects.requireNonNull(publicKey, "publicKey não pode ser null");
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
		private final int statusCode;
		private final String error;

		private SendResult(boolean success, boolean expired, int statusCode, String error) {
			this.success = success;
			this.expired = expired;
			this.statusCode = statusCode;
			this.error = error;
		}

		static SendResult success(int statusCode) {
			return new SendResult(true, false, statusCode, null);
		}

		static SendResult failure(int statusCode, String error) {
			return new SendResult(false, false, statusCode, error);
		}

		static SendResult expired(int statusCode, String error) {
			return new SendResult(false, true, statusCode, error);
		}

		public boolean isSuccess() { return success; }
		public boolean isExpired() { return expired; }
		public int getStatusCode() { return statusCode; }
		public String getError() { return error; }

		@Override
		public String toString() {
			return "SendResult{success=" + success + ", expired=" + expired + ", statusCode=" + statusCode
					+ (error != null ? ", error='" + error + "'" : "") + "}";
		}
	}

	public enum Urgency {
		VERY_LOW, LOW, NORMAL, HIGH
	}
}