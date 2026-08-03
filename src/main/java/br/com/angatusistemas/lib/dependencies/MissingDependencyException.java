package br.com.angatusistemas.lib.dependencies;

/**
 * Exceção lançada quando uma funcionalidade da biblioteca depende de uma
 * biblioteca de terceiros que não está presente no classpath.
 *
 * <p>A mensagem desta exceção contém instruções padronizadas de como adicionar
 * a dependência ausente via Maven e Gradle, geradas por {@link Dependencies}.</p>
 *
 * <p>Exemplo de tratamento:
 * <pre>
 * try {
 *     Dependencies.require(...);
 * } catch (MissingDependencyException e) {
 *     // A mensagem já foi exibida no console; encerre graciosamente.
 * }
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see Dependencies
 */
public class MissingDependencyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Cria a exceção com a mensagem de dependência ausente.
     *
     * @param message Mensagem completa, incluindo instruções de instalação
     */
    public MissingDependencyException(String message) {
        super(message);
    }
}
