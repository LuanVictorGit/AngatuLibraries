package br.com.angatusistemas.lib.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Propriedades SMTP entregues ao Jakarta Mail: TLS verificado de verdade e tempo limite em toda
 * operação de rede. Conferidas sem abrir conexão.
 *
 * @author Angatu Sistemas
 */
class EmailSmtpSettingsTest {

    private final Properties props = EmailAPI.MailSupport.smtpProperties("smtp.gmail.com", 587);

    @Test
    @DisplayName("nenhum host é dispensado da verificação de certificado")
    void noHostIsTrustedWithoutCertificateValidation() {
        assertNull(props.getProperty("mail.smtp.ssl.trust"));
        assertNull(props.get("mail.smtp.ssl.socketFactory"));
        assertNull(props.getProperty("mail.smtp.ssl.socketFactory.class"));
        assertNull(props.getProperty("mail.smtp.socketFactory.class"));
    }

    @Test
    @DisplayName("STARTTLS é obrigatório e o nome no certificado é conferido")
    void startTlsIsRequiredAndServerIdentityIsChecked() {
        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("TLSv1.2 TLSv1.3", props.getProperty("mail.smtp.ssl.protocols"));
        assertEquals("true", props.getProperty("mail.smtp.auth"));
    }

    @Test
    @DisplayName("conexão, leitura e escrita têm tempo limite")
    void everyNetworkOperationHasATimeout() {
        assertEquals("10000", props.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("20000", props.getProperty("mail.smtp.timeout"));
        assertEquals("20000", props.getProperty("mail.smtp.writetimeout"));
    }

    @Test
    @DisplayName("host e porta vêm dos parâmetros")
    void hostAndPortComeFromTheArguments() {
        Properties other = EmailAPI.MailSupport.smtpProperties("127.0.0.1", 2525);
        assertEquals("127.0.0.1", other.getProperty("mail.smtp.host"));
        assertEquals("2525", other.getProperty("mail.smtp.port"));
        assertEquals("true", other.getProperty("mail.smtp.starttls.required"));
        assertTrue(other.getProperty("mail.smtp.ssl.trust") == null, "nenhum host confiável sem verificação");
    }
}
