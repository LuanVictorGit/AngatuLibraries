package br.com.angatusistemas.lib.strings;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classe utilitária para operações comuns com strings.
 *
 * <p><strong>Propósito:</strong> oferecer operações de string seguras contra
 * nulos e sem dependências externas — capitalização, códigos aleatórios,
 * validação, máscaras, conversão de casos (camelCase/snake_case) e extração.</p>
 *
 * <p><strong>Quando usar:</strong> qualquer manipulação simples de texto no
 * projeto; métodos são estáticos e podem ser usados a qualquer momento, sem
 * inicialização prévia da biblioteca.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para parsing/transformações complexas
 * (regex avançadas, HTML, JSON) — use
 * {@link br.com.angatusistemas.lib.browser.BrowserAPI} (HTML) ou Gson (JSON); para
 * guardar senhas, use o hash de {@link br.com.angatusistemas.lib.criptografy.Password} —
 * {@link #randomCode(int)} gera códigos e tokens, não protege senhas.</p>
 *
 * <p><strong>Integração:</strong> usada por {@code EmailAPI} (código anti-spam
 * no assunto), {@code HtmlRouteAPI} (capitalização de nomes de página) e outros
 * módulos; não depende de nenhuma biblioteca externa.</p>
 *
 * <p><strong>Fluxo de utilização:</strong> chamadas estáticas diretas
 * ({@code StringAPI.capitalize("joão")} → {@code "João"}). Métodos que recebem
 * {@code null} retornam valores seguros (vazio ou {@code null}) — consulte o
 * Javadoc de cada método.</p>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * String nome = StringAPI.capitalize("joão");            // "João"
 * String codigo = StringAPI.randomCode(6);               // "aZ3kP9"
 * boolean numerico = StringAPI.containsOnlyDigits("123"); // true
 * String mascarado = StringAPI.maskString("1234-5678", 0, 4, '*'); // "****-5678"
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> use {@link #isNullOrBlank(String)} para
 * validações de entrada; regex internas são pré-compiladas (sem custo por
 * chamada).</p>
 *
 * <p><strong>Limitações:</strong> {@code toCamelCase}/{@code toSnakeCase} tratam
 * espaços como separadores (não convertem hífens); as conversões de caixa seguem as regras
 * gerais do Unicode ({@link Locale#ROOT}), iguais às do português, qualquer que seja o idioma
 * da JVM.</p>
 *
 * <p><strong>Extensões futuras:</strong> novos utilitários (slugify, diffs,
 * normalização Unicode) podem ser adicionados como métodos estáticos sem
 * quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see br.com.angatusistemas.lib.criptografy.Password
 */
public final class StringAPI {

    /** Caracteres permitidos na geração de código aleatório (a-z, A-Z, 0-9). */
    private static final char[] ALLOWED_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    /** Regex pré-compilada para separar palavras (usada em toCamelCase e toSnakeCase). */
    private static final Pattern WORD_SEPARATOR_PATTERN = Pattern.compile("\\s+");

    /**
     * Maior resultado aceito por {@link #repeat(String, int)}. Metade do teto de um array na JVM,
     * porque uma String com caracteres fora do Latin-1 ocupa dois bytes por caractere — acima
     * disso o {@link String#repeat(int)} lança {@link OutOfMemoryError}.
     */
    private static final long MAX_REPEAT_LENGTH = (Integer.MAX_VALUE - 8) / 2;

    private StringAPI() {
        // Impede instanciação
    }

    /**
     * Gerador criptográfico, criado só no primeiro {@link #randomCode(int)}: quem usa apenas as
     * outras operações não paga a inicialização do {@link SecureRandom}.
     */
    private static final class RandomHolder {
        static final SecureRandom RANDOM = new SecureRandom();
    }

    /**
     * Remove o último caractere da string fornecida.
     *
     * @param input String de entrada (não pode ser nula nem vazia)
     * @return A string sem o último caractere
     * @throws IllegalArgumentException se a entrada for nula ou vazia
     */
    public static String removeLastChar(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("A string de entrada não pode ser nula nem vazia");
        }
        return input.substring(0, input.length() - 1);
    }

    /**
     * Capitaliza a primeira letra da string e converte o restante para minúsculas.
     *
     * @param input String de entrada (pode ser nula ou vazia)
     * @return A string capitalizada, ou a original se nula/vazia
     */
    public static String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        char firstChar = Character.toUpperCase(input.charAt(0));
        if (input.length() == 1) {
            return String.valueOf(firstChar);
        }
        // Locale.ROOT: com a JVM em turco, "ITAPIRA" virava "Itapıra" (i sem ponto).
        String remaining = input.substring(1).toLowerCase(Locale.ROOT);
        return firstChar + remaining;
    }

    /**
     * Gera um código alfanumérico aleatório com o comprimento especificado.
     *
     * <p>Usa {@link SecureRandom} (gerador criptográfico): serve para códigos de verificação
     * enviados por e-mail ou SMS e, com comprimento suficiente, para tokens. Cada caractere
     * carrega cerca de 5,95 bits de entropia (62 símbolos): 6 caracteres dão cerca de 36 bits —
     * bastante para um código de uso único com limite de tentativas; para um token de sessão ou
     * de redefinição de senha, use 32 caracteres ou mais (cerca de 190 bits).</p>
     *
     * @param length Comprimento desejado (se menor ou igual a zero, retorna vazio)
     * @return String aleatória contendo letras (A-Z, a-z) e dígitos (0-9)
     */
    public static String randomCode(int length) {
        if (length <= 0) {
            return "";
        }
        // Antes era ThreadLocalRandom, que não é criptográfico: quem viu alguns códigos podia
        // prever os próximos — e este método é o recomendado para códigos de verificação.
        SecureRandom random = RandomHolder.RANDOM;
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            code[i] = ALLOWED_CHARS[random.nextInt(ALLOWED_CHARS.length)];
        }
        return new String(code);
    }

    /**
     * Verifica se a string é nula ou vazia.
     *
     * @param input String de entrada
     * @return {@code true} se nula ou vazia, {@code false} caso contrário
     */
    public static boolean isNullOrEmpty(String input) {
        return input == null || input.isEmpty();
    }

    /**
     * Verifica se a string é nula, vazia ou contém apenas espaços em branco.
     *
     * @param input String de entrada
     * @return {@code true} se nula, vazia ou só com espaços, {@code false} caso contrário
     */
    public static boolean isNullOrBlank(String input) {
        return input == null || input.isBlank();
    }

    /**
     * Repete uma string um determinado número de vezes.
     *
     * @param input String a ser repetida (se nula, tratada como vazia)
     * @param times Número de repetições (se {@code times <= 0}, retorna vazio)
     * @return A string repetida
     * @throws IllegalArgumentException se o resultado passar de 1.073.741.819 caracteres, o
     *         maior tamanho que uma String comporta com segurança
     */
    public static String repeat(String input, int times) {
        if (input == null || times <= 0) {
            return "";
        }
        // Validado antes: o cálculo antigo (input.length() * times) estourava o int e lançava
        // NegativeArraySizeException; o String.repeat, acima do limite, lança OutOfMemoryError.
        long length = (long) input.length() * times;
        if (length > MAX_REPEAT_LENGTH) {
            throw new IllegalArgumentException("O resultado de repeat teria " + length
                    + " caracteres, acima do máximo de " + MAX_REPEAT_LENGTH + ".");
        }
        return input.repeat(times);
    }

    /**
     * Trunca a string para o comprimento máximo especificado. Se a string for mais curta,
     * retorna-a inalterada.
     *
     * @param input     String de entrada (se nula, retorna vazio)
     * @param maxLength Comprimento máximo permitido (se negativo, retorna vazio)
     * @return A string truncada, ou a original se mais curta
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
     * Inverte os caracteres de uma string.
     *
     * @param input String de entrada (se nula, retorna vazio)
     * @return A string invertida, ou vazia se nula
     */
    public static String reverse(String input) {
        if (input == null) {
            return "";
        }
        return new StringBuilder(input).reverse().toString();
    }

    /**
     * Converte uma string para camelCase. Exemplo: {@code "hello world"} → {@code "helloWorld"}.
     *
     * @param input String de entrada (pode ser nula ou vazia)
     * @return A versão em camelCase, ou a original se nula/vazia/em branco
     */
    public static String toCamelCase(String input) {
        if (isNullOrBlank(input)) {
            return input;
        }
        String[] words = WORD_SEPARATOR_PATTERN.split(input.trim());
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i].toLowerCase(Locale.ROOT);
            if (i == 0) {
                result.append(word);
            } else if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1));
            }
        }
        return result.toString();
    }

    /**
     * Converte uma string para snake_case. Exemplo: {@code "hello world"} → {@code "hello_world"}.
     *
     * @param input String de entrada (pode ser nula ou vazia)
     * @return A versão em snake_case, ou a original se nula/vazia/em branco
     */
    public static String toSnakeCase(String input) {
        if (isNullOrBlank(input)) {
            return input;
        }
        return WORD_SEPARATOR_PATTERN.matcher(input.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
    }

    /**
     * Verifica se a string contém apenas os dígitos ASCII de 0 a 9.
     *
     * <p>Dígitos de outros sistemas de escrita (ex.: {@code "１２３"} de largura total ou os
     * arábico-índicos {@code "١٢٣"}) não contam: um CPF ou CEP com eles passaria na validação e
     * quebraria adiante, no {@code Long.parseLong} ou na busca no banco.</p>
     *
     * @param input String de entrada ({@code null} ou vazia retorna {@code false})
     * @return {@code true} se não vazia e todos os caracteres estiverem entre {@code '0'} e {@code '9'}
     */
    public static boolean containsOnlyDigits(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Verifica se a string contém apenas letras — de qualquer alfabeto, inclusive as acentuadas
     * ({@code "João"} e {@code "Conceição"} passam); espaços, dígitos e pontuação não passam.
     *
     * @param input String de entrada ({@code null} ou vazia retorna {@code false})
     * @return {@code true} se não vazia e todos os caracteres forem letras
     *         ({@link Character#isLetter(char)})
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
     * Extrai os dígitos ASCII (0 a 9) de uma string. Exemplo: {@code "a1b2c3"} → {@code "123"}.
     *
     * <p>Dígitos de outros sistemas de escrita são descartados, pelo mesmo motivo de
     * {@link #containsOnlyDigits(String)}.</p>
     *
     * @param input String de entrada (se nula, retorna vazio)
     * @return String contendo apenas os dígitos de 0 a 9, na ordem em que aparecem
     */
    public static String extractNumbers(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /**
     * Mascara uma parte da string com um caractere substituto. Útil para ocultar dados
     * sensíveis, como números de cartão de crédito.
     *
     * <p>O intervalo é ajustado aos limites da string: um fim além do comprimento mascara até o
     * último caractere e um início negativo começa do primeiro. Antes, um índice fora da string
     * devolvia o texto inteiro <em>sem máscara</em> — o dado sensível ia aberto para a tela ou
     * para o log.</p>
     *
     * @param input      String original (pode ser nula)
     * @param startIndex Índice inicial (inclusivo) para mascarar
     * @param endIndex   Índice final (exclusivo) para mascarar
     * @param maskChar   Caractere a ser usado como máscara
     * @return A string mascarada; vazia se a entrada for nula; a original se o intervalo, já
     *         ajustado, ficar vazio
     */
    public static String maskString(String input, int startIndex, int endIndex, char maskChar) {
        if (input == null) {
            return "";
        }
        int start = Math.max(0, startIndex);
        int end = Math.min(endIndex, input.length());
        if (start >= end) {
            return input;
        }
        StringBuilder masked = new StringBuilder(input);
        for (int i = start; i < end; i++) {
            masked.setCharAt(i, maskChar);
        }
        return masked.toString();
    }

    /**
     * Conta quantas vezes uma substring aparece em uma string (sem sobreposição).
     *
     * @param source String fonte (pode ser nula)
     * @param target Substring a ser contada (não pode ser nula nem vazia)
     * @return Número de ocorrências, ou 0 se {@code source} for nula ou {@code target} inválida
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
     * Compara duas strings ignorando maiúsculas/minúsculas, tratando nulos de forma segura.
     *
     * @param str1 Primeira string (pode ser nula)
     * @param str2 Segunda string (pode ser nula)
     * @return {@code true} se ambas forem nulas, ou ambas não nulas e iguais ignorando a caixa
     */
    public static boolean equalsIgnoreCaseNullSafe(String str1, String str2) {
        if (str1 == null) {
            return str2 == null;
        }
        return str1.equalsIgnoreCase(str2);
    }
}
