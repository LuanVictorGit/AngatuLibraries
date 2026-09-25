package br.com.angatusistemas.lib.gson;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Adaptador Gson para serializar/desserializar {@link LocalDate} no formato ISO
 * ({@code yyyy-MM-dd}).
 *
 * <p>Exemplo de JSON produzido: {@code "2025-04-03"}. Valores JSON {@code null}
 * são convertidos para {@code null} Java. Um texto fora do formato, ou uma data que não
 * existe ({@code "2025-02-30"}), lança {@link com.google.gson.JsonSyntaxException} — um erro
 * de entrada (400), e não mais um {@code DateTimeParseException} que escapava como erro 500.</p>
 *
 * <p>Este adaptador é registrado automaticamente em {@link GsonAPI#get()}.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 */
public final class LocalDateTypeAdapter extends TypeAdapter<LocalDate> {

    /** Formato ISO de data (yyyy-MM-dd), com validação estrita. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Cria o adaptador. Sem estado: uma instância pode ser compartilhada entre threads.
     */
    public LocalDateTypeAdapter() {
        // Sem estado a inicializar.
    }

    /**
     * Serializa uma data para o formato ISO.
     *
     * @param out   Escritor JSON de destino
     * @param value Data a serializar (pode ser {@code null})
     * @throws IOException se a escrita falhar
     */
    @Override
    public void write(JsonWriter out, LocalDate value) throws IOException {
        TemporalJson.write(out, value, FORMATTER::format);
    }

    /**
     * Desserializa uma data a partir do formato ISO.
     *
     * @param in Leitor JSON de origem
     * @return Data desserializada, ou {@code null} para JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException se o valor não for uma data ISO válida
     * @throws IOException se a leitura falhar
     */
    @Override
    public LocalDate read(JsonReader in) throws IOException {
        return TemporalJson.read(in, text -> LocalDate.parse(text, FORMATTER), "2025-04-03");
    }
}
