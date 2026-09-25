package br.com.angatusistemas.lib.dependencies;

/**
 * Exceção lançada quando uma funcionalidade da biblioteca depende de uma
 * biblioteca de terceiros que não está presente no classpath.
 *
 * <p>A mensagem desta exceção contém instruções padronizadas de como adicionar
 * a dependência ausente via Maven e Gradle, geradas por {@link Dependencies}. A exceção é
 * lançada a cada chamada sem a dependência; a mesma mensagem vai para o console só na primeira.</p>
 *
 * <p>Exemplo de tratamento:</p>
 * <pre>
 * try {
 *     Dependencies.require(...);
 * } catch (MissingDependencyException e) {
 *     // Na primeira falha, a mensagem já foi exibida no console; encerre graciosamente.
 * }
 * </pre>
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
