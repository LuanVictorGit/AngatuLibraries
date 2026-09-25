package br.com.angatusistemas.lib.email;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.env.Env;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.strings.StringAPI;
import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/**
 * Classe utilitária para envio de e-mails via SMTP (Gmail).
 *
 * <p><strong>Propósito:</strong> envio assíncrono de e-mails em texto simples ou
 * HTML, com suporte a múltiplos destinatários, cópia (CC/BCC), anexos e
 * templates. <strong>Todos os e-mails recebem um código aleatório de 3
 * caracteres no final do assunto</strong> (ex: {@code "Bem-vindo #A7F"}) para
 * evitar marcação como spam.</p>
 *
 * <p><strong>Quando usar:</strong> em qualquer fluxo que precise notificar por
 * e-mail (boas-vindas, recuperação de senha, relatórios).</p>
 *
 * <p><strong>Quando NÃO usar:</strong> sem as credenciais SMTP configuradas no
 * {@code .env} (EMAIL_KEY/EMAIL_PASSWORD) os métodos completam com
 * {@code false}; para e-mails transacionais de alto volume, use um provedor
 * dedicado (SendGrid, SES).</p>
 *
 * <p><strong>Configuração necessária no arquivo {@code .env}:</strong></p>
 * <pre>
 * EMAIL_KEY=seuemail@gmail.com
 * EMAIL_PASSWORD=senhaapp
 * </pre>
 *
 * <p><strong>Integração:</strong> os envios rodam numa fila própria, com threads
 * {@code Angatu-Email-N} — separada do pool compartilhado do
 * {@link br.com.angatusistemas.lib.task.Task}, para que um servidor SMTP lento
 * nunca trave as tarefas do resto da biblioteca. Usa {@link Env} para as
 * credenciais e {@link AssetsAPI} para os templates; os métodos de envio
 * retornam {@code CompletableFuture<Boolean>}.</p>
 *
 * <p><strong>Fluxo de utilização:</strong> configure o {@code .env} → chame o
 * método adequado → aguarde/consuma o future. Verifique
 * {@link #isConfigured()} antes de enviar para evitar falhas previsíveis.</p>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * // E-mail simples (assunto final: "Bem-vindo #A7F")
 * boolean ok = EmailAPI.sendSimple("cliente@email.com", "Bem-vindo", "Olá, seja bem-vindo!").join();
 *
 * // E-mail HTML com template (o valor recebe escape HTML)
 * String html = EmailAPI.loadHtmlTemplate("/emails/welcome.html", Map.of("nome", "João"));
 * boolean ok2 = EmailAPI.sendHtml("cliente@email.com", "Bem-vindo", html).join();
 * </pre>
 *
 * <p><strong>Resultado:</strong> o future <strong>sempre</strong> completa — com
 * {@code true} se o servidor SMTP aceitou a mensagem, ou com {@code false} em
 * qualquer falha: destinatário inválido, anexo ausente, credenciais ausentes, fila
 * cheia, erro SMTP ou erro inesperado (o motivo vai para o {@link Console}). Antes,
 * um {@code Error} dentro do envio deixava o future pendente para sempre, e um
 * {@code join()} travava a thread de quem chamou.</p>
 *
 * <p><strong>Destinatários:</strong> cada item de uma lista é <strong>um</strong>
 * endereço, com ou sem nome ({@code "Ana <ana@x.com>"}). Um item que contenha mais de
 * um endereço ({@code "a@x.com,b@y.com"}), um grupo ({@code "grupo: a@x.com, b@y.com;"}),
 * quebra de linha ou caractere de controle recusa o envio inteiro. Antes, a lista era
 * juntada por vírgula e analisada de uma vez: um campo "e-mail" preenchido com
 * {@code "vitima@x.com,atacante@y.com"} enviava a mensagem aos dois.</p>
 *
 * <p><strong>Boas práticas:</strong> use {@code .join()} apenas em threads que
 * podem bloquear; trate {@code false} como falha. No desligamento da JVM (deploy,
 * fim do {@code main}), o que já está na fila tem até 8 s para sair; um programa que
 * precisa saber se o e-mail saiu (uma ferramenta de linha de comando, um job)
 * aguarda o future antes de terminar.</p>
 *
 * <p><strong>Limitações:</strong> requer {@code com.sun.mail:jakarta.mail:2.0.2}
 * e {@code io.github.cdimascio:dotenv-java:3.2.0}; a classe é detectável
 * (linkável) sem elas — os métodos de envio e {@link #isConfigured()} lançam
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException} com as
 * instruções de instalação, na própria chamada. SMTP configurado para Gmail
 * ({@code smtp.gmail.com:587}, STARTTLS obrigatório, certificado verificado). No
 * máximo 4 envios simultâneos e 1.000 na fila; além disso o envio completa com
 * {@code false} na hora.</p>
 *
 * <p><strong>Extensões futuras:</strong> hosts SMTP configuráveis, templates
 * com lógica (loops/condicionais) e fila de reenvio são evoluções naturais sem
 * quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see Env
 * @see StringAPI
 * @see EmailFormatter
 */
public final class EmailAPI {

