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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.task.Task;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;

/**
 * Envio de notificações Web Push (RFC 8292 / VAPID) com geração de chaves,
 * gerenciamento de assinaturas e envio assíncrono.
 *
 * <p><strong>Propósito:</strong> abstrair o protocolo Web Push — geração de
 * chaves VAPID, assinatura JWT e criptografia do payload — em chamadas
 * simples.</p>
 *
 * <p><strong>Quando usar:</strong> para notificações push no navegador
 * (service workers) com as bibliotecas web-push do lado servidor.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> sem um front-end com service worker
 * registrado não há o que notificar; para push móvel nativo (FCM/APNs direto)
 * use os SDKs específicos.</p>
 *
 * <p><strong>Integração:</strong> usa {@link Saveable} para persistir as chaves
 * VAPID ({@link Key}); {@link PushBootstrap} automatiza o setup; o front-end
 * precisa da chave pública via {@link #getVapidPublicKey()}.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>{@code PushBootstrap.setup()} (gera/persiste chaves e inicializa) — ou
 *       {@code initialize(pub, priv, subject)} com chaves próprias;</li>
 *   <li>Front-end: assinatura do service worker → envie endpoint/p256dh/auth ao
 *       servidor → {@link #createSubscription} + {@link #subscriptionToJson}
 *       para persistir;</li>
 *   <li>Envie com {@link #sendNotification} (fire-and-forget) ou
 *       {@link #sendNotificationAsync} (com resultado).</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong>
 * <pre>
 * PushBootstrap.setup();
 * WebPushAPI.Subscription sub =
 *     WebPushAPI.createSubscription(endpoint, p256dh, auth);
 * WebPushAPI.sendNotification(sub, "Promoção!", "50% off hoje", null);
 * </pre>
 * </p>
 *
 * <p><strong>Boas práticas:</strong> trate {@code SendResult.isExpired()}
 * (assinatura inválida → remova do banco); use o encoding AES128GCM (padrão
 * da classe — o AESGCM legado é rejeitado pelos push services modernos).</p>
 *
 * <p><strong>Limitações:</strong> requer as dependências
 * {@code nl.martijndwars:web-push:5.1.2}, {@code org.bouncycastle:bcprov-jdk18on:1.83},
 * {@code org.apache.httpcomponents:httpclient:4.5.14} e
 * {@code org.bitbucket.b_c:jose4j:0.9.6}; a classe é detectável (linkável) sem
 * elas e os guards exibem instruções de instalação no primeiro uso.</p>
 *
 * <p><strong>Extensões futuras:</strong> encodings adicionais (RFC 8291
 * alternativos) e retry com backoff podem ser adicionados sem quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see PushBootstrap
 * @see Key
 */
public final class WebPushAPI {

    // ==================== CONSTANTES ====================

    private static final int DEFAULT_TTL = 3600;
    private static final Urgency DEFAULT_URGENCY = Urgency.NORMAL;

    /** Coordenadas Maven das dependências do módulo. */
    private static final String WEBPUSH_COORDINATES = "nl.martijndwars:web-push:5.1.2";
    private static final String BOUNCY_CASTLE_COORDINATES = "org.bouncycastle:bcprov-jdk18on:1.83";
    private static final String HTTP_CLIENT_COORDINATES = "org.apache.httpcomponents:httpclient:4.5.14";
    private static final String JOSE4J_COORDINATES = "org.bitbucket.b_c:jose4j:0.9.6";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String WEBPUSH_FEATURE = "Web Push Notifications";

    /** Formato Base64URL válido para chaves VAPID. */
    private static final Pattern BASE64_URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private WebPushAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== INICIALIZAÇÃO ====================

    /**
     * Inicializa o WebPushAPI com as chaves VAPID persistidas no banco
     * (via {@link Saveable}).
     *
     * @return {@code true} se inicializado com sucesso
     */
    public static synchronized boolean initialize() {
        checkDependencies();
        if (PushSupport.initialized)
            return true;

        try {
            Key key = Saveable.findById(Key.class, "key");

            String pubKey = key.getPublicKey();
            String privKey = key.getPrivateKey();
            String subject = PushBootstrap.DEFAULT_SUBJECT;

            if (isBlank(pubKey) || isBlank(privKey) || isBlank(subject)) {
                Console.warn("Chaves VAPID não configuradas. Use WebPushAPI.generateVapidKeys() para criá-las.");
                return false;
            }

            return PushSupport.initializeInternal(pubKey, privKey, subject);
        } catch (Exception e) {
            Console.error("Falha ao inicializar WebPushAPI", e);
            return false;
        }
    }

