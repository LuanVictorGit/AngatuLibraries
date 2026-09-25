package br.com.angatusistemas.lib.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Chave de idempotência das cobranças: a mesma cobrança sempre com a mesma chave, qualquer mudança
 * real com outra, e a chave do chamador validada antes de virar cabeçalho HTTP.
 *
 * @author Angatu Sistemas
 */
class MercadoPagoIdempotencyKeyTest {

    private static final BigDecimal AMOUNT = new BigDecimal("150.00");

    private static String key(String operation, BigDecimal amount, String reference, Object... fields) {
        return MercadoPagoAPI.deriveIdempotencyKey(operation, amount, reference, fields);
    }

    // ==================== CHAVE DERIVADA ====================

    @Test
    @DisplayName("a mesma cobrança gera sempre a mesma chave")
    void sameChargeSameKey() {
        assertEquals(key("pix", AMOUNT, "order-1", "a@b.com", "Pedido 1"),
                key("pix", new BigDecimal("150.00"), "order-1", "a@b.com", "Pedido 1"));
    }

    @Test
    @DisplayName("a derivação é estável entre versões: mudar o cálculo exige mudar o prefixo de versão")
    void derivationIsPinned() {
        // Se este valor mudar, uma nova tentativa depois da atualização da biblioteca sairia com
        // outra chave — e cobraria de novo. Mude IDEMPOTENCY_DERIVATION junto, de propósito.
        assertEquals("31fad9cf-31f2-8229-82a8-2e7e62661bac",
                key("pix", AMOUNT, "order-1234", "cliente@email.com", "Pedido #1234"));
    }

    @Test
    @DisplayName("a chave é um UUID válido (versão 8, variante RFC)")
    void keyIsAWellFormedUuid() {
        String derived = key("card", AMOUNT, "order-1", 3, "visa", "tok_123", "a@b.com", "d");
        UUID uuid = UUID.fromString(derived);
        assertEquals(derived, uuid.toString());
        assertEquals(8, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    @DisplayName("59,699999999999996 e 59,70 são a mesma cobrança depois do arredondamento")
    void normalizedAmountsShareTheKey() {
        assertEquals(key("card", MercadoPagoAPI.toMoney(19.9 * 3), "order-1", "tok"),
                key("card", MercadoPagoAPI.toMoney(new BigDecimal("59.7")), "order-1", "tok"));
    }

    @Test
    @DisplayName("outro pedido, outro valor, outro tipo ou outro token de cartão geram outra chave")
    void anyRealChangeChangesTheKey() {
        String base = key("card", AMOUNT, "order-1", 1, "visa", "tok_1", "a@b.com", "d");
        assertNotEquals(base, key("card", AMOUNT, "order-2", 1, "visa", "tok_1", "a@b.com", "d"));
        assertNotEquals(base, key("card", new BigDecimal("150.01"), "order-1", 1, "visa", "tok_1", "a@b.com", "d"));
        assertNotEquals(base, key("pix", AMOUNT, "order-1", 1, "visa", "tok_1", "a@b.com", "d"));
        assertNotEquals(base, key("card", AMOUNT, "order-1", 1, "visa", "tok_2", "a@b.com", "d"));
        assertNotEquals(base, key("card", AMOUNT, "order-1", 2, "visa", "tok_1", "a@b.com", "d"));
    }

    @Test
    @DisplayName("campos vizinhos não se confundem: \"ab\"+\"c\" é diferente de \"a\"+\"bc\", e nulo de vazio")
    void fieldBoundariesAreUnambiguous() {
        assertNotEquals(key("pix", AMOUNT, "order-1", "ab", "c"), key("pix", AMOUNT, "order-1", "a", "bc"));
        assertNotEquals(key("pix", AMOUNT, "order-1", (Object) null), key("pix", AMOUNT, "order-1", ""));
    }

    @Test
    @DisplayName("metadados montados em outra ordem dão a mesma chave; outro valor dá outra")
    void metadataOrderDoesNotMatter() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("pedido", 10);
        first.put("canal", "loja");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("canal", "loja");
        second.put("pedido", 10);
        assertEquals(key("metadata", AMOUNT, "order-1", "pix", first), key("metadata", AMOUNT, "order-1", "pix", second));

        second.put("canal", "app");
        assertNotEquals(key("metadata", AMOUNT, "order-1", "pix", first), key("metadata", AMOUNT, "order-1", "pix", second));
    }

    @Test
    @DisplayName("sem referência externa não há chave derivada: o SDK sorteia uma, como antes")
    void noReferenceNoDerivedKey() {
        assertNull(key("pix", AMOUNT, null, "a@b.com"));
        assertNull(key("pix", AMOUNT, "   ", "a@b.com"));
    }

    // ==================== CHAVE DO CHAMADOR ====================

    @Test
    @DisplayName("a chave informada pelo chamador vence a derivada e perde os espaços das pontas")
    void explicitKeyWins() {
        assertEquals("pedido-1-tentativa-2",
                MercadoPagoAPI.resolveIdempotencyKey("  pedido-1-tentativa-2 ", "card", AMOUNT, "order-1", "a@b.com"));
    }

    @Test
    @DisplayName("chave nula ou em branco usa a derivada")
    void blankExplicitKeyFallsBackToDerived() {
        String derived = key("card", AMOUNT, "order-1", "a@b.com");
        assertEquals(derived, MercadoPagoAPI.resolveIdempotencyKey(null, "card", AMOUNT, "order-1", "a@b.com"));
        assertEquals(derived, MercadoPagoAPI.resolveIdempotencyKey(" \t ", "card", AMOUNT, "order-1", "a@b.com"));
    }

    @Test
    @DisplayName("chave com quebra de linha, espaço interno, tabulação ou fora do ASCII é recusada")
    void rejectsKeysThatWouldBreakTheHeader() {
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.normalizeIdempotencyKey("abc\r\nX-Evil: 1"));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.normalizeIdempotencyKey("pedido 1"));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.normalizeIdempotencyKey("pedido\t1"));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.normalizeIdempotencyKey("pedido-ç"));
    }
}
