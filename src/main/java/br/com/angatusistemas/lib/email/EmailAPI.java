package br.com.angatusistemas.lib.email;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.env.Env;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.strings.StringAPI;
import br.com.angatusistemas.lib.task.Task;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
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
 * <p><strong>Configuração necessária no arquivo {@code .env}:</strong>
 * <pre>
 * EMAIL_KEY=seuemail@gmail.com
 * EMAIL_PASSWORD=senhaapp
 * </pre>
 * </p>
 *
 * <p><strong>Integração:</strong> usa {@link Task} para envio assíncrono,
 * {@link Env} para credenciais e {@link AssetsAPI} para templates do
 * classpath; os métodos retornam {@code CompletableFuture<Boolean>}.</p>
 *
 * <p><strong>Fluxo de utilização:</strong> configure o {@code .env} → chame o
 * método adequado → aguarde/consuma o future. Verifique
 * {@link #isConfigured()} antes de enviar para evitar falhas previsíveis.</p>
 *
 * <p><strong>Exemplo:</strong>
 * <pre>
 * // E-mail simples (assunto final: "Bem-vindo #A7F")
 * boolean ok = EmailAPI.sendSimple("cliente@email.com", "Bem-vindo", "Olá, seja bem-vindo!").join();
 *
 * // E-mail HTML com template
 * String html = EmailAPI.loadHtmlTemplate("/emails/welcome.html", Map.of("nome", "João"));
 * boolean ok2 = EmailAPI.sendHtml("cliente@email.com", "Bem-vindo", html).join();
 * </pre>
 * </p>
 *
 * <p><strong>Boas práticas:</strong> use {@code .join()} apenas em threads que
 * podem bloquear; trate o resultado {@code false} como falha (destinatário
 * inexistente ou erro SMTP — logado via {@link Console}).</p>
 *
 * <p><strong>Limitações:</strong> requer {@code com.sun.mail:jakarta.mail:2.0.1}
 * e {@code io.github.cdimascio:dotenv-java:3.2.0}; a classe é detectável
 * (linkável) sem elas — o guard exibe instruções de instalação no primeiro uso.
 * SMTP configurado para Gmail (smtp.gmail.com:587/TLS).</p>
 *
 * <p><strong>Extensões futuras:</strong> hosts SMTP configuráveis, templates
 * com lógica (loops/condicionais) e fila de reenvio são evoluções naturais sem
 * quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see Env
 * @see Task
 * @see StringAPI
 */
public final class EmailAPI {

    /** Coordenadas Maven da dependência Jakarta Mail. */
    private static final String MAIL_COORDINATES = "com.sun.mail:jakarta.mail:2.0.1";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String MAIL_FEATURE = "Envio de E-mails (Jakarta Mail)";

    private EmailAPI() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== CREDENCIAIS (LAZY) ====================

    /**
     * Credenciais do remetente, carregadas do {@code .env} apenas no primeiro uso
     * (evita quebrar o classload da classe quando o dotenv está ausente).
     */
    private static final class Credentials {
        static final String REMETENTE = Env.get().get("EMAIL_KEY");
        static final String SENHA_APP = Env.get().get("EMAIL_PASSWORD");

        static {
            if (REMETENTE == null || SENHA_APP == null) {
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
     * <p><strong>Pós-condições:</strong> future completa com {@code true} se
     * enviado; {@code false} se o endereço não existir ou houver falha SMTP.</p>
     *
     * @param destinatario Endereço de e-mail do destinatário
     * @param assunto      Assunto do e-mail (código aleatório será adicionado)
     * @param corpo        Corpo do e-mail em texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendSimple(String destinatario, String assunto, String corpo) {
        return sendSimple(List.of(destinatario), null, null, assunto, corpo);
    }

    /**
     * Envia um e-mail em formato HTML (assíncrono).
     *
     * @param destinatario Endereço de e-mail do destinatário
     * @param assunto      Assunto do e-mail (código aleatório será adicionado)
     * @param corpoHtml    Corpo do e-mail em HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendHtml(String destinatario, String assunto, String corpoHtml) {
        return sendHtml(List.of(destinatario), null, null, assunto, corpoHtml);
    }

    /**
     * Envia um e-mail simples para múltiplos destinatários (assíncrono).
     *
     * @param destinatarios Lista de e-mails dos destinatários
     * @param assunto       Assunto do e-mail (código aleatório será adicionado)
     * @param corpo         Corpo do e-mail em texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendSimpleToMultiple(List<String> destinatarios, String assunto, String corpo) {
        return sendSimple(destinatarios, null, null, assunto, corpo);
    }

    /**
     * Envia um e-mail HTML para múltiplos destinatários (assíncrono).
     *
     * @param destinatarios Lista de e-mails dos destinatários
     * @param assunto       Assunto do e-mail (código aleatório será adicionado)
     * @param corpoHtml     Corpo do e-mail em HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendHtmlToMultiple(List<String> destinatarios, String assunto, String corpoHtml) {
        return sendHtml(destinatarios, null, null, assunto, corpoHtml);
    }

    // ==================== MÉTODOS AVANÇADOS ====================

    /**
     * Envia um e-mail simples com opções avançadas (CC, BCC).
     *
     * @param destinatarios Lista de destinatários principais (TO)
     * @param cc            Lista de destinatários em cópia (pode ser {@code null})
     * @param bcc           Lista de destinatários em cópia oculta (pode ser {@code null})
     * @param assunto       Assunto do e-mail (código aleatório será adicionado)
     * @param corpo         Corpo do e-mail em texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendSimple(List<String> destinatarios, List<String> cc, List<String> bcc,
                                                         String assunto, String corpo) {
        checkDependencies();
        return MailSupport.send(destinatarios, cc, bcc, assunto, corpo, null, false);
    }

    /**
     * Envia um e-mail HTML com opções avançadas (CC, BCC).
     *
     * @param destinatarios Lista de destinatários principais (TO)
     * @param cc            Lista de destinatários em cópia (pode ser {@code null})
     * @param bcc           Lista de destinatários em cópia oculta (pode ser {@code null})
     * @param assunto       Assunto do e-mail (código aleatório será adicionado)
     * @param corpoHtml     Corpo do e-mail em HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendHtml(List<String> destinatarios, List<String> cc, List<String> bcc,
                                                       String assunto, String corpoHtml) {
        checkDependencies();
        return MailSupport.send(destinatarios, cc, bcc, assunto, corpoHtml, null, true);
    }

    /**
     * Envia um e-mail com anexos.
     *
     * @param destinatario E-mail do destinatário
     * @param assunto      Assunto do e-mail (código aleatório será adicionado)
     * @param corpo        Corpo do e-mail (texto ou HTML)
     * @param anexos       Lista de arquivos a anexar
     * @param isHtml       {@code true} se o corpo for HTML, {@code false} para texto puro
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendWithAttachments(String destinatario, String assunto, String corpo,
                                                                  List<File> anexos, boolean isHtml) {
        return sendWithAttachments(List.of(destinatario), null, null, assunto, corpo, anexos, isHtml);
    }

    /**
     * Envia um e-mail com anexos para múltiplos destinatários.
     *
     * @param destinatarios Lista de destinatários
     * @param cc            Lista de cópia (pode ser {@code null})
     * @param bcc           Lista de cópia oculta (pode ser {@code null})
     * @param assunto       Assunto do e-mail (código aleatório será adicionado)
     * @param corpo         Corpo do e-mail (texto ou HTML)
     * @param anexos        Lista de arquivos a anexar
     * @param isHtml        {@code true} se o corpo for HTML
     * @return {@code CompletableFuture<Boolean>} — {@code true} se enviado
     */
    public static CompletableFuture<Boolean> sendWithAttachments(List<String> destinatarios, List<String> cc,
                                                                  List<String> bcc, String assunto, String corpo,
                                                                  List<File> anexos, boolean isHtml) {
        checkDependencies();
        return MailSupport.send(destinatarios, cc, bcc, assunto, corpo, anexos, isHtml);
    }

    // ==================== MÉTODOS DE TEMPLATE ====================

    /**
     * Carrega um template HTML do classpath e substitui placeholders.
     *
     * <p>Placeholders no formato {@code {{nome}}} são substituídos pelos valores
     * fornecidos no mapa.</p>
     *
     * @param templatePath Caminho do template no classpath (ex: {@code "/emails/welcome.html"})
     * @param placeholders Mapa de placeholders (ex: {@code Map.of("nome", "João")})
     * @return HTML processado com os placeholders substituídos
     * @throws IllegalStateException se o template não for encontrado
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
     * Verifica a presença das dependências do módulo (mensagem clara se ausentes).
     */
    private static void checkDependencies() {
        Dependencies.require("jakarta.mail.Session", MAIL_COORDINATES, MAIL_FEATURE);
    }

    /**
     * Verifica se as credenciais de e-mail estão configuradas no {@code .env}.
     *
     * @return {@code true} se EMAIL_KEY e EMAIL_PASSWORD estiverem configurados
     */
    public static boolean isConfigured() {
        return Credentials.REMETENTE != null && Credentials.SENHA_APP != null
                && !Credentials.REMETENTE.isEmpty() && !Credentials.SENHA_APP.isEmpty();
    }

    // ==================== IMPLEMENTAÇÃO (JAKARTA MAIL — LAZY) ====================

    /**
     * Implementação do envio com Jakarta Mail. Classe separada para manter as
     * referências à biblioteca fora do bytecode da {@link EmailAPI} — a classe
     * pública pode ser vinculada sem o jakarta.mail e o guard exibe a mensagem
     * de instalação correta antes de qualquer uso.
     */
    private static final class MailSupport {

        private static final String SMTP_HOST = "smtp.gmail.com";
        private static final String SMTP_PORT = "587";
        private static final String PROTOCOL = "smtp";

        private MailSupport() {
        }

        static CompletableFuture<Boolean> send(List<String> destinatarios, List<String> cc, List<String> bcc,
                String assunto, String corpo, List<File> anexos, boolean isHtml) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            Task.runAsync(() -> {
                try {
                    MimeMessage message = criarMensagem(destinatarios, cc, bcc, assunto);

                    if (anexos != null && !anexos.isEmpty()) {
                        MimeMultipart multipart = new MimeMultipart();

                        MimeBodyPart bodyPart = new MimeBodyPart();
                        if (isHtml) {
                            bodyPart.setContent(corpo, "text/html; charset=utf-8");
                        } else {
                            bodyPart.setText(corpo);
                        }
                        multipart.addBodyPart(bodyPart);

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
                    } else if (isHtml) {
                        message.setContent(corpo, "text/html; charset=utf-8");
                    } else {
                        message.setText(corpo);
                    }

                    Transport.send(message);
                    Console.debug("E-mail enviado para: %s", destinatarios);
                    future.complete(true);
                } catch (SendFailedException e) {
                    Console.warn("E-mail inválido ou inexistente para %s: %s", destinatarios, e.getMessage());
                    future.complete(false);
                } catch (Exception e) {
                    Console.error("Falha ao enviar e-mail para %s", destinatarios, e);
                    future.complete(false);
                }
            });
            return future;
        }

        private static MimeMessage criarMensagem(List<String> destinatarios, List<String> cc, List<String> bcc,
                String assunto) throws MessagingException {

            if (!isConfigured()) {
                throw new IllegalStateException("Credenciais de e-mail não configuradas. "
                        + "Configure EMAIL_KEY e EMAIL_PASSWORD no arquivo .env");
            }

            Session session = createSession();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(Credentials.REMETENTE));

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

            Console.debug("E-mail criado - Assunto: %s", assuntoComCodigo);

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
                    return new PasswordAuthentication(Credentials.REMETENTE, Credentials.SENHA_APP);
                }
            });
        }
    }
}
