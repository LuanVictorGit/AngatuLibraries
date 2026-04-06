package br.com.angatusistemas.lib.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.angatusistemas.lib.env.Env;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.strings.StringAPI;
import br.com.angatusistemas.lib.task.Task;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * [PT] Classe utilitária para envio de e-mails via SMTP (Gmail).
 * <p>
 * Fornece envio assíncrono (utilizando {@link Task}) de e-mails em formato texto simples ou HTML,
 * com suporte a múltiplos destinatários, cópia (CC/BCC), anexos e template HTML.
 * <b>Todos os e-mails recebem um código aleatório de 3 caracteres no final do assunto</b>
 * para evitar marcação como spam e facilitar rastreamento.
 * </p>
 * <p>
 * <b>Configuração necessária no arquivo .env:</b>
 * <pre>
 * EMAIL_KEY=seuemail@gmail.com
 * EMAIL_PASSWORD=senhaapp
 * </pre>
 * </p>
 * <p>
 * <b>Exemplo de uso:</b>
 * <pre>
 * // E-mail simples (assunto final: "Bem-vindo #A7F")
 * EmailAPI.sendSimple("cliente@email.com", "Bem-vindo", "Olá, seja bem-vindo!");
 *
 * // E-mail HTML com template
 * String html = EmailAPI.loadHtmlTemplate("/emails/welcome.html", Map.of("nome", "João"));
 * EmailAPI.sendHtml("cliente@email.com", "Bem-vindo", html);
 * </pre>
 * </p>
 *
 * [EN] Utility class for sending emails via SMTP (Gmail).
 * <p>
 * Provides asynchronous sending (using {@link Task}) of plain text or HTML emails,
 * with support for multiple recipients, copy (CC/BCC), attachments and HTML templates.
 * <b>All emails receive a random 3-character code at the end of the subject</b>
 * to avoid spam filtering and facilitate tracking.
 * </p>
 * <p>
 * <b>Required configuration in .env file:</b>
 * <pre>
 * EMAIL_KEY=youremail@gmail.com
 * EMAIL_PASSWORD=apppassword
 * </pre>
 * </p>
 * <p>
 * <b>Usage example:</b>
 * <pre>
 * // Simple email (final subject: "Welcome #A7F")
 * EmailAPI.sendSimple("client@email.com", "Welcome", "Hello, welcome!");
 *
 * // HTML email with template
 * String html = EmailAPI.loadHtmlTemplate("/emails/welcome.html", Map.of("name", "John"));
 * EmailAPI.sendHtml("client@email.com", "Welcome", html);
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see Task
 * @see Env
 * @see StringAPI
 */
public final class EmailAPI {

    private static final Logger logger = LoggerFactory.getLogger(EmailAPI.class);

    // Configurações SMTP (Gmail)
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "587";
    private static final String PROTOCOL = "smtp";

    // Credenciais do remetente (carregadas do .env)
    private static final String REMETENTE = Env.get().get("EMAIL_KEY");
    private static final String SENHA_APP = Env.get().get("EMAIL_PASSWORD");

    // Validação das credenciais
    static {
        if (REMETENTE == null || SENHA_APP == null) {
            logger.warn("Credenciais de e-mail não configuradas no arquivo .env. " +
                    "Configure EMAIL_KEY e EMAIL_PASSWORD para envio de e-mails.");
        }
    }

    private EmailAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODOS PRINCIPAIS ====================

    /**
     * [PT] Envia um e-mail em formato de texto simples.
     * <p>
     * O envio é realizado de forma assíncrona (não bloqueia a thread atual).
     * Um código aleatório de 3 caracteres é adicionado ao assunto (#XXX).
     * </p>
     *
     * [EN] Sends a plain text email.
     * <p>
     * The sending is performed asynchronously (does not block the current thread).
     * A random 3-character code is added to the subject (#XXX).
     * </p>
     *
     * @param destinatario [PT] endereço de e-mail do destinatário
     *                     [EN] recipient email address
     * @param assunto      [PT] assunto do e-mail (código aleatório será adicionado)
     *                     [EN] email subject (random code will be added)
     * @param corpo        [PT] corpo do e-mail em texto puro
     *                     [EN] plain text email body
     */
    public static void sendSimple(String destinatario, String assunto, String corpo) {
        sendSimple(List.of(destinatario), null, null, assunto, corpo);
    }

