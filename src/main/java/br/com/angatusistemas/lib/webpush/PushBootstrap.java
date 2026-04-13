package br.com.angatusistemas.lib.webpush;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import br.com.angatusistemas.lib.env.Env;
import br.com.angatusistemas.lib.console.Console;

/**
 * [PT] Classe utilitária para inicialização e configuração automática do serviço Web Push.
 * <p>
 * Responsável por verificar a existência das chaves VAPID no arquivo {@code .env},
 * gerá-las automaticamente se estiverem ausentes, e inicializar o {@link WebPushAPI}
 * com as credenciais apropriadas.
 * </p>
 * <p>
 * Esta classe é tipicamente chamada durante a inicialização da aplicação:
 * <pre>
 * PushBootstrap.setup();
 * </pre>
 * </p>
 * <p>
 * <b>Fluxo de funcionamento:</b>
 * <ol>
 *   <li>Verifica se as chaves VAPID estão configuradas no arquivo {@code .env}</li>
 *   <li>Se estiverem ausentes, gera um novo par de chaves automaticamente</li>
 *   <li>Salva as chaves geradas no arquivo {@code .env}</li>
 *   <li>Inicializa o {@link WebPushAPI} com as chaves e assunto configurados</li>
 * </ol>
 * </p>
 *
 * [EN] Utility class for automatic setup and configuration of the Web Push service.
 * <p>
 * Responsible for checking the existence of VAPID keys in the {@code .env} file,
 * automatically generating them if missing, and initializing the {@link WebPushAPI}
 * with the appropriate credentials.
 * </p>
 * <p>
 * This class is typically called during application startup:
 * <pre>
 * PushBootstrap.setup();
 * </pre>
 * </p>
 * <p>
 * <b>Workflow:</b>
 * <ol>
 *   <li>Checks if VAPID keys are configured in the {@code .env} file</li>
 *   <li>If missing, generates a new key pair automatically</li>
 *   <li>Saves the generated keys to the {@code .env} file</li>
 *   <li>Initializes the {@link WebPushAPI} with the configured keys and subject</li>
 * </ol>
 * </p>
 *
 * @author Angatu Sistemas
 * @see WebPushAPI
 * @see Env
 */
public final class PushBootstrap {

    private static final String ENV_FILE = ".env";
    private static final String DEFAULT_SUBJECT = "mailto:angatusistemas@gmail.com";

    private PushBootstrap() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODO PRINCIPAL ====================

    /**
     * [PT] Configura e inicializa o serviço Web Push.
     * <p>
     * Verifica a existência das chaves VAPID no arquivo {@code .env}. Se não estiverem
     * presentes, gera automaticamente um novo par de chaves, salva no arquivo e
     * recarrega as configurações. Em seguida, inicializa o {@link WebPushAPI}
     * com as credenciais obtidas.
     * </p>
     * <p>
     * <b>Exemplo de uso:</b>
     * <pre>
     * public static void main(String[] args) {
     *     PushBootstrap.setup();
     *     // WebPushAPI já está pronto para uso
     * }
     * </pre>
     * </p>
     *
     * [EN] Configures and initializes the Web Push service.
     * <p>
     * Checks for the existence of VAPID keys in the {@code .env} file. If not present,
     * automatically generates a new key pair, saves it to the file, and reloads
     * the configurations. Then initializes the {@link WebPushAPI} with the obtained
     * credentials.
     * </p>
     * <p>
     * <b>Usage example:</b>
     * <pre>
     * public static void main(String[] args) {
     *     PushBootstrap.setup();
     *     // WebPushAPI is now ready to use
     * }
     * </pre>
     * </p>
     *
     * @throws RuntimeException [PT] se ocorrer erro durante a leitura/escrita do arquivo
     *                          ou na inicialização do serviço
     *                          [EN] if an error occurs while reading/writing the file
     *                          or initializing the service
     */
    public static void setup() {
        try {
            String pub = Env.get().get("VAPID_PUBLIC_KEY");
            String priv = Env.get().get("VAPID_PRIVATE_KEY");
            String subject = Env.get().get("VAPID_SUBJECT");

            if (isBlank(pub) || isBlank(priv)) {
                Console.log("Chaves VAPID não encontradas. Gerando automaticamente...");

                WebPushAPI.VapidKeys keys = WebPushAPI.generateVapidKeys();

                if (keys == null) {
                    throw new RuntimeException("Falha ao gerar chaves VAPID");
                }

                Map<String, String> env = readEnvFile();

                env.put("VAPID_PUBLIC_KEY", keys.publicKey);
                env.put("VAPID_PRIVATE_KEY", keys.privateKey);
                env.put("VAPID_SUBJECT", DEFAULT_SUBJECT);

                writeEnvFile(env);

                // Recarrega as variáveis de ambiente
                Env.reload();

                pub = Env.get().get("VAPID_PUBLIC_KEY");
                priv = Env.get().get("VAPID_PRIVATE_KEY");
                subject = Env.get().get("VAPID_SUBJECT");

                Console.log("Chaves VAPID geradas e salvas com sucesso em " + ENV_FILE);
            } else {
                Console.debug("Chaves VAPID já configuradas. Inicializando WebPushAPI...");
            }

            // Inicializa o serviço Web Push
            WebPushAPI.initialize(pub, priv, subject);
            Console.log("WebPushAPI inicializado com sucesso");

        } catch (Exception e) {
            throw new RuntimeException("Erro ao configurar WebPush: " + e.getMessage(), e);
        }
    }

