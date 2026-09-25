package br.com.angatusistemas.lib.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Análise de destinatários do {@link EmailAPI}: cada item da lista é exatamente um endereço —
 * nunca dois, nunca um grupo, nunca um texto com quebra de linha.
 *
 * @author Angatu Sistemas
 */
class EmailRecipientsTest {

    private static InternetAddress[] parse(String... entries) throws AddressException {
        return EmailAPI.MailSupport.parseRecipients(Arrays.asList(entries));
    }

    @Test
    @DisplayName("item com dois endereços separados por vírgula é recusado, e não vira dois destinatários")
    void commaSeparatedEntryIsRejected() {
        assertThrows(AddressException.class, () -> parse("vitima@x.com,atacante@evil.com"));
        assertThrows(AddressException.class, () -> parse("vitima@x.com, Atacante <atacante@evil.com>"));
    }

    @Test
    @DisplayName("dois endereços separados por espaço ou ponto e vírgula também são recusados")
    void otherSeparatorsAreRejected() {
        assertThrows(AddressException.class, () -> parse("vitima@x.com atacante@evil.com"));
        assertThrows(AddressException.class, () -> parse("vitima@x.com;atacante@evil.com"));
    }

    @Test
    @DisplayName("grupo de endereços é recusado (o Jakarta Mail o expandiria em vários destinatários)")
    void groupSyntaxIsRejected() {
        assertThrows(AddressException.class, () -> parse("grupo: vitima@x.com, atacante@evil.com;"));
        assertThrows(AddressException.class, () -> parse("grupo:;"));
    }

    @Test
    @DisplayName("quebra de linha ou caractere de controle no destinatário é recusado antes da análise")
    void lineBreaksAndControlCharactersAreRejected() {
        assertThrows(AddressException.class, () -> parse("vitima@x.com\r\nBcc: atacante@evil.com"));
        assertThrows(AddressException.class, () -> parse("vitima@x.com\nRCPT TO:<atacante@evil.com>"));
        assertThrows(AddressException.class, () -> parse("\"a\r\n b\"@x.com"));
        assertThrows(AddressException.class, () -> parse("a\\\nb@x.com"));
        assertThrows(AddressException.class, () -> parse("a\u0000b@x.com"));
        assertThrows(AddressException.class, () -> parse("a@x.com\u2028"));
    }

    @Test
    @DisplayName("item nulo, vazio ou em branco é recusado")
    void nullOrBlankEntryIsRejected() {
        assertThrows(AddressException.class, () -> parse((String) null));
        assertThrows(AddressException.class, () -> parse(""));
        assertThrows(AddressException.class, () -> parse("   "));
    }

    @Test
    @DisplayName("endereço sem domínio ou malformado é recusado")
    void malformedAddressIsRejected() {
        assertThrows(AddressException.class, () -> parse("semarroba"));
        assertThrows(AddressException.class, () -> parse("a@@x.com"));
        assertThrows(AddressException.class, () -> parse("a@x..com"));
        assertThrows(AddressException.class, () -> parse("<>"));
    }

    @Test
    @DisplayName("endereço simples e endereço com nome viram um destinatário cada, na ordem da lista")
    void validEntriesBecomeOneAddressEach() throws AddressException {
        InternetAddress[] addresses = parse("ana@x.com", "Bruno Silva <bruno@y.com>", "  carla@z.com  ",
                "\"Silva, Dora\" <dora@w.com>");
        assertEquals(4, addresses.length);
        assertEquals("ana@x.com", addresses[0].getAddress());
        assertEquals("bruno@y.com", addresses[1].getAddress());
        assertEquals("Bruno Silva", addresses[1].getPersonal());
        assertEquals("carla@z.com", addresses[2].getAddress());
        assertEquals("dora@w.com", addresses[3].getAddress());
        assertEquals("Silva, Dora", addresses[3].getPersonal());
    }

    @Test
    @DisplayName("lista nula ou vazia não gera destinatários")
    void nullOrEmptyListYieldsNoAddresses() throws AddressException {
        assertEquals(0, EmailAPI.MailSupport.parseRecipients(null).length);
        assertEquals(0, EmailAPI.MailSupport.parseRecipients(List.of()).length);
    }

    @Test
    @DisplayName("um único item inválido recusa a lista inteira")
    void oneInvalidEntryRejectsTheWholeList() {
        assertThrows(AddressException.class, () -> parse("ana@x.com", "vitima@x.com,atacante@evil.com"));
    }

    @Test
    @DisplayName("a mensagem de erro é em português e não reproduz a quebra de linha recebida")
    void errorMessageIsPortugueseAndSingleLine() {
        AddressException e = assertThrows(AddressException.class, () -> parse("a@x.com\r\nBcc: b@y.com"));
        assertTrue(e.getMessage().startsWith("Destinatário com quebra de linha"), e.getMessage());
        assertFalse(e.getMessage().contains("\n"));
        assertFalse(e.getMessage().contains("\r"));
    }

    @Test
    @DisplayName("nome formatado pelo EmailFormatter é aceito como um único destinatário")
    void formatterOutputIsASingleRecipient() throws AddressException {
        String formatted = EmailFormatter.format("Silva, João (financeiro)", "joao@exemplo.com");
        InternetAddress[] addresses = parse(formatted);
        assertEquals(1, addresses.length);
        assertEquals("joao@exemplo.com", addresses[0].getAddress());
        assertEquals("Silva, João (financeiro)", addresses[0].getPersonal());

        String withLineBreak = EmailFormatter.format("Ana\r\nBcc: atacante@evil.com", "ana@x.com");
        InternetAddress[] single = parse(withLineBreak);
        assertEquals(1, single.length);
        assertEquals("ana@x.com", single[0].getAddress());
    }
}