    /**
     * Inicializa o WebPushAPI com chaves e subject informados explicitamente.
     *
     * @param publicKey  Chave pública VAPID (Base64URL, 87 chars)
     * @param privateKey Chave privada VAPID (Base64URL, 43 chars)
     * @param subject    Subject do VAPID (ex: {@code "mailto:contato@empresa.com"})
     * @return {@code true} se inicializado com sucesso
     */
    public static synchronized boolean initialize(String publicKey, String privateKey, String subject) {
        checkDependencies();
        return PushSupport.initializeInternal(publicKey, privateKey, subject);
    }

    // ==================== RESET / STATUS ====================

    /**
     * Desinicializa o módulo, liberando o serviço atual.
     */
    public static synchronized void reset() {
        PushSupport.reset();
        Console.debug("WebPushAPI resetado");
    }

    /**
     * Verifica se o módulo está inicializado.
     *
     * @return {@code true} se pronto para enviar
     */
    public static boolean isInitialized() {
        return PushSupport.initialized;
    }

    /**
     * Retorna a chave pública VAPID usada para assinar as notificações.
     *
     * @return Chave pública Base64URL, ou {@code null} se não inicializado
     */
    public static String getVapidPublicKey() {
        return PushSupport.vapidPublicKey;
    }

    // ==================== GERAÇÃO DE CHAVES VAPID ====================

    /**
     * Gera um par de chaves VAPID correto para Web Push (RFC 8292 / VAPID).
     *
     * <p><b>Chave pública:</b> uncompressed P-256 {@code 0x04 || X(32) || Y(32)} = 65
     * bytes → 87 chars Base64URL sem padding. <b>Chave privada:</b> escalar S → 32
     * bytes → 43 chars Base64URL sem padding.</p>
     *
     * @return Par de chaves (pública e privada)
     */
    public static VapidKeys generateVapidKeys() {
        checkDependencies();
        return PushSupport.generateVapidKeys();
    }

    // ==================== DIAGNÓSTICO ====================

    /**
     * Exibe um diagnóstico da configuração atual no console.
     *
     * @return {@code true} se a configuração parece válida
     */
    public static boolean testConfiguration() {
        return PushSupport.testConfiguration();
    }

    // ==================== ENVIO DE NOTIFICAÇÕES ====================

    /**
     * Envia uma notificação (fire-and-forget assíncrono).
     *
     * @param subscription Assinatura do destinatário
     * @param title        Título da notificação
     * @param body         Corpo da notificação
     * @param iconUrl      URL do ícone (pode ser {@code null})
     */
    public static void sendNotification(Subscription subscription, String title, String body, String iconUrl) {
        sendNotification(subscription, title, body, iconUrl, null, null);
    }