    /**
     * [PT] Envia um e-mail em formato HTML.
     * <p>
     * O envio é realizado de forma assíncrona.
     * Um código aleatório de 3 caracteres é adicionado ao assunto (#XXX).
     * </p>
     *
     * [EN] Sends an HTML formatted email.
     * <p>
     * The sending is performed asynchronously.
     * A random 3-character code is added to the subject (#XXX).
     * </p>
     *
     * @param destinatario [PT] endereço de e-mail do destinatário
     *                     [EN] recipient email address
     * @param assunto      [PT] assunto do e-mail (código aleatório será adicionado)
     *                     [EN] email subject (random code will be added)
     * @param corpoHtml    [PT] corpo do e-mail em HTML
     *                     [EN] HTML email body
     */
    public static void sendHtml(String destinatario, String assunto, String corpoHtml) {
        sendHtml(List.of(destinatario), null, null, assunto, corpoHtml);
    }

    /**
     * [PT] Envia um e-mail simples para múltiplos destinatários.
     *
     * [EN] Sends a plain text email to multiple recipients.
     *
     * @param destinatarios [PT] lista de e-mails dos destinatários
     *                      [EN] list of recipient email addresses
     * @param assunto       [PT] assunto do e-mail (código aleatório será adicionado)
     *                      [EN] email subject (random code will be added)
     * @param corpo         [PT] corpo do e-mail em texto puro
     *                      [EN] plain text email body
     */
    public static void sendSimpleToMultiple(List<String> destinatarios, String assunto, String corpo) {
        sendSimple(destinatarios, null, null, assunto, corpo);
    }

    /**
     * [PT] Envia um e-mail HTML para múltiplos destinatários.
     *
     * [EN] Sends an HTML email to multiple recipients.
     *
     * @param destinatarios [PT] lista de e-mails dos destinatários
     *                      [EN] list of recipient email addresses
     * @param assunto       [PT] assunto do e-mail (código aleatório será adicionado)
     *                      [EN] email subject (random code will be added)
     * @param corpoHtml     [PT] corpo do e-mail em HTML
     *                      [EN] HTML email body
     */
    public static void sendHtmlToMultiple(List<String> destinatarios, String assunto, String corpoHtml) {
        sendHtml(destinatarios, null, null, assunto, corpoHtml);
    }

    // ==================== MÉTODOS AVANÇADOS ====================

    /**
     * [PT] Envia um e-mail simples com opções avançadas (CC, BCC, anexos).
     *
     * [EN] Sends a plain text email with advanced options (CC, BCC, attachments).
     *
     * @param destinatarios [PT] lista de destinatários principais (TO)
     *                      [EN] list of main recipients (TO)
     * @param cc            [PT] lista de destinatários em cópia (pode ser null)
     *                      [EN] list of CC recipients (may be null)
     * @param bcc           [PT] lista de destinatários em cópia oculta (pode ser null)
     *                      [EN] list of BCC recipients (may be null)
     * @param assunto       [PT] assunto do e-mail (código aleatório será adicionado)
     *                      [EN] email subject (random code will be added)
     * @param corpo         [PT] corpo do e-mail em texto puro
     *                      [EN] plain text email body
     */
    public static void sendSimple(List<String> destinatarios, List<String> cc, List<String> bcc,
                                   String assunto, String corpo) {
        Task.runAsync(() -> {
            try {
                Message message = criarMensagem(destinatarios, cc, bcc, assunto);
                message.setText(corpo);
                Transport.send(message);
                logger.info("E-mail simples enviado para: {}", destinatarios);
            } catch (Exception e) {
                logger.error("Falha ao enviar e-mail simples para {}: {}", destinatarios, e.getMessage());
            }
        });
    }