    // ==================== MÉTODOS DE MANIPULAÇÃO DO ARQUIVO .ENV ====================

    /**
     * [PT] Lê o arquivo {@code .env} e retorna um mapa com as variáveis configuradas.
     * <p>
     * Ignora linhas vazias, comentários (iniciadas com '#') e linhas mal formatadas.
     * </p>
     *
     * [EN] Reads the {@code .env} file and returns a map of configured variables.
     * <p>
     * Ignores empty lines, comments (starting with '#') and malformed lines.
     * </p>
     *
     * @return [PT] mapa contendo as variáveis do arquivo {@code .env} (pode ser vazio)
     *         [EN] map containing variables from the {@code .env} file (may be empty)
     * @throws IOException [PT] se ocorrer erro na leitura do arquivo
     *                     [EN] if an error occurs while reading the file
     */
    private static Map<String, String> readEnvFile() throws IOException {
        Map<String, String> map = new HashMap<>();
        Path path = Paths.get(ENV_FILE);

        if (!Files.exists(path)) {
            Console.debug("Arquivo " + ENV_FILE + " não encontrado. Será criado.");
            return map;
        }

        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("=", 2);
            if (parts.length == 2) {
                map.put(parts[0], parts[1]);
                Console.debug("Variável carregada: {} = {}", parts[0], maskValue(parts[1]));
            }
        }

        return map;
    }

    /**
     * [PT] Escreve as variáveis de ambiente no arquivo {@code .env}.
     * <p>
     * Substitui completamente o conteúdo existente.
     * </p>
     *
     * [EN] Writes environment variables to the {@code .env} file.
     * <p>
     * Completely replaces the existing content.
     * </p>
     *
     * @param env [PT] mapa contendo as variáveis a serem escritas
     *            [EN] map containing variables to write
     * @throws IOException [PT] se ocorrer erro na escrita do arquivo
     *                     [EN] if an error occurs while writing the file
     */
    private static void writeEnvFile(Map<String, String> env) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Adiciona cabeçalho comentado
        sb.append("# Arquivo gerado automaticamente pelo PushBootstrap\n");
        sb.append("# Chaves VAPID para Web Push\n");
        sb.append("# Não modifique manualmente a menos que saiba o que está fazendo\n\n");

        for (Map.Entry<String, String> entry : env.entrySet()) {
            sb.append(entry.getKey())
              .append("=")
              .append(entry.getValue())
              .append("\n");
        }

        Files.write(Paths.get(ENV_FILE), sb.toString().getBytes());
        Console.debug("Arquivo " + ENV_FILE + " salvo com " + env.size() + " variáveis");
    }

    // ==================== MÉTODOS UTILITÁRIOS ====================

    /**
     * [PT] Verifica se uma string é nula, vazia ou contém apenas espaços em branco.
     *
     * [EN] Checks if a string is null, empty or contains only whitespace.
     *
     * @param s [PT] string a ser verificada
     *          [EN] string to check
     * @return [PT] true se a string for nula ou vazia, false caso contrário
     *         [EN] true if the string is null or empty, false otherwise
     */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * [PT] Mascara o valor de uma variável para exibição segura nos logs.
     * <p>
     * Exibe apenas os primeiros 20 caracteres seguidos de "...".
     * </p>
     *
     * [EN] Masks a variable value for safe display in logs.
     * <p>
     * Shows only the first 20 characters followed by "...".
     * </p>
     *
     * @param value [PT] valor a ser mascarado
     *              [EN] value to mask
     * @return [PT] valor mascarado
     *         [EN] masked value
     */
    private static String maskValue(String value) {
        if (value == null) return "null";
        if (value.length() <= 20) return value + " (length: " + value.length() + ")";
        return value.substring(0, 20) + "... (length: " + value.length() + ")";
    }
}