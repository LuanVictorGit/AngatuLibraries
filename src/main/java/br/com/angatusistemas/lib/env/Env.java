package br.com.angatusistemas.lib.env;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Classe utilitária para acesso às variáveis de ambiente definidas no arquivo
 * {@code .env} do projeto e no ambiente do sistema.
 *
 * <p>Utiliza a biblioteca dotenv-java para carregar as variáveis do arquivo {@code .env} do
 * diretório de trabalho (ou, na falta dele, da raiz do classpath). <strong>Nunca lança exceção
 * por causa do arquivo:</strong> se ele não existir, valem só as variáveis do sistema; se não
 * puder ser lido — por exemplo, salvo em ANSI/Windows-1252 em vez de UTF-8 —, um aviso vai para o
 * {@code Console} e também valem só as variáveis do sistema, até o arquivo ser corrigido e
 * {@link #reload()} ser chamado. Antes, um {@code .env} nessa codificação derrubava a classe:
 * {@code ExceptionInInitializerError} na primeira chamada, {@code NoClassDefFoundError} em todas
 * as seguintes, e nem {@link #reload()} recuperava.</p>
 *
 * <p><strong>Precedência:</strong> uma variável definida no ambiente do sistema vence a do
 * arquivo com o mesmo nome — é assim que o dotenv-java resolve {@code get}. Na hospedagem, o
 * painel manda; o {@code .env} serve ao desenvolvimento.</p>
 *
 * <p><strong>O que o {@code .env} não alcança:</strong> as configurações que a própria biblioteca
 * lê ao iniciar vêm de {@code System.getenv} ou de propriedades {@code -D}, e não desta classe —
 * {@code ANGATU_ENV} e {@code ENVIRONMENT} (o ambiente do {@code AngatuLib}, que também aceita
 * {@code -Dangatu.env}), {@code ANGATU_DB_PATH} e as demais {@code ANGATU_*}. Declaradas só no
 * {@code .env}, não têm efeito: defina-as no ambiente do processo (painel do Coolify,
 * {@code docker run -e}, configuração de execução da IDE).</p>
 *
 * <p><strong>Linhas que o dotenv-java descarta em silêncio:</strong> ao carregar, esta classe
 * confere o arquivo e avisa no {@code Console} — pelo nome da chave e pelo número da linha,
 * nunca pelo valor — quando o arquivo começa com a marca BOM (a primeira chave se perdia), quando
 * um valor abre aspas duplas que não fecham (ele e as linhas seguintes se perdiam) e quando uma
 * linha não está no formato {@code CHAVE=valor} (ex.: {@code export CHAVE=valor}).</p>
 *
 * <p>Exemplo de uso:</p>
 * <pre>
 * String token = Env.get().get("API_TOKEN");
 * String url   = Env.get().get("API_URL", "https://api.exemplo.com"); // com default
 * </pre>
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
    /** Diretório padrão do arquivo, o mesmo do dotenv-java: o diretório de trabalho. */
    static final String DEFAULT_DIRECTORY = "./";
    /** Nome do arquivo lido. */
    static final String FILE_NAME = ".env";

    /** Serializa a primeira carga e as recargas. */
    private static final Object LOAD_LOCK = new Object();

    /**
     * Instância carregada, criada no primeiro {@link #get()}. É um campo, e não um holder
     * estático: uma falha dentro de um inicializador estático marcaria a classe como quebrada
     * até reiniciar a aplicação — foi o que acontecia com um {@code .env} fora do UTF-8.
     */
    private static volatile Dotenv instance;

    private Env() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Retorna a instância carregada do Dotenv com todas as variáveis do arquivo
     * {@code .env} e do ambiente do sistema.
     *
     * <p>A primeira chamada lê o arquivo; as seguintes devolvem a mesma instância, até
     * {@link #reload()}. Nunca lança exceção por causa do conteúdo do arquivo.</p>
     *
     * @return Instância única do Dotenv já carregada
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência dotenv-java não estiver no classpath
     */
    public static Dotenv get() {
        Dependencies.require("io.github.cdimascio.dotenv.Dotenv", DOTENV_COORDINATES, DOTENV_FEATURE);
        Dotenv current = instance;
        if (current == null) {
            synchronized (LOAD_LOCK) {
                current = instance;
                if (current == null) {
                    current = EnvLoader.load(DEFAULT_DIRECTORY, Env::warn);
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Recarrega o arquivo {@code .env} do disco, substituindo a instância atual.
     *
     * <p>Útil após editar o arquivo {@code .env} em tempo de execução (ex: em ambientes de
     * desenvolvimento) — inclusive para recuperar depois de corrigir um arquivo que não pôde
     * ser lido. Arquivo ausente ou ilegível resulta numa instância só com as variáveis do
     * sistema, com aviso no {@code Console} quando ilegível.</p>
     *
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se a dependência dotenv-java não estiver no classpath
     */
    public static void reload() {
        Dependencies.require("io.github.cdimascio.dotenv.Dotenv", DOTENV_COORDINATES, DOTENV_FEATURE);
        synchronized (LOAD_LOCK) {
            instance = EnvLoader.load(DEFAULT_DIRECTORY, Env::warn);
        }
    }

    /** Envia um aviso ao console; o texto vai como argumento, para que nada nele vire código de cor. */
    private static void warn(String message) {
        Console.warn("%s", message);
    }

    /**
     * Tudo o que toca tipos do dotenv-java fica aqui, e não em {@link Env}: esta classe só é
     * carregada depois de {@code Dependencies.require}, e assim a falta da dependência continua
     * aparecendo como a mensagem de instalação, e não como {@code NoClassDefFoundError}.
     */
    static final class EnvLoader {

        /**
         * Arquivo que nenhum projeto tem: carregá-lo com {@code ignoreIfMissing} dá um
         * {@link Dotenv} só com as variáveis do sistema, pelo próprio dotenv-java.
         */
        private static final String NO_FILE = ".env.angatu-sem-arquivo";

        private EnvLoader() {
        }

        /**
         * Carrega o {@code .env} do diretório sem nunca lançar exceção por causa dele.
         *
         * @param directory Diretório do arquivo
         * @param warn      Destino dos avisos (só nomes de chave e números de linha)
         * @return Dotenv com o arquivo e o sistema, ou só com o sistema se o arquivo for ilegível
         */
        static Dotenv load(String directory, Consumer<String> warn) {
            EnvFileCheck.Report report = EnvFileCheck.inspect(EnvFileCheck.read(directory));
            try {
                Dotenv dotenv = Dotenv.configure()
                        .directory(directory)
                        .ignoreIfMalformed()
                        .ignoreIfMissing()
                        .load();
                report.warnings().forEach(warn);
                return dotenv;
            } catch (RuntimeException e) {
                // O ignoreIfMalformed não cobre codificação: um byte fora do UTF-8 vira
                // MalformedInputException dentro de DotenvException, e antes isso escapava.
                warn.accept(EnvFileCheck.unreadableMessage(e, report.encodingProblem()));
                return Dotenv.configure().filename(NO_FILE).ignoreIfMissing().ignoreIfMalformed().load();
            }
        }
    }

    /**
     * Inspeção do arquivo {@code .env} com as mesmas regras do analisador do dotenv-java 3.2.0,
     * para avisar sobre as linhas que ele descarta sem dizer nada. Só usa tipos do JDK.
     *
     * <p>Os avisos citam o nome da chave e o número da linha, nunca o valor — nem trechos de
     * linhas que não são claramente uma chave, porque a linha de continuação de um valor entre
     * aspas (uma chave privada PEM, por exemplo) pode parecer um {@code NOME=}.</p>
     */
    static final class EnvFileCheck {

        /** A mesma expressão de {@code DotenvParser} (dotenv-java 3.2.0), para ver o arquivo como ele vê. */
        private static final Pattern ENTRY = Pattern.compile(
                "^\\s*([\\w.\\-]+)\\s*(=)\\s*(['][^']*[']|[\"][^\"]*[\"]|[^#]*)?\\s*(#.*)?$");
        /** Início de uma linha {@code CHAVE=}, para nomear a chave de uma linha de primeiro nível. */
        private static final Pattern KEY_PREFIX = Pattern.compile("^\\s*([\\w.\\-]+)\\s*=");
        /** {@code export CHAVE=valor}: o nome depois do {@code export} é uma chave, sem ambiguidade. */
        private static final Pattern EXPORTED = Pattern.compile("^\\s*export\\s+([\\w.\\-]+)\\s*=");
        /** Marca de ordem de bytes do UTF-8, como aparece depois de decodificada. */
        private static final char BOM = '﻿';

        /**
         * Resultado da inspeção.
         *
         * @param warnings        Avisos a exibir quando o arquivo é carregado
         * @param encodingProblem Onde está o primeiro byte fora do UTF-8 ({@code "linha 3, chave X"}),
         *                        ou {@code null}
         */
        record Report(List<String> warnings, String encodingProblem) {
        }

        private EnvFileCheck() {
        }

        /**
         * Lê o arquivo que o dotenv-java vai ler: o do diretório ou, no diretório padrão, o da raiz
         * do classpath.
         *
         * @param directory Diretório do arquivo
         * @return Conteúdo, ou {@code null} se não houver arquivo ou ele não puder ser lido — o
         *         dotenv-java lê o mesmo arquivo em seguida e relata a falha
         */
        static byte[] read(String directory) {
            try {
                Path file = Paths.get(directory).resolve(FILE_NAME);
                if (Files.isRegularFile(file)) {
                    return Files.readAllBytes(file);
                }
                if (DEFAULT_DIRECTORY.equals(directory)) {
                    try (InputStream in = Env.class.getResourceAsStream("/" + FILE_NAME)) {
                        return in == null ? null : in.readAllBytes();
                    }
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
            return null;
        }

        /**
         * Confere o conteúdo linha a linha, simulando o analisador do dotenv-java.
         *
         * @param content Bytes do arquivo (pode ser {@code null})
         * @return Avisos e a localização do primeiro byte fora do UTF-8
         */
        static Report inspect(byte[] content) {
            List<String> warnings = new ArrayList<>();
            if (content == null || content.length == 0) {
                return new Report(warnings, null);
            }
            int invalidLine = firstInvalidUtf8Line(content);
            // Decodificação tolerante, como a do classpath no dotenv-java; o BOM continua no
            // começo da primeira linha, como no Files.readAllLines que ele usa.
            List<String> lines = new String(content, StandardCharsets.UTF_8).lines().toList();

            String current = "";
            String openKey = null;
            int openLine = 0;
            String invalidKey = null;
            int number = 0;
            for (String line : lines) {
                number++;
                if (number == invalidLine && current.isEmpty()) {
                    Matcher key = KEY_PREFIX.matcher(line);
                    invalidKey = key.find() ? key.group(1) : null;
                }
                if (current.isEmpty() && (line.trim().isEmpty() || line.startsWith("#") || line.startsWith("////"))) {
                    continue;
                }
                current += line;
                Matcher entry = ENTRY.matcher(current);
                if (!entry.matches()) {
                    if (openKey != null) {
                        warnings.add(unclosedQuoteMessage(openKey, openLine, number));
                    } else {
                        String message = malformedLineMessage(line, number);
                        if (message != null) {
                            warnings.add(message);
                        }
                    }
                    current = "";
                    openKey = null;
                    continue;
                }
                String value = entry.group(3) == null ? "" : entry.group(3);
                if (value.startsWith("\"") && !value.endsWith("\"")) {
                    // Valor entre aspas em várias linhas: o dotenv-java junta as seguintes até fechar.
                    if (openKey == null) {
                        openKey = entry.group(1);
                        openLine = number;
                    }
                    current += "\n";
                    continue;
                }
                if (!hasBalancedQuotes(value)) {
                    String key = openKey != null ? openKey : entry.group(1);
                    int first = openKey != null ? openLine : number;
                    warnings.add("Arquivo .env: a chave " + key + " (" + lineRange(first, number)
                            + ") foi ignorada porque as aspas duplas do valor não estão balanceadas.");
                }
                current = "";
                openKey = null;
            }
            if (openKey != null) {
                warnings.add(unclosedQuoteMessage(openKey, openLine, number));
            }

            String encodingProblem = null;
            if (invalidLine > 0) {
                encodingProblem = "linha " + invalidLine + (invalidKey != null ? ", chave " + invalidKey : "");
                // Só chega a ser exibido quando o dotenv-java carrega assim mesmo (o .env do
                // classpath, que ele decodifica trocando os bytes inválidos); do disco, a carga falha.
                warnings.add("Arquivo .env: há bytes fora do UTF-8 (" + encodingProblem
                        + "), lidos com caracteres trocados. Salve o arquivo com a codificação UTF-8.");
            }
            return new Report(warnings, encodingProblem);
        }

        /**
         * Aviso para o arquivo que não pôde ser carregado. Cita os tipos das exceções, e não as
         * mensagens: a de uma linha malformada, no dotenv-java, traz a linha inteira.
         *
         * @param error           Falha do dotenv-java
         * @param encodingProblem Onde está o primeiro byte fora do UTF-8, ou {@code null}
         * @return Aviso em português
         */
        static String unreadableMessage(Throwable error, String encodingProblem) {
            String reason = encodingProblem != null
                    ? "ele não está em UTF-8 (primeiro caractere inválido: " + encodingProblem + ")"
                    : "falha ao ler o arquivo (" + exceptionTypes(error) + ")";
            return "Arquivo .env não carregado: " + reason + ". Até corrigir, valem só as variáveis de "
                    + "ambiente do sistema. Salve o arquivo em UTF-8 e chame Env.reload() ou reinicie a aplicação.";
        }

        /** Aviso para uma linha de primeiro nível descartada, ou {@code null} se ela não levava nada. */
        private static String malformedLineMessage(String line, int number) {
            boolean startsWithBom = number == 1 && !line.isEmpty() && line.charAt(0) == BOM;
            String effective = startsWithBom ? line.substring(1) : line;
            String trimmed = effective.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("////")) {
                return null; // comentário recuado ou linha vazia depois do BOM: nada se perdeu
            }
            if (startsWithBom) {
                Matcher entry = ENTRY.matcher(effective);
                if (entry.matches()) {
                    return "Arquivo .env: a chave " + entry.group(1) + " (linha 1) foi ignorada porque o arquivo "
                            + "começa com a marca BOM do UTF-8. Salve o arquivo como UTF-8 sem BOM.";
                }
            }
            Matcher exported = EXPORTED.matcher(effective);
            if (exported.find()) {
                return "Arquivo .env: a chave " + exported.group(1) + " (linha " + number
                        + ") foi ignorada. Tire o \"export\" do começo da linha.";
            }
            return "Arquivo .env: a linha " + number + " foi ignorada porque não está no formato CHAVE=valor.";
        }

        private static String unclosedQuoteMessage(String key, int openLine, int lastLine) {
            String lines = openLine == lastLine
                    ? "a linha " + openLine + " foi ignorada"
                    : "as linhas " + openLine + " a " + lastLine + " foram ignoradas, com as chaves que estavam nelas";
            return "Arquivo .env: o valor da chave " + key + " (linha " + openLine
                    + ") abre aspas duplas que não se fecham; " + lines + ". Feche as aspas.";
        }

        private static String lineRange(int first, int last) {
            return first == last ? "linha " + first : "linhas " + first + " a " + last;
        }

        /** Mesma regra do {@code QuotedStringValidator} do dotenv-java 3.2.0. */
        private static boolean hasBalancedQuotes(String value) {
            String s = value.trim();
            boolean starts = s.startsWith("\"");
            boolean ends = s.endsWith("\"");
            if (!starts && !ends) {
                return true;
            }
            if (s.length() == 1 || !(starts && ends)) {
                return false;
            }
            String content = s.substring(1, s.length() - 1);
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Número (a partir de 1) da linha com o primeiro byte fora do UTF-8, ou 0 se não houver.
         */
        static int firstInvalidUtf8Line(byte[] content) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer in = ByteBuffer.wrap(content);
            CharBuffer out = CharBuffer.allocate(content.length + 1); // UTF-8 nunca dá mais caracteres que bytes
            CoderResult result = decoder.decode(in, out, true);
            if (!result.isError()) {
                result = decoder.flush(out);
                if (!result.isError()) {
                    return 0;
                }
            }
            int line = 1;
            for (int i = 0; i < in.position(); i++) {
                if (content[i] == '\n' || (content[i] == '\r' && (i + 1 >= content.length || content[i + 1] != '\n'))) {
                    line++;
                }
            }
            return line;
        }

        /** Tipos da cadeia de causas, sem as mensagens (ex.: {@code DotenvException: MalformedInputException}). */
        private static String exceptionTypes(Throwable error) {
            StringBuilder types = new StringBuilder();
            for (Throwable current = error; current != null; current = current.getCause()) {
                if (types.length() > 0) {
                    types.append(": ");
                }
                types.append(current.getClass().getSimpleName());
                if (current.getCause() == current) {
                    break;
                }
            }
            return types.toString();
        }
    }
}
