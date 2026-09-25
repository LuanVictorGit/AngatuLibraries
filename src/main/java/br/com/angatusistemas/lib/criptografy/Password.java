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
 *   <li>Gera hash com salt embutido (formato: {@code $2a$} + custo + 53 caracteres), custo 10</li>
 *   <li>Verificação segura contra timing attacks via {@link BCrypt#checkpw(String, String)}; aceita
 *       também hashes {@code $2b$} e {@code $2y$} vindos de outros sistemas (Node, Python, PHP)</li>
 *   <li>{@link #hash(String)} sempre gera o hash — é o método para senha digitada pelo usuário</li>
 *   <li>{@link #criptography(String)} reconhece hashes já gerados e não re-hasheia — para
 *       regravar um valor que pode já estar em hash</li>
 * </ul>
 *
 * <p><strong>Limite do BCrypt:</strong> só os primeiros 72 bytes da senha (em UTF-8) contam; o
 * que passa disso é ignorado pelo algoritmo. Uma senha de 36 caracteres acentuados já ocupa os
 * 72 bytes.</p>
 *
 * <p>Exemplo de uso:</p>
 * <pre>
 * // Cadastro: a senha digitada vira hash
 * String hash = Password.hash(senhaDigitada);
 *
 * // Login
 * if (Password.checkCriptography(senhaDigitada, hashGravado)) {
 *     // senha correta
 * }
 * </pre>
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

    /**
     * Faixa de custo aceita num hash vindo de fora.
     *
     * <h4>Por que existe um teto</h4>
     * <p>O custo do BCrypt dobra o trabalho a cada unidade, e quem monta o hash escolhe o custo.
     * Como {@link #criptography(String)} guardava como veio qualquer valor com cara de hash, um
     * cadastro com a "senha" {@code $2a$16$…} ficava gravado assim — e cada tentativa de login
     * naquela conta levava 3,9 s de CPU (medido); com custo 30, perto de 18 horas por tentativa.
     * Poucas requisições de login ocupavam todos os núcleos do servidor. Doze cobre os sistemas
     * reais (o padrão de Node, Python, PHP e desta biblioteca é 10) e custa ~250 ms.</p>
     */
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 12;

    private Password() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Gera o hash BCrypt de uma senha, sempre — é o método para senha digitada pelo usuário.
     *
     * <p>Diferente de {@link #criptography(String)}, não reconhece hash: um valor com cara de hash
     * é tratado como senha comum. Guardar como veio um "hash" enviado pelo cliente pulava a regra
     * de senha e permitia escolher o custo (ver {@link #MAX_COST}).</p>
     *
     * @param password Senha em texto puro
     * @return Hash BCrypt, ou {@code null} se a entrada for {@code null}
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência jbcrypt não estiver no classpath
     */
    public static String hash(String password) {
        Dependencies.require("org.mindrot.jbcrypt.BCrypt", BCRYPT_COORDINATES, BCRYPT_FEATURE);
        if (password == null) return null;
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    /**
     * Gera o hash BCrypt de uma senha em texto puro — ou devolve o valor como veio, se ele já for
     * um hash BCrypt de custo entre 4 e 12.
     *
     * <p>Existe para regravar um valor que pode já estar em hash sem hashear de novo. Para senha
     * digitada pelo usuário, use {@link #hash(String)}: aqui, quem digita um hash como senha
     * guarda o próprio hash. Fora da faixa de custo, o valor é tratado como senha comum e ganha
     * hash — nunca é gravado um hash que custe caro demais para verificar.</p>
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
        if (isAcceptableHash(password)) {
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
     * <p>A comparação é feita pelo BCrypt e é segura contra timing attacks. Hashes
     * {@code $2b$} e {@code $2y$} (Node, Python, PHP) são verificados como o {@code $2a$}
     * equivalente — antes, a senha certa de um usuário migrado devolvia {@code false}, igual a
     * uma senha errada. Parâmetro {@code null}, hash malformado ou de custo acima de 12 resultam
     * em {@code false} — nunca em exceção, que numa rota de login virava erro 500.</p>
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
        if (!isAcceptableHash(criptographedPassword)) {
            Console.debug("Password.checkCriptography: hash malformado ou de custo fora da faixa, retornando false");
            return false;
        }
        // O jBCrypt 0.4 só entende "$2a$"; "$2b$" e "$2y$" são o mesmo algoritmo para senhas
        // de até 72 bytes.
        String hash = criptographedPassword.startsWith("$2a$")
                ? criptographedPassword
                : "$2a$" + criptographedPassword.substring(4);
        try {
            boolean result = BCrypt.checkpw(password, hash);
            Console.debug("Password.checkCriptography: verificação concluída, resultado=%s", result);
            return result;
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            // Hash malformado não é uma falha de verificação — apenas senha inválida
            Console.debug("Password.checkCriptography: hash malformado, retornando false");
            return false;
        }
    }

    /**
     * O valor tem o formato de um hash BCrypt ({@code $2[aby]$dd$salt+hash}) com custo entre
     * {@link #MIN_COST} e {@link #MAX_COST}?
     *
     * @param value String a testar (pode ser {@code null})
     * @return {@code true} se for um hash aceitável
     */
    private static boolean isAcceptableHash(String value) {
        if (value == null || !BCRYPT_HASH_PATTERN.matcher(value).matches()) return false;
        int cost = Integer.parseInt(value.substring(4, 6));
        return cost >= MIN_COST && cost <= MAX_COST;
    }
}
