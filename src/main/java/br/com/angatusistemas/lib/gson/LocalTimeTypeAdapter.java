package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link LocalTime} no formato ISO 8601
 * ({@code HH:mm:ss}, com fração de segundo quando houver).
 *
 * <p>Exemplo de JSON produzido: {@code "10:30:00"}. Na leitura, os segundos são opcionais
 * ({@code "10:30"} também é aceito). Valores JSON {@code null} são convertidos para
 * {@code null} Java; um texto fora do formato lança
 * {@link com.google.gson.JsonSyntaxException}.</p>
 *
 * <p>Sem este adaptador, o Gson no JDK 21 lança {@code JsonIOException} ao serializar o tipo
 * (os campos privados de {@code java.time} não são abertos à reflexão).</p>
 *
 * <p><strong>Formato antigo:</strong> a leitura também aceita o objeto
 * {@code {"hour":10,"minute":30,"second":0,"nano":0}}, que o Gson gravava por reflexão num
 * projeto rodando com {@code --add-opens java.base/java.time=ALL-UNNAMED}; linhas antigas do
 * banco continuam legíveis. A escrita é sempre o texto ISO.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class LocalTimeTypeAdapter extends TypeAdapter<LocalTime> {

    /** Formato ISO 8601 de hora local, com validação estrita. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public LocalTimeTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa uma hora local para o formato ISO 8601.
     *
     * @param out   Escritor JSON de destino
     * @param value Hora a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, LocalTime value) throws IOException {
        TemporalJson.write(out, value, FORMATTER::format);
    }

    /**
     * Desserializa uma hora local a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Hora desserializada, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for uma hora ISO válida
     * @throws IOException se a leitura falhar
     */
    @Override
    public LocalTime read(JsonReader in) throws IOException {
        return TemporalJson.read(in, text -> LocalTime.parse(text, FORMATTER), TemporalJson::legacyLocalTime,
                "10:30:00");
    }
}
