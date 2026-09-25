package br.com.angatusistemas.lib.console;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;

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
 * <p><strong>Cores e o caractere {@code &}:</strong> a mensagem pode trazer códigos de cor
 * ({@code &c}, {@code &7} — ver {@link AnsiColor}). Nas variantes formatadas
 * ({@code log("… %s", valor)}), os argumentos de texto são protegidos: um {@code &} dentro do
 * valor aparece como está. Na variante de um argumento só ({@code log(objeto)}), a mensagem
 * inteira é interpretada — para um {@code &} literal ali, escreva {@code &&}.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para dados sensíveis em produção
 * (credenciais, tokens); para registrar cada requisição numa rota — o servidor já escreve uma
 * linha por requisição (log de requisições do {@code JavalinAPI}); para detalhe volumoso de
 * depuração, use o nível {@code debug}, silencioso por padrão. Não substitua a política de log
 * da aplicação — este é um logger de console simples, sem arquivos nem rotação.</p>
 *
 * <p><strong>Integração:</strong> o {@link AngatuLib} redireciona
 * {@code System.out} para um {@link InterceptorOutputStream}, que roteia toda
 * saída padrão para o {@code Console}; o stream original fica preservado em
 * {@code AngatuLib#getOriginalOut()}.</p>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * Console.log("Servidor iniciado");
 * Console.info("Usuário logado: %s", username);
 * Console.warn("Disco quase cheio: %d%% usado", percent);
 * Console.error("Falha na conexão", exception);   // imprime stack trace
 * Console.debug("Valor recebido: %s", valor);     // só com debug ativo
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> exceções SEMPRE como último argumento do
 * {@code error} (habilita a stack trace); use {@code %s} em vez de concatenação — assim o
 * valor é protegido;
 * ative debug apenas em desenvolvimento.</p>
 *
 * <p><strong>Limitações:</strong> loga apenas no console (sem persistência);
 * cores ANSI requerem terminal compatível; o redirecionamento do
 * {@code System.out} é global ao processo.</p>
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

    /** Debug ligado por {@code -Dangatu.debug=true} ou {@link #setDebugEnabled(boolean)}. */
    private static volatile boolean debugEnabled = Boolean.parseBoolean(System.getProperty("angatu.debug", "false"));

    private Console() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
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
     * Registra uma mensagem genérica no console (nível padrão).
     *
     * @param obj Objeto a ser logado; códigos de cor na mensagem são interpretados
     */
    public static void log(Object obj) {
        String formatted = formatLogMessage(obj);
        output().println(AnsiColor.parse(formatted));
    }

    /**
     * Registra uma mensagem formatada (como {@code printf}) no nível genérico.
     *
     * <p>Sem {@code %} no formato, os argumentos são concatenados com espaço. Os argumentos de
     * texto são protegidos: um {@code &} dentro deles aparece como está.</p>
     *
     * @param format String de formato ou mensagem base
     * @param args   Argumentos para formatação ou concatenação
     */
    public static void log(String format, Object... args) {
        log((Object) processMessage(format, args));
    }

    /**
     * Escreve a mensagem como está, numa única escrita: sem o horário que os outros métodos põem
     * na frente, com os códigos de cor interpretados.
     *
     * <p>Para quem monta a própria linha e escreve várias de uma vez — o log de requisições do
     * {@code JavalinAPI}, que traz o horário de cada requisição. Dado vindo de fora precisa de
     * {@link AnsiColor#escape(String)} antes de entrar na mensagem.</p>
     *
     * @param message Uma ou mais linhas, separadas por quebra de linha
     */
    public static void logRaw(String message) {
        output().println(AnsiColor.parse(message));
    }

    /**
     * Registra uma mensagem de informação (nível INFO) com cor azul.
     *
     * @param obj Objeto a ser logado; códigos de cor na mensagem são interpretados
     */
    public static void info(Object obj) {
        logColored(obj, "&9");
    }

    /**
     * Registra uma mensagem de informação formatada.
     *
     * @param format String de formato ou mensagem base
     * @param args   Argumentos para formatação ou concatenação
     */
    public static void info(String format, Object... args) {
        info((Object) processMessage(format, args));
    }

    /**
     * Registra um aviso (nível WARN) com cor amarela.
     *
     * @param obj Objeto a ser logado; códigos de cor na mensagem são interpretados
     */
    public static void warn(Object obj) {
        logColored(obj, "&e");
    }

    /**
     * Registra um aviso formatado.
     *
     * @param format String de formato ou mensagem base
     * @param args   Argumentos para formatação ou concatenação
     */
    public static void warn(String format, Object... args) {
        warn((Object) processMessage(format, args));
    }

    /**
     * Registra um erro (nível ERROR) com cor vermelha e, se houver, a stack trace.
     *
     * <p>Mensagem e stack trace saem numa escrita só: escritos em duas, erros simultâneos de
     * threads diferentes se misturavam linha a linha no log.</p>
     *
     * @param obj Mensagem (códigos de cor interpretados)
     * @param t   Exceção opcional (pode ser nula)
     */
    public static void error(Object obj, Throwable t) {
        String timestamp = DataTime.getData().replace(" ", "");
        String header = AnsiColor.parse(String.format("&c[%s] &7%s", timestamp, String.valueOf(obj)));
        if (t == null) {
            output().println(header);
            return;
        }
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        output().println(header + System.lineSeparator() + trace.toString().stripTrailing());
    }

    /**
     * Registra um erro sem exceção.
     *
     * @param obj Mensagem (códigos de cor interpretados)
     */
    public static void error(Object obj) {
        error(obj, null);
    }

    /**
     * Registra um erro formatado com múltiplos argumentos.
     *
     * <p>Se o último argumento for um {@link Throwable}, ele é tratado como a exceção (imprime a
     * stack trace) e não entra na formatação.</p>
     *
     * @param format String de formato ou mensagem base
     * @param args   Argumentos para formatação ou concatenação
     */
    public static void error(String format, Object... args) {
        Throwable throwable = null;
        Object[] actualArgs = args;

        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable last) {
            throwable = last;
            actualArgs = new Object[args.length - 1];
            System.arraycopy(args, 0, actualArgs, 0, args.length - 1);
        }

        error(processMessage(format, actualArgs), throwable);
    }

    /**
     * Registra uma mensagem de depuração (nível DEBUG) com cor cinza — só com o debug ativo.
     *
     * @param obj Objeto a ser logado
     */
    public static void debug(Object obj) {
        if (isDebugEnabled()) {
            logColored(obj, "&8");
        }
    }

    /**
     * Registra uma mensagem de depuração formatada — só com o debug ativo.
     *
     * @param format String de formato ou mensagem base
     * @param args   Argumentos para formatação ou concatenação
     */
    public static void debug(String format, Object... args) {
        if (isDebugEnabled()) {
            debug((Object) processMessage(format, args));
        }
    }

    // ==================== MÉTODOS DE CONTROLE DE DEBUG ====================

    /**
     * Verifica se o modo debug está ativo.
     *
     * @return {@code true} se mensagens de debug devem ser exibidas
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Ativa ou desativa o modo debug em tempo de execução.
     *
     * @param enabled {@code true} para exibir mensagens de debug
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    // ==================== MÉTODOS PRIVADOS AUXILIARES ====================

    /**
     * Monta a mensagem: {@code String.format} quando o formato tem {@code %}; concatenação com
     * espaço quando não tem; o formato como está quando não há argumentos.
     *
     * <p>Os argumentos de texto são protegidos antes (ver {@link #protect(Object[])}); os códigos
     * de cor valem só no formato, que é o texto escrito pelo programador.</p>
     */
    private static String processMessage(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        Object[] safe = protect(args);

        if (format.contains("%")) {
            try {
                return String.format(format, safe);
            } catch (Exception e) {
                // Formato e argumentos não combinam: concatena para não perder a mensagem
                StringBuilder sb = new StringBuilder(format);
                for (Object arg : safe) {
                    sb.append(" ").append(arg);
                }
                return sb.toString();
            }
        }

        StringBuilder result = new StringBuilder(format);
        for (Object arg : safe) {
            if (result.length() > 0 && !format.endsWith(" ")) {
                result.append(" ");
            }
            result.append(arg);
        }
        return result.toString();
    }

    /**
     * Protege os argumentos de texto: cada {@code &} vira {@code &&}, que o {@link AnsiColor}
     * mostra como um {@code &}. Números, booleanos e datas passam como estão, para {@code %d},
     * {@code %f} e {@code %t} continuarem funcionando.
     */
    private static Object[] protect(Object[] args) {
        Object[] safe = args.clone();
        for (int i = 0; i < safe.length; i++) {
            Object arg = safe[i];
            if (arg == null || arg instanceof Number || arg instanceof Boolean || arg instanceof Character
                    || arg instanceof TemporalAccessor || arg instanceof Date || arg instanceof Calendar) {
                continue;
            }
            safe[i] = AnsiColor.escape(String.valueOf(arg));
        }
        return safe;
    }

    /**
     * Formata a mensagem de log com timestamp.
     *
     * @param obj Objeto a ser logado
     * @return Texto pronto para ser colorido pelo {@link AnsiColor}
     */
    private static String formatLogMessage(Object obj) {
        String timestamp = DataTime.getData().replace(" ", "");
        String message = String.valueOf(obj);
        return String.format(LOG_PATTERN, timestamp, message);
    }

    /**
     * Registra uma mensagem com uma cor ANSI específica.
     *
     * @param obj   Objeto a ser logado
     * @param color Código de cor (ex.: {@code "&c"}, {@code "&e"})
     */
    private static void logColored(Object obj, String color) {
        String timestamp = DataTime.getData().replace(" ", "");
        String message = String.valueOf(obj);
        String coloredPattern = String.format("%s[%s] &7%s", color, timestamp, message);
        output().println(AnsiColor.parse(coloredPattern));
    }
}
