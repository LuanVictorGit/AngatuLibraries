package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.Duration;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link Duration} no formato de duração ISO 8601
 * ({@code PnDTnHnMn.nS}).
 *
 * <p>Exemplos de JSON produzido: {@code "PT5M"} (5 minutos), {@code "PT1H30M"} (uma hora e
 * meia), {@code "PT0.5S"} (meio segundo). Valores JSON {@code null} são convertidos para
 * {@code null} Java; um texto fora do formato lança
 * {@link com.google.gson.JsonSyntaxException}.</p>
 *
 * <p>Sem este adaptador, o Gson no JDK 21 lança {@code JsonIOException} ao serializar o tipo
 * (os campos privados de {@code java.time} não são abertos à reflexão).</p>
 *
 * <p><strong>Formato antigo:</strong> a leitura também aceita o objeto
 * {@code {"seconds":5415,"nanos":500000000}}, que o Gson gravava por reflexão num projeto rodando
 * com {@code --add-opens java.base/java.time=ALL-UNNAMED}; linhas antigas do banco continuam
 * legíveis. A escrita é sempre o texto ISO.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class DurationTypeAdapter extends TypeAdapter<Duration> {

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public DurationTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa uma duração para o formato ISO 8601.
     *
     * @param out   Escritor JSON de destino
     * @param value Duração a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, Duration value) throws IOException {
        TemporalJson.write(out, value, Duration::toString);
    }

    /**
     * Desserializa uma duração a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Duração desserializada, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for uma duração ISO válida
     * @throws IOException se a leitura falhar
     */
    @Override
    public Duration read(JsonReader in) throws IOException {
        return TemporalJson.read(in, Duration::parse, TemporalJson::legacyDuration, "PT1H30M");
    }
}
