package br.com.angatusistemas.lib.env;

import br.com.angatusistemas.lib.dependencies.Dependencies;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Classe utilitária para acesso às variáveis de ambiente definidas no arquivo
 * {@code .env} do projeto.
 *
 * <p>Utiliza a biblioteca dotenv-java para carregar as variáveis do arquivo
 * {@code .env} localizado na raiz do projeto (ou classpath). Se o arquivo não
 * existir ou estiver malformado, a instância é criada com as variáveis de
 * ambiente do sistema — <strong>não lança exceção durante a inicialização</strong>.</p>
 *
 * <p>Exemplo de uso:
 * <pre>
 * String token = Env.get().get("API_TOKEN");
 * String url   = Env.get().get("API_URL", "https://api.exemplo.com"); // com default
 * </pre>
 * </p>
 *
 * <p><strong>Dependência:</strong> este módulo requer
 * {@code io.github.cdimascio:dotenv-java:3.2.0} no classpath. Se ausente,
 * {@link #get()} e {@link #reload()} exibem instruções de instalação e lançam
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}.</p>
 *
 * @author Angatu Sistemas
 * @see Dotenv
 * @see br.com.angatusistemas.lib.dependencies.Dependencies
 */
public final class Env {

    /** Coordenadas Maven da dependência dotenv-java. */
    private static final String DOTENV_COORDINATES = "io.github.cdimascio:dotenv-java:3.2.0";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String DOTENV_FEATURE = "Variáveis de Ambiente (.env)";

    private Env() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Retorna a instância carregada do Dotenv com todas as variáveis do arquivo
     * {@code .env} (e do ambiente do sistema).
     *
     * @return Instância única do Dotenv já carregada
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência dotenv-java não estiver no classpath
     */
    public static Dotenv get() {
        Dependencies.require("io.github.cdimascio.dotenv.Dotenv", DOTENV_COORDINATES, DOTENV_FEATURE);
        return EnvHolder.INSTANCE;
    }

    /**
     * Recarrega o arquivo {@code .env} do disco, substituindo a instância atual.
     *
     * <p>Útil após editar o arquivo {@code .env} em tempo de execução (ex: em
     * ambientes de desenvolvimento). Arquivos ausentes ou malformados são
     * ignorados — a instância passa a conter apenas as variáveis do sistema.</p>
     *
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência dotenv-java não estiver no classpath
     */
    public static void reload() {
        Dependencies.require("io.github.cdimascio.dotenv.Dotenv", DOTENV_COORDINATES, DOTENV_FEATURE);
        EnvHolder.INSTANCE = Dotenv.configure()
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();
    }

    /**
     * Holder lazy: a instância só é carregada no primeiro acesso a {@link #get()},
     * e a classe {@link Env} permanece carregável mesmo sem a dependência.
     * {@code volatile} garante visibilidade da instância entre threads após
     * {@link #reload()}.
     */
    private static final class EnvHolder {
        static volatile Dotenv INSTANCE = Dotenv.configure()
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();
    }
}
