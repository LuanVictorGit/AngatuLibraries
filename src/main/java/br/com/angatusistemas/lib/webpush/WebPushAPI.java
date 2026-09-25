package br.com.angatusistemas.lib.webpush;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.apache.http.HttpClientConnection;
import org.apache.http.HttpConnection;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.MessageConstraints;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestExecutor;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.gson.GsonAPI;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Utils;

/**
 * Envio de notificações Web Push (RFC 8030, criptografia RFC 8291 e VAPID RFC 8292) com
 * geração de chaves, gerenciamento de assinaturas e envio assíncrono.
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
 *   <li>Front-end: assinatura do service worker → envie o JSON dela
 *       ({@code PushSubscription.toJSON()}) ao servidor → {@link #parseSubscriptionFromJson}
 *       (ou {@link #createSubscription}) + {@link #subscriptionToJson} para persistir;</li>
 *   <li>Envie com {@link #sendNotification} (fire-and-forget) ou
 *       {@link #sendNotificationAsync} (com resultado).</li>
 * </ol>
 *
 * <p><strong>Exemplo</strong> — a assinatura é a {@code nl.martijndwars.webpush.Subscription},
 * da biblioteca web-push (não existe um tipo {@code WebPushAPI.Subscription}):</p>
 * <pre>
 * import nl.martijndwars.webpush.Subscription;
 *
 * PushBootstrap.setup("mailto:contato@seudominio.com.br");
 *
 * // Na rota que recebe a assinatura do navegador:
 * Subscription sub = WebPushAPI.parseSubscriptionFromJson(ctx.body());
 * String paraSalvar = WebPushAPI.subscriptionToJson(sub);
 *
 * WebPushAPI.sendNotificationAsync(sub, "Promoção!", "50% off hoje", null)
 *         .thenAccept(result -&gt; {
 *             if (result.isExpired()) {
 *                 // assinatura morta: remova do banco
 *             }
 *         });
 * </pre>
 *
 * <h2>Segurança: só push services conhecidos</h2>
 * <p>O endpoint vem do navegador — ou seja, de quem quiser forjá-lo. Sem conferência, o
 * servidor fazia POST em qualquer endereço recebido, inclusive {@code http://127.0.0.1} e
 * serviços internos da rede. Por isso só são aceitos endpoints {@code https} (porta 443) dos
 * push services dos navegadores: {@code fcm.googleapis.com} (Chrome e derivados),
 * {@code *.push.services.mozilla.com} (Firefox), {@code *.push.apple.com} (Safari) e
 * {@code *.notify.windows.com} (Edge no Windows). A conferência acontece em
 * {@link #createSubscription}, em {@link #parseSubscriptionFromJson} e de novo antes de cada
 * envio — assinaturas antigas, lidas do banco, também passam por ela. Outro push service pode
 * ser autorizado com {@link #allowPushServiceHost(String)}; {@link #isAllowedEndpoint(String)}
 * responde, sem exceção, se um endpoint seria aceito.</p>
 *
 * <h2>Envio: threads próprias e prazos</h2>
 * <p>Os envios rodam num executor exclusivo do módulo (32 threads daemon
 * {@code Angatu-WebPush-N}, fila de até 10.000 envios) — nunca no pool compartilhado do
 * {@code Task}, que roda a limpeza do rate limit do servidor web. Cada envio
 * tem prazos: 5 s para conectar (handshake TLS incluído), 5 s por uma conexão livre do pool,
 * 10 s de silêncio do servidor e 30 s no total. Redirecionamentos não são seguidos e só os
 * primeiros 4.096 bytes da resposta são lidos.</p>
 *
 * <p><strong>Resultado:</strong> todo future devolvido completa — e completa
 * <em>normalmente</em>, com um {@link SendResult}. Falha de rede, prazo estourado, endpoint
 * recusado, fila cheia ou erro interno viram {@link SendResult#isSuccess()} {@code false} com
 * {@link SendResult#getStatusCode()} {@code 0}; nunca um future pendente ou excepcional.</p>
 *
 * <p><strong>Boas práticas:</strong> trate {@code SendResult.isExpired()}
 * (assinatura inválida → remova do banco); use o encoding AES128GCM (padrão
 * da classe — o AESGCM legado é rejeitado pelos push services modernos). No desligamento da
 * JVM (deploy, fim do {@code main}), o que já está na fila tem até 8 s para sair; quem precisa
 * saber se a notificação saiu espera o future.</p>
 *
 * <p><strong>Limitações:</strong> requer as dependências
 * {@code nl.martijndwars:web-push:5.1.2}, {@code org.bouncycastle:bcprov-jdk18on:1.86},
 * {@code org.apache.httpcomponents:httpclient:4.5.14}, {@code org.bitbucket.b_c:jose4j:0.9.6}
 * e {@code com.google.code.gson:gson:2.13.2}. A classe carrega sem elas: os métodos de estado
 * ({@link #isInitialized()}, {@link #getVapidPublicKey()}, {@link #reset()},
 * {@link #testConfiguration()}) e os de endpoint funcionam mesmo assim, e os demais exibem as
 * instruções de instalação no primeiro uso.</p>
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
    private static final String BOUNCY_CASTLE_COORDINATES = "org.bouncycastle:bcprov-jdk18on:1.86";
    private static final String HTTP_CLIENT_COORDINATES = "org.apache.httpcomponents:httpclient:4.5.14";
    private static final String JOSE4J_COORDINATES = "org.bitbucket.b_c:jose4j:0.9.6";
    private static final String GSON_COORDINATES = "com.google.code.gson:gson:2.13.2";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String WEBPUSH_FEATURE = "Notificações Web Push";

    /**
     * Dependências do módulo, na ordem em que são conferidas. O Gson está na lista porque o
     * payload e as assinaturas passam por ele: sem ele, até {@link #generateVapidKeys()} caía
     * num {@code NoClassDefFoundError} em vez da mensagem de instalação.
     */
    private static final List<Requirement> REQUIREMENTS = List.of(
            new Requirement("nl.martijndwars.webpush.PushService", WEBPUSH_COORDINATES),
            new Requirement("org.bouncycastle.jce.provider.BouncyCastleProvider", BOUNCY_CASTLE_COORDINATES),
            new Requirement("org.apache.http.impl.client.CloseableHttpClient", HTTP_CLIENT_COORDINATES),
            new Requirement("org.jose4j.jws.JsonWebSignature", JOSE4J_COORDINATES),
            new Requirement("com.google.gson.Gson", GSON_COORDINATES));

    /** Formato Base64URL válido para chaves VAPID. */
    private static final Pattern BASE64_URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    // ==================== TRANSPORTE ====================

    /**
     * Prazo para abrir a conexão TCP. No HTTPS ele vale também para o handshake TLS: o
     * HttpClient usa o prazo de conexão como timeout do socket enquanto negocia o TLS.
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Espera máxima por uma conexão livre do pool. */
    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 5_000;

    /**
     * Silêncio máximo do push service entre dois pacotes da resposta. Não é um prazo total —
     * quem manda um byte a cada 9 s nunca o estoura; para isso existe {@link #SEND_DEADLINE_MS}.
     */
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    /**
     * Prazo total de um envio, da conexão ao fim da leitura. Estourado, a requisição é abortada
     * (o socket é fechado) e a thread volta a atender a fila. Fica acima da soma dos prazos
     * anteriores para só agir no caso patológico: o servidor que responde, mas a conta-gotas.
     */
    private static final long SEND_DEADLINE_MS = 30_000;

    /**
     * Máximo lido do corpo da resposta, que só serve para o log. Um endpoint que devolvia um
     * corpo sem fim levava o processo a {@code OutOfMemoryError} em cerca de 3 s.
     */
    private static final int MAX_RESPONSE_BODY_BYTES = 4_096;

    /**
     * Limites dos cabeçalhos da resposta. O padrão do HttpClient é "sem limite": uma linha de
     * cabeçalho sem fim dava o mesmo {@code OutOfMemoryError} do corpo, só que antes dele.
     */
    private static final int MAX_HEADER_LINE_LENGTH = 8_192;
    private static final int MAX_HEADER_COUNT = 100;

    /**
     * Threads de envio. Um envio passa quase todo o tempo esperando a rede: 32 em paralelo dão
     * vazão a um broadcast sem fazer do Web Push o dono da CPU.
     */
    private static final int SEND_THREADS = 32;

    /**
     * Envios que podem aguardar na fila. Acima disso o envio é recusado na hora, com um
     * {@link SendResult} de falha: sob sobrecarga, acumular trabalho sem fim só adia a queda —
     * e cada envio parado segura o payload na memória.
     */
    private static final int SEND_QUEUE_CAPACITY = 10_000;

    /**
     * Conexões por push service e no total. Um broadcast vai quase inteiro para o FCM: com as
     * 2 conexões por host do cliente padrão, 30 das 32 threads ficariam esperando conexão.
     */
    private static final int MAX_CONNECTIONS_PER_ROUTE = SEND_THREADS;
    private static final int MAX_CONNECTIONS_TOTAL = SEND_THREADS * 2;

    /** Conexão ociosa por mais que isto (s) é fechada pelo HttpClient. */
    private static final long IDLE_CONNECTION_SECONDS = 30;

    /** Thread ociosa por mais que isto (s) termina: aplicação sem push não guarda thread. */
    private static final long IDLE_THREAD_SECONDS = 60;

    /** Intervalo mínimo entre dois avisos de fila cheia no log. */
    private static final long QUEUE_FULL_WARNING_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10);

    // ==================== PUSH SERVICES ACEITOS ====================

    /** Tamanho máximo de um endpoint — os reais ficam bem abaixo de 1.000 caracteres. */
    private static final int MAX_ENDPOINT_LENGTH = 4_096;

    /**
     * Push services dos navegadores: host exato, ou {@code *.} para qualquer subdomínio (sem
     * incluir o próprio domínio).
     */
    private static final List<String> DEFAULT_PUSH_SERVICE_HOSTS = List.of(
            "fcm.googleapis.com",           // Chrome, Edge no Android, Opera, Samsung Internet
            "*.push.services.mozilla.com",  // Firefox
            "*.push.apple.com",             // Safari
            "*.notify.windows.com");        // Edge no Windows

    /**
     * Hosts aceitos: os padrões e os autorizados por {@link #allowPushServiceHost(String)}.
     * Lido a cada envio e alterado quase nunca — daí a cópia na escrita.
     */
    private static final Set<String> PUSH_SERVICE_HOSTS = new CopyOnWriteArraySet<>(DEFAULT_PUSH_SERVICE_HOSTS);

    /** Formato aceito por {@link #allowPushServiceHost(String)}: host com domínio, com ou sem {@code *.}. */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(\\*\\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    /** Caracteres de controle, trocados por espaço no que vem da rede e vai para o log. */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}+");

    // ==================== ESTADO ====================

    /**
     * Configuração ativa, publicada de uma vez só; {@code null} = não inicializado.
     *
     * <p>Um único campo {@code volatile} com um objeto imutável: quem lê vê sempre um conjunto
     * coerente, nunca "inicializado" com as chaves de uma configuração anterior. Antes eram
     * quatro campos estáticos soltos, sem {@code volatile}, dentro da classe que carrega as
     * bibliotecas opcionais — consultar o estado sem os jars dava {@code NoClassDefFoundError}.</p>
     */
    private static volatile ActiveConfig active;

    private WebPushAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== INICIALIZAÇÃO ====================

    /**
     * Inicializa o WebPushAPI com as chaves VAPID persistidas no banco (entidade {@link Key},
     * via {@link Saveable}) e o subject configurado para o projeto — propriedade de sistema
     * {@code angatu.webpush.subject}, variável de ambiente {@code ANGATU_WEBPUSH_SUBJECT} ou,
     * na falta das duas, {@link PushBootstrap#DEFAULT_SUBJECT}.
     *
     * <p>Não gera chaves: sem o registro no banco, registra um aviso e devolve {@code false}.
     * Para gerar e salvar na primeira vez, use {@link PushBootstrap#setup()}.</p>
     *
     * @return {@code true} se inicializado com sucesso (ou se já estava); {@code false} se não
     *         há chaves no banco, se elas forem inválidas, se o subject configurado for inválido
     *         ou se o banco falhar — o motivo vai para o log
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static synchronized boolean initialize() {
        checkDependencies();
        if (active != null)
            return true;

        try {
            Key key = Saveable.findById(Key.class, Key.ID);
            if (key == null || isBlank(key.getPublicKey()) || isBlank(key.getPrivateKey())) {
                // Sem este teste, o registro ausente virava um NullPointerException no log
                Console.warn("Chaves VAPID não encontradas no banco. Use PushBootstrap.setup() para gerá-las e salvá-las.");
                return false;
            }
            return initializeWith(key.getPublicKey(), key.getPrivateKey(), PushBootstrap.configuredSubject());
        } catch (Exception e) {
            Console.error("Falha ao inicializar WebPushAPI", e);
            return false;
        }
    }

    /**
     * Inicializa o WebPushAPI com chaves e subject informados explicitamente.
     *
     * <p>As chaves são conferidas antes de valer: formato Base64URL, tamanhos (65 bytes
     * {@code 04|X|Y} e 32 bytes) e se a privada corresponde à pública. Se a conferência falhar,
     * o método devolve {@code false} e a configuração anterior, se houver, continua valendo.</p>
     *
     * @param publicKey  Chave pública VAPID (Base64URL, 87 caracteres)
     * @param privateKey Chave privada VAPID (Base64URL, 43 caracteres)
     * @param subject    Subject do VAPID (ex: {@code "mailto:contato@empresa.com"})
     * @return {@code true} se inicializado com sucesso; {@code false} se as chaves forem
     *         inválidas — o motivo vai para o log
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static synchronized boolean initialize(String publicKey, String privateKey, String subject) {
        checkDependencies();
        return initializeWith(publicKey, privateKey, subject);
    }

    /** Monta a configuração e, se ela for válida, publica. Chamado com a trava da classe. */
    private static boolean initializeWith(String publicKey, String privateKey, String subject) {
        ActiveConfig config = PushSupport.createConfig(publicKey, privateKey, subject);
        if (config == null)
            return false;
        active = config;
        return true;
    }

    // ==================== RESET / STATUS ====================

    /**
     * Desinicializa o módulo, descartando a configuração atual (chaves e serviço).
     *
     * <p>Envios já em andamento terminam com a configuração que pegaram; os que ainda estavam
     * na fila completam com falha ("não inicializado"). Funciona mesmo sem as dependências do
     * módulo no classpath.</p>
     */
    public static synchronized void reset() {
        active = null;
        Console.debug("WebPushAPI resetado");
    }

    /**
     * Verifica se o módulo está inicializado. Funciona mesmo sem as dependências do módulo no
     * classpath (nesse caso, devolve {@code false}).
     *
     * @return {@code true} se pronto para enviar
     */
    public static boolean isInitialized() {
        return active != null;
    }

    /**
     * Retorna a chave pública VAPID usada para assinar as notificações — a
     * {@code applicationServerKey} que o front-end passa ao {@code pushManager.subscribe()}.
     * Funciona mesmo sem as dependências do módulo no classpath.
     *
     * @return Chave pública Base64URL, ou {@code null} se não inicializado
     */
    public static String getVapidPublicKey() {
        ActiveConfig config = active;
        return config == null ? null : config.publicKey;
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
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static VapidKeys generateVapidKeys() {
        checkDependencies();
        return PushSupport.generateVapidKeys();
    }

    // ==================== DIAGNÓSTICO ====================

    /**
     * Exibe no console um diagnóstico da configuração: dependências, push services aceitos,
     * estado e formato das chaves. Nunca exibe a chave privada, só o tamanho dela.
     *
     * <p>Funciona mesmo sem as dependências no classpath — a presença delas é justamente um dos
     * pontos exibidos.</p>
     *
     * @return {@code true} se todas as dependências estão presentes e o módulo está
     *         inicializado com chaves no formato esperado
     */
    public static boolean testConfiguration() {
        Console.log("=== DIAGNÓSTICO DO WebPushAPI ===");
        boolean dependenciesPresent = true;
        for (Requirement requirement : REQUIREMENTS) {
            boolean present = Dependencies.isPresent(requirement.className());
            dependenciesPresent &= present;
            Console.log("Dependência %s: %s", requirement.coordinates(), present ? "presente" : "AUSENTE");
        }
        Console.log("Push services aceitos: %s", String.join(", ", PUSH_SERVICE_HOSTS));

        ActiveConfig config = active;
        if (config == null) {
            Console.error("WebPushAPI não está inicializado");
            Console.log("=================================");
            return false;
        }

        byte[] pub = Base64.getUrlDecoder().decode(padBase64(config.publicKey));
        byte[] priv = Base64.getUrlDecoder().decode(padBase64(config.privateKey));
        boolean publicKeyOk = pub.length == 65 && pub[0] == 0x04;
        boolean privateKeyOk = priv.length == 32;
        Console.log("Inicializado: sim | Encoding: AES128GCM | Subject: %s", config.subject);
        Console.log("Chave pública: %d caracteres / %d bytes (esperado 87/65) | começa com 0x04: %s",
                config.publicKey.length(), pub.length, pub.length > 0 && pub[0] == 0x04 ? "sim" : "não");
        Console.log("Chave privada: %d caracteres / %d bytes (esperado 43/32)", config.privateKey.length(), priv.length);
        Console.log("=================================");
        return dependenciesPresent && publicKeyOk && privateKeyOk;
    }

    // ==================== ENVIO DE NOTIFICAÇÕES ====================

    /**
     * Envia uma notificação (fire-and-forget assíncrono).
     *
     * <p>O resultado só aparece no log; para tratá-lo (ex: remover assinatura expirada), use
     * {@link #sendNotificationAsync}.</p>
     *
     * @param subscription Assinatura do destinatário
     * @param title        Título da notificação
     * @param body         Corpo da notificação
     * @param iconUrl      URL do ícone (pode ser {@code null})
     * @throws IllegalStateException se o módulo não estiver inicializado
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
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
     * @throws IllegalStateException se o módulo não estiver inicializado
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static void sendNotification(Subscription subscription, String title, String body, String iconUrl,
            String clickUrl, Map<String, Object> extraData) {
        checkDependencies();
        checkInitialized();
        String payload = PushSupport.buildPayload(title, body, iconUrl, clickUrl, extraData);
        PushSupport.submit(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY);
    }

    /**
     * Envia uma notificação e retorna um {@link CompletableFuture} com o resultado.
     *
     * <p>O future sempre completa, e sempre normalmente: falha de rede, prazo estourado,
     * endpoint recusado, fila cheia ou erro interno chegam como {@link SendResult} de falha
     * (status {@code 0}), com o motivo em {@link SendResult#getError()}. Até a versão anterior,
     * uma falha de rede completava o future com exceção — e um {@code Error} o deixava pendente
     * para sempre.</p>
     *
     * @param subscription Assinatura do destinatário
     * @param title        Título da notificação
     * @param body         Corpo da notificação
     * @param iconUrl      URL do ícone (pode ser {@code null})
     * @return Future com o resultado do envio (nunca completa com exceção)
     * @throws IllegalStateException se o módulo não estiver inicializado
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static CompletableFuture<SendResult> sendNotificationAsync(Subscription subscription, String title,
            String body, String iconUrl) {
        checkDependencies();
        checkInitialized();
        String payload = PushSupport.buildPayload(title, body, iconUrl, null, null);
        return PushSupport.submit(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY);
    }

    /**
     * Envia um payload JSON bruto (fire-and-forget assíncrono).
     *
     * @param subscription Assinatura do destinatário
     * @param jsonPayload  Payload JSON a enviar
     * @throws IllegalStateException se o módulo não estiver inicializado
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static void sendRawNotification(Subscription subscription, String jsonPayload) {
        sendRawNotificationAsync(subscription, jsonPayload, DEFAULT_TTL, DEFAULT_URGENCY, null);
    }

    /**
     * Envia um payload JSON bruto com opções e callback de resultado.
     *
     * <p>A urgência vai no cabeçalho {@code Urgency} (RFC 8030) — antes ela era ignorada e
     * nenhum envio levava o cabeçalho. O callback é chamado sempre: no sucesso e também na
     * falha, com o {@link SendResult} correspondente. Ele costuma rodar numa thread de envio do
     * módulo: não faça trabalho demorado nele. Exceção lançada pelo callback vai para o log.</p>
     *
     * @param subscription Assinatura do destinatário
     * @param jsonPayload  Payload JSON a enviar
     * @param ttl          Tempo de vida da notificação em segundos
     * @param urgency      Urgência da notificação ({@code null} = sem o cabeçalho, que o push
     *                     service trata como {@link Urgency#NORMAL})
     * @param onResult     Callback chamado com o resultado (pode ser {@code null})
     * @throws IllegalStateException se o módulo não estiver inicializado
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static void sendRawNotificationAsync(Subscription subscription, String jsonPayload, int ttl, Urgency urgency,
            Consumer<SendResult> onResult) {
        checkDependencies();
        checkInitialized();
        CompletableFuture<SendResult> future = PushSupport.submit(subscription, jsonPayload, ttl, urgency);
        if (onResult != null) {
            // whenComplete, e não thenAccept: com thenAccept, quem pediu nunca ouvia falar da falha
            future.whenComplete((result, error) -> deliver(onResult, result, error));
        }
    }

    /**
     * Envia a mesma notificação para várias assinaturas em paralelo.
     *
     * <p>Cada assinatura vira um envio na fila do módulo. Se a fila lotar (10.000 envios
     * aguardando), os excedentes completam na hora com falha — nada bloqueia nem acumula sem
     * limite. Para broadcasts maiores, envie em lotes e espere cada lote
     * ({@code CompletableFuture.allOf(...).join()}) antes do próximo.</p>
     *
     * @param subscriptions Lista de assinaturas
     * @param title         Título da notificação
     * @param body          Corpo da notificação
     * @param iconUrl       URL do ícone (pode ser {@code null})
     * @return Lista de futures, um por assinatura e na mesma ordem; cada um completa
     *         normalmente com o {@link SendResult} do envio, como em {@link #sendNotificationAsync}
     * @throws IllegalStateException se o módulo não estiver inicializado
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static List<CompletableFuture<SendResult>> sendBatchNotifications(List<Subscription> subscriptions,
            String title, String body, String iconUrl) {
        checkDependencies();
        checkInitialized();
        String payload = PushSupport.buildPayload(title, body, iconUrl, null, null);
        List<CompletableFuture<SendResult>> futures = new ArrayList<>(subscriptions.size());
        for (Subscription subscription : subscriptions) {
            futures.add(PushSupport.submit(subscription, payload, DEFAULT_TTL, DEFAULT_URGENCY));
        }
        return futures;
    }

    // ==================== MÉTODOS DE ASSINATURA ====================

    /**
     * Cria uma assinatura Web Push a partir de endpoint e chaves.
     *
     * <p>O endpoint precisa ser {@code https} de um push service aceito (ver a seção de
     * segurança da classe) — é o que impede o servidor de ser usado para fazer POST em
     * endereços internos.</p>
     *
     * @param endpoint Endpoint do push service (ex: FCM, Mozilla)
     * @param p256dh   Chave pública de autenticação (Base64URL)
     * @param auth     Chave de autenticação (Base64URL)
     * @return Assinatura pronta para uso
     * @throws NullPointerException     se algum parâmetro for {@code null}
     * @throws IllegalArgumentException se o endpoint não for de um push service aceito
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static Subscription createSubscription(String endpoint, String p256dh, String auth) {
        checkDependencies();
        Objects.requireNonNull(endpoint, "endpoint não pode ser null");
        Objects.requireNonNull(p256dh, "p256dh não pode ser null");
        Objects.requireNonNull(auth, "auth não pode ser null");
        requireAllowedEndpoint(endpoint);
        return PushSupport.newSubscription(endpoint, p256dh, auth);
    }

    /**
     * Converte um JSON de assinatura ({@code {"endpoint":..., "keys":{"p256dh":..., "auth":...}}},
     * o formato de {@code PushSubscription.toJSON()}) em {@link Subscription}.
     *
     * <p>O JSON vem do navegador, então é tratado como entrada hostil: qualquer defeito —
     * JSON malformado, campo ausente ou que não é texto, endpoint fora dos push services
     * aceitos — vira {@link IllegalArgumentException} com o motivo em português, pronto para
     * uma resposta 400.</p>
     *
     * @param json JSON da assinatura
     * @return Assinatura desserializada
     * @throws NullPointerException     se {@code json} for {@code null}
     * @throws IllegalArgumentException se o JSON não for uma assinatura válida e aceita
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static Subscription parseSubscriptionFromJson(String json) {
        checkDependencies();
        Objects.requireNonNull(json, "json não pode ser null");
        return PushSupport.parseSubscriptionFromJson(json);
    }

    /**
     * Serializa uma assinatura para JSON (padrão push API).
     *
     * @param subscription Assinatura a serializar
     * @return JSON da assinatura
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException se faltar
     *         alguma dependência do módulo
     */
    public static String subscriptionToJson(Subscription subscription) {
        checkDependencies();
        return PushSupport.subscriptionToJson(subscription);
    }

    // ==================== PUSH SERVICES ACEITOS ====================

    /**
     * Diz se um endpoint seria aceito para envio: {@code https}, porta 443, sem usuário na URL
     * e com o host de um push service aceito (os dos navegadores e os autorizados por
     * {@link #allowPushServiceHost(String)}).
     *
     * <p>Não lança exceção e não depende das bibliotecas do módulo — útil na rota que recebe a
     * assinatura, para responder 400 antes de persistir.</p>
     *
     * @param endpoint Endpoint recebido do navegador (pode ser {@code null})
     * @return {@code true} se o endpoint é aceito
     */
    public static boolean isAllowedEndpoint(String endpoint) {
        return endpointProblem(endpoint) == null;
    }

    /**
     * Autoriza mais um push service, além dos quatro dos navegadores ({@code fcm.googleapis.com},
     * {@code *.push.services.mozilla.com}, {@code *.push.apple.com},
     * {@code *.notify.windows.com}).
     *
     * <p>Use para um push service que surgir antes de uma nova versão da biblioteca, ou para um
     * serviço próprio. Aceita o host exato ({@code "push.exemplo.com.br"}) ou um curinga de
     * subdomínio ({@code "*.push.exemplo.com.br"}, que não inclui o próprio
     * {@code push.exemplo.com.br}). Sem esquema, porta nem caminho: o envio continua exigindo
     * {@code https} na porta 443. Endereço IP e nome sem domínio ({@code localhost}) não são
     * aceitos — autorizar um host é permitir que qualquer navegador faça o servidor enviar POST
     * para ele. Vale para o processo inteiro, a partir da chamada; chame na inicialização.</p>
     *
     * @param hostPattern Host ou curinga de subdomínio
     * @throws NullPointerException     se {@code hostPattern} for {@code null}
     * @throws IllegalArgumentException se o padrão não for um host válido
     */
    public static void allowPushServiceHost(String hostPattern) {
        Objects.requireNonNull(hostPattern, "hostPattern não pode ser null");
        String pattern = hostPattern.trim().toLowerCase(Locale.ROOT);
        if (!HOST_PATTERN.matcher(pattern).matches()) {
            throw new IllegalArgumentException("Host de push service inválido: \"" + hostPattern
                    + "\". Use o host (push.exemplo.com.br) ou um curinga de subdomínio (*.push.exemplo.com.br), "
                    + "sem esquema, porta ou caminho.");
        }
        if (PUSH_SERVICE_HOSTS.add(pattern)) {
            Console.log("Web Push: push service autorizado: %s", pattern);
        }
    }

    // ==================== UTILITÁRIOS PRIVADOS ====================

    /**
     * Verifica a presença de todas as dependências do módulo. Chamado antes de
     * qualquer operação que toque nas bibliotecas externas.
     */
    private static void checkDependencies() {
        for (Requirement requirement : REQUIREMENTS) {
            Dependencies.require(requirement.className(), requirement.coordinates(), WEBPUSH_FEATURE);
        }
    }

    private static void checkInitialized() {
        if (active == null) {
            throw new IllegalStateException(
                    "WebPushAPI não inicializado. Chame PushBootstrap.setup() ou WebPushAPI.initialize() primeiro.");
        }
    }

    /** Lança {@link IllegalArgumentException} com o motivo, se o endpoint não for aceito. */
    private static void requireAllowedEndpoint(String endpoint) {
        String problem = endpointProblem(endpoint);
        if (problem != null) {
            throw new IllegalArgumentException("Endpoint de Web Push recusado: " + problem
                    + ". Só são aceitos endpoints https dos push services dos navegadores; "
                    + "para outro serviço, use WebPushAPI.allowPushServiceHost(host).");
        }
    }

    /** Motivo pelo qual o endpoint não é aceito, ou {@code null} se ele é. Só JDK. */
    private static String endpointProblem(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "endpoint vazio";
        }
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            return "endpoint com " + endpoint.length() + " caracteres (o máximo é " + MAX_ENDPOINT_LENGTH + ")";
        }
        try {
            return uriProblem(new URI(endpoint));
        } catch (URISyntaxException e) {
            return "o endpoint não é uma URL válida";
        }
    }

    /**
     * A mesma conferência sobre o URI já montado. Antes de enviar, ela roda no URI da própria
     * requisição — o que vai de fato para a rede, depois de qualquer reescrita do web-push.
     */
    private static String uriProblem(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "o endpoint precisa usar https";
        }
        if (uri.getRawUserInfo() != null) {
            return "o endpoint não pode ter usuário ou senha na URL";
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "o endpoint não tem um host válido";
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return "o endpoint usa a porta " + uri.getPort() + ", e push service atende só na 443";
        }
        if (!isAllowedHost(host.toLowerCase(Locale.ROOT))) {
            return "o host " + host + " não é um push service conhecido";
        }
        return null;
    }

    /** O host (já em minúsculas) bate com algum push service aceito? */
    private static boolean isAllowedHost(String host) {
        for (String pattern : PUSH_SERVICE_HOSTS) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // "*.push.apple.com" → ".push.apple.com"
                if (host.length() > suffix.length() && host.endsWith(suffix))
                    return true;
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * O que do endpoint pode ir para o log: só o host.
     *
     * <p>O log registrava a assinatura inteira — inclusive o segredo {@code auth} — a cada
     * falha ou assinatura expirada. O caminho do endpoint também fica de fora: ele identifica o
     * aparelho do usuário. Visível no pacote para os testes.</p>
     *
     * @param endpoint Endpoint da assinatura (pode ser {@code null} ou inválido)
     * @return O host, ou {@code "?"} se não houver um
     */
    static String endpointHost(String endpoint) {
        if (endpoint == null)
            return "?";
        try {
            String host = new URI(endpoint).getHost();
            return host == null || host.isEmpty() ? "?" : host;
        } catch (URISyntaxException | RuntimeException e) {
            return "?";
        }
    }

    /** Texto vindo da rede pronto para o log: sem quebras de linha nem outros controles. */
    private static String printable(String text) {
        return text == null ? null : CONTROL_CHARACTERS.matcher(text).replaceAll(" ").trim();
    }

    /** Descrição curta de uma falha, para mensagem e log. */
    private static String describe(Throwable failure) {
        if (failure == null)
            return "erro desconhecido";
        String message = printable(failure.getMessage());
        return failure.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /** Entrega o resultado ao callback do chamador; exceção do callback vai para o log. */
    private static void deliver(Consumer<SendResult> onResult, SendResult result, Throwable error) {
        SendResult delivered = result != null ? result
                : SendResult.failure(0, "Erro ao enviar a notificação: " + describe(error));
        try {
            onResult.accept(delivered);
        } catch (Throwable callbackFailure) {
            Console.error("O callback de resultado do Web Push lançou uma exceção", callbackFailure);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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
        if (mod == 2)
            return b64 + "==";
        if (mod == 3)
            return b64 + "=";
        return b64;
    }

    // ==================== CLASSES DE SUPORTE ====================

    /** Uma dependência do módulo: uma classe dela e as coordenadas Maven para instalá-la. */
    private record Requirement(String className, String coordinates) {
    }

    /**
     * Configuração publicada pela inicialização: imutável, sempre trocada inteira.
     *
     * <p>Só tipos do JDK, de propósito: os métodos de estado leem este objeto sem carregar as
     * bibliotecas opcionais. O {@code PushService} do web-push fica guardado como {@link Object}
     * pelo mesmo motivo — só a {@link PushSupport}, que roda depois da conferência de
     * dependências, o converte de volta.</p>
     */
    private static final class ActiveConfig {

        final Object pushService;
        final String publicKey;
        final String privateKey;
        final String subject;

        ActiveConfig(Object pushService, String publicKey, String privateKey, String subject) {
            this.pushService = pushService;
            this.publicKey = publicKey;
            this.privateKey = privateKey;
            this.subject = subject;
        }
    }

    /**
     * Threads do Web Push: exclusivas do módulo, daemon, nomeadas e em número limitado.
     *
     * <p>Os envios rodavam no pool de 4 threads do {@code Task}, o mesmo do e-mail e da limpeza
     * do rate limit do servidor web: 4 endpoints que nunca respondiam paravam tudo junto — 0 de
     * 4 envios terminados depois de 22 s. Aqui, um push service lento só atrasa o próprio Web
     * Push, e nem ele por mais que o prazo total de um envio.</p>
     *
     * <p>Só tipos do JDK; criado no primeiro envio. As threads terminam depois de 60 s ociosas.</p>
     *
     * <p><strong>Desligamento:</strong> as threads são daemon e sozinhas não esperariam nada — o
     * SIGTERM de um deploy descartaria as notificações ainda na fila. Um gancho de desligamento,
     * registrado junto com a fila, dá ao que já foi pedido até {@value #SHUTDOWN_GRACE_MS} ms
     * para sair, dentro dos 10 s que o Docker espera antes de matar o processo. Envio pedido
     * durante o desligamento completa na hora como falha.</p>
     */
    private static final class SendQueue {

        /** Quanto o desligamento da JVM espera a fila de envios esvaziar. */
        static final long SHUTDOWN_GRACE_MS = 8_000L;

        /** Envios: {@link #SEND_THREADS} threads e fila limitada; cheia, recusa na hora. */
        static final ThreadPoolExecutor SENDERS = newSenders();

        /**
         * Prazo total de cada envio: uma thread só, que aborta a requisição atrasada. A tarefa
         * cancelada sai da fila na hora, então ela nunca guarda mais que os envios em curso.
         */
        static final ScheduledThreadPoolExecutor DEADLINES = newDeadlines();

        /** Envios recusados por fila cheia desde o último aviso no log. */
        static final AtomicLong REJECTED_SINCE_WARNING = new AtomicLong();

        /** Momento ({@code System.nanoTime}) do último aviso de fila cheia. */
        static final AtomicLong LAST_QUEUE_FULL_WARNING = new AtomicLong(System.nanoTime() - QUEUE_FULL_WARNING_INTERVAL_NS);

        private SendQueue() {
        }

        private static ThreadPoolExecutor newSenders() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(SEND_THREADS, SEND_THREADS,
                    IDLE_THREAD_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
                    daemonThreads("Angatu-WebPush-"));
            executor.allowCoreThreadTimeOut(true);
            registerShutdownDrain(executor);
            return executor;
        }

        /**
         * No desligamento da JVM, deixa a fila esvaziar por até {@value #SHUTDOWN_GRACE_MS} ms. A
         * fila de prazos não é encerrada: os envios que ainda saem continuam precisando dela.
         */
        private static void registerShutdownDrain(ThreadPoolExecutor executor) {
            Thread drain = new Thread(() -> {
                executor.shutdown(); // recusa pedido novo; o que está na fila continua saindo
                try {
                    if (!executor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        Console.warn("Desligamento: %d notificação(ões) Web Push não saíram em %d s e foram "
                                + "descartadas.", executor.getQueue().size() + executor.getActiveCount(),
                                SHUTDOWN_GRACE_MS / 1_000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Angatu-WebPush-Shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(drain);
            } catch (IllegalStateException alreadyShuttingDown) {
                // A fila nasceu durante o desligamento: não há gancho a registrar
            }
        }

        private static ScheduledThreadPoolExecutor newDeadlines() {
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                    daemonThreads("Angatu-WebPush-Deadline-"));
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setKeepAliveTime(IDLE_THREAD_SECONDS, TimeUnit.SECONDS);
            scheduler.allowCoreThreadTimeOut(true);
            return scheduler;
        }

        /**
         * Threads daemon: a fila do Web Push nunca impede a JVM de encerrar. O pool do
         * {@code Task} usa threads comuns e exige {@code Task.shutdown()}; este não exige nada.
         */
        private static ThreadFactory daemonThreads(String prefix) {
            AtomicInteger counter = new AtomicInteger(1);
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            };
        }

        /**
         * Registra no log a recusa por fila cheia — no máximo um aviso a cada 10 s, com a
         * contagem do período. Sob sobrecarga, uma linha por envio recusado era outra sobrecarga.
         */
        static void warnQueueFull(String host) {
            REJECTED_SINCE_WARNING.incrementAndGet();
            long now = System.nanoTime();
            long last = LAST_QUEUE_FULL_WARNING.get();
            if (now - last >= QUEUE_FULL_WARNING_INTERVAL_NS && LAST_QUEUE_FULL_WARNING.compareAndSet(last, now)) {
                Console.warn("Web Push: fila de envio cheia (%d aguardando) — %d notificação(ões) recusada(s) "
                        + "desde o último aviso. Último host: %s", SEND_QUEUE_CAPACITY,
                        REJECTED_SINCE_WARNING.getAndSet(0), host);
            }
        }
    }

    /**
     * Implementação do protocolo Web Push. Classe separada para manter as
     * referências às bibliotecas de terceiros fora do bytecode da
     * {@link WebPushAPI} — assim a classe pública pode ser vinculada sem as
     * dependências e os guards exibem a mensagem de instalação correta.
     *
     * <p>Só é tocada depois de {@code checkDependencies()}: sem os jars, até a verificação do
     * bytecode dela falha (ela precisa das classes do Gson, por exemplo).</p>
     */
    private static final class PushSupport {

        /**
         * CRÍTICO: use SEMPRE AES128GCM (RFC 8291). O encoding legado
         * {@code AESGCM} gera header com padding, rejeitado com HTTP 403
         * pelos push services modernos.
         */
        private static final Encoding VAPID_ENCODING = Encoding.AES128GCM;

        private static final String EC_CURVE = "prime256v1"; // secp256r1 / P-256

        /**
         * Resultado pronto para o pior caso: sem memória nem para montar a mensagem da falha, o
         * future ainda completa com ele.
         */
        private static final SendResult INTERNAL_FAILURE = SendResult.failure(0, "Erro interno ao enviar a notificação");

        private PushSupport() {
        }

        /**
         * Confere as chaves e monta a configuração.
         *
         * @return A configuração, ou {@code null} se as chaves não servirem (motivo no log)
         */
        static ActiveConfig createConfig(String publicKey, String privateKey, String subject) {
            try {
                publicKey = cleanBase64Key(publicKey);
                privateKey = cleanBase64Key(privateKey);

                if (!isValidBase64Url(publicKey) || !isValidBase64Url(privateKey)) {
                    Console.error(
                            "Chaves VAPID em formato inválido (não é Base64URL). Gere novas chaves com generateVapidKeys().");
                    return null;
                }

                byte[] pubBytes = Base64.getUrlDecoder().decode(padBase64(publicKey));
                byte[] privBytes = Base64.getUrlDecoder().decode(padBase64(privateKey));
                if (pubBytes.length == 32 && privBytes.length == 65) {
                    // O construtor de Key recebe (privada, pública): quem o usa como (pública, privada)
                    // grava as duas trocadas — e a inicialização falhava sem dizer por quê.
                    Console.error("Chaves VAPID trocadas: a chave pública recebida tem o tamanho da privada e vice-versa. "
                            + "Confira a ordem dos parâmetros (new Key(privada, pública); initialize(pública, privada, subject)).");
                    return null;
                }

                // Validar chave pública: 65 bytes uncompressed P-256 (04 || X32 || Y32) → 87 chars
                if (pubBytes.length != 65 || pubBytes[0] != 0x04) {
                    Console.error("Chave pública VAPID inválida: esperado 65 bytes (04|X|Y), recebido %d bytes.",
                            pubBytes.length);
                    return null;
                }

                // Validar chave privada: escalar S de 32 bytes → 43 chars
                if (privBytes.length != 32) {
                    Console.error("Chave privada VAPID inválida: esperado 32 bytes, recebido %d bytes.", privBytes.length);
                    return null;
                }

                ensureBouncyCastle();
                PushService service = new PushService(publicKey, privateKey, subject);

                // O web-push confere o par a cada envio e lança exceção se não bater: conferido
                // aqui, o erro aparece uma vez, na inicialização, e não em cada notificação.
                if (!Utils.verifyKeyPair(service.getPrivateKey(), service.getPublicKey())) {
                    Console.error("A chave privada VAPID não corresponde à chave pública: são de pares diferentes.");
                    return null;
                }

                Console.log("WebPushAPI inicializado. Encoding=%s, Subject=%s", VAPID_ENCODING, subject);
                Console.debug("Public Key: %d chars / %d bytes", publicKey.length(), pubBytes.length);
                return new ActiveConfig(service, publicKey, privateKey, subject);
            } catch (Exception e) {
                Console.error("Falha ao inicializar WebPushAPI", e);
                return null;
            }
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

        // ---------- Envio ----------

        /**
         * Põe o envio na fila do módulo. O future devolvido sempre completa normalmente, com o
         * {@link SendResult} — também quando a fila está cheia (na hora) ou o envio falha.
         */
        static CompletableFuture<SendResult> submit(Subscription subscription, String payload, int ttl,
                Urgency urgency) {
            CompletableFuture<SendResult> future = new CompletableFuture<>();
            try {
                SendQueue.SENDERS.execute(() -> run(future, subscription, payload, ttl, urgency));
            } catch (RejectedExecutionException full) {
                if (SendQueue.SENDERS.isShutdown()) {
                    future.complete(SendResult.failure(0, "A aplicação está desligando: notificação não enviada"));
                    return future;
                }
                future.complete(SendResult.failure(0, "Fila de envio do Web Push cheia (" + SEND_QUEUE_CAPACITY
                        + " envios aguardando): notificação não enviada"));
                SendQueue.warnQueueFull(endpointHost(subscription));
            }
            return future;
        }

        /**
         * Executa um envio e completa o future — em qualquer caso.
         *
         * <p>O {@code Task} só capturava {@code Exception}: um {@code Error} (dependência
         * transitiva ausente, o {@code OutOfMemoryError} do corpo sem fim) escapava e o future
         * ficava pendente para sempre, com quem esperava por ele. Aqui qualquer
         * {@link Throwable} vira {@link SendResult} de falha, e o {@code finally} garante o
         * resultado até quando nem a mensagem da falha pôde ser montada.</p>
         *
         * <p>O log vem antes do resultado, como no caminho de sucesso: quando o future completa,
         * a linha do envio já está no log.</p>
         */
        private static void run(CompletableFuture<SendResult> future, Subscription subscription, String payload,
                int ttl, Urgency urgency) {
            String host = "?";
            try {
                host = endpointHost(subscription);
                future.complete(send(subscription, payload, ttl, urgency, host));
            } catch (Throwable failure) {
                try {
                    logFailure(host, failure);
                } catch (Throwable ignored) {
                    // Log indisponível não impede o resultado
                }
                try {
                    future.complete(failureResult(failure));
                } catch (Throwable ignored) {
                    // Sem memória nem para a mensagem: o finally completa com o resultado pronto
                }
            } finally {
                if (!future.isDone()) {
                    future.complete(INTERNAL_FAILURE);
                }
            }
        }

        private static SendResult send(Subscription subscription, String payload, int ttl, Urgency urgency,
                String host) throws Exception {
            ActiveConfig config = active;
            if (config == null) {
                return SendResult.failure(0, "WebPushAPI não está inicializado (reset() antes do envio?)");
            }
            if (subscription == null || subscription.keys == null) {
                return SendResult.failure(0, "Assinatura inválida: sem endpoint ou sem chaves");
            }
            if (payload == null) {
                return SendResult.failure(0, "O payload da notificação não pode ser null");
            }
            // A assinatura pode não ter passado por createSubscription: veio do banco, foi criada
            // com new Subscription(...) ou é anterior à conferência de endpoints
            String problem = endpointProblem(subscription.endpoint);
            if (problem != null) {
                return refused(problem, host);
            }

            Console.debug("Enviando notificação | Encoding=%s | Host=%s", VAPID_ENCODING, host);

            Notification.NotificationBuilder notification = Notification.builder()
                    .endpoint(subscription.endpoint)
                    .userPublicKey(subscription.keys.p256dh)
                    .userAuth(subscription.keys.auth)
                    .payload(payload.getBytes(StandardCharsets.UTF_8))
                    .ttl(ttl);
            if (urgency != null) {
                // Sem isto, a urgência pedida pelo chamador não chegava ao push service
                notification.urgency(nl.martijndwars.webpush.Urgency.valueOf(urgency.name()));
            }

            // CRÍTICO: usar preparePost(..., AES128GCM) em vez de send().
            // pushService.send(notification) usa AESGCM por padrão, que gera o
            // header "Crypto-Key: dh=...;p256ecdsa=...=" com padding '=' causando
            // HTTP 403 "crypto-key header had invalid format". O web-push 5.1.2 também não aceita
            // um cliente HTTP próprio: a requisição é montada por ele e enviada pelo nosso cliente,
            // o que tem prazos.
            HttpPost post = ((PushService) config.pushService).preparePost(notification.build(), VAPID_ENCODING);

            // De novo, agora no endereço que vai de fato para a rede (o web-push reescreve o do FCM)
            String finalProblem = uriProblem(post.getURI());
            if (finalProblem != null) {
                return refused(finalProblem, host);
            }

            HttpClientContext context = HttpClientContext.create();
            AtomicBoolean deadlineReached = new AtomicBoolean();
            ScheduledFuture<?> deadline = SendQueue.DEADLINES.schedule(() -> {
                deadlineReached.set(true);
                // Antes do abort: o fechamento do socket não pode prender a única thread de prazos
                shortenSocketTimeout(context.getConnection());
                post.abort(); // fecha o socket: a thread presa na leitura sai com IOException
            }, SEND_DEADLINE_MS, TimeUnit.MILLISECONDS);
            try (CloseableHttpResponse response = Http.CLIENT.execute(post, context)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String reason = printable(response.getStatusLine().getReasonPhrase());
                String retryAfter = response.getFirstHeader("Retry-After") == null ? null
                        : printable(response.getFirstHeader("Retry-After").getValue());
                byte[] body = readPrefix(response.getEntity());
                if (body.length >= MAX_RESPONSE_BODY_BYTES) {
                    // Corpo além do limite: a conexão vai ser descartada no close — sem esperar o servidor
                    shortenSocketTimeout(context.getConnection());
                }
                String responseBody = printable(new String(body, StandardCharsets.UTF_8));
                return interpret(statusCode, reason, responseBody, retryAfter, host);
            } catch (IOException e) {
                if (!deadlineReached.get()) {
                    throw e;
                }
                String message = "Prazo total de " + TimeUnit.MILLISECONDS.toSeconds(SEND_DEADLINE_MS)
                        + " s estourado: envio cancelado";
                Console.warn("%s | Host=%s", message, host);
                return SendResult.failure(0, message);
            } finally {
                deadline.cancel(false);
            }
        }

        /** Envio recusado antes de sair: o endpoint não é de um push service aceito. */
        private static SendResult refused(String problem, String host) {
            String message = "Endpoint recusado: " + problem;
            Console.warn("%s | Host=%s | Para outro push service, use WebPushAPI.allowPushServiceHost(host).",
                    message, host);
            return SendResult.failure(0, message);
        }

        /**
         * Lê no máximo {@link #MAX_RESPONSE_BODY_BYTES} bytes do corpo.
         *
         * <p>Sem try-with-resources no stream, de propósito: fechar o stream do corpo antes do fim
         * faz o HttpClient ler (e descartar) o resto — num corpo sem fim, para sempre. Quem fecha
         * é o {@code response.close()}, que descarta a conexão sem ler mais nada. Um corpo menor
         * que o limite chega ao fim aqui dentro, e aí a conexão volta ao pool para ser reusada.</p>
         *
         * @return Os bytes lidos; {@link #MAX_RESPONSE_BODY_BYTES} deles quando o corpo pode ter mais
         */
        private static byte[] readPrefix(HttpEntity entity) throws IOException {
            if (entity == null)
                return new byte[0];
            InputStream content = entity.getContent();
            if (content == null)
                return new byte[0];
            return content.readNBytes(MAX_RESPONSE_BODY_BYTES);
        }

        /**
         * Reduz a 1 ms o timeout do socket de uma conexão que vai ser fechada.
         *
         * <p>O {@code close()} do {@code SSLSocket} do JDK (TLS 1.3) espera o servidor responder ao
         * encerramento por até o timeout do socket — mesmo com {@code SO_LINGER} 0. Medido: um
         * servidor que nunca responde segurava o envio 10 s no timeout de leitura e mais 10 s no
         * fechamento. Com o timeout reduzido antes, o fechamento não espera.</p>
         */
        private static void shortenSocketTimeout(HttpConnection connection) {
            if (connection == null)
                return;
            try {
                connection.setSocketTimeout(1);
            } catch (RuntimeException alreadyReleased) {
                // Conexão já devolvida ao pool ou fechada: não há fechamento para esperar
            }
        }

        /** Traduz a resposta do push service em {@link SendResult}, registrando no log. */
        private static SendResult interpret(int statusCode, String reason, String responseBody, String retryAfter,
                String host) {
            String details = "Status=" + statusCode + " | Motivo=" + reason + " | Corpo=" + responseBody
                    + " | Retry-After=" + retryAfter + " | Host=" + host;

            if (statusCode >= 200 && statusCode < 300) {
                Console.debug("Notificação enviada com sucesso | %s", details);
                return SendResult.success(statusCode);
            }
            if (statusCode == 410 || statusCode == 404) {
                String msg = "Assinatura inválida/expirada (HTTP " + statusCode + ")";
                Console.warn("%s | %s", msg, details);
                return SendResult.expired(statusCode, msg + " | " + reason);
            }
            if (statusCode == 403) {
                String msg = "Erro de autenticação VAPID (HTTP 403)";
                Console.error("%s | %s", msg, details);
                Console.error("Verifique: o subject é mailto: ou https://? As chaves vieram de generateVapidKeys()? "
                        + "A chave pública usada no front-end (applicationServerKey) é a de getVapidPublicKey()?");
                return SendResult.failure(statusCode, msg + " | " + reason);
            }
            if (statusCode == 429) {
                String msg = "Rate limit (HTTP 429). Retry-After=" + retryAfter;
                Console.warn("%s | %s", msg, details);
                return SendResult.failure(statusCode, msg);
            }
            String msg = "Falha ao enviar notificação (HTTP " + statusCode + ")";
            Console.error("%s | %s", msg, details);
            return SendResult.failure(statusCode, msg + " | " + reason);
        }

        private static SendResult failureResult(Throwable failure) {
            String kind = failure instanceof IOException ? "Falha de rede ao enviar a notificação"
                    : "Erro ao enviar a notificação";
            return SendResult.failure(0, kind + ": " + describe(failure));
        }

        /**
         * Registra a falha sem dado da assinatura: só o host. Falha de rede (timeout, conexão
         * recusada) é rotina e fica numa linha; o resto leva a stack trace.
         */
        private static void logFailure(String host, Throwable failure) {
            if (failure instanceof IOException) {
                Console.error("Falha de rede ao enviar notificação | Host=%s | %s", host, describe(failure));
            } else {
                Console.error("Erro ao enviar notificação | Host=%s", host, failure);
            }
        }

        private static String endpointHost(Subscription subscription) {
            return WebPushAPI.endpointHost(subscription == null ? null : subscription.endpoint);
        }

        // ---------- Assinaturas ----------

        static Subscription newSubscription(String endpoint, String p256dh, String auth) {
            return new Subscription(endpoint, new Subscription.Keys(p256dh, auth));
        }

        static Subscription parseSubscriptionFromJson(String json) {
            JsonElement root;
            try {
                root = JsonParser.parseString(json);
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("JSON de assinatura inválido: " + describe(malformed), malformed);
            }
            if (root == null || !root.isJsonObject()) {
                throw new IllegalArgumentException("O JSON de assinatura deve ser um objeto "
                        + "{\"endpoint\": ..., \"keys\": {\"p256dh\": ..., \"auth\": ...}}");
            }
            JsonObject object = root.getAsJsonObject();
            String endpoint = textField(object, "endpoint");
            JsonElement keys = object.get("keys");
            if (keys == null || !keys.isJsonObject()) {
                throw new IllegalArgumentException("JSON de assinatura sem o objeto \"keys\"");
            }
            String p256dh = textField(keys.getAsJsonObject(), "p256dh");
            String auth = textField(keys.getAsJsonObject(), "auth");
            requireAllowedEndpoint(endpoint);
            return newSubscription(endpoint, p256dh, auth);
        }

        private static String textField(JsonObject object, String name) {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("JSON de assinatura sem o campo de texto \"" + name + "\"");
            }
            return value.getAsString();
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

        // ---------- Utilitários ----------

        private static void ensureBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
                Console.debug("BouncyCastleProvider registrado com sucesso");
            }
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

        /**
         * Cliente HTTP dos envios, criado no primeiro envio.
         *
         * <p>Era {@code HttpClients.createDefault()}: sem prazo nenhum, 2 conexões por host e
         * seguindo redirecionamentos — um redirecionamento levava a requisição para qualquer
         * lugar, mesmo com o endpoint conferido.</p>
         */
        private static final class Http {

            static final CloseableHttpClient CLIENT = build();

            private Http() {
            }

            private static CloseableHttpClient build() {
                RequestConfig requestConfig = RequestConfig.custom()
                        .setConnectTimeout(CONNECT_TIMEOUT_MS)
                        .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS)
                        .setSocketTimeout(SOCKET_TIMEOUT_MS)
                        .setRedirectsEnabled(false)
                        .build();
                ConnectionConfig connectionConfig = ConnectionConfig.custom()
                        .setMessageConstraints(MessageConstraints.custom()
                                .setMaxLineLength(MAX_HEADER_LINE_LENGTH)
                                .setMaxHeaderCount(MAX_HEADER_COUNT)
                                .build())
                        .build();
                return HttpClients.custom()
                        .setDefaultRequestConfig(requestConfig)
                        .setDefaultConnectionConfig(connectionConfig)
                        .setRequestExecutor(new QuickCloseRequestExecutor())
                        .disableRedirectHandling()
                        // Push service não usa cookie, e um pote de cookies compartilhado por todos os
                        // envios só guardaria o que um servidor quisesse devolver aos outros
                        .disableCookieManagement()
                        // A resposta é minúscula; sem Accept-Encoding, nada de descompressão
                        .disableContentCompression()
                        .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                        .setMaxConnTotal(MAX_CONNECTIONS_TOTAL)
                        // Uma thread daemon do próprio HttpClient ("Connection evictor")
                        .evictIdleConnections(IDLE_CONNECTION_SECONDS, TimeUnit.SECONDS)
                        .build();
            }
        }

        /**
         * O {@link HttpRequestExecutor} do HttpCore, com uma diferença: quando a troca falha, a
         * conexão é fechada sem esperar o servidor (ver {@code shortenSocketTimeout}).
         *
         * <p>O original fecha a conexão por dentro, antes de repassar a exceção, sem ponto de
         * extensão no meio — por isso o {@code execute} é reescrito aqui, com a mesma lógica
         * dos métodos protegidos que ele mesmo usa.</p>
         */
        private static final class QuickCloseRequestExecutor extends HttpRequestExecutor {

            @Override
            public HttpResponse execute(HttpRequest request, HttpClientConnection conn, HttpContext context)
                    throws IOException, HttpException {
                try {
                    HttpResponse response = doSendRequest(request, conn, context);
                    return response != null ? response : doReceiveResponse(request, conn, context);
                } catch (IOException | HttpException | RuntimeException failure) {
                    shortenSocketTimeout(conn);
                    try {
                        conn.close();
                    } catch (IOException ignored) {
                        // A conexão já estava quebrada: a falha que importa é a de cima
                    }
                    throw failure;
                }
            }
        }
    }

    // ==================== CLASSES DE APOIO (API PÚBLICA) ====================

    /**
     * Par de chaves VAPID geradas por {@link #generateVapidKeys()}.
     */
    public static final class VapidKeys {

        /** Chave pública (Base64URL, 87 caracteres): vai para o front-end como {@code applicationServerKey}. */
        public final String publicKey;

        /** Chave privada (Base64URL, 43 caracteres): fica só no servidor — nunca a exponha. */
        public final String privateKey;

        /**
         * Cria o par, na ordem (pública, privada).
         *
         * @param publicKey  Chave pública VAPID (Base64URL)
         * @param privateKey Chave privada VAPID (Base64URL)
         * @throws NullPointerException se alguma das chaves for {@code null}
         */
        public VapidKeys(String publicKey, String privateKey) {
            this.publicKey = Objects.requireNonNull(publicKey, "publicKey não pode ser null");
            this.privateKey = Objects.requireNonNull(privateKey, "privateKey não pode ser null");
        }

        /**
         * Representação para log: mostra a chave pública e esconde a privada.
         *
         * @return Texto com a chave pública e {@code [PROTECTED]} no lugar da privada
         */
        @Override
        public String toString() {
            return "VapidKeys{publicKey='" + publicKey + "', privateKey='[PROTECTED]'}";
        }
    }

    /**
     * Resultado de um envio de notificação: sucesso, assinatura expirada ou falha.
     *
     * <p>Falhas que não chegaram a uma resposta HTTP (rede, prazo, endpoint recusado, fila
     * cheia) têm {@link #getStatusCode()} {@code 0}.</p>
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

        /**
         * O push service aceitou a notificação (HTTP 2xx).
         *
         * @return {@code true} se o envio foi aceito
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * A assinatura não existe mais no push service (HTTP 404 ou 410): remova-a do banco —
         * novos envios para ela vão falhar do mesmo jeito.
         *
         * @return {@code true} se a assinatura expirou
         */
        public boolean isExpired() {
            return expired;
        }

        /**
         * Status HTTP devolvido pelo push service.
         *
         * @return O status, ou {@code 0} se o envio falhou antes de haver resposta
         */
        public int getStatusCode() {
            return statusCode;
        }

        /**
         * Motivo da falha, em português.
         *
         * @return A mensagem de erro, ou {@code null} no sucesso
         */
        public String getError() {
            return error;
        }

        /**
         * Representação para log.
         *
         * @return Texto com sucesso, expiração, status e erro
         */
        @Override
        public String toString() {
            return "SendResult{success=" + success + ", expired=" + expired + ", statusCode=" + statusCode
                    + (error != null ? ", error='" + error + "'" : "") + "}";
        }
    }

    /**
     * Urgência da notificação (cabeçalho {@code Urgency} da RFC 8030): influencia a entrega
     * quando o dispositivo está em modo de economia de energia.
     */
    public enum Urgency {
        /** Pode esperar bastante (ex: novidades sem prazo); entregue só em condições ideais. */
        VERY_LOW,
        /** Pode esperar (ex: promoções); entregue quando o dispositivo estiver ativo. */
        LOW,
        /** Padrão: entregue normalmente. */
        NORMAL,
        /** Imediata (ex: mensagem direta, alerta), mesmo com o dispositivo em economia de energia. */
        HIGH
    }
}
