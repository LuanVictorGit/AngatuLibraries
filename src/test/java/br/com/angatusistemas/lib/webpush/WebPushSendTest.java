package br.com.angatusistemas.lib.webpush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.webpush.WebPushAPI.SendResult;
import br.com.angatusistemas.lib.webpush.WebPushAPI.VapidKeys;
import nl.martijndwars.webpush.Subscription;

/**
 * Resultado dos envios: todo future completa, sempre normalmente, e o log não leva segredo.
 *
 * <p>Os envios daqui falham antes de qualquer conexão — endpoint recusado, assinatura nula,
 * chave do navegador inválida —, então nenhum teste usa a rede. É justamente o caminho que
 * falhava: um {@code Error} no envio deixava o future pendente para sempre, o callback só
 * ouvia falar do sucesso e o log gravava a assinatura inteira, com o segredo {@code auth}.</p>
 *
 * @author Angatu Sistemas
 */
class WebPushSendTest {

    private static final String P256DH =
            "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM";
    private static final String AUTH = "tBHItJI5svbpez7KI4CCXg";
    private static final String SUBJECT = "mailto:teste@exemplo.com.br";

    private VapidKeys keys;

    @BeforeEach
    void initialize() {
        WebPushAPI.reset();
        keys = WebPushAPI.generateVapidKeys();
        assertTrue(WebPushAPI.initialize(keys.publicKey, keys.privateKey, SUBJECT));
    }

    @AfterEach
    void reset() {
        WebPushAPI.reset();
    }

    @Test
    @DisplayName("assinatura com endpoint interno não sai do servidor: o future completa normalmente com falha")
    void refusedEndpointCompletesNormallyWithFailure() throws Exception {
        Subscription internal = new Subscription("http://127.0.0.1:8080/internal/admin", new Subscription.Keys(P256DH, AUTH));

        CompletableFuture<SendResult> future = WebPushAPI.sendNotificationAsync(internal, "Título", "Corpo", null);
        SendResult result = future.get(10, TimeUnit.SECONDS);

        assertFalse(future.isCompletedExceptionally());
        assertFalse(result.isSuccess());
        assertFalse(result.isExpired());
        assertEquals(0, result.getStatusCode());
        assertTrue(result.getError().startsWith("Endpoint recusado"), result.getError());
    }

    @Test
    @DisplayName("o callback de sendRawNotificationAsync é chamado também na falha")
    void callbackIsCalledOnFailure() throws Exception {
        Subscription internal = new Subscription("https://169.254.169.254/latest/meta-data/", new Subscription.Keys(P256DH, AUTH));
        CompletableFuture<SendResult> received = new CompletableFuture<>();

        WebPushAPI.sendRawNotificationAsync(internal, "{\"x\":1}", 60, WebPushAPI.Urgency.HIGH, received::complete);

        SendResult result = received.get(10, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("não é um push service conhecido"), result.getError());
    }

    @Test
    @DisplayName("exceção dentro do envio vira SendResult de falha, nunca future excepcional")
    void failureInsideTheSendBecomesAResult() throws Exception {
        Subscription badKey = new Subscription("https://fcm.googleapis.com/fcm/send/abc", new Subscription.Keys("chave-invalida", AUTH));

        CompletableFuture<SendResult> future = WebPushAPI.sendNotificationAsync(badKey, "Título", "Corpo", null);
        SendResult result = future.get(10, TimeUnit.SECONDS);

        assertFalse(future.isCompletedExceptionally());
        assertFalse(result.isSuccess());
        assertEquals(0, result.getStatusCode());
        assertTrue(result.getError().startsWith("Erro ao enviar a notificação"), result.getError());
    }

    @Test
    @DisplayName("assinatura nula completa com falha, sem exceção no future")
    void nullSubscriptionCompletesWithFailure() throws Exception {
        CompletableFuture<SendResult> future = WebPushAPI.sendNotificationAsync(null, "Título", "Corpo", null);

        SendResult result = future.get(10, TimeUnit.SECONDS);

        assertFalse(future.isCompletedExceptionally());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("o log nunca recebe o segredo auth, a chave p256dh nem o caminho do endpoint")
    void logNeverContainsSubscriptionSecrets() throws Exception {
        String secretAuth = "S3gr3doAuthDoNavegador";
        Subscription refused = new Subscription("http://127.0.0.1:8080/push/TOKEN-DO-APARELHO",
                new Subscription.Keys(P256DH, secretAuth));
        Subscription broken = new Subscription("https://fcm.googleapis.com/fcm/send/TOKEN-DO-APARELHO",
                new Subscription.Keys("chave-invalida", secretAuth));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            WebPushAPI.sendNotificationAsync(refused, "Título", "Corpo", null).get(10, TimeUnit.SECONDS);
            WebPushAPI.sendNotificationAsync(broken, "Título", "Corpo", null).get(10, TimeUnit.SECONDS);
        } finally {
            System.setOut(original);
        }

        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Host=127.0.0.1"), log);
        assertTrue(log.contains("Host=fcm.googleapis.com"), log);
        assertFalse(log.contains(secretAuth), log);
        assertFalse(log.contains(P256DH), log);
        assertFalse(log.contains("TOKEN-DO-APARELHO"), log);
    }

    @Test
    @DisplayName("sem inicializar, os métodos de estado respondem e os envios lançam IllegalStateException")
    void statusWithoutInitialization() {
        WebPushAPI.reset();
        Subscription subscription = WebPushAPI.createSubscription("https://fcm.googleapis.com/fcm/send/abc", P256DH, AUTH);

        assertFalse(WebPushAPI.isInitialized());
        assertNull(WebPushAPI.getVapidPublicKey());
        assertFalse(WebPushAPI.testConfiguration());
        assertThrows(IllegalStateException.class,
                () -> WebPushAPI.sendNotificationAsync(subscription, "Título", "Corpo", null));
    }

    @Test
    @DisplayName("initialize recusa chaves trocadas e de pares diferentes, e a configuração anterior continua valendo")
    void initializeRejectsSwappedOrMismatchedKeys() {
        VapidKeys other = WebPushAPI.generateVapidKeys();

        assertFalse(WebPushAPI.initialize(keys.privateKey, keys.publicKey, SUBJECT));
        assertFalse(WebPushAPI.initialize(keys.publicKey, other.privateKey, SUBJECT));
        assertFalse(WebPushAPI.initialize("não-é-base64!", keys.privateKey, SUBJECT));

        assertTrue(WebPushAPI.isInitialized());
        assertEquals(keys.publicKey, WebPushAPI.getVapidPublicKey());
        assertTrue(WebPushAPI.testConfiguration());
    }
}
