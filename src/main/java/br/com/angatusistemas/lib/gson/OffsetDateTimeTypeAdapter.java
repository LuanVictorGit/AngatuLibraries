package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link OffsetDateTime} no formato
 * ISO 8601 ({@code yyyy-MM-ddTHH:mm:ss±HH:MM}).
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03T10:30:00-03:00"}. Valores JSON
 * {@code null} são convertidos para {@code null} Java. Um texto fora do formato lança
 * {@link com.google.gson.JsonSyntaxException} — um erro de entrada (400), e não mais um
 * {@code DateTimeParseException} que escapava como erro 500.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class OffsetDateTimeTypeAdapter extends TypeAdapter<OffsetDateTime> {

    /** Formato ISO 8601 com offset. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public OffsetDateTimeTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa uma data/hora com offset para o formato ISO 8601.
     *
     * @param out   Escritor JSON de destino
     * @param value Data/hora a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, OffsetDateTime value) throws IOException {
        TemporalJson.write(out, value, FORMATTER::format);
    }

    /**
     * Desserializa uma data/hora com offset a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Data/hora desserializada, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for uma data/hora ISO válida
     * @throws IOException se a leitura falhar
     */
    @Override
    public OffsetDateTime read(JsonReader in) throws IOException {
        return TemporalJson.read(in, text -> OffsetDateTime.parse(text, FORMATTER), "2025-04-03T10:30:00-03:00");
    }
}