    /**
     * Coordenadas Maven da dependência Jakarta Mail (alinhadas ao pom).
     *
     * <p>A 2.0.1 é afetada pela CVE-2025-7962 (injeção de comandos SMTP por quebra de linha). Esta
     * classe não depende da versão instalada para se proteger: recusa destinatário com quebra de
     * linha ou caractere de controle e troca esses caracteres por espaço no assunto.</p>
     */
    private static final String MAIL_COORDINATES = "com.sun.mail:jakarta.mail:2.0.2";
    /** Classe usada para detectar o Jakarta Mail no classpath. */
    private static final String MAIL_CLASS = "jakarta.mail.Session";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String MAIL_FEATURE = "Envio de E-mails (Jakarta Mail)";
    /** Coordenadas Maven do dotenv-java, de onde vêm as credenciais. */
    private static final String DOTENV_COORDINATES = "io.github.cdimascio:dotenv-java:3.2.0";
    /** Classe usada para detectar o dotenv-java no classpath. */
    private static final String DOTENV_CLASS = "io.github.cdimascio.dotenv.Dotenv";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String DOTENV_FEATURE = "Envio de E-mails (credenciais no .env)";
    /** Limite de caracteres de um valor escrito no log (um destinatário malicioso pode ser enorme). */
    private static final int MAX_LOGGED_CHARS = 300;

    private EmailAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== CREDENCIAIS (LAZY) ====================

    /**
     * Credenciais do remetente, carregadas do {@code .env} apenas no primeiro uso
     * (evita quebrar o classload da classe quando o dotenv está ausente).
     *
     * <p>Só é tocada depois de {@code requireDotenv()}: sem o dotenv, a inicialização desta classe
     * falhava com {@code ExceptionInInitializerError} dentro da thread de envio — um {@code Error},
     * que passava pelo tratamento de exceções e deixava o future sem resposta — e, dali em diante,
     * com {@code NoClassDefFoundError} em toda chamada de {@link EmailAPI#isConfigured()}.</p>
     */
    private static final class Credentials {
        static final String SENDER = Env.get().get("EMAIL_KEY");
        static final String APP_PASSWORD = Env.get().get("EMAIL_PASSWORD");

        static {
            if (StringAPI.isNullOrBlank(SENDER) || StringAPI.isNullOrBlank(APP_PASSWORD)) {
                Console.warn("Credenciais de e-mail não configuradas no arquivo .env. "
                        + "Configure EMAIL_KEY e EMAIL_PASSWORD para envio de e-mails.");
            }
        }

        private Credentials() {
        }
    }

    // ==================== MÉTODOS PRINCIPAIS ====================

    /**
     * Envia um e-mail em formato de texto simples (assíncrono).
     *
     * <p><strong>Pós-condições:</strong> o future sempre completa: {@code true} se enviado;
     * {@code false} se o endereço for inválido ou não existir, ou em qualquer outra falha.</p>
     *
     * @param recipient Endereço de e-mail do destinatário
     * @param subject   Assunto do e-mail (código aleatório será adicionado)
     * @param body      Corpo do e-mail em texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendSimple(String recipient, String subject, String body) {
        return sendSimple(Collections.singletonList(recipient), null, null, subject, body);
    }

    /**
     * Envia um e-mail em formato HTML (assíncrono).
     *
     * @param recipient Endereço de e-mail do destinatário
     * @param subject   Assunto do e-mail (código aleatório será adicionado)
     * @param htmlBody  Corpo do e-mail em HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendHtml(String recipient, String subject, String htmlBody) {
        return sendHtml(Collections.singletonList(recipient), null, null, subject, htmlBody);
    }

    /**
     * Envia um e-mail simples para múltiplos destinatários (assíncrono).
     *
     * @param recipients Lista de e-mails dos destinatários (um endereço por item)
     * @param subject    Assunto do e-mail (código aleatório será adicionado)
     * @param body       Corpo do e-mail em texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendSimpleToMultiple(List<String> recipients, String subject, String body) {
        return sendSimple(recipients, null, null, subject, body);
    }

    /**
     * Envia um e-mail HTML para múltiplos destinatários (assíncrono).
     *
     * @param recipients Lista de e-mails dos destinatários (um endereço por item)
     * @param subject    Assunto do e-mail (código aleatório será adicionado)
     * @param htmlBody   Corpo do e-mail em HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendHtmlToMultiple(List<String> recipients, String subject, String htmlBody) {
        return sendHtml(recipients, null, null, subject, htmlBody);
    }

    // ==================== MÉTODOS AVANÇADOS ====================

    /**
     * Envia um e-mail simples com opções avançadas (CC, BCC).
     *
     * @param recipients Lista de destinatários principais (TO), um endereço por item
     * @param cc         Lista de destinatários em cópia (pode ser {@code null})
     * @param bcc        Lista de destinatários em cópia oculta (pode ser {@code null})
     * @param subject    Assunto do e-mail (código aleatório será adicionado)
     * @param body       Corpo do e-mail em texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendSimple(List<String> recipients, List<String> cc, List<String> bcc,
                                                         String subject, String body) {
        checkDependencies();
        return MailSupport.send(recipients, cc, bcc, subject, body, null, false);
    }

    /**
     * Envia um e-mail HTML com opções avançadas (CC, BCC).
     *
     * @param recipients Lista de destinatários principais (TO), um endereço por item
     * @param cc         Lista de destinatários em cópia (pode ser {@code null})
     * @param bcc        Lista de destinatários em cópia oculta (pode ser {@code null})
     * @param subject    Assunto do e-mail (código aleatório será adicionado)
     * @param htmlBody   Corpo do e-mail em HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendHtml(List<String> recipients, List<String> cc, List<String> bcc,
                                                       String subject, String htmlBody) {
        checkDependencies();
        return MailSupport.send(recipients, cc, bcc, subject, htmlBody, null, true);
    }

    /**
     * Envia um e-mail com anexos.
     *
     * <p>Se algum anexo não existir, não for um arquivo ou não puder ser lido, <strong>nada é
     * enviado</strong> e o future completa com {@code false} (o console diz qual arquivo). Antes, o
     * anexo ausente era pulado em silêncio e o envio ainda devolvia {@code true}: o cliente recebia
     * "segue em anexo" sem anexo e o sistema registrava sucesso.</p>
     *
     * @param recipient   E-mail do destinatário
     * @param subject     Assunto do e-mail (código aleatório será adicionado)
     * @param body        Corpo do e-mail (texto ou HTML)
     * @param attachments Lista de arquivos a anexar (todos precisam existir e ser legíveis)
     * @param isHtml      {@code true} se o corpo for HTML, {@code false} para texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado com todos os anexos
     */
    public static CompletableFuture<Boolean> sendWithAttachments(String recipient, String subject, String body,
                                                                  List<File> attachments, boolean isHtml) {
        return sendWithAttachments(Collections.singletonList(recipient), null, null, subject, body, attachments, isHtml);
    }

