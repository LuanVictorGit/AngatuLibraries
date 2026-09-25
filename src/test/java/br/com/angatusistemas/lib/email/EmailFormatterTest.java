package br.com.angatusistemas.lib.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validação e formatação de endereços do {@link EmailFormatter}: formato, descartáveis, entradas
 * enormes, locale da JVM e segurança do texto gerado.
 *
 * @author Angatu Sistemas
 */
class EmailFormatterTest {

    // ==================== DESCARTÁVEIS ====================

    @Test
    @DisplayName("espaço nas pontas não esconde domínio descartável")
    void surroundingWhitespaceDoesNotHideADisposableDomain() {
        assertFalse(EmailFormatter.isValidNormal("x@mailinator.com "));
        assertFalse(EmailFormatter.isValidNormal(" x@mailinator.com"));
        assertFalse(EmailFormatter.isValidNormal("\tx@mailinator.com\n"));
        assertEquals("mailinator.com", EmailFormatter.getDomain("x@mailinator.com "));
        assertEquals("x", EmailFormatter.getLocalPart(" x@mailinator.com"));
    }

    @Test
    @DisplayName("subdomínio de serviço descartável também é descartável")
    void disposableSubdomainsAreRejected() {
        assertFalse(EmailFormatter.isValidNormal("x@sub.mailinator.com"));
        assertFalse(EmailFormatter.isValidNormal("x@a.b.c.yopmail.com"));
        assertTrue(EmailFormatter.isDisposableDomain("qualquer.guerrillamail.com"));
        assertTrue(EmailFormatter.isDisposableDomain(" MAILINATOR.COM. "));
    }

    @Test
    @DisplayName("domínio que só se parece com um descartável não é recusado")
    void lookalikeDomainsAreNotDisposable() {
        assertFalse(EmailFormatter.isDisposableDomain("mailinator.com.br.exemplo.com.br"));
        assertTrue(EmailFormatter.isValidNormal("ana@minhaloja-mailinator.com.br"));
        assertFalse(EmailFormatter.isDisposableDomain(null));
        assertFalse(EmailFormatter.isDisposableDomain("   "));
    }

    @Test
    @DisplayName("provedores reais não são tratados como descartáveis")
    void realProvidersAreAccepted() {
        for (String domain : List.of("microsoft.com", "mail.com", "mail.ru", "hushmail.com", "lycos.com",
                "mailfence.com", "gmx.fr", "mail.de", "mm.st", "airpost.net", "gmail.com", "outlook.com")) {
            assertTrue(EmailFormatter.isValidNormal("ana@" + domain), domain);
            assertFalse(EmailFormatter.isDisposableDomain(domain), domain);
        }
    }

    @Test
    @DisplayName("a lista de permitidos é consultada primeiro e sempre vence")
    void allowListIsConsultedFirst() {
        EmailFormatter.addDisposableDomain("gmail.com");
        try {
            assertTrue(EmailFormatter.isValidNormal("ana@gmail.com"));
            assertFalse(EmailFormatter.isDisposableDomain("gmail.com"));
            assertTrue(EmailFormatter.isAllowedDomain(" GMAIL.COM "));
        } finally {
            EmailFormatter.removeDisposableDomain("gmail.com");
        }
    }

    @Test
    @DisplayName("domínio adicionado em tempo de execução é recusado com os subdomínios, e removido volta a valer")
    void runtimeAddAndRemove() {
        String domain = "descartavel-" + UUID.randomUUID().toString().substring(0, 8) + ".com";
        assertTrue(EmailFormatter.isValidNormal("ana@sub." + domain));
        EmailFormatter.addDisposableDomain("  " + domain.toUpperCase(Locale.ROOT) + " ");
        try {
            assertFalse(EmailFormatter.isValidNormal("ana@" + domain));
            assertFalse(EmailFormatter.isValidNormal("ana@sub." + domain));
        } finally {
            EmailFormatter.removeDisposableDomain(domain);
        }
        assertTrue(EmailFormatter.isValidNormal("ana@" + domain));
    }

