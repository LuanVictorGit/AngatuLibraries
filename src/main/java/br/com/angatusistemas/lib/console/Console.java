package br.com.angatusistemas.lib.console;

import java.io.PrintStream;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.time.DataTime;

/**
 * Classe utilitária de logging no console com cores ANSI e timestamp.
 *
 * <p><strong>Propósito:</strong> centralizar toda a saída de log da biblioteca e
 * das aplicações consumidoras, com níveis (log/info/warn/error/debug), cores
 * ({@link AnsiColor}) e timestamp ({@link DataTime#getData()}).</p>
 *
 * <p><strong>Quando usar:</strong> em qualquer ponto da aplicação — inclusive
 * antes da inicialização do {@link AngatuLib} (o {@code Console} funciona com
 * fallback para {@code System.out}). Os métodos aceitam formatação printf
 * ({@code %s}, {@code %d}) e exceções como último argumento (imprime stack trace).</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para dados sensíveis em produção
 * (credenciais, tokens); para logs volumosos por requisição (use o nível
 * {@code debug}, silencioso por padrão); não substitua a política de log da
 * aplicação — este é um logger de console simples, sem arquivos nem rotação.</p>
 *
 * <p><strong>Integração:</strong> o {@link AngatuLib} redireciona
 * {@code System.out} para um {@link InterceptorOutputStream}, que roteia toda
 * saída padrão para o {@code Console}; o stream original fica preservado em
 * {@code AngatuLib#getOriginalOut()}.</p>
 *
 * <p><strong>Fluxo de utilização:</strong> use o nível adequado — {@code log}
 * (genérico), {@code info} (informação), {@code warn} (aviso), {@code error}
 * (falha, com exceção opcional), {@code debug} (detalhes, ativo apenas com
 * {@code -Dangatu.debug=true} ou {@link #setDebugEnabled(boolean)}).</p>
 *
 * <p><strong>Exemplo:</strong>
 * <pre>
 * Console.log("Servidor iniciado");
 * Console.info("Usuário logado: %s", username);
 * Console.warn("Disco quase cheio: %d%% usado", percent);
 * Console.error("Falha na conexão", exception);   // imprime stack trace
 * Console.debug("Valor recebido: %s", valor);     // só com debug ativo
 * </pre>
 * </p>
 *
 * <p><strong>Boas práticas:</strong> exceções SEMPRE como último argumento do
 * {@code error} (habilita a stack trace); use {@code %s} em vez de concatenação;
 * ative debug apenas em desenvolvimento.</p>
 *
 * <p><strong>Limitações:</strong> loga apenas no console (sem persistência);
 * cores ANSI requerem terminal compatível; o redirecionamento do
 * {@code System.out} é global ao processo.</p>
 *
 * <p><strong>Extensões futuras:</strong> níveis configuráveis por propriedade e
 * sinks de saída (arquivo, SLF4J) são evoluções naturais sem quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see AnsiColor
 * @see DataTime
 * @see InterceptorOutputStream
 * @see AngatuLib
 */
public final class Console {

    // Formato base do log: [data/hora] mensagem
    private static final String LOG_PATTERN = "&6[%s] &7%s";

