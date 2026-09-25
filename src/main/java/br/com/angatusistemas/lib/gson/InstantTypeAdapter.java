package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link Instant} no formato ISO 8601 em UTC
 * ({@code yyyy-MM-ddTHH:mm:ssZ}, com fração de segundo quando houver).
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03T13:30:00Z"}. Na leitura, também é aceito um
 * offset no lugar do {@code Z} ({@code "2025-04-03T10:30:00-03:00"}). Valores JSON
 * {@code null} são convertidos para {@code null} Java; um texto fora do formato lança
 * {@link com.google.gson.JsonSyntaxException}.</p>
 *
 * <p>Sem este adaptador, o Gson no JDK 21 lança {@code JsonIOException} ao serializar o tipo
 * (os campos privados de {@code java.time} não são abertos à reflexão).</p>
 *
 * <p><strong>Formato antigo:</strong> a leitura também aceita o objeto
 * {@code {"seconds":1743687000,"nanos":0}}, que o Gson gravava por reflexão num projeto rodando
 * com {@code --add-opens java.base/java.time=ALL-UNNAMED}; linhas antigas do banco continuam
 * legíveis. A escrita é sempre o texto ISO.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class InstantTypeAdapter extends TypeAdapter<Instant> {

    /** Formato ISO 8601 de instante em UTC. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public InstantTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa um instante para o formato ISO 8601 em UTC.
     *
     * @param out   Escritor JSON de destino
     * @param value Instante a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, Instant value) throws IOException {
        TemporalJson.write(out, value, FORMATTER::format);
    }

    /**
     * Desserializa um instante a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Instante desserializado, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for um instante ISO válido
     * @throws IOException se a leitura falhar
     */
    @Override
    public Instant read(JsonReader in) throws IOException {
        return TemporalJson.read(in, text -> FORMATTER.parse(text, Instant::from), TemporalJson::legacyInstant,
                "2025-04-03T13:30:00Z");
    }
}