    @Test
    @DisplayName("alterar a lista de descartáveis enquanto outras threads leem não quebra nem erra a leitura")
    void concurrentChangesWhileReading() throws Exception {
        AtomicBoolean wrong = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int w = 0; w < 3; w++) {
                tasks.add(pool.submit(() -> {
                    for (int i = 0; i < 20_000; i++) {
                        String domain = "tmp-" + UUID.randomUUID() + ".com";
                        EmailFormatter.addDisposableDomain(domain);
                        EmailFormatter.removeDisposableDomain(domain);
                    }
                }));
            }
            for (int r = 0; r < 3; r++) {
                tasks.add(pool.submit(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        if (EmailFormatter.isValidNormal("x@mailinator.com") || !EmailFormatter.isValidNormal("x@gmail.com")) {
                            wrong.set(true);
                        }
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertFalse(wrong.get());
    }

    // ==================== FORMATO ====================

    @Test
    @DisplayName("entrada de 4 KB ou mais devolve false sem estourar a pilha")
    void hugeInputIsRejectedSafely() {
        List<String> inputs = List.of(
                "user@" + "a.".repeat(2_000) + "com",
                "a".repeat(4_000) + "@x.com",
                "a" + ".a".repeat(25_000) + "@b.com",
                "x@" + "a-".repeat(30_000) + "b.com",
                "a".repeat(50_000) + "@" + "a".repeat(50_000));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String input : inputs) {
                assertDoesNotThrow(() -> {
                    assertFalse(EmailFormatter.isValidFormat(input));
                    assertFalse(EmailFormatter.isValidStrict(input));
                    assertFalse(EmailFormatter.isValidNormal(input));
                    assertNull(EmailFormatter.getDomain(input));
                    assertNull(EmailFormatter.mask(input));
                    assertEquals("Formato de e-mail inválido.", EmailFormatter.getValidationErrorMessage(input));
                });
            }
            assertFalse(EmailFormatter.isDisposableDomain("a.".repeat(100_000) + "mailinator.com"));
        });
    }

    @Test
    @DisplayName("limites da RFC: 254 caracteres no total, 64 na parte local, 63 por rótulo")
    void rfcLengthLimits() {
        String local64 = "a".repeat(64);
        String longest = local64 + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com";
        assertEquals(254, longest.length());
        assertTrue(EmailFormatter.isValidFormat(longest));
        assertTrue(EmailFormatter.isValidStrict(longest));

        String tooLong = local64 + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".com";
        assertEquals(255, tooLong.length());
        assertFalse(EmailFormatter.isValidFormat(tooLong));
        assertFalse(EmailFormatter.isValidFormat(local64 + "a@x.com"));
        assertFalse(EmailFormatter.isValidFormat("a@" + "b".repeat(64) + ".com"));
    }

    @Test
    @DisplayName("endereços válidos comuns, inclusive com apóstrofo, são aceitos")
    void commonValidAddresses() {
        for (String email : List.of("o'brien@example.com", "nome.sobrenome+tag@empresa.com.br", "a_b-c@sub-dominio.exemplo.org",
                "USUARIO@EXEMPLO.COM", "usuario@exemplo.xn--p1ai", "x@a1.io", "1234@numeros.com", "a!#$%&*/=?^_`{|}~-z@x.com")) {
            assertTrue(EmailFormatter.isValidFormat(email), email);
        }
    }

    @Test
    @DisplayName("endereços malformados são recusados")
    void malformedAddressesAreRejected() {
        for (String email : Arrays.asList(null, "", "   ", "semarroba", "@exemplo.com", "a@", "a@exemplo", "a@@exemplo.com",
                "a@b@exemplo.com", ".a@exemplo.com", "a.@exemplo.com", "a..b@exemplo.com", "a@-exemplo.com",
                "a@exemplo-.com", "a@exemplo..com", "a@.exemplo.com", "a@exemplo.com.", "a@exemplo.c", "a@exemplo.123",
                "a b@exemplo.com", "a@exem plo.com", "\"a\"@exemplo.com", "a@[127.0.0.1]", "josé@exemplo.com",
                "a<b@exemplo.com", "a,b@exemplo.com", "a@exemplo.com\r\nBcc: b@c.com", "a@exemplo.xn--")) {
            assertFalse(EmailFormatter.isValidFormat(email), String.valueOf(email));
        }
    }

    @Test
    @DisplayName("validação rigorosa usa o conjunto conservador de símbolos")
    void strictValidationIsConservative() {
        assertTrue(EmailFormatter.isValidStrict("nome.sobrenome+tag@empresa.com.br"));
        assertTrue(EmailFormatter.isValidStrict(" nome_1-2@empresa.com "));
        assertFalse(EmailFormatter.isValidStrict("o'brien@example.com"));
        assertFalse(EmailFormatter.isValidStrict("a&b@example.com"));
        assertFalse(EmailFormatter.isValidStrict("a..b@example.com"));
    }

    @Test
    @DisplayName("maiúsculas são tratadas sem depender do locale da JVM (turco)")
    void lowerCaseDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertFalse(EmailFormatter.isValidNormal("X@MAILINATOR.COM"));
            assertEquals("admin@example.com", EmailFormatter.normalize("ADMIN@EXAMPLE.COM"));
            assertEquals("mailinator.com", EmailFormatter.getDomain("X@MAILINATOR.COM"));
            assertTrue(EmailFormatter.isAllowedDomain("GMAIL.COM"));
            assertTrue(EmailFormatter.equalsIgnoreCase("ADMIN@EXAMPLE.COM", "admin@example.com"));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ==================== FORMATAÇÃO E MENSAGENS ====================

    @Test
    @DisplayName("format: nome comum sai como antes; nome com caractere especial sai entre aspas")
    void formatQuotesNamesWithSpecialCharacters() {
        assertEquals("Usuário <usuario@exemplo.com>", EmailFormatter.format("Usuário", " Usuario@Exemplo.com "));
        assertEquals("\"Silva, João\" <joao@exemplo.com>", EmailFormatter.format("Silva, João", "joao@exemplo.com"));
        assertEquals("\"Ana \\\"Aninha\\\" Souza\" <ana@x.com>", EmailFormatter.format("Ana \"Aninha\" Souza", "ana@x.com"));
        assertEquals("\"Dr. Ana\" <ana@x.com>", EmailFormatter.format("Dr. Ana", "ana@x.com"));
        assertEquals("ana@x.com", EmailFormatter.format("   ", "ana@x.com"));
        assertEquals("ana@x.com", EmailFormatter.format(null, "ana@x.com"));
        assertNull(EmailFormatter.format("Ana", null));
    }

    @Test
    @DisplayName("format: quebra de linha no nome vira espaço, sem injeção de cabeçalho")
    void formatRemovesLineBreaksFromTheName() {
        String formatted = EmailFormatter.format("Ana\r\nBcc: atacante@evil.com", "ana@x.com");
        assertFalse(formatted.contains("\r"));
        assertFalse(formatted.contains("\n"));
        assertEquals("\"Ana  Bcc: atacante@evil.com\" <ana@x.com>", formatted);
    }

    @Test
    @DisplayName("mensagens de erro são só em português")
    void validationMessagesArePortugueseOnly() {
        assertEquals("O e-mail não pode estar vazio.", EmailFormatter.getValidationErrorMessage(null));
        assertEquals("O e-mail não pode estar vazio.", EmailFormatter.getValidationErrorMessage("  "));
        assertEquals("Formato de e-mail inválido.", EmailFormatter.getValidationErrorMessage("semarroba"));
        assertEquals("E-mail temporário não é permitido. Use um e-mail permanente.",
                EmailFormatter.getValidationErrorMessage("x@mailinator.com "));
        assertNull(EmailFormatter.getValidationErrorMessage("ana@gmail.com"));
    }

    @Test
    @DisplayName("e-mails gerados são aceitos, como a documentação diz")
    void generatedEmailsAreAccepted() {
        String random = EmailFormatter.generateRandomEmail();
        assertTrue(random.startsWith("test_") && random.endsWith("@example.com"), random);
        assertTrue(EmailFormatter.isValidNormal(random));
        for (int i = 0; i < 300; i++) {
            String normal = EmailFormatter.generateNormalRandomEmail();
            assertTrue(EmailFormatter.isValidNormal(normal), normal);
        }
    }

    @Test
    @DisplayName("máscaras ignoram os espaços nas pontas")
    void maskingUsesTheTrimmedAddress() {
        assertEquals("us***@exemplo.com", EmailFormatter.mask(" usuario@Exemplo.com "));
        assertEquals("***@exemplo.com", EmailFormatter.mask("ab@exemplo.com"));
        assertEquals("u***@exemplo.com", EmailFormatter.maskWithFirstChar("usuario@exemplo.com "));
        assertNull(EmailFormatter.mask("invalido"));
        assertNull(EmailFormatter.maskWithFirstChar(null));
    }

    @Test
    @DisplayName("filterNormal descarta inválidos e descartáveis e normaliza os demais")
    void filterNormalDropsInvalidAndDisposable() {
        List<String> result = EmailFormatter.filterNormal(Arrays.asList(" Ana@Gmail.com ", "x@mailinator.com", null,
                "b@sub.yopmail.com", "o'brien@empresa.com.br"));
        assertEquals(List.of("ana@gmail.com", "o'brien@empresa.com.br"), result);
        assertTrue(EmailFormatter.areAllNormal(List.of("ana@gmail.com", " bia@empresa.com.br")));
        assertFalse(EmailFormatter.areAllNormal(List.of("ana@gmail.com", "x@mailinator.com")));
    }
}
