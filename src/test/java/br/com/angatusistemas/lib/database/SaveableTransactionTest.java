package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.database.TestEntities.Account;
import br.com.angatusistemas.lib.database.TestEntities.Customer;
import br.com.angatusistemas.lib.database.TestEntities.Entry;
import br.com.angatusistemas.lib.database.TestEntities.Invoice;
import br.com.angatusistemas.lib.database.TestEntities.NoIdField;
import br.com.angatusistemas.lib.database.TestEntities.Note;
import br.com.angatusistemas.lib.database.TestEntities.Purchase;
import br.com.angatusistemas.lib.database.TestEntities.Status;

/**
 * Transações, {@code mutate}, busca por campo e o ciclo de vida da entidade.
 *
 * @author Angatu Sistemas
 */
class SaveableTransactionTest extends DatabaseTestSupport {

    // ==================== TRANSAÇÕES ====================

    @Test
    @DisplayName("transação que falha não grava nada")
    void failedTransactionWritesNothing() {
        assertThrows(IllegalStateException.class, () -> Saveable.transaction(() -> {
            new Note("a", "1").save();
            new Note("b", "2").save();
            throw new IllegalStateException("falha");
        }));
        assertEquals(0, Saveable.count(Note.class));
    }

    @Test
    @DisplayName("transação interna que falha desfaz só o que ela gravou")
    void innerTransactionIsAllOrNothingOnItsOwn() {
        Saveable.transaction(() -> {
            new Note("antes", "1").save();
            try {
                Saveable.transaction(() -> {
                    new Note("dentro", "2").save();
                    throw new IllegalStateException("falha interna");
                });
            } catch (IllegalStateException expected) {
                // a externa segue
            }
            new Note("depois", "3").save();
        });
        assertNotNull(Saveable.findById(Note.class, "antes"));
        assertNull(Saveable.findById(Note.class, "dentro"));
        assertNotNull(Saveable.findById(Note.class, "depois"));
    }

    @Test
    @DisplayName("leitura dentro da transação enxerga o que ela já gravou; fora dela, só depois do commit")
    void readsInsideTheTransactionSeeItsOwnWrites() {
        Saveable.count(Note.class); // cria a tabela antes, para a leitura de fora não esperar a transação
        Saveable.transaction(() -> {
            new Note("a", "1").save();
            assertTrue(Saveable.exists(Note.class, "a"));
            assertEquals(1, Saveable.count(Note.class));
        });
        assertEquals(1, Saveable.count(Note.class));
    }

    @Test
    @DisplayName("saveAll grava tudo ou nada")
    void saveAllIsAllOrNothing() {
        assertEquals(2, Saveable.saveAll(List.of(new Note("a", "1"), new Note("b", "2"))));
        assertEquals(2, Saveable.count(Note.class));
    }

    // ==================== TRANSAÇÃO DESFEITA PELO PRÓPRIO SQLITE ====================

    @Test
    @DisplayName("comando que faz o SQLite desfazer a transação inteira derruba a transação, sem gravar metade")
    void statementThatAbortsTheWholeTransactionFailsItWithoutHalfWrites() {
        new Note("dup", "existente").save();

        PersistenceException failure = assertThrows(PersistenceException.class, () -> Saveable.transaction(() -> {
            new Note("antes", "1").save();
            try {
                // ON CONFLICT ROLLBACK: o SQLite desfaz a transação inteira, não só o comando.
                Saveable.query(Note.class, "INSERT OR ROLLBACK INTO notes (id, data) VALUES (?, ?)", "dup", "{}");
            } catch (PersistenceException expected) {
                // quem chamou engole a falha e segue, como se só o comando tivesse falhado
            }
            new Note("depois", "3").save();
        }));

        assertTrue(failure.getMessage().startsWith("Transação perdida"), failure.getMessage());
        assertNull(Saveable.findById(Note.class, "antes"));
        assertNull(Saveable.findById(Note.class, "depois"), "o que veio depois da falha foi confirmado sozinho");
        assertEquals(1, Saveable.count(Note.class));
        assertTrue(new Note("seguinte", "4").save(), "a conexão de escrita não se recuperou");
    }