    /**
     * [PT] Envia um e-mail HTML com opções avançadas (CC, BCC, anexos).
     *
     * [EN] Sends an HTML email with advanced options (CC, BCC, attachments).
     *
     * @param destinatarios [PT] lista de destinatários principais (TO)
     *                      [EN] list of main recipients (TO)
     * @param cc            [PT] lista de destinatários em cópia (pode ser null)
     *                      [EN] list of CC recipients (may be null)
     * @param bcc           [PT] lista de destinatários em cópia oculta (pode ser null)
     *                      [EN] list of BCC recipients (may be null)
     * @param assunto       [PT] assunto do e-mail (código aleatório será adicionado)
     *                      [EN] email subject (random code will be added)
     * @param corpoHtml     [PT] corpo do e-mail em HTML
     *                      [EN] HTML email body
     */
    public static void sendHtml(List<String> destinatarios, List<String> cc, List<String> bcc,
                                 String assunto, String corpoHtml) {
        Task.runAsync(() -> {
            try {
                Message message = criarMensagem(destinatarios, cc, bcc, assunto);
                message.setContent(corpoHtml, "text/html; charset=utf-8");
                Transport.send(message);
                logger.info("E-mail HTML enviado para: {}", destinatarios);
            } catch (Exception e) {
                logger.error("Falha ao enviar e-mail HTML para {}: {}", destinatarios, e.getMessage());
            }
        });
    }

    /**
     * [PT] Envia um e-mail com anexos.
     *
     * [EN] Sends an email with attachments.
     *
     * @param destinatario [PT] e-mail do destinatário
     *                     [EN] recipient email
     * @param assunto      [PT] assunto do e-mail (código aleatório será adicionado)
     *                     [EN] email subject (random code will be added)
     * @param corpo        [PT] corpo do e-mail (pode ser texto ou HTML)
     *                     [EN] email body (can be text or HTML)
     * @param anexos       [PT] lista de arquivos a serem anexados
     *                     [EN] list of files to attach
     * @param isHtml       [PT] true se o corpo for HTML, false para texto puro
     *                     [EN] true if body is HTML, false for plain text
     */
    public static void sendWithAttachments(String destinatario, String assunto, String corpo,
                                            List<File> anexos, boolean isHtml) {
        sendWithAttachments(List.of(destinatario), null, null, assunto, corpo, anexos, isHtml);
    }

    /**
     * [PT] Envia um e-mail com anexos para múltiplos destinatários.
     *
     * [EN] Sends an email with attachments to multiple recipients.
     *
     * @param destinatarios [PT] lista de destinatários
     *                      [EN] list of recipients
     * @param cc            [PT] lista de cópia (pode ser null)
     *                      [EN] list of CC (may be null)
     * @param bcc           [PT] lista de cópia oculta (pode ser null)
     *                      [EN] list of BCC (may be null)
     * @param assunto       [PT] assunto do e-mail (código aleatório será adicionado)
     *                      [EN] email subject (random code will be added)
     * @param corpo         [PT] corpo do e-mail
     *                      [EN] email body
     * @param anexos        [PT] lista de arquivos a anexar
     *                      [EN] list of files to attach
     * @param isHtml        [PT] true se o corpo for HTML
     *                      [EN] true if body is HTML
     */
    public static void sendWithAttachments(List<String> destinatarios, List<String> cc, List<String> bcc,
                                            String assunto, String corpo, List<File> anexos, boolean isHtml) {
        Task.runAsync(() -> {
            try {
                MimeMessage message = (MimeMessage) criarMensagem(destinatarios, cc, bcc, assunto);

                // Se houver anexos, usar Multipart
                if (anexos != null && !anexos.isEmpty()) {
                    MimeMultipart multipart = new MimeMultipart();

                    // Parte do corpo
                    MimeBodyPart bodyPart = new MimeBodyPart();
                    if (isHtml) {
                        bodyPart.setContent(corpo, "text/html; charset=utf-8");
                    } else {
                        bodyPart.setText(corpo);
                    }
                    multipart.addBodyPart(bodyPart);

                    // Anexos
                    for (File anexo : anexos) {
                        if (anexo != null && anexo.exists() && anexo.isFile()) {
                            MimeBodyPart attachmentPart = new MimeBodyPart();
                            DataSource source = new FileDataSource(anexo);
                            attachmentPart.setDataHandler(new DataHandler(source));
                            attachmentPart.setFileName(anexo.getName());
                            multipart.addBodyPart(attachmentPart);
                        }
                    }

                    message.setContent(multipart);
                } else {
                    // Sem anexos, define conteúdo normalmente
                    if (isHtml) {
                        message.setContent(corpo, "text/html; charset=utf-8");
                    } else {
                        message.setText(corpo);
                    }
                }

                Transport.send(message);
                logger.info("E-mail com anexos enviado para: {}", destinatarios);
            } catch (Exception e) {
                logger.error("Falha ao enviar e-mail com anexos: {}", e.getMessage());
            }
        });
    }

    // ==================== MÉTODOS DE TEMPLATE ====================