    private Console() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Resolve o PrintStream de saída de forma preguiçosa.
     *
     * <p>Se o {@link AngatuLib} já foi inicializado, usa o output original
     * (preservado antes da intercepção do System.out). Caso contrário — por
     * exemplo, quando {@link Console} é usado antes da inicialização da
     * biblioteca — cai em {@link System#out} para não lançar NPE.</p>
     */
    private static PrintStream output() {
        AngatuLib lib = AngatuLib.getInstance();
        return lib != null ? lib.getOriginalOut() : System.out;
    }

    // ==================== MÉTODOS PRINCIPAIS ====================

    /**
     * [PT] Registra uma mensagem genérica no console (nível padrão).
     *
     * [EN] Logs a generic message to the console (default level).
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     */
    public static void log(Object obj) {
        String formatted = formatLogMessage(obj);
        output().println(AnsiColor.parse(formatted));
    }

    /**
     * [PT] Registra uma mensagem formatada (como {@code printf}) no nível genérico.
     * <p>
     * Suporta múltiplos argumentos e concatenação automática quando não há formato.
     * </p>
     *
     * [EN] Logs a formatted message (like {@code printf}) at generic level.
     * <p>
     * Supports multiple arguments and automatic concatenation when no format is present.
     * </p>
     *
     * @param format [PT] string de formato ou mensagem base
     *               [EN] format string or base message
     * @param args   [PT] argumentos para formatação ou concatenação
     *               [EN] arguments for formatting or concatenation
     */
    public static void log(String format, Object... args) {
        String message = processMessage(format, args);
        log(message);
    }

    /**
     * [PT] Registra uma mensagem de informação (nível INFO) com cor azul.
     *
     * [EN] Logs an info message (INFO level) with blue color.
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     */
    public static void info(Object obj) {
        logColored(obj, "&9");
    }

    /**
     * [PT] Registra uma mensagem de informação formatada.
     *
     * [EN] Logs a formatted info message.
     *
     * @param format [PT] string de formato ou mensagem base
     *               [EN] format string or base message
     * @param args   [PT] argumentos para formatação ou concatenação
     *               [EN] arguments for formatting or concatenation
     */
    public static void info(String format, Object... args) {
        String message = processMessage(format, args);
        info(message);
    }

    /**
     * [PT] Registra um aviso (nível WARN) com cor amarela.
     *
     * [EN] Logs a warning (WARN level) with yellow color.
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     */
    public static void warn(Object obj) {
        logColored(obj, "&e");
    }

    /**
     * [PT] Registra um aviso formatado.
     *
     * [EN] Logs a formatted warning.
     *
     * @param format [PT] string de formato ou mensagem base
     *               [EN] format string or base message
     * @param args   [PT] argumentos para formatação ou concatenação
     *               [EN] arguments for formatting or concatenation
     */
    public static void warn(String format, Object... args) {
        String message = processMessage(format, args);
        warn(message);
    }

    /**
     * [PT] Registra um erro (nível ERROR) com cor vermelha.
     * <p>
     * Se um {@link Throwable} for fornecido, sua stack trace é impressa.
     * </p>
     *
     * [EN] Logs an error (ERROR level) with red color.
     * <p>
     * If a {@link Throwable} is provided, its stack trace is printed.
     * </p>
     *
     * @param obj [PT] objeto a ser logado (pode ser string ou objeto)
     *            [EN] object to log (can be string or object)
     * @param t   [PT] exceção opcional (pode ser nula)
     *            [EN] optional exception (may be null)
     */
    public static void error(Object obj, Throwable t) {
        String timestamp = DataTime.getData().replace(" ", "");
        String message = String.valueOf(obj);
        
        // Log da mensagem principal
        String coloredPattern = String.format("&c[%s] &7%s", timestamp, message);
        output().println(AnsiColor.parse(coloredPattern));

        // Log da stack trace se houver exceção
        if (t != null) {
            t.printStackTrace(output());
        }
    }

    /**
     * [PT] Registra um erro sem exceção.
     *
     * [EN] Logs an error without exception.
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     */
    public static void error(Object obj) {
        error(obj, null);
    }

    /**
     * [PT] Registra um erro formatado com múltiplos argumentos.
     * <p>
     * Suporta múltiplos argumentos e concatenação automática quando não há formato.
     * Se o último argumento for uma Throwable, ela é tratada como exceção.
     * </p>
     *
     * [EN] Logs a formatted error with multiple arguments.
     * <p>
     * Supports multiple arguments and automatic concatenation when no format is present.
     * If the last argument is a Throwable, it is treated as an exception.
     * </p>
     *
     * @param format [PT] string de formato ou mensagem base
     *               [EN] format string or base message
     * @param args   [PT] argumentos para formatação ou concatenação
     *               [EN] arguments for formatting or concatenation
     */
    public static void error(String format, Object... args) {
        // Verifica se o último argumento é uma Throwable
        Throwable throwable = null;
        Object[] actualArgs = args;
        
        if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
            throwable = (Throwable) args[args.length - 1];
            // Remove a exceção dos argumentos
            actualArgs = new Object[args.length - 1];
            System.arraycopy(args, 0, actualArgs, 0, args.length - 1);
        }
        
        String message = processMessage(format, actualArgs);
        error(message, throwable);
    }

    /**
     * [PT] Registra uma mensagem de depuração (nível DEBUG) com cor cinza.
     * <p>
     * Por padrão, essas mensagens não são exibidas a menos que a flag de debug esteja ativa.
     * </p>
     *
     * [EN] Logs a debug message (DEBUG level) with gray color.
     * <p>
     * By default, these messages are not shown unless the debug flag is enabled.
     * </p>
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     */
    public static void debug(Object obj) {
        if (isDebugEnabled()) {
            logColored(obj, "&8");
        }
    }

