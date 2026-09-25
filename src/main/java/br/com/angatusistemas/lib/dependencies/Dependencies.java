package br.com.angatusistemas.lib.dependencies;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utilitário para detecção automática de dependências ausentes no classpath.
 *
 * <p>A AngatuLibraries é distribuída <strong>sem</strong> empacotar as bibliotecas
 * de terceiros que utiliza. Cada funcionalidade (Web, Banco de dados, E-mail,
 * Web Push, Discord, IA, etc.) depende de uma biblioteca externa que deve ser
 * declarada pelo consumidor quando a funcionalidade for realmente utilizada.</p>
 *
 * <p>Quando uma funcionalidade é acionada sem a dependência correspondente,
 * esta classe detecta a ausência via {@link Class#forName(String)} (reflexão,
 * sem causar {@code NoClassDefFoundError} durante a inicialização) e exibe uma
 * mensagem padronizada no console — uma vez por classe ausente, para não inundar o log
 * quando a funcionalidade é chamada a cada requisição — informando:</p>
 *
 * <ul>
 *   <li>Qual biblioteca está ausente (coordenadas Maven);</li>
 *   <li>Qual funcionalidade depende dela;</li>
 *   <li>Como adicioná-la via Maven;</li>
 *   <li>Como adicioná-la via Gradle.</li>
 * </ul>
 *
 * <p>Exemplo de mensagem exibida:</p>
 * <pre>
 * [AngatuLibraries] Dependência ausente: io.javalin:javalin:7.2.3
 *
 * A funcionalidade "Web Server (Javalin)" depende desta biblioteca, mas ela não foi encontrada no classpath.
 *
 * Para habilitar esta funcionalidade, adicione:
 *
 * Maven:
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.javalin&lt;/groupId&gt;
 *     &lt;artifactId&gt;javalin&lt;/artifactId&gt;
 *     &lt;version&gt;7.2.3&lt;/version&gt;
 * &lt;/dependency&gt;
 *
 * Gradle:
 * implementation("io.javalin:javalin:7.2.3")
 * </pre>
 *
 * <p><strong>Importante:</strong> esta classe nunca lança exceções de carregamento
 * de classe — ela apenas verifica nomes de classes via string, portanto é segura
 * para uso no início da inicialização da aplicação.</p>
 *
 * @author Angatu Sistemas
 * @see MissingDependencyException
 */
public final class Dependencies {

    /** Prefixo padronizado das mensagens no console. */
    private static final String PREFIX = "[AngatuLibraries]";
    /** Cache de presença/ausência para evitar verificações repetidas. */
    private static final Map<String, Boolean> PRESENCE_CACHE = new ConcurrentHashMap<>();
    /** Classes ausentes cuja mensagem de instalação já foi exibida no console. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Dependencies() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Verifica se uma classe está presente no classpath, com cache de resultado.
     *
     * @param className Nome totalmente qualificado da classe (ex: {@code "io.javalin.Javalin"})
     * @return {@code true} se a classe foi encontrada
     */
    public static boolean isPresent(String className) {
        // get() antes: para uma chave que divide o compartimento da tabela com outra, o
        // computeIfAbsent do ConcurrentHashMap entra num synchronized a cada chamada, mesmo com a
        // chave já presente — e isto roda em toda operação dos módulos, sob milhares de requisições.
        Boolean cached = PRESENCE_CACHE.get(className);
        if (cached != null) {
            return cached;
        }
        return PRESENCE_CACHE.computeIfAbsent(className, Dependencies::exists);
    }

    /**
     * Exige que uma dependência esteja presente no classpath.
     *
     * <p>Se a classe correspondente for encontrada, retorna normalmente.
     * Caso contrário, lança {@link MissingDependencyException} com a mensagem padronizada de
     * dependência ausente — a cada chamada. A mesma mensagem vai para o console só na primeira
     * falha de cada classe: antes, as cerca de 15 linhas se repetiam a cada chamada.</p>
     *
     * <p>Deve ser chamado no <strong>início</strong> dos métodos públicos dos
     * módulos que dependem de bibliotecas externas, antes de qualquer referência
     * direta aos tipos da biblioteca verificada.</p>
     *
     * @param className  Nome totalmente qualificado de uma classe da biblioteca
     *                   (ex: {@code "io.javalin.Javalin"})
     * @param coordinates Coordenadas Maven no formato {@code groupId:artifactId:version}
     *                    (ex: {@code "io.javalin:javalin:7.2.3"})
     * @param feature    Nome descritivo da funcionalidade que depende da biblioteca
     *                   (ex: {@code "Web Server (Javalin)"})
     * @throws MissingDependencyException se a dependência não estiver presente
     */
    public static void require(String className, String coordinates, String feature) {
        if (isPresent(className)) return;
        String message = buildMessage(coordinates, feature);
        printOnce(className, message);
        throw new MissingDependencyException(message);
    }

    /**
     * Exige uma dependência e retorna {@code false} (sem lançar exceção) se ausente.
     *
     * <p>Variante não-throwing para funcionalidades opcionais: a mensagem é exibida no console
     * (na primeira falha de cada classe) e o método retorna {@code false}, permitindo que o
     * chamador degrade graciosamente a funcionalidade.</p>
     *
     * @param className   Nome totalmente qualificado de uma classe da biblioteca
     * @param coordinates Coordenadas Maven {@code groupId:artifactId:version}
     * @param feature     Nome descritivo da funcionalidade dependente
     * @return {@code true} se a dependência está presente
     */
    public static boolean check(String className, String coordinates, String feature) {
        if (isPresent(className)) return true;
        printOnce(className, buildMessage(coordinates, feature));
        return false;
    }

    /**
     * Exibe a mensagem de instalação só na primeira falha de cada classe. A exceção de
     * {@link #require} continua sendo lançada sempre, com a mensagem completa.
     */
    private static void printOnce(String className, String message) {
        if (REPORTED.add(className)) {
            System.out.println(message);
        }
    }

    /**
     * Constrói a mensagem padronizada de dependência ausente.
     *
     * @param coordinates Coordenadas Maven {@code groupId:artifactId:version}
     * @param feature     Nome descritivo da funcionalidade dependente
     * @return Mensagem formatada conforme o padrão da biblioteca
     */
    private static String buildMessage(String coordinates, String feature) {
        String[] parts = coordinates.split(":");
        if (parts.length != 3) {
            return PREFIX + " Coordenadas inválidas para " + coordinates;
        }
        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];

        return PREFIX + " Dependência ausente: " + coordinates + "\n"
                + "\n"
                + "A funcionalidade \"" + feature + "\" depende desta biblioteca, "
                + "mas ela não foi encontrada no classpath.\n"
                + "\n"
                + "Para habilitar esta funcionalidade, adicione:\n"
                + "\n"
                + "Maven:\n"
                + "<dependency>\n"
                + "    <groupId>" + groupId + "</groupId>\n"
                + "    <artifactId>" + artifactId + "</artifactId>\n"
                + "    <version>" + version + "</version>\n"
                + "</dependency>\n"
                + "\n"
                + "Gradle:\n"
                + "implementation(\"" + coordinates + "\")";
    }

    /** Procura a classe sem inicializá-la; qualquer falha de carregamento conta como ausente. */
    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Dependencies.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
