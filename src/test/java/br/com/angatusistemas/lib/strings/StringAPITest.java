package br.com.angatusistemas.lib.strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Máscara que não falha aberta, código aleatório criptográfico, dígitos só de 0 a 9,
 * {@code repeat} sem estouro e conversões de caixa que não dependem do idioma da JVM.
 *
 * @author Angatu Sistemas
 */
class StringAPITest {

    @Test
    @DisplayName("maskString com fim além do tamanho mascara até o último caractere")
    void maskStringClampsEndBeyondLength() {
        assertEquals("***********", StringAPI.maskString("41111111111", 0, 12, '*'));
        assertEquals("4111****", StringAPI.maskString("41111111", 4, 99, '*'));
    }

    @Test
    @DisplayName("maskString com início negativo começa do primeiro caractere")
    void maskStringClampsNegativeStart() {
        assertEquals("****5678", StringAPI.maskString("12345678", -3, 4, '*'));
    }

    @Test
    @DisplayName("maskString mantém o comportamento dentro dos limites e nos casos vazios")
    void maskStringKeepsRegularBehavior() {
        assertEquals("****-5678", StringAPI.maskString("1234-5678", 0, 4, '*'));
        assertEquals("abc", StringAPI.maskString("abc", 2, 2, '*'), "intervalo vazio devolve a original");
        assertEquals("abc", StringAPI.maskString("abc", 5, 9, '*'), "intervalo todo fora devolve a original");
        assertEquals("", StringAPI.maskString(null, 0, 4, '*'));
    }

    @Test
    @DisplayName("randomCode gera o tamanho pedido só com letras e dígitos ASCII")
    void randomCodeHasRequestedLengthAndAlphabet() {
        for (int length : new int[] {1, 6, 32, 256}) {
            String code = StringAPI.randomCode(length);
            assertEquals(length, code.length());
            assertTrue(code.matches("[A-Za-z0-9]+"), code);
        }
        assertEquals("", StringAPI.randomCode(0));
        assertEquals("", StringAPI.randomCode(-5));
    }

    @Test
    @DisplayName("randomCode não repete códigos de 12 caracteres em 20 mil gerações")
    void randomCodeDoesNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertTrue(seen.add(StringAPI.randomCode(12)), "código repetido");
        }
    }

    @Test
    @DisplayName("randomCode usa todos os 62 símbolos")
    void randomCodeUsesTheWholeAlphabet() {
        Set<Character> symbols = new HashSet<>();
        for (char c : StringAPI.randomCode(20_000).toCharArray()) {
            symbols.add(c);
        }
        assertEquals(62, symbols.size());
    }

    @Test
    @DisplayName("containsOnlyDigits aceita só 0 a 9, e não dígitos de outros alfabetos")
    void containsOnlyDigitsAcceptsOnlyAsciiDigits() {
        assertTrue(StringAPI.containsOnlyDigits("0123456789"));
        assertFalse(StringAPI.containsOnlyDigits("１２３"), "dígitos de largura total");
        assertFalse(StringAPI.containsOnlyDigits("١٢٣"), "dígitos arábico-índicos");
        assertFalse(StringAPI.containsOnlyDigits("12a"));
        assertFalse(StringAPI.containsOnlyDigits(""));
        assertFalse(StringAPI.containsOnlyDigits(null));
    }

    @Test
    @DisplayName("extractNumbers extrai só 0 a 9")
    void extractNumbersKeepsOnlyAsciiDigits() {
        assertEquals("4567", StringAPI.extractNumbers("CPF: ١٢٣.456-7"));
        assertEquals("123", StringAPI.extractNumbers("a1b2c3"));
        assertEquals("", StringAPI.extractNumbers(null));
    }

    @Test
    @DisplayName("containsOnlyLetters aceita letras acentuadas, como documentado")
    void containsOnlyLettersAcceptsAccentedLetters() {
        assertTrue(StringAPI.containsOnlyLetters("João"));
        assertTrue(StringAPI.containsOnlyLetters("Conceição"));
        assertFalse(StringAPI.containsOnlyLetters("João Silva"));
        assertFalse(StringAPI.containsOnlyLetters("R2D2"));
    }

    @Test
    @DisplayName("repeat valida o tamanho do resultado em vez de estourar o int")
    void repeatRejectsResultsBeyondTheStringLimit() {
        assertEquals("abcabcabc", StringAPI.repeat("abc", 3));
        assertEquals("", StringAPI.repeat("abc", 0));
        assertEquals("", StringAPI.repeat(null, 3));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StringAPI.repeat("abc", 1_000_000_000));
        assertTrue(error.getMessage().contains("3000000000"), error.getMessage());
    }

    @Test
    @DisplayName("conversões de caixa não mudam com a JVM em turco")
    void caseConversionsIgnoreTurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("Itapira", StringAPI.capitalize("ITAPIRA"));
            assertEquals("id_cliente", StringAPI.toSnakeCase("ID CLIENTE"));
            assertEquals("idCliente", StringAPI.toCamelCase("ID CLIENTE"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