    /**
     * Envia uma notificação com dados extras (fire-and-forget assíncrono).
     *
     * @param subscription Assinatura do destinatário
     * @param title        Título da notificação
     * @param body         Corpo da notificação
     * @param iconUrl      URL do ícone (pode ser {@code null})
     * @param clickUrl     URL aberta ao clicar (pode ser {@code null})
     * @param extraData    Campos extras do payload (pode ser {@code null})
     */
    public static void sendNotification(Subscription subscription, String title, String body, String iconUrl,
            String clickUrl, Map<String, Object> extraData) {
        checkDependencies();
        PushSupport.checkInitialized();
        String payload = PushSupport.buildPayload(title, body, iconUrl, clickUrl, extraData);
        sendRawNotificationAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY, null);
    }

    /**
     * Envia uma notificação e retorna um {@link CompletableFuture} com o resultado.
     *
     * @param subscription Assinatura do destinatário
     * @param title        Título da notificação
     * @param body         Corpo da notificação
     * @param iconUrl      URL do ícone (pode ser {@code null})
     * @return Future com o resultado do envio
     */
    public static CompletableFuture<SendResult> sendNotificationAsync(Subscription subscription, String title,
            String body, String iconUrl) {
        checkDependencies();
        PushSupport.checkInitialized();
        String payload = PushSupport.buildPayload(title, body, iconUrl, null, null);
        return PushSupport.doSendAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY);
    }

    /**
     * Envia um payload JSON bruto (fire-and-forget assíncrono).
     *
     * @param subscription Assinatura do destinatário
     * @param jsonPayload  Payload JSON a enviar
     */
    public static void sendRawNotification(Subscription subscription, String jsonPayload) {
        sendRawNotificationAsync(subscription, jsonPayload, DEFAULT_TTL, DEFAULT_URGENCY, null);
    }

    /**
     * Envia um payload JSON bruto com opções e callback de resultado.
     *
     * @param subscription Assinatura do destinatário
     * @param jsonPayload  Payload JSON a enviar
     * @param ttl          Tempo de vida da notificação em segundos
     * @param urgency      Urgência da notificação
     * @param onResult     Callback chamado com o resultado (pode ser {@code null})
     */
    public static void sendRawNotificationAsync(Subscription subscription, String jsonPayload, int ttl, Urgency urgency,
            Consumer<SendResult> onResult) {
        checkDependencies();
        PushSupport.checkInitialized();
        CompletableFuture<SendResult> future = PushSupport.doSendAsync(subscription, jsonPayload, ttl, urgency);
        if (onResult != null) {
            future.thenAccept(onResult);
        }
    }

    /**
     * Envia a mesma notificação para várias assinaturas em paralelo.
     *
     * @param subscriptions Lista de assinaturas
     * @param title         Título da notificação
     * @param body          Corpo da notificação
     * @param iconUrl       URL do ícone (pode ser {@code null})
     * @return Lista de futures, um por assinatura
     */
    public static List<CompletableFuture<SendResult>> sendBatchNotifications(List<Subscription> subscriptions,
            String title, String body, String iconUrl) {
        checkDependencies();
        PushSupport.checkInitialized();
        String payload = PushSupport.buildPayload(title, body, iconUrl, null, null);
        List<CompletableFuture<SendResult>> futures = new ArrayList<>(subscriptions.size());
        for (Subscription subscription : subscriptions) {
            futures.add(PushSupport.doSendAsync(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY));
        }
        return futures;
    }

    // ==================== MÉTODOS DE ASSINATURA ====================

    /**
     * Cria uma assinatura Web Push a partir de endpoint e chaves.
     *
     * @param endpoint Endpoint do push service (ex: FCM, Mozilla)
     * @param p256dh   Chave pública de autenticação (Base64URL)
     * @param auth     Chave de autenticação (Base64URL)
     * @return Assinatura pronta para uso
     */
    public static Subscription createSubscription(String endpoint, String p256dh, String auth) {
        checkDependencies();
        Objects.requireNonNull(endpoint, "endpoint não pode ser null");
        Objects.requireNonNull(p256dh, "p256dh não pode ser null");
        Objects.requireNonNull(auth, "auth não pode ser null");
        return new Subscription(endpoint, new Subscription.Keys(p256dh, auth));
    }

    /**
     * Converte um JSON de assinatura ({@code {"endpoint":..., "keys":{...}}}) em
     * {@link Subscription}.
     *
     * @param json JSON da assinatura
     * @return Assinatura desserializada
     */
    public static Subscription parseSubscriptionFromJson(String json) {
        checkDependencies();
        return PushSupport.parseSubscriptionFromJson(json);
    }

    /**
     * Serializa uma assinatura para JSON (padrão push API).
     *
     * @param subscription Assinatura a serializar
     * @return JSON da assinatura
     */
    public static String subscriptionToJson(Subscription subscription) {
        return PushSupport.subscriptionToJson(subscription);
    }

    // ==================== UTILITÁRIOS PRIVADOS ====================

    /**
     * Verifica a presença de todas as dependências do módulo. Chama antes de
     * qualquer operação que toque nas bibliotecas externas.
     */
    private static void checkDependencies() {
        Dependencies.require("nl.martijndwars.webpush.PushService", WEBPUSH_COORDINATES, WEBPUSH_FEATURE);
        Dependencies.require("org.bouncycastle.jce.provider.BouncyCastleProvider", BOUNCY_CASTLE_COORDINATES, WEBPUSH_FEATURE);
        Dependencies.require("org.apache.http.impl.client.CloseableHttpClient", HTTP_CLIENT_COORDINATES, WEBPUSH_FEATURE);
        Dependencies.require("org.jose4j.jws.JsonWebSignature", JOSE4J_COORDINATES, WEBPUSH_FEATURE);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ==================== CLASSES DE SUPORTE ====================

    /**
     * Implementação do protocolo Web Push. Classe separada para manter as
     * referências às bibliotecas de terceiros fora do bytecode da
     * {@link WebPushAPI} — assim a classe pública pode ser vinculada sem as
     * dependências e os guards exibem a mensagem de instalação correta.
     */
    private static final class PushSupport {

        /**
         * CRÍTICO: use SEMPRE AES128GCM (RFC 8291). O encoding legado
         * {@code AESGCM} gera header com padding, rejeitado com HTTP 403
         * pelos push services modernos.
         */
        private static final Encoding VAPID_ENCODING = Encoding.AES128GCM;

        private static final String EC_CURVE = "prime256v1"; // secp256r1 / P-256

        /** Cliente HTTP compartilhado (evita criar um pool de conexões por envio). */
        private static final CloseableHttpClient HTTP_CLIENT = HttpClients.createDefault();

        private static PushService pushService;
        private static boolean initialized = false;
        private static String vapidPublicKey;
        private static String vapidPrivateKey;

        private PushSupport() {
        }

        static synchronized boolean initializeInternal(String publicKey, String privateKey, String subject) {
            try {
                publicKey = cleanBase64Key(publicKey);
                privateKey = cleanBase64Key(privateKey);

                if (!isValidBase64Url(publicKey) || !isValidBase64Url(privateKey)) {
                    Console.error(
                            "Chaves VAPID em formato inválido (não é Base64URL). Gere novas chaves com generateVapidKeys().");
                    return false;
                }

                // Validar chave pública: 65 bytes uncompressed P-256 (04 || X32 || Y32) → 87 chars
                byte[] pubBytes = Base64.getUrlDecoder().decode(padBase64(publicKey));
                if (pubBytes.length != 65 || pubBytes[0] != 0x04) {
                    Console.error("Chave pública VAPID inválida: esperado 65 bytes (04|X|Y), recebido %d bytes.",
                            pubBytes.length);
                    return false;
                }

                // Validar chave privada: escalar S de 32 bytes → 43 chars
                byte[] privBytes = Base64.getUrlDecoder().decode(padBase64(privateKey));
                if (privBytes.length != 32) {
                    Console.error("Chave privada VAPID inválida: esperado 32 bytes, recebido %d bytes.", privBytes.length);
                    return false;
                }

                ensureBouncyCastle();
                pushService = new PushService(publicKey, privateKey, subject);

                vapidPublicKey = publicKey;
                vapidPrivateKey = privateKey;
                initialized = true;

                Console.log("WebPushAPI inicializado. Encoding=%s, Subject=%s", VAPID_ENCODING, subject);
                Console.debug("Public Key: %d chars / %d bytes", vapidPublicKey.length(), pubBytes.length);

                return true;
            } catch (Exception e) {
                Console.error("Falha ao inicializar WebPushAPI", e);
                initialized = false;
                pushService = null;
                return false;
            }
        }

        static synchronized void reset() {
            pushService = null;
            vapidPublicKey = null;
            vapidPrivateKey = null;
            initialized = false;
        }

        static VapidKeys generateVapidKeys() {
            try {
                ensureBouncyCastle();
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
                kpg.initialize(new ECGenParameterSpec(EC_CURVE));
                KeyPair kp = kpg.generateKeyPair();

                // Chave pública: 0x04 || X(32) || Y(32)
                ECPublicKey pub = (ECPublicKey) kp.getPublic();
                ECPoint point = pub.getW();
                byte[] x = toExact32Bytes(point.getAffineX().toByteArray());
                byte[] y = toExact32Bytes(point.getAffineY().toByteArray());

                byte[] pubBytes = new byte[65];
                pubBytes[0] = 0x04;
                System.arraycopy(x, 0, pubBytes, 1, 32);
                System.arraycopy(y, 0, pubBytes, 33, 32);

                // Chave privada: escalar S em exatamente 32 bytes
                ECPrivateKey priv = (ECPrivateKey) kp.getPrivate();
                byte[] privBytes = toExact32Bytes(priv.getS().toByteArray());

                // OBRIGATÓRIO: sem padding '='
                Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
                String pubKey = enc.encodeToString(pubBytes); // 87 chars
                String privKey = enc.encodeToString(privBytes); // 43 chars

                if (pubKey.length() != 87)
                    Console.warn("AVISO: chave pública tem %d chars (esperado 87).", pubKey.length());
                if (privKey.length() != 43)
                    Console.warn("AVISO: chave privada tem %d chars (esperado 43).", privKey.length());

                Console.debug("Chaves VAPID geradas: pub=%d chars, priv=%d chars", pubKey.length(), privKey.length());
                return new VapidKeys(pubKey, privKey);

            } catch (Exception e) {
                throw new RuntimeException("Erro ao gerar chaves VAPID", e);
            }
        }

        static boolean testConfiguration() {
            if (!initialized) {
                Console.error("WebPushAPI não está inicializado");
                return false;
            }

            Console.log("=== TESTE DE CONFIGURAÇÃO WebPushAPI ===");
            Console.log("Initialized: %s", initialized);
            Console.log("Encoding: %s", VAPID_ENCODING);
            Console.log("PushService: %s", pushService != null ? "OK" : "NULL");

            if (vapidPublicKey != null) {
                byte[] pub = Base64.getUrlDecoder().decode(padBase64(vapidPublicKey));
                Console.log("Public Key: %d chars / %d bytes (esperado 87/65)", vapidPublicKey.length(), pub.length);
                Console.log("Public Key starts 0x04: %s", pub.length > 0 && pub[0] == 0x04);
            }
            if (vapidPrivateKey != null) {
                byte[] priv = Base64.getUrlDecoder().decode(padBase64(vapidPrivateKey));
                Console.log("Private Key: %d chars / %d bytes (esperado 43/32)", vapidPrivateKey.length(), priv.length);
            }

            Console.log("=========================================");
            return vapidPublicKey != null && vapidPrivateKey != null && pushService != null;
        }

        static CompletableFuture<SendResult> doSendAsync(Subscription subscription, String payload, int ttl,
                Urgency urgency) {

            CompletableFuture<SendResult> future = new CompletableFuture<>();

            Task.runAsync(() -> {
                try {
                    Console.debug("Enviando notificação | Encoding=%s | Endpoint=%s", VAPID_ENCODING,
                            truncateEndpoint(subscription.endpoint));

                    Notification notification = new Notification(subscription.endpoint, subscription.keys.p256dh,
                            subscription.keys.auth, payload.getBytes(StandardCharsets.UTF_8), ttl);

                    // CRÍTICO: usar preparePost(..., AES128GCM) em vez de send().
                    // pushService.send(notification) usa AESGCM por padrão, que gera o
                    // header "Crypto-Key: dh=...;p256ecdsa=...=" com padding '=' causando
                    // HTTP 403 "crypto-key header had invalid format".
                    HttpPost post = pushService.preparePost(notification, VAPID_ENCODING);

                    HttpResponse response = HTTP_CLIENT.execute(post);

                    int statusCode = response.getStatusLine().getStatusCode();
                    String reason = response.getStatusLine().getReasonPhrase();

                    String responseBody = "";
                    if (response.getEntity() != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                            responseBody = reader.lines().collect(Collectors.joining("\n"));
                        }
                    }

                    String retryAfter = null;
                    if (response.getFirstHeader("Retry-After") != null) {
                        retryAfter = response.getFirstHeader("Retry-After").getValue();
                    }

                    String baseLog = "Status=" + statusCode + " | Reason=" + reason + " | Body=" + responseBody
                            + " | Retry-After=" + retryAfter;

                    if (statusCode >= 200 && statusCode < 300) {
                        Console.debug("Notificação enviada com sucesso | %s | Endpoint=%s", baseLog,
                                truncateEndpoint(subscription.endpoint));
                        future.complete(SendResult.success(statusCode));

                    } else if (statusCode == 410 || statusCode == 404) {
                        String msg = "Assinatura inválida/expirada (HTTP " + statusCode + ")";
                        Console.warn("%s | %s | Subscription=%s", msg, baseLog, GsonAPI.get().toJson(subscription));
                        future.complete(SendResult.expired(statusCode, msg + " | " + reason));

                    } else if (statusCode == 403) {
                        String msg = "Erro de autenticação VAPID (HTTP 403)";
                        Console.error("%s | %s", msg, baseLog);
                        Console.error(
                                "Verifique: subject válido (mailto: ou https://)? chaves geradas com generateVapidKeys()? chave pública no front-end bate com vapidPublicKey?");
                        future.complete(SendResult.failure(statusCode, msg + " | " + reason));

                    } else if (statusCode == 429) {
                        String msg = "Rate limit (HTTP 429). Retry-After=" + retryAfter;
                        Console.warn("%s | %s", msg, baseLog);
                        future.complete(SendResult.failure(statusCode, msg));

                    } else {
                        String msg = "Falha ao enviar notificação (HTTP " + statusCode + ")";
                        Console.error("%s | %s | Subscription=%s", msg, baseLog, GsonAPI.get().toJson(subscription));
                        future.complete(SendResult.failure(statusCode, msg + " | " + reason));
                    }

                } catch (Exception e) {
                    Console.error("Exceção ao enviar notificação | Subscription=%s", GsonAPI.get().toJson(subscription), e);
                    future.completeExceptionally(e);
                }
            });

            return future;
        }

        static Subscription parseSubscriptionFromJson(String json) {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String endpoint = obj.get("endpoint").getAsString();
            JsonObject keys = obj.getAsJsonObject("keys");
            String p256dh = keys.get("p256dh").getAsString();
            String auth = keys.get("auth").getAsString();
            return new Subscription(endpoint, new Subscription.Keys(p256dh, auth));
        }

        static String subscriptionToJson(Subscription subscription) {
            JsonObject keys = new JsonObject();
            keys.addProperty("p256dh", subscription.keys.p256dh);
            keys.addProperty("auth", subscription.keys.auth);
            JsonObject obj = new JsonObject();
            obj.addProperty("endpoint", subscription.endpoint);
            obj.add("keys", keys);
            return GsonAPI.get().toJson(obj);
        }

        static String buildPayload(String title, String body, String iconUrl, String clickUrl,
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
            return GsonAPI.get().toJson(payload);
        }

        static void checkInitialized() {
            if (!initialized) {
                throw new IllegalStateException("WebPushAPI não inicializado. Chame WebPushAPI.initialize() primeiro.");
            }
        }

        private static void ensureBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
                Console.debug("BouncyCastleProvider registrado com sucesso");
            }
        }

        private static String truncateEndpoint(String endpoint) {
            if (endpoint == null)
                return "null";
            if (endpoint.length() <= 60)
                return endpoint;
            return endpoint.substring(0, 30) + "..." + endpoint.substring(endpoint.length() - 30);
        }

        private static String cleanBase64Key(String key) {
            if (key == null)
                return null;
            return key.replace("=", "").replace("\n", "").replace("\r", "").replace(" ", "").trim();
        }

        private static boolean isValidBase64Url(String key) {
            return key != null && !key.isEmpty() && BASE64_URL_PATTERN.matcher(key).matches();
        }

        private static String padBase64(String b64) {
            int mod = b64.length() % 4;
            if (mod == 0)
                return b64;
            if (mod == 2)
                return b64 + "==";
            if (mod == 3)
                return b64 + "=";
            return b64;
        }

        private static boolean isBlank(String s) {
            return s == null || s.trim().isEmpty();
        }

        private static byte[] toExact32Bytes(byte[] src) {
            if (src.length == 32)
                return src;
            byte[] dst = new byte[32];
            if (src.length > 32) {
                System.arraycopy(src, src.length - 32, dst, 0, 32);
            } else {
                System.arraycopy(src, 0, dst, 32 - src.length, src.length);
            }
            return dst;
        }
    }

    // ==================== CLASSES DE APOIO (API PÚBLICA) ====================

    /**
     * Par de chaves VAPID geradas por {@link #generateVapidKeys()}.
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
     * Resultado de um envio de notificação: sucesso, assinatura expirada ou falha.
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

        public boolean isSuccess() {
            return success;
        }

        public boolean isExpired() {
            return expired;
        }

        public int getStatusCode() {
            return statusCode;
        }

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
     * Urgência da notificação (influencia a entrega quando o dispositivo está
     * em modo de economia de energia).
     */
    public enum Urgency {
        VERY_LOW, LOW, NORMAL, HIGH
    }
}
