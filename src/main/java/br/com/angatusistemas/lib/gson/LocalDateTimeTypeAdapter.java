package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link LocalDateTime} no formato ISO 8601
 * ({@code yyyy-MM-ddTHH:mm:ss}, com fração de segundo quando houver).
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03T10:30:00"}. Valores JSON {@code null} são
 * convertidos para {@code null} Java; um texto fora do formato lança
 * {@link com.google.gson.JsonSyntaxException}.</p>
 *
 * <p>Sem este adaptador, o Gson no JDK 21 lança {@code JsonIOException} ao serializar o tipo
 * (os campos privados de {@code java.time} não são abertos à reflexão), e uma entidade
 * {@code Saveable} com um campo {@code LocalDateTime} — o tipo que
 * {@code DataTime.getCurrentDateTime()} devolve — falhava no {@code save()}.</p>
 *
 * <p><strong>Formato antigo:</strong> a leitura também aceita o objeto que o Gson gravava por
 * reflexão num projeto rodando com {@code --add-opens java.base/java.time=ALL-UNNAMED}:
 * {@code {"date":"2025-04-03","time":{"hour":10,"minute":30,"second":0,"nano":0}}} — a data em
 * texto, como o {@code GsonAPI} a gravava, ou em objeto {@code {"year","month","day"}}. Linhas
 * antigas do banco continuam legíveis; a escrita é sempre o texto ISO.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class LocalDateTimeTypeAdapter extends TypeAdapter<LocalDateTime> {

    /** Formato ISO 8601 de data e hora local, com validação estrita. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public LocalDateTimeTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa uma data/hora local para o formato ISO 8601.
     *
     * @param out   Escritor JSON de destino
     * @param value Data/hora a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, LocalDateTime value) throws IOException {
        TemporalJson.write(out, value, FORMATTER::format);
    }

    /**
     * Desserializa uma data/hora local a partir do formato ISO 8601.
     *
     * @param in Leitor JSON de origem
     * @return Data/hora desserializada, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for uma data/hora ISO válida
     * @throws IOException se a leitura falhar
     */
    @Override
    public LocalDateTime read(JsonReader in) throws IOException {
        return TemporalJson.read(in, text -> LocalDateTime.parse(text, FORMATTER), TemporalJson::legacyLocalDateTime,
                "2025-04-03T10:30:00");
    }
}