    @Test
    @DisplayName("transação interna que leva a externa junto faz a externa falhar inteira")
    void innerStatementThatAbortsEverythingFailsTheOuterTransaction() {
        new Note("dup", "existente").save();
        new Account("a", 100).save();
        new Account("b", 100).save();

        assertThrows(PersistenceException.class, () -> Saveable.transaction(() -> {
            Saveable.mutate(Account.class, "a", account -> account.balance -= 10);
            try {
                Saveable.transaction(() -> Saveable.query(Note.class,
                        "INSERT OR ROLLBACK INTO notes (id, data) VALUES (?, ?)", "dup", "{}"));
            } catch (PersistenceException expected) {
                // o padrão documentado: a externa captura a falha da interna e segue
            }
            Saveable.mutate(Account.class, "b", account -> account.balance += 10);
        }));

        assertEquals(100, Saveable.findById(Account.class, "a").balance);
        assertEquals(100, Saveable.findById(Account.class, "b").balance, "crédito confirmado sem o débito");
    }

    @Test
    @DisplayName("disco cheio no meio da transação não confirma a outra metade")
    void fullDiskInTheMiddleOfATransactionConfirmsNothing() {
        new Note("grande", "x").save();
        new Account("a", 100).save();
        new Account("b", 100).save();
        long pages = Saveable.query(Account.class,
                "SELECT json_object('id', 'paginas', 'balance', page_count) AS data FROM pragma_page_count")
                .get(0).balance;
        // Disco quase cheio: a conexão de escrita não deixa o arquivo crescer mais que duas páginas.
        // O PRAGMA devolve uma linha sem a coluna data, e o query a recusa — depois de aplicá-lo.
        assertThrows(UnsupportedOperationException.class,
                () -> Saveable.query(Note.class, "PRAGMA max_page_count = " + (pages + 2)));
        String big = "{\"id\":\"grande\",\"text\":\"" + "x".repeat(200_000) + "\"}";

        assertThrows(PersistenceException.class, () -> Saveable.transaction(() -> {
            Saveable.mutate(Account.class, "a", account -> account.balance -= 10);
            try {
                Saveable.transaction(() -> Saveable.query(Note.class,
                        "UPDATE notes SET data = ? WHERE id = ?", big, "grande"));
            } catch (PersistenceException expected) {
                // SQLITE_FULL
            }
            Saveable.mutate(Account.class, "b", account -> account.balance += 10);
        }));

        assertEquals(100, Saveable.findById(Account.class, "a").balance);
        assertEquals(100, Saveable.findById(Account.class, "b").balance, "crédito confirmado sem o débito");
        assertTrue(new Note("seguinte", "y").save(), "a conexão de escrita não se recuperou");
    }

    // ==================== MUTATE ====================

