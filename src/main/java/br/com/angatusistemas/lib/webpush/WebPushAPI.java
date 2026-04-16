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
import java.security.interfaces.ECPublicKey;
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
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.task.Task;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Notification.NotificationBuilder;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;

/**
 * [PT] Classe utilitária para envio de notificações Web Push utilizando a
 * biblioteca webpush-java.
 * <p>
 * Suporta VAPID (Voluntary Application Server Identification), payloads
 * criptografados, gerenciamento de assinaturas de usuários e envio assíncrono
 * via {@link Task}.
 * </p>
 * <p>
 * <b>Dependências Maven necessárias (pom.xml):</b>
 * 
 * <pre>
 * &lt;!-- Web Push --&gt;
 * &lt;dependency&gt;
 *     &lt;groupId&gt;nl.martijndwars&lt;/groupId&gt;
 *     &lt;artifactId&gt;web-push&lt;/artifactId&gt;
 *     &lt;version&gt;5.1.1&lt;/version&gt;
 * &lt;/dependency&gt;
 *
 * &lt;!-- BouncyCastle (criptografia) --&gt;
 * &lt;dependency&gt;
 *     &lt;groupId&gt;org.bouncycastle&lt;/groupId&gt;
 *     &lt;artifactId&gt;bcprov-jdk18on&lt;/artifactId&gt;
 *     &lt;version&gt;1.78.1&lt;/version&gt;
 * &lt;/dependency&gt;
 *
 * &lt;!-- Gson (serialização JSON) --&gt;
 * &lt;dependency&gt;
 *     &lt;groupId&gt;com.google.code.gson&lt;/groupId&gt;
 *     &lt;artifactId&gt;gson&lt;/artifactId&gt;
 *     &lt;version&gt;2.11.0&lt;/version&gt;
 * &lt;/dependency&gt;
 *
 * &lt;!-- Apache HttpClient (dependência transitiva do web-push) --&gt;
 * &lt;dependency&gt;
 *     &lt;groupId&gt;org.apache.httpcomponents&lt;/groupId&gt;
 *     &lt;artifactId&gt;httpclient&lt;/artifactId&gt;
 *     &lt;version&gt;4.5.14&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 * </p>
 * <p>
 * <b>Configuração necessária no arquivo .env:</b>
 * 
 * <pre>
 * VAPID_PUBLIC_KEY=BGgL7I82SAQM78oyGwaJdrQFhVfZqL9h4Y18BLtgJQ-9pSGXwxqAWQudqmcv41RcWgk1ssUeItv4-8khxbhYveM=
 * VAPID_PRIVATE_KEY=ANlfcVVFB4JiMYcI74_h9h04QZ1Ks96AyEa1yrMgDwn3
 * VAPID_SUBJECT=mailto:contact@example.com
 * </pre>
 * </p>
 * <p>
 * <b>Exemplo de uso:</b>
 * 
 * <pre>
 * // Gerar novas chaves VAPID
 * WebPushAPI.VapidKeys keys = WebPushAPI.generateVapidKeys();
 * WebPushAPI.saveVapidKeys(keys, "vapid_keys.pem");
 *
 * // Inicializar com chaves do arquivo .env
 * WebPushAPI.initialize();
 *
 * // Criar assinatura a partir dos dados recebidos do frontend
 * Subscription sub = WebPushAPI.createSubscription("https://fcm.googleapis.com/fcm/send/...",
 * 		"BOtBVgsHVWXzwhDAoFE8P2IgQvabz_tuJjIlNacmS3XZ3fRDuVWiBp8bPR3vHCA78edquclcXXYb-olcj3QtIZ4=",
 * 		"IOScBh9LW5mJ_K2JwXyNqQ==");
 *
 * // Enviar notificação simples
 * WebPushAPI.sendNotification(sub, "Título", "Corpo da mensagem", "/icon.png");
 *
 * // Enviar com callback de resultado
 * WebPushAPI.sendNotificationAsync(sub, "Título", "Corpo", "/icon.png").thenAccept(result -> {
 * 	if (result.isSuccess())
 * 		System.out.println("Enviado!");
 * 	else
 * 		System.err.println("Falha: " + result.getError());
 * });
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://github.com/web-push-libs/webpush-java">webpush-java on
 *      GitHub</a>
 */
public final class WebPushAPI {

	// ==================== CONSTANTES ====================

	/** TTL padrão das notificações em segundos (1 hora) */
	private static final int DEFAULT_TTL = 3600;

	/** Urgência padrão das notificações */
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

