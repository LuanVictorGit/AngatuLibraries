package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link OffsetDateTime} no formato
 * ISO 8601 ({@code yyyy-MM-ddTHH:mm:ss±HH:MM}).
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03T10:30:00-03:00"}. Valores JSON
 * {@code null} são convertidos para {@code null} Java.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class OffsetDateTimeTypeAdapter extends TypeAdapter<OffsetDateTime> {

    /** Formato ISO 8601 com offset. */
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * Serializa uma data/hora com offset para o formato ISO 8601.
     *
     * @param out   Escritor JSON de destino
     * @param value Data/hora a serializar (pode ser {@code null})
     */
    @Override
    public void write(JsonWriter out, OffsetDateTime value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(formatter.format(value));
        }
    }

    /**
     * Desserializa uma data/hora com offset a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Data/hora desserializada, ou {@code null} para JSON {@code null}
     */
    @Override
    public OffsetDateTime read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        return OffsetDateTime.parse(in.nextString(), formatter);
    }
}
