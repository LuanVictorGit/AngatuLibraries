package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link ZonedDateTime} no formato ISO 8601
 * estendido, com offset e o nome do fuso entre colchetes.
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03T10:30:00-03:00[America/Sao_Paulo]"} — o
 * nome do fuso é mantido para que a leitura devolva o mesmo fuso, com as mesmas regras de
 * horário de verão. Na leitura, o fuso entre colchetes é opcional
 * ({@code "2025-04-03T10:30:00-03:00"} também é aceito). Valores JSON {@code null} são
 * convertidos para {@code null} Java; um texto fora do formato lança
 * {@link com.google.gson.JsonSyntaxException}.</p>
 *
 * <p>Sem este adaptador, o Gson no JDK 21 lança {@code JsonIOException} ao serializar o tipo
 * (os campos privados de {@code java.time} não são abertos à reflexão).</p>
 *
 * <p><strong>Formato antigo:</strong> num projeto rodando com
 * {@code --add-opens java.base/java.time=ALL-UNNAMED}, o Gson gravava o tipo por reflexão como
 * {@code {"dateTime":{...},"offset":{"totalSeconds":-10800},"zone":{"id":"America/Sao_Paulo"}}}
 * (ou {@code "zone":{"totalSeconds":-10800}} para um fuso por offset) — e nunca conseguia ler de
 * volta, porque não instancia o {@code ZoneId} abstrato. A leitura aceita essas duas formas do
 * fuso e remonta a data pelo instante gravado; qualquer outra forma do fuso é ambígua e lança
 * {@link com.google.gson.JsonSyntaxException}. A escrita é sempre o texto ISO.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class ZonedDateTimeTypeAdapter extends TypeAdapter<ZonedDateTime> {

    /** Formato ISO 8601 com offset e fuso ({@code [America/Sao_Paulo]}). */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public ZonedDateTimeTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa uma data/hora com fuso para o formato ISO 8601 estendido.
     *
     * @param out   Escritor JSON de destino
     * @param value Data/hora a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, ZonedDateTime value) throws IOException {
        TemporalJson.write(out, value, FORMATTER::format);
    }

    /**
     * Desserializa uma data/hora com fuso a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Data/hora desserializada, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for uma data/hora ISO válida
     * @throws IOException se a leitura falhar
     */
    @Override
    public ZonedDateTime read(JsonReader in) throws IOException {
        return TemporalJson.read(in, text -> ZonedDateTime.parse(text, FORMATTER), TemporalJson::legacyZonedDateTime,
                "2025-04-03T10:30:00-03:00[America/Sao_Paulo]");
    }
}
