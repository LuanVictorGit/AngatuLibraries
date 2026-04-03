package br.com.angatusistemas.lib.gson;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.google.gson.GsonBuilder;

/**
 * [PT] Classe utilitária que fornece uma instância pré-configurada do {@link com.google.gson.Gson}
 * com suporte nativo para os tipos {@link OffsetDateTime} e {@link LocalDate}.
 * <p>
 * Utiliza {@link OffsetDateTimeTypeAdapter} e {@link LocalDateTypeAdapter} para serialização/desserialização
 * correta desses tipos, evitando problemas com formatos padrão do Gson.
 * </p>
 * 
 * [EN] Utility class that provides a pre-configured {@link com.google.gson.Gson} instance
 * with native support for {@link OffsetDateTime} and {@link LocalDate} types.
 * <p>
 * Uses {@link OffsetDateTimeTypeAdapter} and {@link LocalDateTypeAdapter} for proper
 * serialization/deserialization of these types, avoiding issues with default Gson formats.
 * </p>
 * 
 * @author [Sua equipe]
 * @see com.google.gson.Gson
 * @see OffsetDateTimeTypeAdapter
 * @see LocalDateTypeAdapter
 */
public final class GsonAPI {

    // Instância única do Gson com adapters registrados (padrão Singleton)
    // Single Gson instance with registered adapters (Singleton pattern)
    private static final com.google.gson.Gson PRECONFIGURED_GSON = new GsonBuilder()
            .registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeTypeAdapter())
            .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
            .create();

    /**
     * [PT] Construtor privado para evitar instanciação da classe utilitária.
     * [EN] Private constructor to prevent instantiation of this utility class.
     */
    private GsonAPI() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * [PT] Retorna a instância pré-configurada do Gson, pronta para uso.
     * <p>
     * A instância já possui adapters registrados para {@link OffsetDateTime} e {@link LocalDate},
     * permitindo serializar e desserializar esses tipos sem configuração adicional.
     * </p>
     * <p>
     * Exemplo de uso:
     * <pre>
     * MeuObjeto obj = Gson.get().fromJson(jsonString, MeuObjeto.class);
     * String json = Gson.get().toJson(obj);
     * </pre>
     * </p>
     * 
     * [EN] Returns the pre-configured Gson instance, ready to use.
     * <p>
     * The instance already has registered adapters for {@link OffsetDateTime} and {@link LocalDate},
     * allowing serialization and deserialization of these types without additional configuration.
     * </p>
     * <p>
     * Usage example:
     * <pre>
     * MyObject obj = Gson.get().fromJson(jsonString, MyObject.class);
     * String json = Gson.get().toJson(obj);
     * </pre>
     * </p>
     *
     * @return [PT] a instância única do Gson com os adapters configurados
     *         [EN] the single Gson instance with the configured adapters
     */
    public static com.google.gson.Gson get() {
        return PRECONFIGURED_GSON;
    }
}