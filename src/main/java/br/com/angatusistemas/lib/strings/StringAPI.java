package br.com.angatusistemas.lib.strings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [PT] Classe utilitária para operações comuns com strings.
 * [EN] Utility class providing common string operations.
 * 
 * <p>Todos os métodos são estáticos e otimizados para serem seguros contra nulos quando apropriado.
 * [EN] All methods are static and optimized to be null-safe where appropriate.</p>
 */
public final class StringAPI {

    // Conjunto pré-calculado de caracteres permitidos para geração de código aleatório
    // Precomputed set of allowed characters for random code generation
    private static final char[] ALLOWED_CHARS;

    static {
        List<Character> chars = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) chars.add(c);
        for (char c = 'A'; c <= 'Z'; c++) chars.add(c);
        for (char c = '0'; c <= '9'; c++) chars.add(c);
        ALLOWED_CHARS = new char[chars.size()];
        for (int i = 0; i < chars.size(); i++) {
            ALLOWED_CHARS[i] = chars.get(i);
        }
    }

    private StringAPI() {
        // Impede instanciação / Prevents instantiation
    }

    /**
     * [PT] Remove o último caractere da string fornecida.
     * [EN] Removes the last character of the given string.
     *
     * @param input [PT] string de entrada (não pode ser nula ou vazia)
     *              [EN] the input string (must not be null or empty)
     * @return [PT] a string sem o último caractere
     *         [EN] the string without its last character
     * @throws IllegalArgumentException [PT] se a entrada for nula ou vazia
     *                                   [EN] if input is null or empty
     */
    public static String removeLastChar(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Input string cannot be null or empty");
        }
        return input.substring(0, input.length() - 1);
    }

    /**
     * [PT] Capitaliza a primeira letra da string e converte o restante para minúsculas.
     * [EN] Capitalizes the first letter of the input string and converts the rest to lowercase.
     *
     * @param input [PT] string de entrada (pode ser nula ou vazia)
     *              [EN] the input string (may be null or empty)
     * @return [PT] a string capitalizada, ou a original se nula/vazia
     *         [EN] the capitalized string, or the original if null/empty
     */
    public static String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        char firstChar = Character.toUpperCase(input.charAt(0));
        if (input.length() == 1) {
            return String.valueOf(firstChar);
        }
        String remaining = input.substring(1).toLowerCase();
        return firstChar + remaining;
    }

    /**
     * [PT] Gera um código alfanumérico aleatório com o comprimento especificado.
     * [EN] Generates a random alphanumeric code of the specified length.
     *
     * @param length [PT] comprimento desejado (deve ser > 0)
     *               [EN] the desired length (must be > 0)
     * @return [PT] string aleatória contendo letras (A-Z, a-z) e dígitos (0-9)
     *         [EN] a random string containing letters (A-Z, a-z) and digits (0-9)
     */
    public static String randomCode(int length) {
        if (length <= 0) {
            return "";
        }
        StringBuilder codeBuilder = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            int randomIndex = random.nextInt(ALLOWED_CHARS.length);
            codeBuilder.append(ALLOWED_CHARS[randomIndex]);
        }
        return codeBuilder.toString();
    }

    /**
     * [PT] Verifica se a string é nula ou vazia.
     * [EN] Checks if a string is null or empty.
     *
     * @param input [PT] string de entrada
     *              [EN] the input string
     * @return [PT] true se nula ou vazia, false caso contrário
     *         [EN] true if null or empty, false otherwise
     */
    public static boolean isNullOrEmpty(String input) {
        return input == null || input.isEmpty();
    }

    /**
     * [PT] Verifica se a string é nula, vazia ou contém apenas espaços em branco.
     * [EN] Checks if a string is null, empty, or contains only whitespace.
     *
     * @param input [PT] string de entrada
     *              [EN] the input string
     * @return [PT] true se nula, vazia ou apenas espaços, false caso contrário
     *         [EN] true if null, empty, or whitespace-only, false otherwise
     */
    public static boolean isNullOrBlank(String input) {
        return input == null || input.trim().isEmpty();
    }

    /**
     * [PT] Repete uma string um determinado número de vezes.
     * [EN] Repeats a string a given number of times.
     *
     * @param input [PT] string a ser repetida (se nula, tratada como vazia)
     *              [EN] the string to repeat (if null, treated as empty)
     * @param times [PT] número de repetições (se <= 0, retorna vazio)
     *              [EN] the number of repetitions (if <= 0, returns empty string)
     * @return [PT] a string repetida
     *         [EN] the repeated string
     */
    public static String repeat(String input, int times) {
        if (input == null || times <= 0) {
            return "";
        }
        StringBuilder result = new StringBuilder(input.length() * times);
        for (int i = 0; i < times; i++) {
            result.append(input);
        }
        return result.toString();
    }

    /**
     * [PT] Trunca a string para o comprimento máximo especificado.
     *     Se a string for mais curta, retorna-a inalterada.
     * [EN] Truncates a string to the specified maximum length.
     *     If the string is shorter, it is returned unchanged.
     *
     * @param input     [PT] string de entrada (se nula, tratada como vazia)
     *                  [EN] the input string (if null, treated as empty)
     * @param maxLength [PT] comprimento máximo permitido
     *                  [EN] the maximum allowed length
     * @return [PT] a string truncada, ou a original se mais curta
     *         [EN] the truncated string, or the original if shorter
     */
    public static String truncate(String input, int maxLength) {
        if (input == null || maxLength < 0) {
            return "";
        }
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength);
    }

    /**
     * [PT] Inverte os caracteres de uma string.
     * [EN] Reverses the characters of a string.
     *
     * @param input [PT] string de entrada (se nula, retorna vazio)
     *              [EN] the input string (if null, returns empty)
     * @return [PT] a string invertida, ou vazia se nula
     *         [EN] the reversed string, or empty if null
     */
    public static String reverse(String input) {
        if (input == null) {
            return "";
        }
        return new StringBuilder(input).reverse().toString();
    }

    /**
     * [PT] Converte uma string para camelCase.
     *     Exemplo: "hello world" -> "helloWorld"
     * [EN] Converts a string to camelCase.
     *     Example: "hello world" -> "helloWorld"
     *
     * @param input [PT] string de entrada (pode ser nula ou vazia)
     *              [EN] the input string (may be null or empty)
     * @return [PT] a versão em camelCase, ou a original se nula/vazia
     *         [EN] the camelCase version, or the original if null/empty
     */
    public static String toCamelCase(String input) {
        if (isNullOrBlank(input)) {
            return input;
        }
        String[] words = input.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i].toLowerCase();
            if (i == 0) {
                result.append(word);
            } else {
                if (word.length() > 0) {
                    result.append(Character.toUpperCase(word.charAt(0)))
                          .append(word.substring(1));
                }
            }
        }
        return result.toString();
    }

    /**
     * [PT] Converte uma string para snake_case.
     *     Exemplo: "hello world" -> "hello_world"
     * [EN] Converts a string to snake_case.
     *     Example: "hello world" -> "hello_world"
     *
     * @param input [PT] string de entrada (pode ser nula ou vazia)
     *              [EN] the input string (may be null or empty)
     * @return [PT] a versão em snake_case, ou a original se nula/vazia
     *         [EN] the snake_case version, or the original if null/empty
     */
    public static String toSnakeCase(String input) {
        if (isNullOrBlank(input)) {
            return input;
        }
        return input.trim().toLowerCase().replaceAll("\\s+", "_");
    }

    /**
     * [PT] Verifica se a string contém apenas dígitos (0-9).
     * [EN] Checks if the string contains only digits (0-9).
     *
     * @param input [PT] string de entrada (null retorna false)
     *              [EN] the input string (null returns false)
     * @return [PT] true se não nula e todos os caracteres forem dígitos
     *         [EN] true if non-null and all characters are digits
     */
    public static boolean containsOnlyDigits(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (!Character.isDigit(input.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * [PT] Verifica se a string contém apenas letras (A-Z, a-z).
     * [EN] Checks if the string contains only letters (A-Z, a-z).
     *
     * @param input [PT] string de entrada (null retorna false)
     *              [EN] the input string (null returns false)
     * @return [PT] true se não nula e todos os caracteres forem letras
     *         [EN] true if non-null and all characters are letters
     */
    public static boolean containsOnlyLetters(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (!Character.isLetter(input.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * [PT] Extrai todos os dígitos numéricos de uma string.
     *     Exemplo: "a1b2c3" -> "123"
     * [EN] Extracts all numeric digits from a string.
     *     Example: "a1b2c3" -> "123"
     *
     * @param input [PT] string de entrada (se nula, retorna vazio)
     *              [EN] the input string (if null, returns empty string)
     * @return [PT] string contendo apenas os dígitos
     *         [EN] a string containing only digits
     */
    public static String extractNumbers(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /**
     * [PT] Mascara uma parte da string com um caractere substituto.
     *     Útil para ocultar dados sensíveis, como números de cartão de crédito.
     * [EN] Masks a portion of the string with a placeholder character.
     *     Useful for hiding sensitive data like credit card numbers.
     *
     * @param input      [PT] string original (pode ser nula)
     *                   [EN] the original string (may be null)
     * @param startIndex [PT] índice inicial (inclusivo) para mascarar
     *                   [EN] the starting index (inclusive) to mask
     * @param endIndex   [PT] índice final (exclusivo) para mascarar
     *                   [EN] the ending index (exclusive) to mask
     * @param maskChar   [PT] caractere a ser usado como máscara
     *                   [EN] the character to use for masking
     * @return [PT] a string mascarada, ou vazia se entrada for nula
     *         [EN] the masked string, or empty if input is null
     */
    public static String maskString(String input, int startIndex, int endIndex, char maskChar) {
        if (input == null || startIndex < 0 || endIndex > input.length() || startIndex >= endIndex) {
            return input == null ? "" : input;
        }
        StringBuilder masked = new StringBuilder(input);
        for (int i = startIndex; i < endIndex; i++) {
            masked.setCharAt(i, maskChar);
        }
        return masked.toString();
    }

    /**
     * [PT] Conta quantas vezes uma substring aparece em uma string (não sobrepostas).
     * [EN] Counts how many times a substring appears in a string (non-overlapping).
     *
     * @param source [PT] string fonte (pode ser nula)
     *               [EN] the source string (may be null)
     * @param target [PT] substring a ser contada (não pode ser nula ou vazia)
     *               [EN] the substring to count (must not be null or empty)
     * @return [PT] número de ocorrências, ou 0 se source for nula ou target inválida
     *         [EN] the number of occurrences, or 0 if source is null or target invalid
     */
    public static int countOccurrences(String source, String target) {
        if (source == null || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }

    /**
     * [PT] Compara duas strings ignorando maiúsculas/minúsculas, tratando nulos de forma segura.
     * [EN] Compares two strings ignoring case, handling nulls safely.
     *
     * @param str1 [PT] primeira string (pode ser nula)
     *             [EN] first string (may be null)
     * @param str2 [PT] segunda string (pode ser nula)
     *             [EN] second string (may be null)
     * @return [PT] true se ambas forem nulas, ou ambas não nulas e iguais ignorando caso
     *         [EN] true if both are null, or both non-null and equal ignoring case
     */
    public static boolean equalsIgnoreCaseNullSafe(String str1, String str2) {
        if (str1 == null) {
            return str2 == null;
        }
        return str1.equalsIgnoreCase(str2);
    }
}