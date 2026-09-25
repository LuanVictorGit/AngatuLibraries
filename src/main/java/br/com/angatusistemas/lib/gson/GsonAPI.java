package br.com.angatusistemas.lib.gson;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Classe utilitária que fornece uma instância única do {@link Gson} pré-configurada
 * com os tipos de data e hora do {@code java.time} em texto ISO 8601.
 *
 * <p><strong>Por que os adaptadores existem:</strong> o Gson serializa objetos por reflexão, e
 * no JDK 17+ os campos privados de {@code java.time} não são abertos a ela. Sem adaptador, o
 * Gson 2.13 lança {@code JsonIOException} ("Failed making field ... accessible") ao encontrar um
 * desses tipos — uma entidade {@code Saveable} com um campo {@link LocalDateTime} falhava no
 * {@code save()}. Com os adaptadores, cada tipo vira um texto ISO 8601, legível por qualquer
 * linguagem:</p>
 *
 * <table>
 *   <caption>Tipos registrados e formato produzido</caption>
 *   <tr><th>Tipo</th><th>Exemplo</th><th>Adaptador</th></tr>
 *   <tr><td>{@link LocalDate}</td><td>{@code "2025-04-03"}</td><td>{@link LocalDateTypeAdapter}</td></tr>
 *   <tr><td>{@link LocalDateTime}</td><td>{@code "2025-04-03T10:30:00"}</td><td>{@link LocalDateTimeTypeAdapter}</td></tr>
 *   <tr><td>{@link LocalTime}</td><td>{@code "10:30:00"}</td><td>{@link LocalTimeTypeAdapter}</td></tr>
 *   <tr><td>{@link OffsetDateTime}</td><td>{@code "2025-04-03T10:30:00-03:00"}</td><td>{@link OffsetDateTimeTypeAdapter}</td></tr>
 *   <tr><td>{@link ZonedDateTime}</td><td>{@code "2025-04-03T10:30:00-03:00[America/Sao_Paulo]"}</td><td>{@link ZonedDateTimeTypeAdapter}</td></tr>
 *   <tr><td>{@link Instant}</td><td>{@code "2025-04-03T13:30:00Z"}</td><td>{@link InstantTypeAdapter}</td></tr>
 *   <tr><td>{@link Duration}</td><td>{@code "PT1H30M"}</td><td>{@link DurationTypeAdapter}</td></tr>
 * </table>
 *
 * <p><strong>Leitura de entrada inválida:</strong> um texto fora do formato, ou uma data que não
 * existe ({@code "2025-02-30"}), lança {@link com.google.gson.JsonSyntaxException} — a mesma
 * família de erro de um JSON malformado. Uma rota que trata
 * {@link com.google.gson.JsonParseException} como erro do cliente (400) cobre também as datas.
 * {@code null} é aceito e devolvido como {@code null} nos dois sentidos.</p>
 *
 * <p><strong>Linhas antigas do banco:</strong> num projeto que rodava com
 * {@code --add-opens java.base/java.time=ALL-UNNAMED}, o Gson gravava {@code Instant},
 * {@code Duration}, {@code LocalTime}, {@code LocalDateTime} e {@code ZonedDateTime} por reflexão,
 * como objetos ({@code {"seconds":...,"nanos":...}} e parecidos). A leitura continua aceitando
 * esses objetos; a escrita passa a ser sempre o texto ISO. {@link LocalDate} e
 * {@link OffsetDateTime} já tinham adaptador e mantêm exatamente o formato de antes.</p>
 *
 * <p>Outros tipos de {@code java.time} sem adaptador aqui ({@code Period}, {@code YearMonth},
 * {@code OffsetTime}, {@code ZoneId}...) continuam lançando {@code JsonIOException}; quem
 * precisar deles registra um {@code TypeAdapter} próprio num {@link GsonBuilder}.</p>
 *
 * <p>É utilizada internamente pelo {@code Saveable} para persistência JSON e pode
 * ser usada por qualquer consumidor que queira o mesmo comportamento.</p>
 *
 * <p>Exemplo de uso:</p>
 * <pre>
 * MeuObjeto obj = GsonAPI.get().fromJson(jsonString, MeuObjeto.class);
 * String json = GsonAPI.get().toJson(obj);
 * </pre>
 *
 * <p><strong>Dependência:</strong> este módulo requer {@code com.google.code.gson:gson:2.13.2}
 * no classpath. Se ausente, {@link #get()} exibe instruções de instalação e lança
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}.</p>
 *
 * @author Angatu Sistemas
 * @see OffsetDateTimeTypeAdapter
 * @see LocalDateTypeAdapter
 * @see br.com.angatusistemas.lib.dependencies.Dependencies
 */
public final class GsonAPI {

    /** Coordenadas Maven da dependência Gson. */
    private static final String GSON_COORDINATES = "com.google.code.gson:gson:2.13.2";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String GSON_FEATURE = "JSON (Gson)";

    private GsonAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Retorna a instância única do Gson com os adapters configurados.
     *
     * <p>A instância já possui adaptadores registrados para {@link LocalDate},
     * {@link LocalDateTime}, {@link LocalTime}, {@link OffsetDateTime}, {@link ZonedDateTime},
     * {@link Instant} e {@link Duration}, permitindo serializar/desserializar esses tipos sem
     * configuração adicional. A instância é imutável e segura entre threads.</p>
     *
     * @return Instância pré-configurada do Gson
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência Gson não estiver no classpath
     */
    public static Gson get() {
        Dependencies.require("com.google.gson.Gson", GSON_COORDINATES, GSON_FEATURE);
        return Holder.INSTANCE;
    }

    /**
     * Holder lazy (inicialização preguiçosa): o Gson só é construído no primeiro
     * acesso, evitando custo de inicialização e falhas de carregamento quando a
     * dependência está ausente.
     */
    private static final class Holder {
        static final Gson INSTANCE = new GsonBuilder()
                .registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeTypeAdapter())
                .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
                .registerTypeAdapter(LocalTime.class, new LocalTimeTypeAdapter())
                .registerTypeAdapter(ZonedDateTime.class, new ZonedDateTimeTypeAdapter())
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .registerTypeAdapter(Duration.class, new DurationTypeAdapter())
                .create();
    }
}
