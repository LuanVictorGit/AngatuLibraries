package br.com.angatusistemas.lib.webpush;

import java.util.regex.Pattern;

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
 * <p>Deve ser chamada durante a inicialização da aplicação:</p>
 * <pre>
 * PushBootstrap.setup("mailto:contato@seudominio.com.br");
 * </pre>
 *
 * <p><strong>Fluxo de funcionamento:</strong></p>
 * <ol>
 *   <li>Verifica se as chaves VAPID estão persistidas (via {@link Saveable})</li>
 *   <li>Se ausentes, gera um novo par de chaves automaticamente e salva</li>
 *   <li>Inicializa o {@link WebPushAPI} com as chaves e o subject do projeto — e falha, com
 *       exceção, se a inicialização for recusada</li>
 * </ol>
 *
 * <p><strong>Subject VAPID:</strong> é o contato ({@code mailto:} ou {@code https:}) que os push
 * services (Google, Mozilla, Apple, Microsoft) usam para falar com quem envia as notificações.
 * Cada projeto deve informar o seu, nesta ordem de prioridade: o parâmetro de
 * {@link #setup(String)}; a propriedade de sistema {@code angatu.webpush.subject}; a variável de
 * ambiente {@code ANGATU_WEBPUSH_SUBJECT}. Sem nenhum deles vale {@link #DEFAULT_SUBJECT}, o
 * contato da Angatu Sistemas.</p>
 *
 * @author Angatu Sistemas
 * @see WebPushAPI
 * @see Key
 */
public final class PushBootstrap {

    /**
     * Subject VAPID usado quando o projeto não configura o seu: o contato da Angatu Sistemas.
     *
     * <p>É só o valor padrão. Projetos de terceiros devem informar o próprio contato — por
     * {@link #setup(String)}, pela propriedade de sistema {@code angatu.webpush.subject} ou pela
     * variável de ambiente {@code ANGATU_WEBPUSH_SUBJECT} —, senão é a Angatu que os push
     * services procuram quando houver problema com as notificações do projeto.</p>
     */
    public static final String DEFAULT_SUBJECT = "mailto:angatusistemas@gmail.com";

    /** Propriedade de sistema com o subject do projeto (tem prioridade sobre a variável). */
    private static final String SUBJECT_PROPERTY = "angatu.webpush.subject";

    /** Variável de ambiente com o subject do projeto. */
    private static final String SUBJECT_ENVIRONMENT = "ANGATU_WEBPUSH_SUBJECT";

    /** Subject aceito pela RFC 8292: {@code mailto:} com e-mail, ou URL {@code https://}. */
    private static final Pattern VALID_SUBJECT = Pattern.compile("^(mailto:[^\\s@]+@[^\\s@]+|https://\\S+)$",
            Pattern.CASE_INSENSITIVE);

    private PushBootstrap() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== MÉTODO PRINCIPAL ====================

    /**
     * Configura e inicializa o serviço Web Push com o subject configurado para o projeto
     * (propriedade {@code angatu.webpush.subject}, variável {@code ANGATU_WEBPUSH_SUBJECT} ou
     * {@link #DEFAULT_SUBJECT}).
     *
     * <p>Verifica a existência das chaves VAPID no banco de dados. Se não estiverem
     * presentes, gera automaticamente um novo par de chaves e o salva. Em seguida,
     * inicializa o {@link WebPushAPI} com as credenciais obtidas.</p>
     *
     * <p>Exemplo de uso:</p>
     * <pre>
     * public static void main(String[] args) {
     *     PushBootstrap.setup();
     *     // WebPushAPI já está pronto para uso
     * }
     * </pre>
     *
     * @throws RuntimeException se o subject configurado for inválido, se ocorrer erro na geração
     *                          de chaves ou se a inicialização do serviço for recusada — antes, a
     *                          recusa era registrada como "inicializado com sucesso"
     */
    public static void setup() {
        try {
            initializeWithStoredKeys(configuredSubject());
        } catch (Exception e) {
            throw new RuntimeException("Erro ao configurar o Web Push: " + e.getMessage(), e);
        }
    }

    /**
     * Configura e inicializa o serviço Web Push com um subject explícito — o contato do projeto
     * para os push services.
     *
     * <p>Faz o mesmo que {@link #setup()}, sem consultar a propriedade de sistema nem a variável
     * de ambiente.</p>
     *
     * @param subject Contato do projeto: {@code "mailto:contato@seudominio.com.br"} ou
     *                {@code "https://seudominio.com.br"}
     * @throws IllegalArgumentException se o subject não for {@code mailto:} com e-mail nem
     *                                  {@code https://}
     * @throws RuntimeException         se ocorrer erro na geração de chaves ou se a
     *                                  inicialização do serviço for recusada
     */
    public static void setup(String subject) {
        String validSubject = requireValidSubject(subject, "no parâmetro de setup(subject)");
        try {
            initializeWithStoredKeys(validSubject);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao configurar o Web Push: " + e.getMessage(), e);
        }
    }

    // ==================== SUBJECT ====================

    /**
     * Subject VAPID configurado para o projeto: a propriedade de sistema, a variável de ambiente
     * ou, na falta das duas, {@link #DEFAULT_SUBJECT}.
     *
     * <p>{@code System.getProperty}/{@code System.getenv}, como {@code Saveable} e
     * {@code AngatuLib} fazem com as próprias configurações: não depende do módulo opcional de
     * {@code .env}.</p>
     *
     * @return Subject a usar
     * @throws IllegalArgumentException se o valor configurado não for um subject válido
     */
    static String configuredSubject() {
        return resolveSubject(System.getProperty(SUBJECT_PROPERTY), System.getenv(SUBJECT_ENVIRONMENT));
    }

    /**
     * Regra de {@link #configuredSubject()} sobre valores já lidos — separada para os testes.
     *
     * @param property    Valor da propriedade de sistema (pode ser {@code null})
     * @param environment Valor da variável de ambiente (pode ser {@code null})
     * @return O primeiro valor preenchido, conferido; ou {@link #DEFAULT_SUBJECT}
     * @throws IllegalArgumentException se o valor escolhido não for um subject válido
     */
    static String resolveSubject(String property, String environment) {
        if (!isBlank(property))
            return requireValidSubject(property, "na propriedade de sistema " + SUBJECT_PROPERTY);
        if (!isBlank(environment))
            return requireValidSubject(environment, "na variável de ambiente " + SUBJECT_ENVIRONMENT);
        return DEFAULT_SUBJECT;
    }

    /**
     * Confere o subject. Subject errado não falha aqui por capricho: o push service da Apple
     * recusa o token VAPID dele (HTTP 403), e o problema só aparecia nos envios.
     *
     * @param source De onde veio o valor, já com a preposição ({@code "na variável de ambiente X"})
     */
    private static String requireValidSubject(String subject, String source) {
        String trimmed = subject == null ? "" : subject.trim();
        if (!VALID_SUBJECT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Subject VAPID inválido " + source + ": \"" + subject
                    + "\". Use \"mailto:contato@seudominio.com.br\" ou \"https://seudominio.com.br\" (RFC 8292).");
        }
        return trimmed;
    }

    // ==================== CHAVES ====================

    /** Garante as chaves no banco e inicializa o {@link WebPushAPI}; falha se ele recusar. */
    private static void initializeWithStoredKeys(String subject) {
        Key key = loadOrCreateKey();
        if (!WebPushAPI.initialize(key.getPublicKey(), key.getPrivateKey(), subject)) {
            // Não gere chaves novas por cima: as assinaturas já feitas pelos navegadores são
            // presas à chave pública atual e deixariam de funcionar todas de uma vez.
            throw new IllegalStateException("o WebPushAPI recusou as chaves VAPID salvas no banco (tabela "
                    + Saveable.tableName(Key.class) + ", id \"" + Key.ID + "\") — o motivo está no log acima. "
                    + "Corrija o registro em vez de apagá-lo: chaves novas invalidam todas as assinaturas existentes.");
        }
        Console.log("WebPushAPI inicializado com sucesso");
    }

    /**
     * Lê o par de chaves do banco ou, se ele não existir (ou estiver vazio), gera e grava um novo.
     *
     * <p>A geração acontece fora da transação — trabalho lento dentro dela seguraria as
     * gravações do processo inteiro. Dentro, o registro é lido de novo antes de gravar: se outra
     * thread (ou outro processo com o mesmo banco) salvou um par nesse meio tempo, é esse que
     * vale, e as duas inicializações ficam com as mesmas chaves. Antes, a última gravação vencia
     * e a primeira instância seguia com uma chave que não existia mais no banco.</p>
     */
    private static Key loadOrCreateKey() {
        Key stored = Saveable.findById(Key.class, Key.ID);
        if (isComplete(stored)) {
            Console.debug("Chaves VAPID já configuradas. Inicializando WebPushAPI...");
            return stored;
        }

        Console.log("Chaves VAPID não encontradas. Gerando automaticamente...");
        WebPushAPI.VapidKeys generated = WebPushAPI.generateVapidKeys();
        if (generated == null) {
            throw new IllegalStateException("falha ao gerar as chaves VAPID");
        }
        return Saveable.computeInTransaction(() -> {
            Key current = Saveable.findById(Key.class, Key.ID);
            if (isComplete(current)) {
                return current;
            }
            Key created = Key.of(generated);
            created.save();
            Console.log("Chaves VAPID geradas e salvas com sucesso em " + Key.class.getSimpleName());
            return created;
        });
    }

    // ==================== MÉTODOS UTILITÁRIOS ====================

    /** O registro existe e tem as duas chaves preenchidas? */
    private static boolean isComplete(Key key) {
        return key != null && !isBlank(key.getPublicKey()) && !isBlank(key.getPrivateKey());
    }

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
