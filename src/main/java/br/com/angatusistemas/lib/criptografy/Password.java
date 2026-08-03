package br.com.angatusistemas.lib.criptografy;

import java.util.regex.Pattern;

import org.mindrot.jbcrypt.BCrypt;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;

/**
 * Classe utilitária para hash e verificação de senhas utilizando o algoritmo
 * BCrypt.
 *
 * <p>BCrypt é um algoritmo de hash adaptativo, considerado seguro contra ataques
 * de força bruta, pois permite ajustar o custo computacional. O salt é gerado
 * automaticamente e embutido no hash resultante.</p>
 *
 * <p><strong>Características:</strong></p>
 * <ul>
 *   <li>Gera hash com salt embutido (formato: {@code $2a$/$2b$/$2y$} + custo + hash de 53 caracteres)</li>
 *   <li>Verificação segura contra timing attacks via {@link BCrypt#checkpw(String, String)}</li>
 *   <li>{@link #criptography(String)} reconhece hashes já gerados e não re-hasheia</li>
 * </ul>
 *
 * <p>Exemplo de uso:
 * <pre>
 * // Gerar hash
 * String hash = Password.criptography("minhaSenha123");
 *
 * // Verificar senha
 * if (Password.checkCriptography("minhaSenha123", hash)) {
 *     System.out.println("Senha correta");
 * }
 * </pre>
 * </p>
 *
 * <p><strong>Dependência:</strong> este módulo requer {@code org.mindrot:jbcrypt:0.4}
 * no classpath. Se ausente, os métodos exibem instruções de instalação e lançam
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException}.</p>
 *
 * @author Angatu Sistemas
 * @see BCrypt
 * @see br.com.angatusistemas.lib.dependencies.Dependencies
 */
public final class Password {

    /** Coordenadas Maven da dependência jbcrypt. */
    private static final String BCRYPT_COORDINATES = "org.mindrot:jbcrypt:0.4";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String BCRYPT_FEATURE = "Hash de Senhas (BCrypt)";

    /** Formato de hash BCrypt: $2a$/$2b$/$2y$ + custo de 2 dígitos + 53 caracteres. */
    private static final Pattern BCRYPT_HASH_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private Password() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Gera o hash BCrypt de uma senha em texto puro.
     *
     * <p>Se o valor informado já estiver no formato de um hash BCrypt válido
     * (ex: começando com {@code $2a$}, {@code $2b$} ou {@code $2y$}), ele é
     * retornado sem modificação — evitando re-hash acidental de hashes.</p>
     *
     * @param password Senha em texto puro (pode ser {@code null})
     * @return Hash BCrypt da senha, o próprio hash se já for válido, ou {@code null}
     *         se a entrada for {@code null}
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência jbcrypt não estiver no classpath
     */
    public static String criptography(String password) {
        Dependencies.require("org.mindrot.jbcrypt.BCrypt", BCRYPT_COORDINATES, BCRYPT_FEATURE);
        if (password == null) {
            Console.warn("Password.criptography: entrada nula retornará null");
            return null;
        }
        if (isBCryptHash(password)) {
            Console.debug("Password.criptography: valor já é um hash BCrypt, retornando original");
            return password;
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        Console.debug("Password.criptography: novo hash gerado");
        return hash;
    }

    /**
     * Verifica se uma senha em texto puro corresponde a um hash BCrypt previamente
     * gerado.
     *
     * <p>A comparação é feita pelo BCrypt e é segura contra timing attacks.
     * Parâmetros {@code null} ou hash malformado resultam em {@code false}.</p>
     *
     * @param password              Senha em texto puro
     * @param criptographedPassword Hash BCrypt previamente armazenado
     * @return {@code true} se a senha corresponde ao hash; {@code false} caso contrário
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência jbcrypt não estiver no classpath
     */
    public static boolean checkCriptography(String password, String criptographedPassword) {
        Dependencies.require("org.mindrot.jbcrypt.BCrypt", BCRYPT_COORDINATES, BCRYPT_FEATURE);
        if (password == null || criptographedPassword == null) {
            Console.debug("Password.checkCriptography: um dos parâmetros é nulo, retornando false");
            return false;
        }
        try {
            boolean result = BCrypt.checkpw(password, criptographedPassword);
            Console.debug("Password.checkCriptography: verificação concluída, resultado=%s", result);
            return result;
        } catch (IllegalArgumentException e) {
            // Hash malformado não é uma falha de verificação — apenas senha inválida
            Console.debug("Password.checkCriptography: hash malformado, retornando false");
            return false;
        }
    }

    /**
     * Verifica se uma string tem o formato de um hash BCrypt válido
     * ({@code $2[aby]$dd$salt+hash}).
     *
     * @param value String a testar (pode ser {@code null})
     * @return {@code true} se a string corresponde ao formato de hash BCrypt
     */
    private static boolean isBCryptHash(String value) {
        return value != null && BCRYPT_HASH_PATTERN.matcher(value).matches();
    }
}
