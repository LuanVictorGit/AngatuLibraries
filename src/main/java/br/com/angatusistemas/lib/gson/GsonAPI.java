package br.com.angatusistemas.lib.gson;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Classe utilitária que fornece uma instância única do {@link Gson} pré-configurada
 * com suporte nativo aos tipos {@link OffsetDateTime} e {@link LocalDate}.
 *
 * <p>Sem os adapters, o Gson serializaria os tipos temporais em formatos internos
 * ({@code {"year":2025,...}}), quebrando a compatibilidade com outras linguagens.
 * Esta instância utiliza {@link OffsetDateTimeTypeAdapter} e {@link LocalDateTypeAdapter}
 * para produzir formatos ISO padrão.</p>
 *
 * <p>É utilizada internamente pelo {@code Saveable} para persistência JSON e pode
 * ser usada por qualquer consumidor que queira o mesmo comportamento.</p>
 *
 * <p>Exemplo de uso:
 * <pre>
 * MeuObjeto obj = GsonAPI.get().fromJson(jsonString, MeuObjeto.class);
 * String json = GsonAPI.get().toJson(obj);
 * </pre>
 * </p>
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
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Retorna a instância única do Gson com os adapters configurados.
     *
     * <p>A instância já possui adapters registrados para {@link OffsetDateTime} e
     * {@link LocalDate}, permitindo serializar/desserializar esses tipos sem
     * configuração adicional.</p>
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
                .create();
    }
}
