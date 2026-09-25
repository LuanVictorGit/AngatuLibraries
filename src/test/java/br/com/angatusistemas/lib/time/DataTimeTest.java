package br.com.angatusistemas.lib.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura estrita das datas brasileiras (sem 31/02 virando 28/02) e nomes de mês e de dia em
 * português, qualquer que seja o idioma da JVM.
 *
 * @author Angatu Sistemas
 */
class DataTimeTest {

    @Test
    @DisplayName("isValidDate rejeita datas que não existem no calendário")
    void isValidDateRejectsImpossibleDates() {
        assertFalse(DataTime.isValidDate("31/02/2025"));
        assertFalse(DataTime.isValidDate("30/02/2024"));
        assertFalse(DataTime.isValidDate("29/02/2023"), "2023 não é bissexto");
        assertFalse(DataTime.isValidDate("31/04/2025"), "abril tem 30 dias");
        assertFalse(DataTime.isValidDate("00/01/2025"));
        assertFalse(DataTime.isValidDate("12/13/2025"));
        assertFalse(DataTime.isValidDate("2025-12-25"));
        assertTrue(DataTime.isValidDate("29/02/2024"), "2024 é bissexto");
        assertTrue(DataTime.isValidDate("25/12/2025"));
        assertTrue(DataTime.isValidDate("01/01/0001"));
    }

    @Test
    @DisplayName("parseDate lança exceção para data inexistente em vez de ajustá-la")
    void parseDateThrowsForImpossibleDate() {
        assertThrows(DateTimeParseException.class, () -> DataTime.parseDate("31/02/2025"));
        assertThrows(DateTimeParseException.class, () -> DataTime.parseDate("31/04/2025"));
        assertEquals(LocalDate.of(2025, 12, 25), DataTime.parseDate("25/12/2025"));
        assertEquals(LocalDate.of(2024, 2, 29), DataTime.parseDate("29/02/2024"));
    }

    @Test
    @DisplayName("isValidDateTime e parseDateTime também são estritos, na data e na hora")
    void dateTimeParsingIsStrict() {
        assertFalse(DataTime.isValidDateTime("30/02/2024 - 10:00"));
        assertFalse(DataTime.isValidDateTime("29/02/2024 - 24:00"));
        assertFalse(DataTime.isValidDateTime("29/02/2024 - 10:60"));
        assertTrue(DataTime.isValidDateTime("29/02/2024 - 23:59"));
        assertEquals(LocalDateTime.of(2025, 12, 25, 14, 30), DataTime.parseDateTime("25/12/2025 - 14:30"));
        assertThrows(DateTimeParseException.class, () -> DataTime.parseDateTime("31/06/2025 - 08:00"));
    }

    @Test
    @DisplayName("validação de nulo devolve false em vez de lançar NullPointerException")
    void validationOfNullReturnsFalse() {
        assertFalse(DataTime.isValidDate(null));
        assertFalse(DataTime.isValidDateTime(null));
    }

    @Test
    @DisplayName("nomes de mês e de dia saem em português mesmo com a JVM em inglês")
    void monthAndDayNamesArePortugueseRegardlessOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String formatted = DataTime.formatCustom(LocalDateTime.of(2026, 9, 23, 10, 0),
                    "EEEE, dd 'de' MMMM 'de' yyyy");
            assertEquals("quarta-feira, 23 de setembro de 2026", formatted);
            assertEquals("dez.", DataTime.formatCustom(LocalDateTime.of(2026, 12, 1, 0, 0), "MMM"),
                    "abreviação do CLDR em pt-BR");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("a formatação de saída continua igual à de antes")
    void outputFormattingIsUnchanged() {
        assertEquals("03/08/2026", DataTime.formatDate(LocalDate.of(2026, 8, 3)));
        assertEquals("03/08/2026 - 14:30", DataTime.formatDateTime(LocalDateTime.of(2026, 8, 3, 14, 30)));
        assertEquals("03/08/2026", LocalDate.of(2026, 8, 3).format(DataTime.DATE_BR_FORMATTER));
        assertTrue(DataTime.getData().matches("\\d{2}/\\d{2}/\\d{4} - \\d{2}:\\d{2}"), DataTime.getData());
    }

    @Test
    @DisplayName("parseCustom segue o modo SMART documentado e aceita padrões com yyyy")
    void parseCustomKeepsTheDocumentedSmartResolution() {
        assertEquals(LocalDateTime.of(2025, 4, 3, 10, 30), DataTime.parseCustom("2025/04/03 10:30", "yyyy/MM/dd HH:mm"));
        assertEquals(LocalDateTime.of(2025, 2, 28, 10, 0), DataTime.parseCustom("2025/02/31 10:00", "yyyy/MM/dd HH:mm"));
        assertThrows(IllegalArgumentException.class, () -> DataTime.formatCustom(LocalDateTime.now(), "padrão {inválido}"));
    }

    @Test
    @DisplayName("muitos padrões diferentes continuam funcionando depois do teto de padrões guardados")
    void manyDistinctPatternsKeepWorkingPastTheCacheCeiling() {
        LocalDateTime moment = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        for (int i = 0; i < 600; i++) {
            String pattern = "yyyy-MM-dd '" + i + "'";
            assertEquals("2026-01-02 " + i, DataTime.formatCustom(moment, pattern));
        }
    }
}