    /**
     * Envia um e-mail com anexos para múltiplos destinatários.
     *
     * <p>Se algum anexo não existir, não for um arquivo ou não puder ser lido, nada é enviado e o
     * future completa com {@code false} (ver {@link #sendWithAttachments(String, String, String, List, boolean)}).</p>
     *
     * @param recipients  Lista de destinatários, um endereço por item
     * @param cc          Lista de cópia (pode ser {@code null})
     * @param bcc         Lista de cópia oculta (pode ser {@code null})
     * @param subject     Assunto do e-mail (código aleatório será adicionado)
     * @param body        Corpo do e-mail (texto ou HTML)
     * @param attachments Lista de arquivos a anexar (todos precisam existir e ser legíveis)
     * @param isHtml      {@code true} se o corpo for HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado com todos os anexos
     */
    public static CompletableFuture<Boolean> sendWithAttachments(List<String> recipients, List<String> cc,
                                                                  List<String> bcc, String subject, String body,
                                                                  List<File> attachments, boolean isHtml) {
        checkDependencies();
        return MailSupport.send(recipients, cc, bcc, subject, body, attachments, isHtml);
    }

    // ==================== MÉTODOS DE TEMPLATE ====================

    /**
     * Carrega um template HTML e substitui os placeholders, <strong>escapando os valores como
     * HTML</strong>.
     *
     * <p>Cada placeholder {@code {{chave}}} recebe o valor correspondente do mapa com
     * {@code & < > " '} convertidos em entidades HTML: um nome como {@code <a href=...>} digitado
     * por um cliente aparece como texto no e-mail, em vez de virar HTML enviado pela conta da
     * empresa.</p>
     *
     * <p>A substituição é feita <strong>numa única passada</strong>: o texto de um valor nunca é
     * lido de novo como placeholder. Antes, um valor contendo {@code {{token}}} era trocado pelo
     * placeholder seguinte, e um cliente podia puxar para dentro do próprio texto um dado que o
     * template não mostrava ali. Placeholder sem chave no mapa fica como está; valor {@code null}
     * vira texto vazio.</p>
     *
     * <p><strong>Onde o valor pode ir:</strong> texto entre tags e atributo entre aspas
     * ({@code title="{{x}}"}). O escape não torna seguro um valor dentro de {@code <script>} ou
     * {@code <style>}, em atributo sem aspas nem em URL — {@code href="{{link}}"} aceita
     * {@code javascript:}. Links devem ser montados e validados pelo sistema.</p>
     *
     * <p><strong>Trate o template como público.</strong> O caminho é lido de {@code public/} no
     * classpath (via {@link AssetsAPI}): {@code "/emails/welcome.html"} é o arquivo
     * {@code src/main/resources/public/emails/welcome.html}, dentro da pasta que o
     * {@link br.com.angatusistemas.lib.javalin.JavalinAPI} publica na raiz do site. Nunca coloque
     * no template dado sigiloso, link interno ou comentário que não possa ser lido por qualquer um;
     * dado de cliente entra só pelos placeholders.</p>
     *
     * <p><strong>Mudança de comportamento:</strong> antes desta correção os valores entravam sem
     * escape. Quem passava um trecho de HTML pronto num placeholder deve passá-lo por
     * {@link #loadHtmlTemplateWithTrustedHtml(String, Map, Map)}.</p>
     *
     * @param templatePath Caminho do template dentro de {@code public/} no classpath (ex: {@code "/emails/welcome.html"})
     * @param placeholders Mapa de placeholders (ex: {@code Map.of("nome", "João")}); os valores recebem escape HTML
     * @return HTML processado com os placeholders substituídos
     * @throws IllegalStateException se o template não for encontrado
     */
    public static String loadHtmlTemplate(String templatePath, Map<String, String> placeholders) {
        return renderTemplate(readTemplate(templatePath), placeholders, null);
    }

