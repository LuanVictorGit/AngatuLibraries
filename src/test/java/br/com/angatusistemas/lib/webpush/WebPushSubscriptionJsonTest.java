package br.com.angatusistemas.lib.webpush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import nl.martijndwars.webpush.Subscription;

/**
 * Leitura e escrita do JSON de assinatura ({@code PushSubscription.toJSON()} do navegador).
 *
 * <p>O JSON chega do navegador, então é entrada hostil: qualquer defeito tem de virar
 * {@link IllegalArgumentException} com o motivo — pronto para uma resposta 400 —, e não um
 * {@code NullPointerException}, {@code IllegalStateException} ou exceção do Gson.</p>
 *
 * @author Angatu Sistemas
 */
class WebPushSubscriptionJsonTest {

    private static final String P256DH =
            "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM";
    private static final String AUTH = "tBHItJI5svbpez7KI4CCXg";
    private static final String ENDPOINT = "https://updates.push.services.mozilla.com/wpush/v2/gAAAAABhtoken";

    /** O formato exato do {@code PushSubscription.toJSON()}, com {@code expirationTime}. */
    private static final String BROWSER_JSON = "{\"endpoint\":\"" + ENDPOINT + "\",\"expirationTime\":null,"
            + "\"keys\":{\"p256dh\":\"" + P256DH + "\",\"auth\":\"" + AUTH + "\"}}";

    @Test
    @DisplayName("lê o JSON exato que o navegador envia")
    void parsesTheBrowserJson() {
        Subscription subscription = WebPushAPI.parseSubscriptionFromJson(BROWSER_JSON);

        assertEquals(ENDPOINT, subscription.endpoint);
        assertEquals(P256DH, subscription.keys.p256dh);
        assertEquals(AUTH, subscription.keys.auth);
    }

    @Test
    @DisplayName("o JSON gravado por subscriptionToJson volta idêntico")
    void roundTripsThroughSubscriptionToJson() {
        String json = WebPushAPI.subscriptionToJson(WebPushAPI.parseSubscriptionFromJson(BROWSER_JSON));
        Subscription again = WebPushAPI.parseSubscriptionFromJson(json);

        assertEquals(ENDPOINT, again.endpoint);
        assertEquals(P256DH, again.keys.p256dh);
        assertEquals(AUTH, again.keys.auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{",
            "nada disso é JSON",
            "[]",
            "null",
            "\"texto\"",
            "{}",
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\"}",
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\",\"keys\":\"texto\"}",
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\",\"keys\":{\"auth\":\"a\"}}",
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\",\"keys\":{\"p256dh\":\"p\"}}",
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\",\"keys\":{\"p256dh\":\"p\",\"auth\":123}}",
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\",\"keys\":{\"p256dh\":{},\"auth\":\"a\"}}",
            "{\"endpoint\":123,\"keys\":{\"p256dh\":\"p\",\"auth\":\"a\"}}",
            "{\"endpoint\":null,\"keys\":{\"p256dh\":\"p\",\"auth\":\"a\"}}" })
    @DisplayName("JSON malformado ou incompleto vira IllegalArgumentException")
    void rejectsMalformedJson(String json) {
        assertThrows(IllegalArgumentException.class, () -> WebPushAPI.parseSubscriptionFromJson(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/internal/admin",
            "https://169.254.169.254/latest/meta-data/",
            "https://fcm.googleapis.com.evil.example/x" })
    @DisplayName("JSON bem formado com endpoint fora dos push services é recusado")
    void rejectsJsonWithForbiddenEndpoint(String endpoint) {
        String json = "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + P256DH + "\",\"auth\":\"" + AUTH + "\"}}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WebPushAPI.parseSubscriptionFromJson(json));
        assertTrue(e.getMessage().startsWith("Endpoint de Web Push recusado"), e.getMessage());
    }

    @Test
    @DisplayName("JSON nulo continua sendo NullPointerException")
    void rejectsNullJson() {
        assertThrows(NullPointerException.class, () -> WebPushAPI.parseSubscriptionFromJson(null));
    }
}
