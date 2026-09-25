package br.com.angatusistemas.lib.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Valor monetário enviado ao Mercado Pago: sempre em reais e centavos, arredondamento comercial e
 * nunca zero ou negativo.
 *
 * @author Angatu Sistemas
 */
class MercadoPagoMoneyTest {

    @Test
    @DisplayName("o double 19,9 × 3 vira 59,70, e não 59,699999999999996")
    void roundsBinaryFloatingPointNoise() {
        double amount = 19.9 * 3;
        assertNotEquals(59.7, amount); // o defeito: é isto que um double carrega
        assertEquals(new BigDecimal("59.70"), MercadoPagoAPI.toMoney(amount));
        assertEquals(new BigDecimal("0.30"), MercadoPagoAPI.toMoney(0.1 + 0.2));
    }

    @Test
    @DisplayName("o arredondamento é comercial sobre o decimal escrito: 1,005 vira 1,01")
    void roundsHalfUpOnTheShortestDecimalRepresentation() {
        assertEquals(new BigDecimal("1.01"), MercadoPagoAPI.toMoney(1.005));
        assertEquals(new BigDecimal("0.01"), MercadoPagoAPI.toMoney(0.005));
        assertEquals(new BigDecimal("59.70"), MercadoPagoAPI.toMoney(new BigDecimal("59.695")));
        assertEquals(new BigDecimal("59.69"), MercadoPagoAPI.toMoney(new BigDecimal("59.694")));
    }

    @Test
    @DisplayName("o valor sai sempre com duas casas decimais")
    void alwaysHasTwoDecimalPlaces() {
        assertEquals("10.00", MercadoPagoAPI.toMoney(10).toPlainString());
        assertEquals("59.70", MercadoPagoAPI.toMoney(new BigDecimal("59.7")).toPlainString());
        assertEquals(2, MercadoPagoAPI.toMoney(new BigDecimal("1E+3")).scale());
    }

    @Test
    @DisplayName("valor que arredonda para zero, zero ou negativo é recusado")
    void rejectsNonPositiveAmounts() {
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney(0.004));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney(0));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney(-10));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney(new BigDecimal("-0.01")));
    }

    @Test
    @DisplayName("NaN, infinito e nulo são recusados com mensagem clara")
    void rejectsInvalidNumbers() {
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> MercadoPagoAPI.toMoney((BigDecimal) null));
    }
}
