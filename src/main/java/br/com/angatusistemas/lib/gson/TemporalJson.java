package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Leitura e escrita comuns aos adaptadores de tipos de {@code java.time} do pacote.
 *
 * <p><strong>Escrita:</strong> sempre texto ISO 8601; {@code null} vira {@code null}.</p>
 *
 * <p><strong>Leitura:</strong> o texto ISO 8601 e, para os tipos que não tinham adaptador antes
 * ({@code Instant}, {@code Duration}, {@code LocalTime}, {@code LocalDateTime},
 * {@code ZonedDateTime}), também o objeto que o Gson gravava por reflexão num projeto rodando com
 * {@code --add-opens java.base/java.time=ALL-UNNAMED} — o formato do banco não pode mudar, e essas
 * linhas precisam continuar legíveis. As formas aceitas, conferidas no JDK 21 com o Gson 2.13.2:</p>
 * <ul>
 *   <li>{@code Instant} e {@code Duration}: {@code {"seconds":N,"nanos":N}};</li>
 *   <li>{@code LocalTime}: {@code {"hour":H,"minute":M,"second":S,"nano":N}};</li>
 *   <li>{@code LocalDateTime}: {@code {"date":D,"time":T}}, com {@code D} em texto ISO
 *       ({@code "2025-04-03"}, como o {@code GsonAPI} gravava, porque já tinha o adaptador de
 *       {@code LocalDate}) ou em objeto {@code {"year","month","day"}} (um {@code Gson} sem
 *       adaptadores), e {@code T} no objeto de {@code LocalTime};</li>
 *   <li>{@code ZonedDateTime}: {@code {"dateTime":LDT,"offset":{"totalSeconds":N},"zone":Z}},
 *       com {@code Z} igual a {@code {"id":"America/Sao_Paulo"}} (fuso por região) ou
 *       {@code {"totalSeconds":N}} (fuso por offset). Qualquer outra forma de {@code Z} é ambígua
 *       e lança {@link JsonSyntaxException}. Essas linhas eram gravadas, mas nunca puderam ser
 *       lidas antes: o Gson não instancia o {@code ZoneId} abstrato.</li>
 * </ul>
 *
 * <p><strong>Erros:</strong> texto fora do formato, objeto que não segue exatamente uma dessas
 * formas ou valor fora da faixa (hora 25) viram {@link JsonSyntaxException}. O
 * {@code DateTimeParseException} do JDK não é {@link com.google.gson.JsonParseException}: escapava
 * do {@code catch} de quem lia o corpo de uma requisição, e uma data errada enviada pelo cliente
 * virava erro 500 em vez de 400.</p>
 *
 * <p>Uso interno do pacote; não faz parte da API pública.</p>
 *
 * @author Angatu Sistemas
 */
final class TemporalJson {

    private TemporalJson() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== ESCRITA E LEITURA ====================

