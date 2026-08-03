package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link LocalDate} no formato ISO
 * ({@code yyyy-MM-dd}).
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03"}. Valores JSON {@code null}
 * são convertidos para {@code null} Java.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public class LocalDateTypeAdapter extends TypeAdapter<LocalDate> {

    /** Formato ISO de data (yyyy-MM-dd). */
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Serializa uma data para o formato ISO.
     *
     * @param out   Escritor JSON de destino
     * @param value Data a serializar (pode ser {@code null})
     */
    @Override
    public void write(JsonWriter out, LocalDate value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(formatter.format(value));
        }
    }

    /**
     * Desserializa uma data a partir do formato ISO.
     *
     * @param in Leitor JSON de origem
     * @return Data desserializada, ou {@code null} para JSON {@code null}
     */
    @Override
    public LocalDate read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        return LocalDate.parse(in.nextString(), formatter);
    }
}
