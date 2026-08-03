package br.com.angatusistemas.lib.webpush;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;

/**
 * Classe utilitária para inicialização e configuração automática do serviço
 * Web Push.
 *
 * <p>Responsável por verificar a existência das chaves VAPID persistidas no
 * banco (entidade {@link Key}), gerá-las automaticamente se estiverem ausentes
 * e inicializar o {@link WebPushAPI} com as credenciais apropriadas.</p>
 *
 * <p>Deve ser chamada durante a inicialização da aplicação:
 * <pre>
 * PushBootstrap.setup();
 * </pre>
 * </p>
 *
 * <p><strong>Fluxo de funcionamento:</strong></p>
 * <ol>
 *   <li>Verifica se as chaves VAPID estão persistidas (via {@link Saveable})</li>
 *   <li>Se ausentes, gera um novo par de chaves automaticamente e salva</li>
 *   <li>Inicializa o {@link WebPushAPI} com as chaves e o subject padrão</li>
 * </ol>
 *
 * @author Angatu Sistemas
 * @see WebPushAPI
 * @see Key
 */
public final class PushBootstrap {

    /** Subject padrão usado na autenticação VAPID. */
    public static final String DEFAULT_SUBJECT = "mailto:angatusistemas@gmail.com";

    private PushBootstrap() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODO PRINCIPAL ====================

    /**
     * Configura e inicializa o serviço Web Push.
     *
     * <p>Verifica a existência das chaves VAPID no banco de dados. Se não estiverem
     * presentes, gera automaticamente um novo par de chaves e o salva. Em seguida,
     * inicializa o {@link WebPushAPI} com as credenciais obtidas.</p>
     *
     * <p>Exemplo de uso:
     * <pre>
     * public static void main(String[] args) {
     *     PushBootstrap.setup();
     *     // WebPushAPI já está pronto para uso
     * }
     * </pre>
     * </p>
     *
     * @throws RuntimeException se ocorrer erro na geração de chaves ou na
     *                          inicialização do serviço
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
     * Verifica se uma string é nula, vazia ou contém apenas espaços em branco.
     *
     * @param s String a ser verificada
     * @return {@code true} se a string for nula ou apenas espaços em branco
     */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

}