    /**
     * Escreve o valor como texto, ou {@code null} JSON.
     *
     * @param out       Escritor JSON de destino
     * @param value     Valor a escrever (pode ser {@code null})
     * @param formatter Conversão do valor em texto ISO 8601
     * @param <T>       Tipo temporal
     * @throws IOException se a escrita falhar
     */
    static <T> void write(JsonWriter out, T value, Function<T, String> formatter) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(formatter.apply(value));
        }
    }

    /**
     * Lê um texto ISO 8601, ou {@code null} JSON. Não aceita objeto: para os tipos que já tinham
     * adaptador ({@code LocalDate}, {@code OffsetDateTime}), o banco nunca teve outra forma.
     *
     * @param in      Leitor JSON de origem
     * @param parser  Conversão do texto no tipo temporal; lança {@link DateTimeException} se inválido
     * @param example Exemplo do formato esperado, para a mensagem de erro
     * @param <T>     Tipo temporal
     * @return Valor lido, ou {@code null} para JSON {@code null}
     * @throws JsonSyntaxException se o valor não for texto ou não estiver no formato
     * @throws IOException         se a leitura falhar
     */
    static <T> T read(JsonReader in, Function<String, T> parser, String example) throws IOException {
        return read(in, parser, null, example);
    }

    /**
     * Lê um texto ISO 8601, o objeto do formato antigo (se {@code legacy} não for nulo) ou
     * {@code null} JSON.
     *
     * <p>A mensagem de erro aponta o campo ({@code $.vencimento}) e o formato esperado, sem
     * repetir o valor recebido; o texto original fica na causa, onde o JDK já o corta em 64
     * caracteres.</p>
     *
     * @param in      Leitor JSON de origem
     * @param parser  Conversão do texto no tipo temporal; lança {@link DateTimeException} se inválido
     * @param legacy  Conversão do objeto do formato antigo, ou {@code null} se o tipo não o tinha
     * @param example Exemplo do formato esperado, para a mensagem de erro
     * @param <T>     Tipo temporal
     * @return Valor lido, ou {@code null} para JSON {@code null}
     * @throws JsonSyntaxException se o valor não estiver em nenhuma das formas aceitas
     * @throws IOException         se a leitura falhar
     */
    static <T> T read(JsonReader in, Function<String, T> parser, Function<JsonElement, T> legacy, String example)
            throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        // Lido antes de consumir o valor: depois, num array, o caminho já apontaria o próximo item.
        String path = in.getPath();
        if (token == JsonToken.BEGIN_OBJECT && legacy != null) {
            JsonElement element = JsonParser.parseReader(in);
            try {
                return legacy.apply(element);
            } catch (JsonSyntaxException | DateTimeException e) {
                throw new JsonSyntaxException("Valor inválido em " + path + ": o objeto não segue o formato antigo"
                        + " gravado por reflexão (" + reason(e) + "). O formato atual é um texto ISO-8601, como "
                        + example + ".", e);
            }
        }
        if (token != JsonToken.STRING) {
            throw new JsonSyntaxException("Valor inválido em " + path
                    + ": use um texto no formato ISO-8601, como " + example + ".");
        }
        String text = in.nextString();
        try {
            return parser.apply(text);
        } catch (DateTimeException e) {
            throw new JsonSyntaxException("Valor inválido em " + path
                    + ": use o formato ISO-8601, como " + example + ".", e);
        }
    }

    /** Motivo em português para a mensagem: o do próprio decodificador, ou um valor inválido. */
    private static String reason(RuntimeException error) {
        return error instanceof JsonSyntaxException
                ? error.getMessage()
                : "algum valor é inválido: fora da faixa, fuso inexistente ou data que não existe";
    }

    // ==================== FORMATO ANTIGO (REFLEXÃO) ====================

    /**
     * {@code Instant} gravado por reflexão: {@code {"seconds":N,"nanos":N}}.
     *
     * @param element Objeto lido
     * @return Instante correspondente
     * @throws JsonSyntaxException se o objeto não tiver exatamente essa forma
     */
    static Instant legacyInstant(JsonElement element) {
        JsonObject object = object(element, "o instante", "seconds", "nanos");
        return Instant.ofEpochSecond(integer(object, "seconds"), nanos(object, "nanos"));
    }

    /**
     * {@code Duration} gravada por reflexão: {@code {"seconds":N,"nanos":N}}.
     *
     * @param element Objeto lido
     * @return Duração correspondente
     * @throws JsonSyntaxException se o objeto não tiver exatamente essa forma
     */
    static Duration legacyDuration(JsonElement element) {
        JsonObject object = object(element, "a duração", "seconds", "nanos");
        return Duration.ofSeconds(integer(object, "seconds"), nanos(object, "nanos"));
    }

    /**
     * {@code LocalTime} gravado por reflexão: {@code {"hour":H,"minute":M,"second":S,"nano":N}};
     * aceita também o texto ISO.
     *
     * @param element Objeto (ou texto) lido
     * @return Hora correspondente
     * @throws JsonSyntaxException se não tiver essa forma
     * @throws DateTimeException   se algum valor estiver fora da faixa
     */
    static LocalTime legacyLocalTime(JsonElement element) {
        if (isText(element)) {
            return LocalTime.parse(element.getAsString(), DateTimeFormatter.ISO_LOCAL_TIME);
        }
        JsonObject object = object(element, "a hora", "hour", "minute", "second", "nano");
        return LocalTime.of(smallInteger(object, "hour"), smallInteger(object, "minute"),
                smallInteger(object, "second"), smallInteger(object, "nano"));
    }

    /**
     * {@code LocalDateTime} gravado por reflexão: {@code {"date":D,"time":T}}; aceita também o
     * texto ISO.
     *
     * @param element Objeto (ou texto) lido
     * @return Data e hora correspondentes
     * @throws JsonSyntaxException se não tiver essa forma
     * @throws DateTimeException   se algum valor estiver fora da faixa
     */
    static LocalDateTime legacyLocalDateTime(JsonElement element) {
        if (isText(element)) {
            return LocalDateTime.parse(element.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        JsonObject object = object(element, "a data e hora", "date", "time");
        return LocalDateTime.of(legacyLocalDate(object.get("date")), legacyLocalTime(object.get("time")));
    }

    /**
     * {@code ZonedDateTime} gravado por reflexão: {@code {"dateTime":LDT,"offset":O,"zone":Z}}.
     *
     * <p>Remonta pelo instante (data e hora mais o offset gravados), no fuso gravado: se as regras
     * do fuso mudaram desde a gravação, o momento é preservado e a hora local se ajusta.</p>
     *
     * @param element Objeto lido
     * @return Data e hora com fuso correspondentes
     * @throws JsonSyntaxException se não tiver essa forma, inclusive um fuso ambíguo
     * @throws DateTimeException   se algum valor estiver fora da faixa ou o fuso não existir
     */
    static ZonedDateTime legacyZonedDateTime(JsonElement element) {
        JsonObject object = object(element, "a data e hora com fuso", "dateTime", "offset", "zone");
        LocalDateTime dateTime = legacyLocalDateTime(object.get("dateTime"));
        ZoneOffset offset = legacyZoneOffset(object.get("offset"), "o offset");
        return ZonedDateTime.ofInstant(dateTime, offset, legacyZone(object.get("zone")));
    }

    /** {@code LocalDate} dentro de um objeto antigo: texto ISO (via {@code GsonAPI}) ou {@code {"year","month","day"}}. */
    private static LocalDate legacyLocalDate(JsonElement element) {
        if (isText(element)) {
            return LocalDate.parse(element.getAsString(), DateTimeFormatter.ISO_LOCAL_DATE);
        }
        JsonObject object = object(element, "a data", "year", "month", "day");
        return LocalDate.of(smallInteger(object, "year"), smallInteger(object, "month"), smallInteger(object, "day"));
    }

    /** {@code ZoneOffset} gravado por reflexão: {@code {"totalSeconds":N}} (o {@code id} é transient). */
    private static ZoneOffset legacyZoneOffset(JsonElement element, String what) {
        JsonObject object = object(element, what, "totalSeconds");
        return ZoneOffset.ofTotalSeconds(smallInteger(object, "totalSeconds"));
    }

    /**
     * {@code ZoneId} gravado por reflexão: o Gson grava o tipo real — {@code ZoneRegion} vira
     * {@code {"id":...}} e {@code ZoneOffset} vira {@code {"totalSeconds":...}}. Outra forma não
     * diz qual era o fuso, e adivinhar mudaria o momento gravado.
     */
    private static ZoneId legacyZone(JsonElement element) {
        if (element != null && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.size() == 1 && object.has("id") && isText(object.get("id"))) {
                return ZoneId.of(object.get("id").getAsString());
            }
            if (object.size() == 1 && object.has("totalSeconds")) {
                return legacyZoneOffset(object, "o fuso");
            }
        }
        throw new JsonSyntaxException("o fuso (zone) não está em nenhuma das formas conhecidas,"
                + " {\"id\":...} ou {\"totalSeconds\":...}, e não dá para saber qual era");
    }

    // ==================== AUXILIARES ====================

    private static boolean isText(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    /** Exige um objeto com exatamente os campos informados: outra forma não é o formato antigo. */
    private static JsonObject object(JsonElement element, String what, String... members) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonSyntaxException(what + " deveria ser um objeto");
        }
        JsonObject object = element.getAsJsonObject();
        boolean exact = object.size() == members.length;
        for (String member : members) {
            exact &= object.has(member);
        }
        if (!exact) {
            throw new JsonSyntaxException(what + " deveria ter exatamente os campos " + String.join(", ", members));
        }
        return object;
    }

    /** Número inteiro exato (sem casa decimal) de um campo. */
    private static long integer(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                try {
                    BigDecimal number = primitive.getAsBigDecimal();
                    return number.longValueExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    // cai na mensagem abaixo
                }
            }
        }
        throw new JsonSyntaxException("o campo " + member + " deveria ser um número inteiro");
    }

    /** Inteiro que cabe num {@code int}. */
    private static int smallInteger(JsonObject object, String member) {
        long value = integer(object, member);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonSyntaxException("o campo " + member + " está fora da faixa");
        }
        return (int) value;
    }

    /** Nanossegundos, de 0 a 999.999.999, como o JDK guarda. */
    private static int nanos(JsonObject object, String member) {
        long value = integer(object, member);
        if (value < 0 || value > 999_999_999) {
            throw new JsonSyntaxException("o campo " + member + " deveria estar entre 0 e 999999999");
        }
        return (int) value;
    }
}