    /**
     * Variante de {@link #loadHtmlTemplate(String, Map)} para inserir trechos de HTML montados pelo
     * próprio sistema (uma tabela com os itens do pedido, um botão pronto).
     *
     * <p>{@code placeholders} recebe escape HTML exatamente como em
     * {@link #loadHtmlTemplate(String, Map)}; {@code trustedHtml} entra <strong>sem escape
     * nenhum</strong>. Use o segundo mapa só com HTML que o sistema montou — nunca com texto vindo de
     * cliente, formulário, banco ou API externa. Se o trecho confiável carrega dado de cliente (o
     * nome de um item, por exemplo), escape esse dado com {@link #escapeHtml(String)} ao montar o
     * trecho.</p>
     *
     * <p>A mesma chave nos dois mapas é recusada: não há como saber qual dos dois o chamador queria,
     * e escolher em silêncio poderia publicar sem escape o que deveria ser escapado.</p>
     *
     * @param templatePath Caminho do template dentro de {@code public/} no classpath (o template é
     *                     público — ver {@link #loadHtmlTemplate(String, Map)})
     * @param placeholders Valores de texto, que recebem escape HTML (pode ser {@code null})
     * @param trustedHtml  Trechos de HTML confiáveis, inseridos como estão (pode ser {@code null})
     * @return HTML processado com os placeholders substituídos
     * @throws IllegalStateException    se o template não for encontrado
     * @throws IllegalArgumentException se a mesma chave aparecer nos dois mapas
     */
    public static String loadHtmlTemplateWithTrustedHtml(String templatePath, Map<String, String> placeholders,
                                                         Map<String, String> trustedHtml) {
        return renderTemplate(readTemplate(templatePath), placeholders, trustedHtml);
    }

