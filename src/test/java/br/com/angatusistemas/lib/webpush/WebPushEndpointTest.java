package br.com.angatusistemas.lib.webpush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import nl.martijndwars.webpush.Subscription;

/**
 * Conferência dos endpoints de Web Push: só push services conhecidos, por {@code https}.
 *
 * <p>O endpoint vem do navegador — ou seja, de quem quiser forjá-lo. Sem a conferência, o
 * servidor fazia POST em qualquer endereço recebido: o revisor mediu um envio chegando a
 * {@code http://127.0.0.1:<porta>/internal/admin}. Nenhum teste aqui usa a rede: a conferência
 * é só sobre o texto do endpoint.</p>
 *
 * @author Angatu Sistemas
 */
class WebPushEndpointTest {

    private static final String P256DH =
            "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM";
    private static final String AUTH = "tBHItJI5svbpez7KI4CCXg";
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/cXjk5k1lT3c:APA91bHtoken";

    @ParameterizedTest
    @ValueSource(strings = {
            FCM,
            "https://fcm.googleapis.com/wp/cXjk5k1lT3c",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAAABhtoken",
            "https://web.push.apple.com/QGuQyavXutnMHtoken",
            "https://wns2-par02p.notify.windows.com/w/?token=BQYAAABtoken",
            "https://FCM.GoogleAPIs.com/fcm/send/abc",
            "HTTPS://fcm.googleapis.com/fcm/send/abc",
            "https://fcm.googleapis.com:443/fcm/send/abc" })
    @DisplayName("aceita endpoints https dos push services dos navegadores")
    void acceptsBrowserPushServices(String endpoint) {
        assertTrue(WebPushAPI.isAllowedEndpoint(endpoint), endpoint);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/internal/admin",
            "https://127.0.0.1/internal/admin",
            "https://localhost/push",
            "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.5/push",
            "https://[::1]/push",
            "http://fcm.googleapis.com/fcm/send/abc",
            "ftp://fcm.googleapis.com/abc",
            "https://fcm.googleapis.com.evil.example/abc",
            "https://evilfcm.googleapis.com/abc",
            "https://android.googleapis.com/gcm/send/abc",
            "https://push.services.mozilla.com/wpush/v2/abc",
            "https://xpush.services.mozilla.com/wpush/v2/abc",
            "https://push.apple.com.evil.example/abc",
            "https://notify.windows.com/w/?token=abc",
            "https://fcm.googleapis.com@evil.example/abc",
            "https://user:senha@fcm.googleapis.com/abc",
            "https://fcm.googleapis.com:8443/abc",
            "https:fcm.googleapis.com/abc",
            "https:///abc",
            "fcm.googleapis.com/fcm/send/abc",
            "não é uma url" })
    @DisplayName("recusa endpoint interno, sem https, parecido com um push service ou com truque de URL")
    void rejectsInternalAndLookAlikeEndpoints(String endpoint) {
        assertFalse(WebPushAPI.isAllowedEndpoint(endpoint), endpoint);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("recusa endpoint nulo ou vazio sem lançar exceção")
    void rejectsBlankEndpoints(String endpoint) {
        assertFalse(WebPushAPI.isAllowedEndpoint(endpoint));
    }

    @Test
    @DisplayName("recusa endpoint gigante, mas aceita um longo dentro do limite")
    void rejectsOversizedEndpoints() {
        String prefix = "https://fcm.googleapis.com/fcm/send/";
        assertTrue(WebPushAPI.isAllowedEndpoint(prefix + "a".repeat(4_096 - prefix.length())));
        assertFalse(WebPushAPI.isAllowedEndpoint(prefix + "a".repeat(4_097 - prefix.length())));
    }

    @Test
    @DisplayName("createSubscription recusa endpoint interno com IllegalArgumentException em português")
    void createSubscriptionRejectsInternalEndpoint() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WebPushAPI.createSubscription("http://127.0.0.1:8080/internal/admin", P256DH, AUTH));
        assertTrue(e.getMessage().contains("precisa usar https"), e.getMessage());
        assertTrue(e.getMessage().contains("allowPushServiceHost"), e.getMessage());

        IllegalArgumentException host = assertThrows(IllegalArgumentException.class,
                () -> WebPushAPI.createSubscription("https://169.254.169.254/latest/meta-data/", P256DH, AUTH));
        assertTrue(host.getMessage().contains("não é um push service conhecido"), host.getMessage());
    }

    @Test
    @DisplayName("createSubscription aceita push service conhecido e preserva endpoint e chaves")
    void createSubscriptionKeepsTheData() {
        Subscription subscription = WebPushAPI.createSubscription(FCM, P256DH, AUTH);

        assertEquals(FCM, subscription.endpoint);
        assertEquals(P256DH, subscription.keys.p256dh);
        assertEquals(AUTH, subscription.keys.auth);
    }

    @Test
    @DisplayName("createSubscription continua recusando null com NullPointerException")
    void createSubscriptionRejectsNulls() {
        assertThrows(NullPointerException.class, () -> WebPushAPI.createSubscription(null, P256DH, AUTH));
        assertThrows(NullPointerException.class, () -> WebPushAPI.createSubscription(FCM, null, AUTH));
        assertThrows(NullPointerException.class, () -> WebPushAPI.createSubscription(FCM, P256DH, null));
    }

    @Test
    @DisplayName("allowPushServiceHost autoriza só o host exato, ainda exigindo https na porta 443")
    void allowsAnExactHost() {
        String endpoint = "https://push.exato-teste.com.br/abc";
        assertFalse(WebPushAPI.isAllowedEndpoint(endpoint));

        WebPushAPI.allowPushServiceHost("  Push.Exato-Teste.com.br ");

        assertTrue(WebPushAPI.isAllowedEndpoint(endpoint));
        assertFalse(WebPushAPI.isAllowedEndpoint("https://outro.push.exato-teste.com.br/abc"));
        assertFalse(WebPushAPI.isAllowedEndpoint("http://push.exato-teste.com.br/abc"));
        assertFalse(WebPushAPI.isAllowedEndpoint("https://push.exato-teste.com.br:8443/abc"));
    }

    @Test
    @DisplayName("allowPushServiceHost com curinga autoriza os subdomínios, mas não o próprio domínio")
    void allowsWildcardSubdomains() {
        WebPushAPI.allowPushServiceHost("*.curinga-teste.com.br");

        assertTrue(WebPushAPI.isAllowedEndpoint("https://a.curinga-teste.com.br/x"));
        assertTrue(WebPushAPI.isAllowedEndpoint("https://a.b.curinga-teste.com.br/x"));
        assertFalse(WebPushAPI.isAllowedEndpoint("https://curinga-teste.com.br/x"));
        assertFalse(WebPushAPI.isAllowedEndpoint("https://xcuringa-teste.com.br/x"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "*", "*.com", "localhost", "127.0.0.1", "10.0.0.5",
            "https://push.exemplo.com", "push.exemplo.com/caminho", "push.exemplo.com:8443", "*.*.exemplo.com",
            "push..exemplo.com", "-push.exemplo.com", "push.exemplo.com.", "push exemplo.com" })
    @DisplayName("allowPushServiceHost recusa o que não é um host com domínio")
    void rejectsInvalidHostPatterns(String pattern) {
        assertThrows(IllegalArgumentException.class, () -> WebPushAPI.allowPushServiceHost(pattern));
    }

    @Test
    @DisplayName("allowPushServiceHost recusa null com NullPointerException")
    void rejectsNullHostPattern() {
        assertThrows(NullPointerException.class, () -> WebPushAPI.allowPushServiceHost(null));
    }

    @Test
    @DisplayName("o log recebe só o host do endpoint, nunca o caminho com o token do aparelho")
    void endpointHostKeepsOnlyTheHost() {
        assertEquals("fcm.googleapis.com", WebPushAPI.endpointHost("https://fcm.googleapis.com/fcm/send/TOKEN-DO-APARELHO"));
        assertEquals("wns2-par02p.notify.windows.com",
                WebPushAPI.endpointHost("https://wns2-par02p.notify.windows.com/w/?token=SEGREDO"));
        assertEquals("?", WebPushAPI.endpointHost(null));
        assertEquals("?", WebPushAPI.endpointHost("não é uma url"));
        assertEquals("?", WebPushAPI.endpointHost("https:///sem-host"));
    }
}