    /**
     * [PT] Registra uma mensagem de depuração formatada.
     *
     * [EN] Logs a formatted debug message.
     *
     * @param format [PT] string de formato ou mensagem base
     *               [EN] format string or base message
     * @param args   [PT] argumentos para formatação ou concatenação
     *               [EN] arguments for formatting or concatenation
     */
    public static void debug(String format, Object... args) {
        if (isDebugEnabled()) {
            String message = processMessage(format, args);
            debug(message);
        }
    }

    // ==================== MÉTODOS DE CONTROLE DE DEBUG ====================

    private static boolean debugEnabled = Boolean.parseBoolean(System.getProperty("angatu.debug", "false"));

    /**
     * [PT] Verifica se o modo debug está ativo.
     * [EN] Checks if debug mode is enabled.
     *
     * @return [PT] true se mensagens de debug devem ser exibidas
     *         [EN] true if debug messages should be displayed
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * [PT] Ativa ou desativa o modo debug em tempo de execução.
     * [EN] Enables or disables debug mode at runtime.
     *
     * @param enabled [PT] true para exibir mensagens de debug
     *                [EN] true to show debug messages
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    // ==================== MÉTODOS PRIVADOS AUXILIARES ====================

    /**
     * [PT] Processa a mensagem tratando formatação e múltiplos argumentos.
     * <p>
     * Regras:
     * <ul>
     *   <li>Se não houver argumentos, retorna o format como está</li>
     *   <li>Se format contiver % (placeholder de formatação), usa String.format</li>
     *   <li>Se não houver placeholders, concatena todos os argumentos</li>
     * </ul>
     * </p>
     *
     * [EN] Processes the message handling formatting and multiple arguments.
     * <p>
     * Rules:
     * <ul>
     *   <li>If no arguments, returns format as is</li>
     *   <li>If format contains % (format placeholder), uses String.format</li>
     *   <li>If no placeholders, concatenates all arguments</li>
     * </ul>
     * </p>
     *
     * @param format [PT] string de formato ou mensagem base
     *               [EN] format string or base message
     * @param args   [PT] argumentos para processamento
     *               [EN] arguments for processing
     * @return [PT] mensagem processada
     *         [EN] processed message
     */
    private static String processMessage(String format, Object... args) {
        // Caso 1: Sem argumentos
        if (args == null || args.length == 0) {
            return format;
        }
        
        // Caso 2: Verifica se é uma string de formato (contém %)
        boolean hasFormatPlaceholder = format.contains("%");
        
        if (hasFormatPlaceholder) {
            try {
                // Tenta formatar com os argumentos
                return String.format(format, args);
            } catch (Exception e) {
                // Se falhar, faz concatenação simples
                StringBuilder sb = new StringBuilder(format);
                for (Object arg : args) {
                    sb.append(" ").append(arg);
                }
                return sb.toString();
            }
        }
        
        // Caso 3: Sem placeholders, concatena todos os argumentos
        StringBuilder result = new StringBuilder(format);
        for (Object arg : args) {
            if (result.length() > 0 && !format.endsWith(" ")) {
                result.append(" ");
            }
            result.append(arg);
        }
        
        return result.toString();
    }

    /**
     * [PT] Formata a mensagem de log com timestamp.
     * [EN] Formats log message with timestamp.
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     * @return [PT] string pronta para ser colorida pelo AnsiColor
     *         [EN] string ready to be colored by AnsiColor
     */
    private static String formatLogMessage(Object obj) {
        String timestamp = DataTime.getData().replace(" ", "");
        String message = String.valueOf(obj);
        return String.format(LOG_PATTERN, timestamp, message);
    }

    /**
     * [PT] Registra uma mensagem com uma cor ANSI específica.
     *
     * [EN] Logs a message with a specific ANSI color.
     *
     * @param obj   [PT] objeto a ser logado
     *              [EN] object to log
     * @param color [PT] código de cor ANSI (ex: "&c", "&e")
     *              [EN] ANSI color code (e.g., "&c", "&e")
     */
    private static void logColored(Object obj, String color) {
        String timestamp = DataTime.getData().replace(" ", "");
        String message = String.valueOf(obj);
        String coloredPattern = String.format("%s[%s] &7%s", color, timestamp, message);
        output().println(AnsiColor.parse(coloredPattern));
    }
}