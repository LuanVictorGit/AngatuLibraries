package br.com.angatusistemas.lib.webpush;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.env.Env;

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

    public static final String DEFAULT_SUBJECT = "mailto:angatusistemas@gmail.com";

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
        	
        	Key key = Saveable.findById(Key.class, "key");
            String pub = key == null ? null : key.getPublicKey();
            String priv = key == null ? null : key.getPrivateKey();
            String subject = DEFAULT_SUBJECT;

            if (isBlank(pub) || isBlank(priv)) {
                Console.log("Chaves VAPID não encontradas. Gerando automaticamente...");

                WebPushAPI.VapidKeys keys = WebPushAPI.generateVapidKeys();

                if (keys == null) {
                    throw new RuntimeException("Falha ao gerar chaves VAPID");
                }

                key = new Key(keys.privateKey, keys.publicKey);
                key.save();

                pub = key.getPublicKey();
                priv = key.getPrivateKey();
                subject = DEFAULT_SUBJECT;

                Console.log("Chaves VAPID geradas e salvas com sucesso em " + Key.class.getSimpleName());
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
    
}