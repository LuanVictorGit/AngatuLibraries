package br.com.angatusistemas.lib.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O que o envio garante sem rede: o future sempre completa, roda na fila própria do e-mail e um
 * anexo ausente nunca vira {@code true}.
 *
 * <p>Todos os casos falham na checagem dos anexos, que acontece antes de qualquer leitura de
 * credencial ou conexão: nenhum teste daqui consegue enviar e-mail de verdade, mesmo numa máquina
 * com um {@code .env} configurado.</p>
 *
 * @author Angatu Sistemas
 */
class EmailSendResultTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    @Test
    @DisplayName("o future completa com false mesmo quando o envio lança um Error")
    void futureCompletesEvenWhenTheSendThrowsAnError() {
        File exploding = new File("relatorio.pdf") {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isFile() {
                throw new ExceptionInInitializerError("falha simulada");
            }
        };
        CompletableFuture<Boolean> result = EmailAPI.sendWithAttachments("cliente@example.com", "Relatório",
                "Segue o relatório.", List.of(exploding), false);
        assertEquals(Boolean.FALSE, assertTimeoutPreemptively(LIMIT, () -> result.get()));
    }

    @Test
    @DisplayName("o envio roda na fila própria do e-mail, em thread daemon, e não no pool do Task")
    void sendRunsOnTheDedicatedDaemonExecutor() {
        AtomicReference<Thread> worker = new AtomicReference<>();
        File probe = new File("sonda.pdf") {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isFile() {
                worker.set(Thread.currentThread());
                return false;
            }
        };
        CompletableFuture<Boolean> result = EmailAPI.sendWithAttachments("cliente@example.com", "Sonda", "x",
                List.of(probe), false);
        assertEquals(Boolean.FALSE, assertTimeoutPreemptively(LIMIT, () -> result.get()));
        Thread thread = worker.get();
        assertTrue(thread.getName().startsWith("Angatu-Email-"), thread.getName());
        assertTrue(thread.isDaemon());
    }

    @Test
    @DisplayName("anexo inexistente faz o envio falhar, em vez de sair sem o anexo e devolver true")
    void missingAttachmentFailsTheSend() {
        File missing = new File("nao-existe-" + UUID.randomUUID() + ".pdf");
        CompletableFuture<Boolean> result = EmailAPI.sendWithAttachments("cliente@example.com", "Relatório",
                "Segue o relatório.", List.of(missing), false);
        assertEquals(Boolean.FALSE, assertTimeoutPreemptively(LIMIT, () -> result.get()));
    }

    @Test
    @DisplayName("item nulo ou diretório na lista de anexos faz o envio falhar")
    void nullOrDirectoryAttachmentFailsTheSend() {
        CompletableFuture<Boolean> withNull = EmailAPI.sendWithAttachments(List.of("cliente@example.com"), null, null,
                "Relatório", "Segue.", Arrays.asList((File) null), true);
        assertEquals(Boolean.FALSE, assertTimeoutPreemptively(LIMIT, () -> withNull.get()));

        File directory = new File(System.getProperty("java.io.tmpdir"));
        CompletableFuture<Boolean> withDirectory = EmailAPI.sendWithAttachments("cliente@example.com", "Relatório",
                "Segue.", List.of(directory), false);
        assertEquals(Boolean.FALSE, assertTimeoutPreemptively(LIMIT, () -> withDirectory.get()));
    }
}