	/**
	 * [PT] Inicializa o serviço de push com as chaves VAPID carregadas das
	 * variáveis de ambiente.
	 * <p>
	 * Configurações esperadas: VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, VAPID_SUBJECT.
	 * </p>
	 *
	 * @return true se a inicialização foi bem-sucedida, false caso contrário
	 */
	public static synchronized boolean initialize() {
		if (initialized)
			return true;

		try {
			String pubKey = System.getenv("VAPID_PUBLIC_KEY");
			String privKey = System.getenv("VAPID_PRIVATE_KEY");
			String subject = System.getenv("VAPID_SUBJECT");

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

	/**
	 * [PT] Inicializa o serviço de push com chaves VAPID fornecidas explicitamente.
	 *
	 * @param publicKey  chave pública VAPID (Base64 URL-safe, formato uncompressed
	 *                   point)
	 * @param privateKey chave privada VAPID (Base64 URL-safe)
	 * @param subject    assunto VAPID (ex: "mailto:contact@example.com" ou URL
	 *                   HTTPS)
	 * @return true se a inicialização foi bem-sucedida
	 */
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

	/**
	 * [PT] Reseta o estado de inicialização (útil para testes ou troca de chaves em
	 * runtime).
	 */
	public static synchronized void reset() {
		pushService = null;
		vapidPublicKey = null;
		initialized = false;
		Console.debug("WebPushAPI resetado");
	}

	/**
	 * @return true se o serviço já foi inicializado
	 */
	public static boolean isInitialized() {
		return initialized;
	}

	/**
	 * @return a chave pública VAPID atual (Base64 URL-safe), ou null se não
	 *         inicializado
	 */
	public static String getVapidPublicKey() {
		return vapidPublicKey;
	}

	// ==================== GERAÇÃO DE CHAVES VAPID ====================

	/**
	 * [PT] Gera um novo par de chaves VAPID utilizando a curva elíptica P-256 (RFC
	 * 8292).
	 *
	 * @return objeto {@link VapidKeys} com as chaves em Base64 URL-safe, ou null em
	 *         caso de erro
	 */
	public static VapidKeys generateVapidKeys() {
		try {
			KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
			keyPairGen.initialize(new java.security.spec.ECGenParameterSpec("P-256"));
			KeyPair keyPair = keyPairGen.generateKeyPair();

			ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
			ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

			// Chave pública: formato uncompressed EC point (04 || X || Y) — 65 bytes
			// A biblioteca webpush-java (Utils.loadPublicKey) exige este formato.
			// getEncoded() retorna DER/ASN.1 (começa com 0x30) e causa
			// "Invalid point encoding 0x30".
			org.bouncycastle.jce.interfaces.ECPublicKey bcPublicKey =
				(org.bouncycastle.jce.interfaces.ECPublicKey) publicKey;
			byte[] publicKeyBytes = bcPublicKey.getQ().getEncoded(false); // false = uncompressed

			// Chave privada: apenas o valor escalar S (32 bytes big-endian)
			// getEncoded() retorna PKCS#8 DER — formato errado para webpush-java.
			byte[] sBytes = privateKey.getS().toByteArray();
			// toByteArray() pode incluir byte de sinal 0x00 no início; normaliza para 32 bytes
			byte[] privateKeyBytes = new byte[32];
			if (sBytes.length >= 32) {
				System.arraycopy(sBytes, sBytes.length - 32, privateKeyBytes, 0, 32);
			} else {
				System.arraycopy(sBytes, 0, privateKeyBytes, 32 - sBytes.length, sBytes.length);
			}

			String publicKeyBase64  = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes);
			String privateKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKeyBytes);

			Console.debug("Chaves VAPID geradas com sucesso");
			return new VapidKeys(publicKeyBase64, privateKeyBase64);
		} catch (Exception e) {
			Console.error("Falha ao gerar chaves VAPID: {}", e);
			return null;
		}
	}

	/**
	 * [PT] Carrega chaves VAPID de um arquivo PEM.
	 *
	 * @param filePath caminho do arquivo PEM
	 * @return objeto {@link VapidKeys} com as chaves carregadas
	 * @throws IOException se o arquivo não for encontrado ou inválido
	 */
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

	/**
	 * [PT] Salva chaves VAPID em arquivo PEM.
	 *
	 * @param keys     chaves VAPID a salvar
	 * @param filePath caminho do arquivo de destino
	 * @throws IOException se ocorrer erro na escrita
	 */
	public static void saveVapidKeys(VapidKeys keys, String filePath) throws IOException {
		Objects.requireNonNull(keys, "VapidKeys não pode ser null");
		try (PemWriter pemWriter = new PemWriter(new FileWriter(filePath, StandardCharsets.UTF_8))) {
			pemWriter.writeObject(new PemObject("PUBLIC KEY", Base64.getUrlDecoder().decode(keys.publicKey)));
			pemWriter.writeObject(new PemObject("PRIVATE KEY", Base64.getUrlDecoder().decode(keys.privateKey)));
		}
		Console.debug("Chaves VAPID salvas em: {}", filePath);
	}