    /**
     * Escapa um texto para inserção em HTML: {@code & < > " '} viram entidades.
     *
     * <p>É o escape aplicado por {@link #loadHtmlTemplate(String, Map)}. Fica público para quem
     * monta trechos para {@link #loadHtmlTemplateWithTrustedHtml(String, Map, Map)} com dado de
     * cliente dentro. Serve para texto entre tags e atributo entre aspas — não para
     * {@code <script>}, {@code <style>} nem URL.</p>
     *
     * @param value Texto a escapar ({@code null} vira texto vazio)
     * @return Texto seguro para HTML
     */
    public static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String entity = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (entity != null) {
                if (escaped == null) {
                    escaped = new StringBuilder(value.length() + 16).append(value, 0, i);
                }
                escaped.append(entity);
            } else if (escaped != null) {
                escaped.append(c);
            }
        }
        return escaped == null ? value : escaped.toString();
    }

    /**
     * Lê o template de {@code public/} no classpath.
     *
     * @throws IllegalStateException se o template não for encontrado
     */
    private static String readTemplate(String templatePath) {
        String template = AssetsAPI.readAssetAsString(templatePath);
        if (template == null) {
            throw new IllegalStateException("Template não encontrado: " + templatePath);
        }
        return template;
    }

    /**
     * Substitui os placeholders {@code {{chave}}} numa única passada: o texto inserido nunca é
     * examinado de novo. Valores de {@code placeholders} recebem {@link #escapeHtml(String)}; os de
     * {@code trustedHtml} entram como estão. Pacote-privado para os testes.
     *
     * <p>A procura pelo fecho <code>}}</code> de cada <code>{{</code> vai no máximo até o tamanho da
     * maior chave: o custo fica linear no tamanho do template mesmo com milhares de <code>{{</code>
     * sem chave correspondente.</p>
     *
     * @throws IllegalArgumentException se a mesma chave aparecer nos dois mapas
     */
    static String renderTemplate(String template, Map<String, String> placeholders, Map<String, String> trustedHtml) {
        Map<String, String> escaped = placeholders == null ? Map.of() : placeholders;
        Map<String, String> raw = trustedHtml == null ? Map.of() : trustedHtml;
        if (escaped.isEmpty() && raw.isEmpty()) {
            return template;
        }
        int maxKeyLength = 0;
        for (String key : escaped.keySet()) {
            if (key != null) {
                maxKeyLength = Math.max(maxKeyLength, key.length());
            }
        }
        for (String key : raw.keySet()) {
            if (key == null) {
                continue;
            }
            if (escaped.containsKey(key)) {
                throw new IllegalArgumentException("A chave \"" + key + "\" aparece nos dois mapas (texto e HTML "
                        + "confiável); use cada chave em um só.");
            }
            maxKeyLength = Math.max(maxKeyLength, key.length());
        }

        StringBuilder out = new StringBuilder(template.length() + 64);
        int copied = 0;
        int from = 0;
        int open;
        while ((open = template.indexOf("{{", from)) >= 0) {
            int keyStart = open + 2;
            int close = indexOfClose(template, keyStart, maxKeyLength);
            String replacement = close < 0 ? null : resolve(template.substring(keyStart, close), escaped, raw);
            if (replacement == null) {
                from = open + 1; // não é placeholder conhecido: segue procurando a partir do próximo caractere
                continue;
            }
            out.append(template, copied, open).append(replacement);
            copied = close + 2;
            from = copied;
        }
        return out.append(template, copied, template.length()).toString();
    }

    /** Posição do <code>}}</code> que fecha a chave iniciada em {@code keyStart}, ou -1. */
    private static int indexOfClose(String template, int keyStart, int maxKeyLength) {
        int lastStart = Math.min(template.length() - 2, keyStart + maxKeyLength);
        for (int i = keyStart; i <= lastStart; i++) {
            if (template.charAt(i) == '}' && template.charAt(i + 1) == '}') {
                return i;
            }
        }
        return -1;
    }

    /** Texto que substitui a chave, ou {@code null} se a chave não estiver em nenhum mapa. */
    private static String resolve(String key, Map<String, String> escaped, Map<String, String> raw) {
        if (escaped.containsKey(key)) {
            return escapeHtml(escaped.get(key));
        }
        if (raw.containsKey(key)) {
            String html = raw.get(key);
            return html == null ? "" : html;
        }
        return null;
    }

    // ==================== MÉTODOS PRIVADOS ====================

    /**
     * Verifica a presença das dependências do módulo (mensagem clara se ausentes).
     *
     * <p>Exige também o dotenv: sem ele as credenciais não podem ser lidas, e a falha acontecia
     * tarde demais — dentro da thread de envio, como {@code Error}, com o future nunca completado.
     * Aqui ela acontece na chamada, com as instruções de instalação, como para o Jakarta Mail.</p>
     */
    private static void checkDependencies() {
        Dependencies.require(MAIL_CLASS, MAIL_COORDINATES, MAIL_FEATURE);
        requireDotenv();
    }

    /** Exige o dotenv-java, de onde vêm as credenciais. */
    private static void requireDotenv() {
        Dependencies.require(DOTENV_CLASS, DOTENV_COORDINATES, DOTENV_FEATURE);
    }

    /**
     * Verifica se as credenciais de e-mail estão configuradas no {@code .env}.
     *
     * @return {@code true} se EMAIL_KEY e EMAIL_PASSWORD estiverem configurados e não estiverem em branco
     * @throws br.com.angatusistemas.lib.dependencies.MissingDependencyException
     *         se o dotenv-java não estiver no classpath (antes: {@code NoClassDefFoundError})
     */
    public static boolean isConfigured() {
        requireDotenv();
        return !StringAPI.isNullOrBlank(Credentials.SENDER) && !StringAPI.isNullOrBlank(Credentials.APP_PASSWORD);
    }

    /**
     * Versão de um texto segura para o log: controles e quebras de linha viram {@code ?} (um
     * destinatário com {@code \r\n} forjaria linhas falsas no console) e o tamanho é limitado.
     */
    private static String printable(String text) {
        if (text == null) {
            return "null";
        }
        int limit = Math.min(text.length(), MAX_LOGGED_CHARS);
        StringBuilder out = new StringBuilder(limit + 1);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            out.append(isControlOrLineBreak(c) ? '?' : c);
        }
        if (text.length() > limit) {
            out.append('…');
        }
        return out.toString();
    }

    /** {@link #printable(String)} de uma lista inteira. */
    private static String printable(List<?> values) {
        return printable(String.valueOf(values));
    }

    /** Caractere de controle (CR, LF, TAB, NUL...) ou separador de linha/parágrafo Unicode. */
    private static boolean isControlOrLineBreak(char c) {
        return Character.isISOControl(c) || c == '\u2028' || c == '\u2029';
    }

    // ==================== FILA DE ENVIO ====================

    /**
     * Executor exclusivo do envio de e-mails, criado no primeiro envio.
     *
     * <h2>Por que não o pool do Task</h2>
     * <p>O envio rodava no pool compartilhado de 4 threads do
     * {@link br.com.angatusistemas.lib.task.Task} — o mesmo que roda a varredura periódica do rate
     * limit do servidor web. Sem timeout de socket, quatro envios presos num servidor SMTP que não
     * responde congelavam aquele pool inteiro, e com ele tarefas que nada têm a ver com e-mail. Aqui
     * o e-mail só consegue atrasar o próprio e-mail, e por tempo limitado: os timeouts de
     * {@link MailSupport#smtpProperties(String, int)} encerram cada tentativa.</p>
     *
     * <h2>Por que limitado</h2>
     * <p>No máximo {@value #THREADS} envios simultâneos (o Gmail limita conexões simultâneas por
     * conta) e {@value #QUEUE_CAPACITY} na fila. Passando disso, o envio completa com {@code false} na
     * hora, com aviso no console, em vez de acumular memória sem limite enquanto o servidor não
     * responde. O limite diário do Gmail (500 mensagens na conta gratuita, 2.000 no Workspace) chega
     * antes da fila encher em uso normal.</p>
     *
     * <p>Threads daemon com nome {@code Angatu-Email-N}: não seguram o encerramento da JVM e aparecem
     * identificadas num thread dump. Ociosas por {@value #KEEP_ALIVE_SECONDS} s, terminam — aplicação
     * que quase não envia e-mail não mantém thread parada.</p>
     *
     * <h2>Desligamento</h2>
     * <p>Por serem daemon, as threads sozinhas não esperariam nada: o SIGTERM de um deploy, ou um
     * {@code main} que termina logo depois de pedir o envio, descartaria os e-mails ainda na fila.
     * Um gancho de desligamento, registrado junto com a fila, dá ao que já foi pedido até
     * {@value #SHUTDOWN_GRACE_MS} ms para sair — dentro dos 10 s que o Docker espera antes de
     * matar o processo. Pedido feito durante o desligamento completa com {@code false}.</p>
     */
    private static final class SendExecutor {
        static final int THREADS = 4;
        static final int QUEUE_CAPACITY = 1_000;
        static final long KEEP_ALIVE_SECONDS = 60L;
        static final long SHUTDOWN_GRACE_MS = 8_000L;
        static final ThreadPoolExecutor INSTANCE = create();

        private SendExecutor() {
        }

        private static ThreadPoolExecutor create() {
            AtomicInteger counter = new AtomicInteger(1);
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "Angatu-Email-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            };
            ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS, KEEP_ALIVE_SECONDS,
                    TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), factory,
                    new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            registerShutdownDrain(executor);
            return executor;
        }

        /** No desligamento da JVM, deixa a fila esvaziar por até {@value #SHUTDOWN_GRACE_MS} ms. */
        private static void registerShutdownDrain(ThreadPoolExecutor executor) {
            Thread drain = new Thread(() -> {
                executor.shutdown(); // recusa pedido novo; o que está na fila continua saindo
                try {
                    if (!executor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        Console.warn("Desligamento: %d e-mail(s) não saíram em %d s e foram descartados.",
                                executor.getQueue().size() + executor.getActiveCount(), SHUTDOWN_GRACE_MS / 1_000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Angatu-Email-Shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(drain);
            } catch (IllegalStateException alreadyShuttingDown) {
                // A fila nasceu durante o desligamento: não há gancho a registrar
            }
        }
    }

    // ==================== IMPLEMENTAÇÃO (JAKARTA MAIL — LAZY) ====================

    /**
     * Implementação do envio com Jakarta Mail. Classe separada para manter as
     * referências à biblioteca fora do bytecode da {@link EmailAPI} — a classe
     * pública pode ser vinculada sem o jakarta.mail e o guard exibe a mensagem
     * de instalação correta antes de qualquer uso.
     *
     * <p>Pacote-privada (e não privada) para que os testes do pacote exercitem a análise de
     * destinatários e as propriedades SMTP sem abrir conexão.</p>
     */
    static final class MailSupport {

        private static final String SMTP_HOST = "smtp.gmail.com";
        private static final int SMTP_PORT = 587;
        /** Tempo máximo para abrir a conexão TCP (ms). */
        static final int CONNECTION_TIMEOUT_MS = 10_000;
        /** Tempo máximo de espera por cada leitura do socket (ms). */
        static final int READ_TIMEOUT_MS = 20_000;
        /** Tempo máximo de cada escrita no socket (ms). */
        static final int WRITE_TIMEOUT_MS = 20_000;
        /** Versões de TLS aceitas. */
        static final String TLS_PROTOCOLS = "TLSv1.2 TLSv1.3";

        private MailSupport() {
        }

        /**
         * Sessão SMTP única, criada no primeiro envio (inicialização de classe: preguiçosa e segura
         * entre threads, sem trava explícita).
         *
         * <p>Antes, cada e-mail criava uma {@link Session} nova, e criar uma sessão varre o classpath
         * atrás de provedores ({@code ServiceLoader}, {@code META-INF/javamail.providers}) a cada
         * envio. A sessão não muda depois de criada e é segura entre threads; reaproveitá-la corta
         * esse custo.</p>
         *
         * <p>A conexão (TCP + TLS + AUTH) continua sendo uma por e-mail, de propósito: guardar a
         * conexão entre envios exigiria detectar conexão morta (o servidor fecha a ociosa, o NAT a
         * derruba sem avisar), e quando ela cai no meio do {@code DATA} não há como saber se o
         * servidor já aceitou a mensagem — reenviar arrisca e-mail duplicado, não reenviar arrisca
         * e-mail perdido. Correção antes de economia de handshake.</p>
         */
        private static final class SessionHolder {
            static final Session SESSION = Session.getInstance(smtpProperties(SMTP_HOST, SMTP_PORT),
                    new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(Credentials.SENDER, Credentials.APP_PASSWORD);
                        }
                    });

            private SessionHolder() {
            }
        }

        /**
         * Propriedades SMTP da sessão. Pacote-privado para os testes conferirem o que vai para o
         * Jakarta Mail sem abrir conexão.
         *
         * <h4>TLS: por que cada linha existe</h4>
         * <ul>
         * <li><b>Sem {@code mail.smtp.ssl.trust}.</b> Com ele (valia {@code smtp.gmail.com}), o
         * Jakarta Mail troca o validador de certificados por um que aceita <em>qualquer</em>
         * certificado apresentado por aquele host: um servidor falso com certificado autoassinado
         * (DNS envenenado, Wi-Fi hostil) recebia o login e a senha de app. Sem a propriedade vale a
         * cadeia de certificados confiáveis do próprio Java.</li>
         * <li><b>{@code starttls.required}.</b> Só com {@code starttls.enable}, um servidor — ou
         * alguém no meio do caminho — que não anunciasse STARTTLS recebia o AUTH em texto puro.
         * Agora a conexão é recusada antes do AUTH.</li>
         * <li><b>{@code ssl.checkserveridentity}.</b> O validador padrão confere a cadeia, não o
         * nome: sem isto, o certificado válido de qualquer outro domínio seria aceito. Com isto, o
         * nome no certificado precisa corresponder ao host.</li>
         * <li><b>{@code ssl.protocols}.</b> TLS 1.2 e 1.3 (o 1.3 estava de fora).</li>
         * </ul>
         *
         * <h4>Timeouts</h4>
         * <p>O padrão do Jakarta Mail é esperar para sempre na conexão, na leitura e na escrita: um
         * servidor mudo prendia a thread de envio indefinidamente. Os limites valem por operação —
         * um servidor que responde um byte a cada 19 s ainda segura a thread —, mas servidor mudo,
         * porta filtrada ou conexão morta passam a falhar em segundos. O {@code writetimeout} é
         * implementado pelo Jakarta Mail com uma thread auxiliar por conexão, encerrada quando a
         * conexão fecha: custo aceitável com no máximo {@value SendExecutor#THREADS} conexões ao mesmo
         * tempo. Essa thread auxiliar não é daemon: enquanto uma conexão está aberta, ela segura o
         * encerramento da JVM — no máximo pelo tempo dos limites acima.</p>
         *
         * @param host servidor SMTP
         * @param port porta SMTP (submissão com STARTTLS)
         * @return propriedades para {@link Session#getInstance(Properties, Authenticator)}
         */
        static Properties smtpProperties(String host, int port) {
            Properties props = new Properties();
            props.setProperty("mail.transport.protocol", "smtp");
            props.setProperty("mail.smtp.host", host);
            props.setProperty("mail.smtp.port", String.valueOf(port));
            props.setProperty("mail.smtp.auth", "true");

            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.starttls.required", "true");
            props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            props.setProperty("mail.smtp.ssl.protocols", TLS_PROTOCOLS);

            props.setProperty("mail.smtp.connectiontimeout", String.valueOf(CONNECTION_TIMEOUT_MS));
            props.setProperty("mail.smtp.timeout", String.valueOf(READ_TIMEOUT_MS));
            props.setProperty("mail.smtp.writetimeout", String.valueOf(WRITE_TIMEOUT_MS));
            return props;
        }

        /**
         * Enfileira o envio e devolve o future, que completa em qualquer caso.
         *
         * <p>As listas são copiadas aqui, na thread de quem chamou: com a fila, o e-mail pode esperar
         * um pouco antes de sair, e o que sai tem de ser o que foi pedido, mesmo que a lista original
         * seja alterada nesse meio-tempo.</p>
         */
        static CompletableFuture<Boolean> send(List<String> recipients, List<String> cc, List<String> bcc,
                String subject, String body, List<File> attachments, boolean isHtml) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            List<String> toCopy = copyOf(recipients);
            List<String> ccCopy = copyOf(cc);
            List<String> bccCopy = copyOf(bcc);
            List<File> attachmentsCopy = copyOf(attachments);
            try {
                SendExecutor.INSTANCE.execute(() -> deliver(future, toCopy, ccCopy, bccCopy, subject, body,
                        attachmentsCopy, isHtml));
            } catch (RejectedExecutionException e) {
                if (SendExecutor.INSTANCE.isShutdown()) {
                    Console.warn("E-mail para %s NÃO enviado: a aplicação está desligando.", printable(toCopy));
                } else {
                    Console.warn("Fila de envio de e-mails cheia (%d pendentes): e-mail para %s NÃO enviado.",
                            SendExecutor.QUEUE_CAPACITY, printable(toCopy));
                }
                future.complete(Boolean.FALSE);
            }
            return future;
        }

        /**
         * Executa um envio na thread da fila. O future é completado no {@code finally}: nenhum
         * caminho — nem um {@code Error} — o deixa pendente. Um {@link VirtualMachineError} (falta de
         * memória, por exemplo) ainda é relançado depois, para não ser engolido.
         */
        private static void deliver(CompletableFuture<Boolean> future, List<String> recipients, List<String> cc,
                List<String> bcc, String subject, String body, List<File> attachments, boolean isHtml) {
            boolean sent = false;
            try {
                List<String> unusable = unusableAttachments(attachments);
                if (!unusable.isEmpty()) {
                    Console.error("E-mail para %s NÃO enviado: anexo inexistente, que não é arquivo ou "
                            + "ilegível: %s", printable(recipients), printable(String.join(", ", unusable)));
                    return;
                }
                MimeMessage message = createMessage(recipients, cc, bcc, subject);
                setContent(message, body, attachments, isHtml);
                Transport.send(message);
                sent = true;
                Console.debug("E-mail enviado para: %s", printable(recipients));
            } catch (AddressException e) {
                Console.warn("E-mail NÃO enviado. %s", e.getMessage());
            } catch (SendFailedException e) {
                Console.warn("E-mail inválido ou inexistente para %s: %s", printable(recipients), e.getMessage());
            } catch (Throwable t) {
                Console.error("Falha ao enviar e-mail para %s", printable(recipients), t);
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
            } finally {
                future.complete(sent);
            }
        }

        private static <T> List<T> copyOf(List<T> list) {
            return list == null ? null : new ArrayList<>(list);
        }

        /** Anexos que não podem ser enviados: nulos, inexistentes, que não são arquivo ou ilegíveis. */
        private static List<String> unusableAttachments(List<File> attachments) {
            List<String> problems = new ArrayList<>();
            if (attachments == null) {
                return problems;
            }
            for (File file : attachments) {
                if (file == null) {
                    problems.add("(item nulo na lista de anexos)");
                } else if (!file.isFile() || !file.canRead()) {
                    problems.add(file.getPath());
                }
            }
            return problems;
        }

        private static MimeMessage createMessage(List<String> recipients, List<String> cc, List<String> bcc,
                String subject) throws MessagingException {

            if (!isConfigured()) {
                throw new IllegalStateException("Credenciais de e-mail não configuradas. "
                        + "Configure EMAIL_KEY e EMAIL_PASSWORD no arquivo .env.");
            }

            InternetAddress[] toAddresses = parseRecipients(recipients);
            InternetAddress[] ccAddresses = parseRecipients(cc);
            InternetAddress[] bccAddresses = parseRecipients(bcc);

            MimeMessage message = new MimeMessage(SessionHolder.SESSION);
            message.setFrom(senderAddress());
            if (toAddresses.length > 0) {
                message.setRecipients(Message.RecipientType.TO, toAddresses);
            }
            if (ccAddresses.length > 0) {
                message.setRecipients(Message.RecipientType.CC, ccAddresses);
            }
            if (bccAddresses.length > 0) {
                message.setRecipients(Message.RecipientType.BCC, bccAddresses);
            }

            // Assunto com código aleatório (formato: assunto #XXX). Locale.ROOT: com a JVM em turco,
            // "i" maiúsculo vira "İ" e o código deixava de ser ASCII.
            String subjectWithCode = singleLine(String.valueOf(subject)) + " #"
                    + StringAPI.randomCode(3).toUpperCase(Locale.ROOT);
            message.setSubject(subjectWithCode);

            Console.debug("E-mail criado - Assunto: %s", subjectWithCode);

            return message;
        }

        /**
         * Remetente a partir de {@code EMAIL_KEY}. Um valor inválido vira
         * {@link IllegalStateException}, para não ser relatado como "destinatário inválido".
         */
        private static InternetAddress senderAddress() {
            try {
                return new InternetAddress(Credentials.SENDER);
            } catch (AddressException e) {
                throw new IllegalStateException("EMAIL_KEY do arquivo .env não é um endereço de e-mail válido.", e);
            }
        }

        /**
         * Assunto em uma linha só: controles e quebras de linha viram espaço.
         *
         * <p>O Jakarta Mail já dobra o cabeçalho com segurança, mas só enquanto a propriedade de
         * sistema {@code mail.mime.foldtext} não for desligada por alguém na JVM; com ela desligada,
         * um {@code \r\n} num assunto montado com dado de cliente injetaria cabeçalhos. Aqui não
         * depende disso.</p>
         */
        private static String singleLine(String text) {
            StringBuilder out = null;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isControlOrLineBreak(c)) {
                    if (out == null) {
                        out = new StringBuilder(text.length()).append(text, 0, i);
                    }
                    out.append(' ');
                } else if (out != null) {
                    out.append(c);
                }
            }
            return out == null ? text : out.toString();
        }

        private static void setContent(MimeMessage message, String body, List<File> attachments, boolean isHtml)
                throws MessagingException {
            if (attachments == null || attachments.isEmpty()) {
                if (isHtml) {
                    message.setContent(body, "text/html; charset=utf-8");
                } else {
                    message.setText(body, "UTF-8");
                }
                return;
            }

            MimeMultipart multipart = new MimeMultipart();

            MimeBodyPart bodyPart = new MimeBodyPart();
            if (isHtml) {
                bodyPart.setContent(body, "text/html; charset=utf-8");
            } else {
                bodyPart.setText(body, "UTF-8");
            }
            multipart.addBodyPart(bodyPart);

            for (File attachment : attachments) {
                MimeBodyPart attachmentPart = new MimeBodyPart();
                attachmentPart.setDataHandler(new DataHandler(new FileDataSource(attachment)));
                attachmentPart.setFileName(attachment.getName());
                multipart.addBodyPart(attachmentPart);
            }

            message.setContent(multipart);
        }

        /**
         * Converte uma lista de destinatários em endereços, <strong>um por item</strong>.
         *
         * <p>Antes, a lista era juntada por vírgula e analisada de uma vez: o item
         * {@code "vitima@x.com,atacante@y.com"} virava dois destinatários — e um campo de formulário
         * bastava para mandar a mensagem da empresa a quem o atacante quisesse. Agora cada item é
         * analisado sozinho e precisa ser exatamente um endereço; grupos
         * ({@code "grupo: a@x.com, b@y.com;"}) são recusados porque o Jakarta Mail os expande em
         * vários destinatários na hora do envio; quebra de linha ou caractere de controle é recusado
         * antes da análise (injeção de comandos SMTP, CVE-2025-7962).</p>
         *
         * @param entries itens da lista (pode ser {@code null} ou vazia)
         * @return endereços validados, na ordem dos itens (vazio se não houver itens)
         * @throws AddressException com mensagem em português se algum item for inválido
         */
        static InternetAddress[] parseRecipients(List<String> entries) throws AddressException {
            if (entries == null || entries.isEmpty()) {
                return new InternetAddress[0];
            }
            InternetAddress[] addresses = new InternetAddress[entries.size()];
            int index = 0;
            for (String entry : entries) {
                addresses[index++] = parseRecipient(entry);
            }
            return addresses;
        }

        private static InternetAddress parseRecipient(String entry) throws AddressException {
            if (entry == null || entry.isBlank()) {
                throw new AddressException("Destinatário vazio na lista.");
            }
            for (int i = 0; i < entry.length(); i++) {
                if (isControlOrLineBreak(entry.charAt(i))) {
                    throw new AddressException("Destinatário com quebra de linha ou caractere de controle: \""
                            + printable(entry) + "\".");
                }
            }
            InternetAddress[] parsed;
            try {
                parsed = InternetAddress.parse(entry, true);
            } catch (AddressException e) {
                throw invalidRecipient(entry, e);
            }
            if (parsed.length != 1) {
                throw new AddressException("Cada item da lista de destinatários deve ser um único endereço, mas \""
                        + printable(entry) + "\" contém " + parsed.length + ".");
            }
            InternetAddress address = parsed[0];
            if (address.isGroup()) {
                throw new AddressException("Grupo de endereços não é aceito como destinatário: \""
                        + printable(entry) + "\".");
            }
            try {
                address.validate();
            } catch (AddressException e) {
                throw invalidRecipient(entry, e);
            }
            return address;
        }

        private static AddressException invalidRecipient(String entry, AddressException cause) {
            return new AddressException("Destinatário inválido: \"" + printable(entry) + "\" (Jakarta Mail: "
                    + cause.getMessage() + ").");
        }
    }
}
