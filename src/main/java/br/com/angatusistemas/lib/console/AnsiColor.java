package br.com.angatusistemas.lib.console;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classe utilitária para aplicar cores e estilos ANSI em textos no console.
 *
 * <p>Os códigos de formatação são escritos com {@code &} seguido de um caractere
 * ({@code &c} vermelho, {@code &l} negrito, {@code &r} reset). Para um {@code &} literal,
 * escreva {@code &&} — ou passe o texto por {@link #escape(String)}.</p>
 *
 * <pre>
 * String colored = AnsiColor.parse("&amp;cTexto vermelho&amp;r e &amp;aTexto verde");
 * </pre>
 *
 * <h2>Por que existe o escape</h2>
 * <p>Sem ele, todo {@code &} seguido de letra ou dígito virava código de cor — inclusive dentro
 * do dado logado. Uma URL com {@code ?q=cafe&categoria=bebidas&limite=5} saía no log como
 * {@code ?q=cafeategoria=bebidasimite=5}, e {@code R&D} como {@code R}. O {@code Console}
 * escapa os argumentos das mensagens formatadas e o texto capturado do {@code System.out}.</p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code">ANSI escape codes</a>
 */
public final class AnsiColor {

    /**
     * Código ANSI para resetar todas as formatações (cores, negrito, etc.).
     */
    public static final String RESET = "\u001B[0m";

    /** Caractere de código → sequência ANSI. */
    private static final Map<Character, String> COLOR_MAP = Map.ofEntries(
            // Cores principais (índices da paleta de 256 cores)
            Map.entry('0', "\u001B[30m"),       // preto
            Map.entry('1', "\u001B[38;5;19m"),  // azul profundo
            Map.entry('2', "\u001B[38;5;22m"),  // verde musgo
            Map.entry('3', "\u001B[38;5;30m"),  // ciano escuro
            Map.entry('4', "\u001B[38;5;88m"),  // vermelho vinho
            Map.entry('5', "\u001B[38;5;127m"), // roxo vibrante
            Map.entry('6', "\u001B[38;5;172m"), // laranja dourado
            Map.entry('7', "\u001B[38;5;250m"), // cinza claro
            Map.entry('8', "\u001B[38;5;240m"), // cinza carvão
            Map.entry('9', "\u001B[38;5;39m"),  // azul celeste
            Map.entry('a', "\u001B[38;5;46m"),  // verde neon
            Map.entry('b', "\u001B[38;5;51m"),  // ciano neon
            Map.entry('c', "\u001B[38;5;203m"), // vermelho neon
            Map.entry('d', "\u001B[38;5;207m"), // rosa choque
            Map.entry('e', "\u001B[38;5;226m"), // amarelo neon
            Map.entry('f', "\u001B[38;5;231m"), // branco
            // Estilos
            Map.entry('r', RESET),              // reset
            Map.entry('l', "\u001B[1m"),        // negrito
            Map.entry('n', "\u001B[4m"),        // sublinhado
            Map.entry('o', "\u001B[3m"),        // itálico
            Map.entry('m', "\u001B[9m"));       // tachado

    /** {@code &&} (um {@code &} literal) ou {@code &X}, com X um código permitido. */
    private static final Pattern CODE_PATTERN = Pattern.compile("&(&|[0-9a-frlomn])", Pattern.CASE_INSENSITIVE);

    /**
     * Construtor privado para evitar instanciação da classe utilitária.
     */
    private AnsiColor() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Substitui os códigos de formatação da mensagem pelas sequências ANSI e acrescenta o
     * reset ({@link #RESET}) ao final.
     *
     * <p>Códigos suportados:</p>
     * <ul>
     *   <li>Cores: {@code &0} (preto), {@code &1} (azul escuro), {@code &2} (verde musgo),
     *       {@code &3} (ciano escuro), {@code &4} (vinho), {@code &5} (roxo), {@code &6} (laranja),
     *       {@code &7} (cinza claro), {@code &8} (cinza carvão), {@code &9} (azul celeste),
     *       {@code &a} (verde neon), {@code &b} (ciano neon), {@code &c} (vermelho neon),
     *       {@code &d} (rosa), {@code &e} (amarelo), {@code &f} (branco)</li>
     *   <li>Estilos: {@code &l} (negrito), {@code &n} (sublinhado), {@code &o} (itálico),
     *       {@code &m} (tachado), {@code &r} (reset)</li>
     *   <li>{@code &&}: um {@code &} literal</li>
     * </ul>
     *
     * @param message Mensagem com códigos de formatação (ex.: {@code "&cTexto vermelho"})
     * @return Texto com as sequências ANSI e reset ao final; mensagem nula ou vazia volta como
     *         string vazia ou a própria mensagem
     */
    public static String parse(String message) {
        if (message == null || message.isEmpty()) {
            return message == null ? "" : message;
        }

        Matcher matcher = CODE_PATTERN.matcher(message);
        StringBuilder buffer = new StringBuilder(message.length() + 16);

        while (matcher.find()) {
            String code = matcher.group(1);
            String replacement = code.equals("&")
                    ? "&"
                    : COLOR_MAP.getOrDefault(code.toLowerCase(Locale.ROOT).charAt(0), "");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);

        return buffer.append(RESET).toString();
    }

    /**
     * Protege um texto para ser exibido como está: cada {@code &} vira {@code &&}.
     *
     * <p>Use em dado vindo de fora antes de montar uma mensagem com códigos de cor.</p>
     *
     * @param text Texto a proteger (pode ser {@code null})
     * @return O texto com {@code &} duplicado, ou {@code "null"} para {@code null}
     */
    public static String escape(String text) {
        return text == null ? "null" : text.replace("&", "&&");
    }
}
