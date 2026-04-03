package br.com.angatusistemas.lib.console;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [PT] Classe utilitária para aplicar cores e estilos ANSI em textos no console.
 * <p>
 * Utiliza códigos de escape ANSI para colorir e estilizar saídas de texto em terminais compatíveis.
 * Os códigos de formatação são inseridos através do símbolo {@code &} seguido de um caractere
 * (ex.: {@code &c} para vermelho, {@code &l} para negrito, {@code &r} para reset).
 * </p>
 * <p>
 * Exemplo de uso:
 * <pre>
 * String colored = AnsiColor.parse("&cTexto vermelho&r e &aTexto verde");
 * System.out.println(colored);
 * </pre>
 * </p>
 * 
 * [EN] Utility class to apply ANSI colors and styles to console text.
 * <p>
 * Uses ANSI escape codes to color and style text output in compatible terminals.
 * Formatting codes are inserted using the {@code &} symbol followed by a character
 * (e.g., {@code &c} for red, {@code &l} for bold, {@code &r} for reset).
 * </p>
 * <p>
 * Usage example:
 * <pre>
 * String colored = AnsiColor.parse("&cRed text&r and &aGreen text");
 * System.out.println(colored);
 * </pre>
 * </p>
 * 
 * @author [Sua equipe]
 * @see <a href="https://en.wikipedia.org/wiki/ANSI_escape_code">ANSI escape codes</a>
 */
public final class AnsiColor {

    /**
     * [PT] Código ANSI para resetar todas as formatações (cores, negrito, etc.).
     * [EN] ANSI code to reset all formatting (colors, bold, etc.).
     */
    public static final String RESET = "\u001B[0m";

    // Mapeamento de caracteres para códigos ANSI (inicializado no bloco estático)
    // Mapping of characters to ANSI codes (initialized in static block)
    private static final Map<Character, String> COLOR_MAP = new HashMap<>();

    // Padrão regex para encontrar códigos no formato &X (X = caractere permitido)
    // Regex pattern to find codes in the format &X (X = allowed character)
    private static final Pattern CODE_PATTERN = Pattern.compile("&([0-9a-frlomn])", Pattern.CASE_INSENSITIVE);

    static {
        // Cores principais (índices da paleta 256 cores)
        // Main colors (256-color palette indices)
        COLOR_MAP.put('0', "\u001B[30m");      // Preto / Black
        COLOR_MAP.put('1', "\u001B[38;5;19m"); // Azul profundo / Deep blue
        COLOR_MAP.put('2', "\u001B[38;5;22m"); // Verde musgo / Moss green
        COLOR_MAP.put('3', "\u001B[38;5;30m"); // Ciano escuro / Dark cyan
        COLOR_MAP.put('4', "\u001B[38;5;88m"); // Vermelho vinho / Wine red
        COLOR_MAP.put('5', "\u001B[38;5;127m");// Roxo vibrante / Vibrant purple
        COLOR_MAP.put('6', "\u001B[38;5;172m");// Laranja dourado / Golden orange
        COLOR_MAP.put('7', "\u001B[38;5;250m");// Cinza claro / Light gray
        COLOR_MAP.put('8', "\u001B[38;5;240m");// Cinza carvão / Charcoal gray
        COLOR_MAP.put('9', "\u001B[38;5;39m"); // Azul celeste / Sky blue
        COLOR_MAP.put('a', "\u001B[38;5;46m"); // Verde neon / Neon green
        COLOR_MAP.put('b', "\u001B[38;5;51m"); // Ciano neon / Neon cyan
        COLOR_MAP.put('c', "\u001B[38;5;203m");// Vermelho neon / Neon red
        COLOR_MAP.put('d', "\u001B[38;5;207m");// Rosa choque / Hot pink
        COLOR_MAP.put('e', "\u001B[38;5;226m");// Amarelo neon / Neon yellow
        COLOR_MAP.put('f', "\u001B[38;5;231m");// Branco real / Pure white

        // Estilos / Styles
        COLOR_MAP.put('r', RESET);           // Reset
        COLOR_MAP.put('l', "\u001B[1m");     // Negrito / Bold
        COLOR_MAP.put('n', "\u001B[4m");     // Sublinhado / Underline
        COLOR_MAP.put('o', "\u001B[3m");     // Itálico / Italic
        COLOR_MAP.put('m', "\u001B[9m");     // Tachado / Strikethrough
    }

    /**
     * [PT] Construtor privado para evitar instanciação da classe utilitária.
     * [EN] Private constructor to prevent instantiation of this utility class.
     */
    private AnsiColor() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * [PT] Interpreta os códigos de formatação presentes na mensagem e os substitui pelos respectivos
     * códigos ANSI, adicionando o código de reset ({@value #RESET}) ao final da string.
     * <p>
     * Códigos suportados:
     * <ul>
     *   <li>Cores: &0 (preto), &1 (azul escuro), &2 (verde musgo), &3 (ciano escuro), &4 (vinho),
     *       &5 (roxo), &6 (laranja), &7 (cinza claro), &8 (cinza carvão), &9 (azul celeste),
     *       &a (verde neon), &b (ciano neon), &c (vermelho neon), &d (rosa), &e (amarelo), &f (branco)</li>
     *   <li>Estilos: &l (negrito), &n (sublinhado), &o (itálico), &m (tachado), &r (reset)</li>
     * </ul>
     * </p>
     * <p>
     * Exemplo: {@code AnsiColor.parse("&cErro&r: &2sucesso")} retorna uma string com os códigos ANSI.
     * </p>
     *
     * [EN] Interprets the formatting codes in the message and replaces them with the respective
     * ANSI codes, adding the reset code ({@value #RESET}) at the end of the string.
     * <p>
     * Supported codes:
     * <ul>
     *   <li>Colors: &0 (black), &1 (dark blue), &2 (moss green), &3 (dark cyan), &4 (wine red),
     *       &5 (purple), &6 (orange), &7 (light gray), &8 (charcoal gray), &9 (sky blue),
     *       &a (neon green), &b (neon cyan), &c (neon red), &d (hot pink), &e (neon yellow), &f (pure white)</li>
     *   <li>Styles: &l (bold), &n (underline), &o (italic), &m (strikethrough), &r (reset)</li>
     * </ul>
     * </p>
     * <p>
     * Example: {@code AnsiColor.parse("&cError&r: &2success")} returns a string with ANSI codes.
     * </p>
     *
     * @param message [PT] mensagem contendo códigos de formatação (ex.: "&cTexto vermelho")
     *                [EN] message containing formatting codes (e.g., "&cRed text")
     * @return [PT] string com os códigos ANSI substituídos e reset ao final;
     *              se a mensagem for nula ou vazia, retorna a própria mensagem (ou string vazia)
     *         [EN] string with ANSI codes replaced and reset at the end;
     *              if the message is null or empty, returns the message itself (or empty string)
     */
    public static String parse(String message) {
        if (message == null || message.isEmpty()) {
            return message == null ? "" : message;
        }

        Matcher matcher = CODE_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            char code = matcher.group(1).toLowerCase().charAt(0);
            String replacement = COLOR_MAP.getOrDefault(code, "");
            matcher.appendReplacement(buffer, replacement);
        }
        matcher.appendTail(buffer);

        return buffer.toString() + RESET;
    }
}