package br.com.angatusistemas.lib.env;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * [PT] Classe utilitária para acesso às variáveis de ambiente definidas no arquivo <code>.env</code>.
 * <p>
 * Utiliza a biblioteca dotenv-java para carregar as variáveis do arquivo <code>.env</code>
 * localizado no classpath (geralmente na raiz do projeto).
 * </p>
 * 
 * [EN] Utility class to access environment variables defined in the <code>.env</code> file.
 * <p>
 * Uses the dotenv-java library to load variables from the <code>.env</code> file
 * located in the classpath (usually at the project root).
 * </p>
 * 
 * @author [Seu nome ou equipe]
 * @see io.github.cdimascio.dotenv.Dotenv
 */
public final class Env {

    // Instância única do Dotenv carregada uma única vez (Singleton pattern)
    // Single Dotenv instance loaded once (Singleton pattern)
    private static Dotenv DOTENV_INSTANCE = Dotenv.load();

    /**
     * [PT] Construtor privado para evitar instanciação da classe utilitária.
     * [EN] Private constructor to prevent instantiation of this utility class.
     */
    private Env() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * [PT] Retorna a instância carregada do Dotenv, contendo todas as variáveis do arquivo <code>.env</code>.
     * <p>
     * As variáveis podem ser acessadas através dos métodos da instância, como
     * <code>Env.get().get("VAR_NAME")</code> ou <code>Env.get().get("VAR_NAME", "defaultValue")</code>.
     * </p>
     * 
     * [EN] Returns the loaded Dotenv instance, containing all variables from the <code>.env</code> file.
     * <p>
     * Variables can be accessed via the instance methods, such as
     * <code>Env.get().get("VAR_NAME")</code> or <code>Env.get().get("VAR_NAME", "defaultValue")</code>.
     * </p>
     *
     * @return [PT] a instância única do Dotenv já carregada
     *         [EN] the single loaded Dotenv instance
     * 
     * @throws io.github.cdimascio.dotenv.DotenvException [PT] se o arquivo .env não puder ser carregado
     *                                                    [EN] if the .env file cannot be loaded
     */
    public static Dotenv get() {
        return DOTENV_INSTANCE;
    }
    
    public static void reload() {
    	DOTENV_INSTANCE = Dotenv.configure().ignoreIfMalformed().ignoreIfMissing().load();
    }
}