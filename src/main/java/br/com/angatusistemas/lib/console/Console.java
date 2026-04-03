package br.com.angatusistemas.lib.console;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.time.DataTime;

/**
 * [PT] Classe utilitária para logging no console com suporte a cores ANSI e formatação de data/hora.
 * <p>
 * Encapsula a saída original do sistema (gerenciada pelo {@link AngatuLib}) e aplica estilos
 * visuais via {@link AnsiColor}. A data/hora é obtida do método {@link DataTime#getData()}.
 * </p>
 * <p>
 * Exemplos de uso:
 * <pre>
 * Console.log("Servidor iniciado");
 * Console.info("Usuário logado: %s", username);
 * Console.warn("Disco quase cheio: %d%% usado", percent);
 * Console.error("Falha na conexão", exception);
 * Console.debug("Valor recebido: " + valor);
 * </pre>
 * </p>
 *
 * [EN] Utility class for console logging with ANSI color support and date/time formatting.
 * <p>
 * Wraps the system's original output (managed by {@link AngatuLib}) and applies visual styles
 * via {@link AnsiColor}. Date/time is obtained from {@link DataTime#getData()}.
 * </p>
 * <p>
 * Usage examples:
 * <pre>
 * Console.log("Server started");
 * Console.info("User logged in: %s", username);
 * Console.warn("Disk almost full: %d%% used", percent);
 * Console.error("Connection failed", exception);
 * Console.debug("Received value: " + value);
 * </pre>
 * </p>
 *
 * @author [Sua equipe]
 * @see AnsiColor
 * @see DataTime
 */
public final class Console {

    // Formato base do log: [data/hora] mensagem
    // Base log format: [date/time] message
    private static final String LOG_PATTERN = "&6[%s] &7%s";

    // Instância do AngatuLib para acesso ao output original
    // AngatuLib instance to access original output
    private static final AngatuLib ANGATU_LIB = AngatuLib.getInstance();

    private Console() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODOS PRINCIPAIS ====================
    // ==================== CORE METHODS ====================

    /**
     * [PT] Registra uma mensagem genérica no console (nível padrão).
     * <p>
     * A mensagem é convertida para minúsculas e exibida com timestamp e cor neutra.
     * </p>
     *
     * [EN] Logs a generic message to the console (default level).
     * <p>
     * The message is converted to lowercase and displayed with timestamp and neutral color.
     * </p>
     *
     * @param obj [PT] objeto a ser logado (qualquer tipo, será convertido via {@link String#valueOf})
     *            [EN] object to log (any type, will be converted via {@link String#valueOf})
     */
    public static void log(Object obj) {
        String formatted = formatLogMessage(obj);
        ANGATU_LIB.getOriginalOut().println(AnsiColor.parse(formatted));
    }

    /**
     * [PT] Registra uma mensagem formatada (como {@code printf}) no nível genérico.
     *
     * [EN] Logs a formatted message (like {@code printf}) at generic level.
     *
     * @param format [PT] string de formato (ex: "Usuário: %s")
     *               [EN] format string (e.g., "User: %s")
     * @param args   [PT] argumentos para formatação
     *               [EN] arguments for formatting
     */
    public static void log(String format, Object... args) {
        log(String.format(format, args));
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
     * @param format [PT] string de formato
     *               [EN] format string
     * @param args   [PT] argumentos
     *               [EN] arguments
     */
    public static void info(String format, Object... args) {
        info(String.format(format, args));
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
     * @param format [PT] string de formato
     *               [EN] format string
     * @param args   [PT] argumentos
     *               [EN] arguments
     */
    public static void warn(String format, Object... args) {
        warn(String.format(format, args));
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
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     * @param t   [PT] exceção opcional (pode ser nula)
     *            [EN] optional exception (may be null)
     */
    public static void error(Object obj, Throwable t) {
        logColored(obj, "&c");
        if (t != null) {
            t.printStackTrace(ANGATU_LIB.getOriginalOut());
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
     * [PT] Registra um erro formatado.
     *
     * [EN] Logs a formatted error.
     *
     * @param format [PT] string de formato
     *               [EN] format string
     * @param args   [PT] argumentos
     *               [EN] arguments
     */
    public static void error(String format, Object... args) {
        error(String.format(format, args));
    }

    /**
     * [PT] Registra uma mensagem de depuração (nível DEBUG) com cor cinza.
     * <p>
     * Por padrão, essas mensagens não são exibidas a menos que a flag de debug esteja ativa.
     * Para ativar, defina a propriedade {@code angatu.debug=true} ou configure via {@link #setDebugEnabled(boolean)}.
     * </p>
     *
     * [EN] Logs a debug message (DEBUG level) with gray color.
     * <p>
     * By default, these messages are not shown unless the debug flag is enabled.
     * To enable, set the system property {@code angatu.debug=true} or use {@link #setDebugEnabled(boolean)}.
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
     * @param format [PT] string de formato
     *               [EN] format string
     * @param args   [PT] argumentos
     *               [EN] arguments
     */
    public static void debug(String format, Object... args) {
        debug(String.format(format, args));
    }

    // ==================== MÉTODOS DE CONTROLE DE DEBUG ====================
    // ==================== DEBUG CONTROL METHODS ====================

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
    // ==================== PRIVATE HELPER METHODS ====================

    /**
     * [PT] Formata a mensagem de log com timestamp e remove espaços da data.
     * [EN] Formats log message with timestamp and removes spaces from date.
     *
     * @param obj [PT] objeto a ser logado
     *            [EN] object to log
     * @return [PT] string pronta para ser colorida pelo AnsiColor
     *         [EN] string ready to be colored by AnsiColor
     */
    private static String formatLogMessage(Object obj) {
        String timestamp = DataTime.getData().replace(" ", "");
        String message = String.valueOf(obj);
        return LOG_PATTERN.formatted(timestamp, message);
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
        String coloredPattern = color + "[%s] &7%s".formatted(timestamp, message);
        ANGATU_LIB.getOriginalOut().println(AnsiColor.parse(coloredPattern));
    }
}