	// ==================== ENVIO DE NOTIFICAÇÕES ====================

	/**
	 * [PT] Envia uma notificação push simples de forma assíncrona
	 * (fire-and-forget).
	 *
	 * @param subscription assinatura do usuário
	 * @param title        título da notificação
	 * @param body         corpo da mensagem
	 * @param iconUrl      URL do ícone (pode ser null)
	 */
	public static void sendNotification(Subscription subscription, String title, String body, String iconUrl) {
		sendNotification(subscription, title, body, iconUrl, null, null);
	}

	/**
	 * [PT] Envia uma notificação push com dados extras e URL de ação.
	 *
	 * @param subscription assinatura do usuário
	 * @param title        título da notificação
	 * @param body         corpo da mensagem
	 * @param iconUrl      URL do ícone (pode ser null)
	 * @param clickUrl     URL a abrir ao clicar na notificação (pode ser null)
	 * @param extraData    dados extras em mapa (podem ser null)
	 */
	public static void sendNotification(Subscription subscription, String title, String body, String iconUrl,
			String clickUrl, Map<String, Object> extraData) {
		checkInitialized();
		String payload = buildPayload(title, body, iconUrl, clickUrl, extraData);
		sendRawNotificationAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY, null);
	}

	/**
	 * [PT] Envia uma notificação push e retorna um {@link CompletableFuture} com o
	 * resultado.
	 *
	 * @param subscription assinatura do usuário
	 * @param title        título da notificação
	 * @param body         corpo da mensagem
	 * @param iconUrl      URL do ícone (pode ser null)
	 * @return future com o resultado do envio
	 */
	public static CompletableFuture<SendResult> sendNotificationAsync(Subscription subscription, String title,
			String body, String iconUrl) {
		checkInitialized();
		String payload = buildPayload(title, body, iconUrl, null, null);
		return doSendAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY);
	}

	/**
	 * [PT] Envia uma notificação push com payload JSON personalizado
	 * (fire-and-forget).
	 *
	 * @param subscription assinatura do usuário
	 * @param jsonPayload  payload JSON conforme padrão Web Push
	 */
	public static void sendRawNotification(Subscription subscription, String jsonPayload) {
		sendRawNotificationAsync(subscription, jsonPayload, DEFAULT_TTL, DEFAULT_URGENCY, null);
	}

	/**
	 * [PT] Envia uma notificação push com controle completo de parâmetros.
	 *
	 * @param subscription assinatura do usuário
	 * @param jsonPayload  payload JSON da notificação
	 * @param ttl          tempo de vida em segundos (0 = entrega imediata ou
	 *                     descarte)
	 * @param urgency      urgência da mensagem {@link Urgency}
	 * @param onResult     callback opcional chamado após o envio (pode ser null)
	 */
	public static void sendRawNotificationAsync(Subscription subscription, String jsonPayload, int ttl, Urgency urgency,
			Consumer<SendResult> onResult) {
		checkInitialized();
		CompletableFuture<SendResult> future = doSendAsync(subscription, jsonPayload, ttl, urgency);
		if (onResult != null) {
			future.thenAccept(onResult);
		}
	}

	/**
	 * [PT] Envia notificações em lote para múltiplas assinaturas.
	 *
	 * @param subscriptions lista de assinaturas
	 * @param title         título da notificação
	 * @param body          corpo da mensagem
	 * @param iconUrl       URL do ícone (pode ser null)
	 * @return lista de futures com os resultados individuais
	 */
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

	/**
	 * [PT] Cria um objeto {@link Subscription} a partir de seus componentes
	 * individuais.
	 *
	 * @param endpoint endpoint do serviço push (FCM, Mozilla, etc.)
	 * @param p256dh   chave pública P-256DH do cliente (Base64 URL-safe)
	 * @param auth     segredo de autenticação do cliente (Base64 URL-safe)
	 * @return objeto Subscription pronto para uso
	 */
	public static Subscription createSubscription(String endpoint, String p256dh, String auth) {
		Objects.requireNonNull(endpoint, "endpoint não pode ser null");
		Objects.requireNonNull(p256dh, "p256dh não pode ser null");
		Objects.requireNonNull(auth, "auth não pode ser null");
		return new Subscription(endpoint, new Subscription.Keys(p256dh, auth));
	}

	/**
	 * [PT] Converte uma assinatura no formato JSON padrão Web Push para um objeto
	 * {@link Subscription}.
	 * <p>
	 * Formato esperado:
	 * 
	 * <pre>
	 * {
	 *   "endpoint": "https://fcm.googleapis.com/...",
	 *   "keys": {
	 *     "p256dh": "BOtBVgsHVWXzwhDAoFE8...",
	 *     "auth":   "IOScBh9LW5mJ_K2JwXyNqQ=="
	 *   }
	 * }
	 * </pre>
	 * </p>
	 *
	 * @param json string JSON da assinatura recebida do frontend
	 * @return objeto Subscription
	 * @throws com.google.gson.JsonSyntaxException se o JSON for inválido
	 * @throws NullPointerException                se campos obrigatórios estiverem
	 *                                             ausentes
	 */
	public static Subscription parseSubscriptionFromJson(String json) {
		JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
		String endpoint = obj.get("endpoint").getAsString();
		JsonObject keys = obj.getAsJsonObject("keys");
		String p256dh = keys.get("p256dh").getAsString();
		String auth = keys.get("auth").getAsString();
		return createSubscription(endpoint, p256dh, auth);
	}

	/**
	 * [PT] Serializa um objeto {@link Subscription} para JSON (útil para
	 * persistência).
	 *
	 * @param subscription assinatura a serializar
	 * @return string JSON representando a assinatura
	 */
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
				// Usar o construtor correto: Notification(Subscription, String, Urgency)
				nl.martijndwars.webpush.Urgency libUrgency = nl.martijndwars.webpush.Urgency.valueOf(urgency.name());

				Notification notification = new Notification(subscription, payload, libUrgency);

				@SuppressWarnings("static-access")
				NotificationBuilder builder = notification.builder();
				builder.ttl(ttl);
				notification = builder.build();

				HttpResponse response = pushService.send(notification);
				int statusCode = response.getStatusLine().getStatusCode();

				if (statusCode >= 200 && statusCode < 300) {
					Console.debug("Notificação enviada. Status: {} | Endpoint: {}", statusCode, subscription.endpoint);
					future.complete(SendResult.success(statusCode));
				} else if (statusCode == 410 || statusCode == 404) {
					// Assinatura expirada/inválida — deve ser removida do banco de dados
					String msg = "Assinatura inválida/expirada (HTTP " + statusCode + "). Remova do banco.";
					Console.warn(msg + " Endpoint: {}", subscription.endpoint);
					future.complete(SendResult.expired(statusCode, msg));
				} else {
					String msg = "Falha ao enviar notificação. HTTP " + statusCode;
					Console.error(msg + " | Endpoint: {}", subscription.endpoint);
					future.complete(SendResult.failure(statusCode, msg));
				}
			} catch (Exception e) {
				Console.error("Exceção ao enviar notificação para {}: {}", subscription.endpoint, e);
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

	/**
	 * [PT] Par de chaves VAPID (pública + privada) em formato Base64 URL-safe.
	 */
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

	/**
	 * [PT] Resultado de um envio de notificação push.
	 */
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

		/** @return true se o push foi aceito pelo servidor */
		public boolean isSuccess() {
			return success;
		}

		/**
		 * @return true se a assinatura está expirada/inválida (HTTP 410/404). Quando
		 *         true, a assinatura DEVE ser removida do banco de dados.
		 */
		public boolean isExpired() {
			return expired;
		}

		/** @return código HTTP retornado pelo servidor de push */
		public int getStatusCode() {
			return statusCode;
		}

		/** @return mensagem de erro, ou null em caso de sucesso */
		public String getError() {
			return error;
		}

		@Override
		public String toString() {
			return "SendResult{success=" + success + ", expired=" + expired + ", statusCode=" + statusCode
					+ (error != null ? ", error='" + error + "'" : "") + "}";
		}
	}

	/**
	 * [PT] Nível de urgência da notificação conforme RFC 8030. Influencia o
	 * comportamento do servidor de push ao encaminhar a mensagem.
	 */
	public enum Urgency {
		/**
		 * Para alertas críticos que exigem atenção imediata (ex: alarme de incêndio)
		 */
		VERY_LOW,
		/** Para mensagens que não precisam ser entregues imediatamente */
		LOW,
		/** Padrão — para a maioria das notificações */
		NORMAL,
		/** Para alertas críticos que exigem atenção imediata (ex: chamadas VoIP) */
		HIGH
	}
}
