package br.com.angatusistemas.lib.gson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

import br.com.angatusistemas.lib.time.DataTime;

/**
 * Os tipos de {@code java.time} do {@link GsonAPI} em texto ISO 8601: ida e volta, {@code null}
 * nos dois sentidos, e entrada inválida como {@link JsonSyntaxException} (erro 400), nunca como
 * {@code DateTimeParseException} (erro 500).
 *
 * @author Angatu Sistemas
 */
class GsonAdaptersTest {

    /** Entidade com todos os tipos registrados, como um {@code Saveable} de projeto teria. */
    static final class Everything {
        LocalDate date;
        LocalDateTime dateTime;
        LocalTime time;
        OffsetDateTime offsetDateTime;
        ZonedDateTime zonedDateTime;
        Instant instant;
        Duration duration;
    }

    /** Entidade do caso que falhava no {@code save()}: um campo preenchido pelo {@code DataTime}. */
    static final class Order {
        LocalDateTime createdAt = DataTime.getCurrentDateTime();
    }

    private final Gson gson = GsonAPI.get();

    @Test
    @DisplayName("cada tipo sai no formato ISO 8601 esperado")
    void eachTypeIsWrittenAsIso8601() {
        assertEquals("\"2025-04-03\"", gson.toJson(LocalDate.of(2025, 4, 3)));
        assertEquals("\"2025-04-03T10:30:00\"", gson.toJson(LocalDateTime.of(2025, 4, 3, 10, 30)));
        assertEquals("\"10:30:00\"", gson.toJson(LocalTime.of(10, 30)));
        assertEquals("\"2025-04-03T10:30:00-03:00\"",
                gson.toJson(OffsetDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneOffset.ofHours(-3))));
        assertEquals("\"2025-04-03T10:30:00-03:00[America/Sao_Paulo]\"",
                gson.toJson(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneId.of("America/Sao_Paulo"))));
        assertEquals("\"2025-04-03T13:30:00Z\"", gson.toJson(Instant.parse("2025-04-03T13:30:00Z")));
        assertEquals("\"PT1H30M\"", gson.toJson(Duration.ofMinutes(90)));
    }

    @Test
    @DisplayName("ida e volta preserva todos os tipos, inclusive nanossegundos")
    void everyTypeRoundTrips() {
        Everything original = new Everything();
        original.date = LocalDate.of(2024, 2, 29);
        original.dateTime = LocalDateTime.of(2025, 4, 3, 10, 30, 15, 123_456_789);
        original.time = LocalTime.of(23, 59, 59, 1);
        original.offsetDateTime = OffsetDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneOffset.ofHours(-3));
        original.zonedDateTime = ZonedDateTime.of(2025, 11, 2, 0, 30, 0, 0, ZoneId.of("America/Sao_Paulo"));
        original.instant = Instant.ofEpochSecond(1_700_000_000L, 42);
        original.duration = Duration.ofSeconds(3_725, 500_000_000);

        Everything copy = gson.fromJson(gson.toJson(original), Everything.class);

        assertEquals(original.date, copy.date);
        assertEquals(original.dateTime, copy.dateTime);
        assertEquals(original.time, copy.time);
        assertEquals(original.offsetDateTime, copy.offsetDateTime);
        assertEquals(original.zonedDateTime, copy.zonedDateTime);
        assertEquals(original.instant, copy.instant);
        assertEquals(original.duration, copy.duration);
    }

    @Test
    @DisplayName("entidade com LocalDateTime do DataTime serializa e volta")
    void entityWithLocalDateTimeFromDataTimeSerializes() {
        Order order = new Order();
        String json = gson.toJson(order);
        assertTrue(json.startsWith("{\"createdAt\":\""), json);
        assertEquals(order.createdAt, gson.fromJson(json, Order.class).createdAt);
    }

    @Test
    @DisplayName("null vira null nos dois sentidos")
    void nullIsKeptInBothDirections() {
        Everything copy = gson.fromJson("{\"date\":null,\"dateTime\":null,\"time\":null,\"offsetDateTime\":null,"
                + "\"zonedDateTime\":null,\"instant\":null,\"duration\":null}", Everything.class);
        assertNull(copy.date);
        assertNull(copy.dateTime);
        assertNull(copy.time);
        assertNull(copy.offsetDateTime);
        assertNull(copy.zonedDateTime);
        assertNull(copy.instant);
        assertNull(copy.duration);
        assertEquals("null", new LocalDateTimeTypeAdapter().toJson(null));
        assertEquals("null", new DurationTypeAdapter().toJson(null));
    }

    @Test
    @DisplayName("texto inválido vira JsonSyntaxException com o caminho do campo")
    void invalidTextBecomesJsonSyntaxException() {
        List<String> invalidBodies = List.of(
                "{\"date\":\"2025-02-30\"}",
                "{\"dateTime\":\"2025-13-01T10:00:00\"}",
                "{\"time\":\"25:00\"}",
                "{\"offsetDateTime\":\"ontem\"}",
                "{\"zonedDateTime\":\"2025-04-03T10:30:00[Lugar/Nenhum]\"}",
                "{\"instant\":\"amanhã\"}",
                "{\"duration\":\"5 minutos\"}");
        for (String body : invalidBodies) {
            JsonSyntaxException error = assertThrows(JsonSyntaxException.class,
                    () -> gson.fromJson(body, Everything.class), body);
            String field = body.substring(2, body.indexOf('"', 2));
            assertTrue(error.getMessage().contains("$." + field), error.getMessage());
            assertTrue(error instanceof JsonParseException, "um catch de JsonParseException precisa pegar");
        }
    }

    @Test
    @DisplayName("a mensagem de erro não repete o valor recebido")
    void errorMessageDoesNotEchoTheValue() {
        JsonSyntaxException error = assertThrows(JsonSyntaxException.class,
                () -> gson.fromJson("{\"date\":\"texto-enorme-vindo-do-cliente\"}", Everything.class));
        assertFalse(error.getMessage().contains("texto-enorme"), error.getMessage());
    }

    @Test
    @DisplayName("número ou objeto no lugar do texto vira JsonSyntaxException")
    void nonStringTokenBecomesJsonSyntaxException() {
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("{\"date\":20250403}", Everything.class));
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("{\"instant\":{\"seconds\":1}}", Everything.class));
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("{\"duration\":[1]}", Everything.class));
    }

    // ==================== FORMATO ANTIGO (REFLEXÃO COM --add-opens) ====================

    /**
     * Linha exatamente como o {@code GsonAPI} anterior a gravava no banco num projeto rodando com
     * {@code --add-opens java.base/java.time=ALL-UNNAMED} (JDK 21, Gson 2.13.2).
     */
    private static final String LEGACY_ROW = "{\"instant\":{\"seconds\":1743687000,\"nanos\":123000000},"
            + "\"duration\":{\"seconds\":5415,\"nanos\":500000000},"
            + "\"time\":{\"hour\":10,\"minute\":30,\"second\":15,\"nano\":42},"
            + "\"dateTime\":{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":15,\"nano\":42}},"
            + "\"zonedRegion\":{\"dateTime\":{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":0,"
            + "\"nano\":0}},\"offset\":{\"totalSeconds\":-10800},\"zone\":{\"id\":\"America/Sao_Paulo\"}},"
            + "\"zonedOffset\":{\"dateTime\":{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":0,"
            + "\"nano\":0}},\"offset\":{\"totalSeconds\":-10800},\"zone\":{\"totalSeconds\":-10800}},"
            + "\"zonedUtc\":{\"dateTime\":{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":0,"
            + "\"nano\":0}},\"offset\":{\"totalSeconds\":0},\"zone\":{\"id\":\"UTC\"}}}";

    /** Entidade da linha antiga. */
    static final class LegacyRow {
        Instant instant;
        Duration duration;
        LocalTime time;
        LocalDateTime dateTime;
        ZonedDateTime zonedRegion;
        ZonedDateTime zonedOffset;
        ZonedDateTime zonedUtc;
    }

    @Test
    @DisplayName("Instant no formato antigo {seconds, nanos} é lido")
    void instantReadsLegacyObject() {
        assertEquals(Instant.ofEpochSecond(1_743_687_000L, 123_000_000),
                gson.fromJson("{\"seconds\":1743687000,\"nanos\":123000000}", Instant.class));
    }

    @Test
    @DisplayName("Duration no formato antigo {seconds, nanos} é lida")
    void durationReadsLegacyObject() {
        assertEquals(Duration.ofSeconds(5_415, 500_000_000),
                gson.fromJson("{\"seconds\":5415,\"nanos\":500000000}", Duration.class));
    }

    @Test
    @DisplayName("LocalTime no formato antigo {hour, minute, second, nano} é lido")
    void localTimeReadsLegacyObject() {
        assertEquals(LocalTime.of(10, 30, 15, 42),
                gson.fromJson("{\"hour\":10,\"minute\":30,\"second\":15,\"nano\":42}", LocalTime.class));
    }

    @Test
    @DisplayName("LocalDateTime no formato antigo é lido, com a data em texto ou em objeto")
    void localDateTimeReadsLegacyObject() {
        LocalDateTime expected = LocalDateTime.of(2025, 4, 3, 10, 30, 15, 42);
        assertEquals(expected, gson.fromJson(
                "{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":15,\"nano\":42}}",
                LocalDateTime.class), "forma gravada pelo GsonAPI, que já tinha o adaptador de LocalDate");
        assertEquals(expected, gson.fromJson(
                "{\"date\":{\"year\":2025,\"month\":4,\"day\":3},\"time\":{\"hour\":10,\"minute\":30,\"second\":15,\"nano\":42}}",
                LocalDateTime.class), "forma gravada por um Gson sem adaptadores");
    }

    @Test
    @DisplayName("ZonedDateTime no formato antigo é lido, com fuso por região ou por offset")
    void zonedDateTimeReadsLegacyObject() {
        String dateTime = "{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":0,\"nano\":0}}";
        assertEquals(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneId.of("America/Sao_Paulo")), gson.fromJson(
                "{\"dateTime\":" + dateTime + ",\"offset\":{\"totalSeconds\":-10800},\"zone\":{\"id\":\"America/Sao_Paulo\"}}",
                ZonedDateTime.class));
        assertEquals(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneOffset.ofHours(-3)), gson.fromJson(
                "{\"dateTime\":" + dateTime + ",\"offset\":{\"totalSeconds\":-10800},\"zone\":{\"totalSeconds\":-10800}}",
                ZonedDateTime.class));
    }

    @Test
    @DisplayName("fuso em forma ambígua no formato antigo vira JsonSyntaxException explicando o motivo")
    void ambiguousLegacyZoneIsRejected() {
        String dateTime = "{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,\"second\":0,\"nano\":0}}";
        for (String zone : List.of("{}", "{\"id\":\"UTC\",\"totalSeconds\":0}", "{\"rules\":{}}", "\"America/Sao_Paulo\"")) {
            String json = "{\"zonedDateTime\":{\"dateTime\":" + dateTime + ",\"offset\":{\"totalSeconds\":-10800},\"zone\":"
                    + zone + "}}";
            JsonSyntaxException error = assertThrows(JsonSyntaxException.class, () -> gson.fromJson(json, Everything.class), zone);
            assertTrue(error.getMessage().contains("$.zonedDateTime") && error.getMessage().contains("fuso"), error.getMessage());
        }
    }

    @Test
    @DisplayName("linha antiga inteira, como o Saveable gravava, é lida e regravada em ISO")
    void wholeLegacyRowIsReadAndRewrittenAsIso() {
        LegacyRow row = gson.fromJson(LEGACY_ROW, LegacyRow.class);

        assertEquals(Instant.ofEpochSecond(1_743_687_000L, 123_000_000), row.instant);
        assertEquals(Duration.ofSeconds(5_415, 500_000_000), row.duration);
        assertEquals(LocalTime.of(10, 30, 15, 42), row.time);
        assertEquals(LocalDateTime.of(2025, 4, 3, 10, 30, 15, 42), row.dateTime);
        assertEquals(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneId.of("America/Sao_Paulo")), row.zonedRegion);
        assertEquals(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneOffset.ofHours(-3)), row.zonedOffset);
        assertEquals(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneId.of("UTC")), row.zonedUtc);

        String rewritten = gson.toJson(row);
        assertFalse(rewritten.contains("{\"seconds\"") || rewritten.contains("\"hour\""), "a escrita é sempre texto: " + rewritten);
        assertTrue(rewritten.contains("\"dateTime\":\"2025-04-03T10:30:15.000000042\""), rewritten);
    }

    @Test
    @DisplayName("objeto antigo malformado vira JsonSyntaxException")
    void malformedLegacyObjectIsRejected() {
        List<String> bodies = List.of(
                "{\"instant\":{\"seconds\":1}}",
                "{\"instant\":{\"seconds\":1.5,\"nanos\":0}}",
                "{\"instant\":{\"seconds\":1,\"nanos\":1000000000}}",
                "{\"duration\":{\"seconds\":\"5\",\"nanos\":0}}",
                "{\"time\":{\"hour\":25,\"minute\":0,\"second\":0,\"nano\":0}}",
                "{\"time\":{\"hour\":10,\"minute\":30,\"second\":0,\"nano\":0,\"extra\":1}}",
                "{\"dateTime\":{\"date\":\"2025-02-30\",\"time\":{\"hour\":10,\"minute\":30,\"second\":0,\"nano\":0}}}",
                "{\"zonedDateTime\":{\"dateTime\":{\"date\":\"2025-04-03\",\"time\":{\"hour\":10,\"minute\":30,"
                        + "\"second\":0,\"nano\":0}},\"offset\":{\"totalSeconds\":-10800},\"zone\":{\"id\":\"Lugar/Nenhum\"}}}");
        for (String body : bodies) {
            assertThrows(JsonSyntaxException.class, () -> gson.fromJson(body, Everything.class), body);
        }
    }

    @Test
    @DisplayName("LocalDate e OffsetDateTime mantêm exatamente o formato de antes, sem forma de objeto")
    void localDateAndOffsetDateTimeKeepTheirFormat() {
        assertEquals("\"2025-04-03\"", gson.toJson(LocalDate.of(2025, 4, 3)));
        assertEquals("\"2026-09-24T21:59:29.193875-03:00\"",
                gson.toJson(OffsetDateTime.of(2026, 9, 24, 21, 59, 29, 193_875_000, ZoneOffset.ofHours(-3))));
        assertEquals(LocalDate.of(2025, 4, 3), gson.fromJson("\"2025-04-03\"", LocalDate.class));
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("{\"date\":{\"year\":2025,\"month\":4,\"day\":3}}",
                Everything.class), "LocalDate sempre teve adaptador: o banco nunca o guardou como objeto");
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("{\"offsetDateTime\":{\"dateTime\":{}}}",
                Everything.class));
    }

    @Test
    @DisplayName("formatos alternativos aceitos na leitura")
    void alternativeInputFormatsAreAccepted() {
        assertEquals(LocalTime.of(10, 30), gson.fromJson("\"10:30\"", LocalTime.class));
        assertEquals(Instant.parse("2025-04-03T13:30:00Z"), gson.fromJson("\"2025-04-03T10:30:00-03:00\"", Instant.class));
        assertEquals(ZonedDateTime.of(2025, 4, 3, 10, 30, 0, 0, ZoneOffset.ofHours(-3)),
                gson.fromJson("\"2025-04-03T10:30:00-03:00\"", ZonedDateTime.class));
    }
}
