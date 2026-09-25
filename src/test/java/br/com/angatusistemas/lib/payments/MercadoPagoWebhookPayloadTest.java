package br.com.angatusistemas.lib.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura do ID de pagamento no corpo do webhook, nos formatos em que ele chega de verdade.
 *
 * @author Angatu Sistemas
 */
class MercadoPagoWebhookPayloadTest {

    private static Map<String, Object> payload(Object type, Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("data", data);
        return body;
    }

    @Test
    @DisplayName("ID como texto é lido, com ou sem espaços")
    void readsTextId() {
        assertEquals(Optional.of(123456789L), MercadoPagoAPI.parsePaymentId("123456789"));
        assertEquals(Optional.of(123456789L), MercadoPagoAPI.parsePaymentId(" 123456789 "));
    }

    @Test
    @DisplayName("ID numérico vindo do Gson como Double (1.23456789E8) é lido como inteiro")
    void readsGsonDoubleId() {
        assertEquals(Optional.of(123456789L), MercadoPagoAPI.parsePaymentId(Double.valueOf(1.23456789E8)));
        assertEquals(Optional.of(98765432101L), MercadoPagoAPI.parsePaymentId(Double.valueOf(98765432101d)));
        assertEquals(Optional.of(42L), MercadoPagoAPI.parsePaymentId(Integer.valueOf(42)));
        assertEquals(Optional.of(42L), MercadoPagoAPI.parsePaymentId(Long.valueOf(42)));
    }

    @Test
    @DisplayName("número com fração, texto não numérico, valor fora do long e nulo dão vazio")
    void rejectsUnreadableIds() {
        assertTrue(MercadoPagoAPI.parsePaymentId(Double.valueOf(1.5)).isEmpty());
        assertTrue(MercadoPagoAPI.parsePaymentId("abc").isEmpty());
        assertTrue(MercadoPagoAPI.parsePaymentId(new BigInteger("99999999999999999999")).isEmpty());
        assertTrue(MercadoPagoAPI.parsePaymentId(null).isEmpty());
    }

    @Test
    @DisplayName("notificação de pagamento devolve o ID; outros tipos e formatos devolvem vazio sem lançar")
    void extractsOnlyPaymentNotifications() {
        assertEquals(Optional.of(77L),
                MercadoPagoAPI.extractPaymentIdFromWebhook(payload("payment", Map.of("id", "77"))));
        assertEquals(Optional.of(77L),
                MercadoPagoAPI.extractPaymentIdFromWebhook(payload("payment", Map.of("id", Double.valueOf(77)))));
        assertTrue(MercadoPagoAPI.extractPaymentIdFromWebhook(payload("merchant_order", Map.of("id", "77"))).isEmpty());
        assertTrue(MercadoPagoAPI.extractPaymentIdFromWebhook(payload(Integer.valueOf(1), Map.of("id", "77"))).isEmpty());
        assertTrue(MercadoPagoAPI.extractPaymentIdFromWebhook(payload("payment", List.of("77"))).isEmpty());
        assertTrue(MercadoPagoAPI.extractPaymentIdFromWebhook(payload("payment", Map.of("outro", "77"))).isEmpty());
        assertTrue(MercadoPagoAPI.extractPaymentIdFromWebhook(null).isEmpty());
    }
}