    /**
     * [PT] Carrega um template HTML e substitui placeholders.
     * <p>
     * Placeholders no formato {@code {{nome}}} são substituídos pelos valores fornecidos.
     * </p>
     *
     * [EN] Loads an HTML template and replaces placeholders.
     * <p>
     * Placeholders in the format {@code {{name}}} are replaced by the provided values.
     * </p>
     *
     * @param templatePath [PT] caminho do template no classpath (ex: "/emails/welcome.html")
     *                     [EN] template path in classpath (e.g., "/emails/welcome.html")
     * @param placeholders [PT] mapa de placeholders (ex: {{"nome", "João"}})
     *                     [EN] map of placeholders (e.g., {{"name", "John"}})
     * @return [PT] HTML processado com os placeholders substituídos
     *         [EN] processed HTML with placeholders replaced
     * @throws IllegalStateException [PT] se o template não for encontrado
     *                               [EN] if the template is not found
     */
    public static String loadHtmlTemplate(String templatePath, Map<String, String> placeholders) {
        String template = AssetsAPI.readAssetAsString(templatePath);
        if (template == null) {
            throw new IllegalStateException("Template não encontrado: " + templatePath);
        }
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return template;
    }

    // ==================== MÉTODOS PRIVADOS ====================

    /**
     * [PT] Cria uma mensagem de e-mail com assunto contendo código aleatório.
     * <p>
     * O código aleatório tem 3 caracteres alfanuméricos e é adicionado no formato {@code #XXX}.
     * </p>
     *
     * [EN] Creates an email message with subject containing random code.
     * <p>
     * The random code has 3 alphanumeric characters and is added in the format {@code #XXX}.
     * </p>
     *
     * @param destinatarios [PT] lista de destinatários TO
     *                      [EN] list of TO recipients
     * @param cc            [PT] lista de destinatários CC
     *                      [EN] list of CC recipients
     * @param bcc           [PT] lista de destinatários BCC
     *                      [EN] list of BCC recipients
     * @param assunto       [PT] assunto original (código será adicionado)
     *                      [EN] original subject (code will be added)
     * @return [PT] mensagem pronta para envio
     *         [EN] message ready to send
     * @throws MessagingException [PT] se ocorrer erro na criação
     *                            [EN] if creation fails
     * @throws IllegalStateException [PT] se credenciais não estiverem configuradas
     *                               [EN] if credentials are not configured
     */
    private static Message criarMensagem(List<String> destinatarios, List<String> cc, List<String> bcc,
                                          String assunto) throws MessagingException {

        if (REMETENTE == null || SENHA_APP == null) {
            throw new IllegalStateException("Credenciais de e-mail não configuradas. " +
                    "Configure EMAIL_KEY e EMAIL_PASSWORD no arquivo .env");
        }

        Session session = createSession();
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(REMETENTE));

        // Destinatários principais (TO)
        if (destinatarios != null && !destinatarios.isEmpty()) {
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(
                    destinatarios.stream().collect(Collectors.joining(","))));
        }

        // Cópia (CC)
        if (cc != null && !cc.isEmpty()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(
                    cc.stream().collect(Collectors.joining(","))));
        }

        // Cópia oculta (BCC)
        if (bcc != null && !bcc.isEmpty()) {
            message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(
                    bcc.stream().collect(Collectors.joining(","))));
        }

        // Assunto com código aleatório (formato: assunto #XXX)
        String codigoAleatorio = StringAPI.randomCode(3).toUpperCase();
        String assuntoComCodigo = assunto + " #" + codigoAleatorio;
        message.setSubject(assuntoComCodigo);

        logger.debug("E-mail criado - Assunto: {}", assuntoComCodigo);

        return message;
    }

    private static Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.ssl.trust", SMTP_HOST);
        props.put("mail.transport.protocol", PROTOCOL);

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(REMETENTE, SENHA_APP);
            }
        });
    }

    /**
     * [PT] Verifica se as credenciais de e-mail estão configuradas.
     * [EN] Checks if email credentials are configured.
     *
     * @return [PT] true se EMAIL_KEY e EMAIL_PASSWORD estiverem configurados
     *         [EN] true if EMAIL_KEY and EMAIL_PASSWORD are configured
     */
    public static boolean isConfigured() {
        return REMETENTE != null && SENHA_APP != null && !REMETENTE.isEmpty() && !SENHA_APP.isEmpty();
    }
}