    @Test
    @DisplayName("save dentro do mutate entra na mesma transação, sem pedir segunda conexão")
    void saveInsideMutateJoinsTheTransaction() {
        new Account("conta", 100).save();
        Account result = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                Saveable.mutate(Account.class, "conta", account -> {
                    account.balance -= 30;
                    new Entry("conta", -30).save();
                }));
        assertEquals(70, result.balance);
        assertEquals(70, Saveable.findById(Account.class, "conta").balance);
        assertEquals(1, Saveable.count(Entry.class));
    }

    @Test
    @DisplayName("mutate que lança exceção não grava nada — nem o que o bloco gravou")
    void failingMutateWritesNothing() {
        new Account("conta", 100).save();
        assertThrows(IllegalStateException.class, () -> Saveable.mutate(Account.class, "conta", account -> {
            account.balance = 0;
            new Entry("conta", -100).save();
            throw new IllegalStateException("recusado");
        }));
        assertEquals(100, Saveable.findById(Account.class, "conta").balance);
        assertEquals(0, Saveable.count(Entry.class));
    }

    @Test
    @DisplayName("mutate não pode trocar o ID do registro")
    void mutateCannotChangeTheId() {
        new Account("conta", 100).save();
        assertThrows(IllegalStateException.class, () -> Saveable.mutate(Account.class, "conta", account -> {
            account.id = "outra";
            account.balance = 1;
        }));
        assertEquals(100, Saveable.findById(Account.class, "conta").balance);
        assertNull(Saveable.findById(Account.class, "outra"));
    }

    @Test
    @DisplayName("mutate de registro inexistente devolve null e não grava")
    void mutateOfAMissingRecordReturnsNull() {
        assertNull(Saveable.mutate(Account.class, "nenhuma", account -> account.balance = 1));
        assertEquals(0, Saveable.count(Account.class));
    }

    // ==================== QUERY E CONFIGURAÇÃO ====================

    @Test
    @DisplayName("comando que não é SELECT em query() grava pela fila de escrita")
    void nonSelectQueryGoesThroughTheWriter() {
        new Note("a", "1").save();
        new Note("b", "2").save();

        List<Note> result = Saveable.query(Note.class, "DELETE FROM notes WHERE id = ?", "a");

        assertTrue(result.isEmpty());
        assertEquals(1, Saveable.count(Note.class));
        assertNull(Saveable.findById(Note.class, "a"));
    }

    @Test
    @DisplayName("VACUUM pelo query() roda fora de transação, como sempre rodou")
    void vacuumThroughQueryRunsOutsideATransaction() {
        for (int i = 0; i < 50; i++) new Note("n" + i, "x".repeat(1_000)).save();
        Saveable.deleteAll(Note.class);
        new Note("fica", "1").save();

        assertTrue(Saveable.query(Note.class, "VACUUM").isEmpty());
        assertEquals("1", Saveable.findById(Note.class, "fica").text);
    }

    @Test
    @DisplayName("controle de transação avulso no query() é recusado, dentro e fora de transação")
    void transactionControlThroughQueryIsRejected() {
        new Note("a", "1").save();
        for (String sql : List.of("BEGIN", "  begin immediate", "COMMIT", "END", "ROLLBACK", "SAVEPOINT x",
                "RELEASE x", "/* comentário */ BEGIN", "-- linha\nBEGIN")) {
            assertThrows(IllegalArgumentException.class, () -> Saveable.query(Note.class, sql), sql);
        }
        Saveable.transaction(() -> {
            new Note("b", "2").save();
            assertThrows(IllegalArgumentException.class, () -> Saveable.query(Note.class, "COMMIT"));
            new Note("c", "3").save();
        });

        assertEquals(3, Saveable.count(Note.class));
        assertTrue(new Note("d", "4").save(), "a conexão de escrita ficou presa numa transação aberta");
        assertEquals(4, Saveable.count(Note.class));
    }

    @Test
    @DisplayName("comentário antes do SELECT não tira a consulta do pool de leitura")
    void commentBeforeSelectStillGoesToTheReaders() {
        List<Account> rows = Saveable.query(Account.class, "/* relatório */ -- só leitura\n"
                + "SELECT json_object('id', 'leitor', 'balance', query_only) AS data FROM pragma_query_only");
        assertEquals(1, rows.get(0).balance, "a consulta foi parar na conexão de escrita");
    }

    @Test
    @DisplayName("as configurações do banco chegam de fato às conexões")
    void connectionSettingsAreApplied() {
        // Pela API pública: a consulta devolve o valor do PRAGMA dentro do JSON de uma Account.
        assertEquals(-8192, pragma("cache_size", "cache_size"), "cache_size (KiB, negativo)");
        assertEquals(30000, pragma("busy_timeout", "timeout"), "busy_timeout");
        assertEquals(64L * 1024 * 1024, pragma("journal_size_limit", "journal_size_limit"), "journal_size_limit");
        assertEquals(1, pragma("synchronous", "synchronous"), "synchronous NORMAL");
        assertEquals(2, pragma("temp_store", "temp_store"), "temp_store MEMORY");
        assertEquals(1, pragma("query_only", "query_only"), "leitor em query_only");

        List<Note> mode = Saveable.query(Note.class,
                "SELECT json_object('id', 'modo', 'text', journal_mode) AS data FROM pragma_journal_mode");
        assertEquals("wal", mode.get(0).text);

        // Um comando que não começa por SELECT vai ao escritor, que não é query_only.
        List<Account> writer = Saveable.query(Account.class, "WITH x AS (SELECT 1) "
                + "SELECT json_object('id', 'escritor', 'balance', query_only) AS data FROM pragma_query_only");
        assertEquals(0, writer.get(0).balance, "escritor não pode estar em query_only");
    }

    private static long pragma(String name, String column) {
        List<Account> rows = Saveable.query(Account.class,
                "SELECT json_object('id', 'pragma', 'balance', " + column + ") AS data FROM pragma_" + name);
        return rows.get(0).balance;
    }

    // ==================== ENTIDADE ====================

    @Test
    @DisplayName("ID herdado da classe base é gerado e injetado no campo certo")
    void inheritedIdIsGeneratedAndInjected() {
        Customer customer = new Customer("Ana");
        assertTrue(customer.save());
        assertNotNull(customer.getId());
        assertEquals("Ana", Saveable.findById(Customer.class, customer.getId()).name);

        customer.save(); // a segunda gravação atualiza o mesmo registro
        assertEquals(1, Saveable.count(Customer.class));
    }

    @Test
    @DisplayName("campo boolean terminado em 'id' não é confundido com o ID")
    void booleanFieldEndingInIdIsNotTakenForTheId() {
        Invoice invoice = new Invoice();
        invoice.paid = true;
        assertTrue(invoice.save());
        assertNotNull(invoice.getId());
        assertTrue(Saveable.findById(Invoice.class, invoice.getId()).paid);
    }

    @Test
    @DisplayName("entidade sem campo de ID falha alto, em vez de devolver false em silêncio")
    void entityWithoutAnIdFieldFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> new NoIdField().save());
    }

    @Test
    @DisplayName("reload recarrega os campos herdados e preserva os transient")
    void reloadRefreshesInheritedFieldsAndKeepsTransientOnes() {
        Customer customer = new Customer("Ana");
        customer.save();
        Saveable.mutate(Customer.class, customer.getId(), fresh -> {
            fresh.name = "Bia";
            fresh.version = 7;
        });
        customer.sessionOnly = "sessão";

        assertEquals(customer, customer.reload());
        assertEquals("Bia", customer.name);
        assertEquals(7, customer.version);
        assertEquals("sessão", customer.sessionOnly);
    }

    // ==================== BUSCA POR CAMPO ====================

    @Test
    @DisplayName("busca por campo resolve enum, BigDecimal, número, UUID, booleano e null no SQL")
    void findByFieldResolvesEveryPrimitiveForm() {
        UUID reference = UUID.randomUUID();
        new Purchase("p1", Status.OPEN, new BigDecimal("10.50"), reference, null, true).save();
        new Purchase("p2", Status.PAID, new BigDecimal("7"), UUID.randomUUID(), "obs", false).save();
        assertTrue(Saveable.createIndex(Purchase.class, "status"));

        assertOnly("p2", Saveable.findByField(Purchase.class, "status", Status.PAID));
        assertOnly("p1", Saveable.findByField(Purchase.class, "total", new BigDecimal("10.5")));
        assertOnly("p2", Saveable.findByField(Purchase.class, "total", 7));
        assertOnly("p1", Saveable.findByField(Purchase.class, "reference", reference));
        assertOnly("p1", Saveable.findByField(Purchase.class, "express", true));
        assertOnly("p1", Saveable.findByField(Purchase.class, "note", null));
        assertEquals("p2", Saveable.findFirstByField(Purchase.class, "note", "obs").getId());
        assertNull(Saveable.findFirstByField(Purchase.class, "note", "nenhuma"));
    }

    @Test
    @DisplayName("índice com nome de campo inválido é recusado sem tocar no SQL")
    void createIndexRejectsUnsafeFieldNames() {
        assertFalse(Saveable.createIndex(Note.class, "text'); DROP TABLE notes; --"));
        assertTrue(Saveable.createIndex(Note.class, "text"));
    }

    private static void assertOnly(String id, List<Purchase> found) {
        assertEquals(1, found.size(), "esperado só " + id);
        assertEquals(id, found.get(0).getId());
    }
}
