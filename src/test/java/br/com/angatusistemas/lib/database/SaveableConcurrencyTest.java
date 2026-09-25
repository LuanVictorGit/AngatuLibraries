package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import br.com.angatusistemas.lib.database.TestEntities.Account;
import br.com.angatusistemas.lib.database.TestEntities.Child;
import br.com.angatusistemas.lib.database.TestEntities.Entry;
import br.com.angatusistemas.lib.database.TestEntities.Note;
import br.com.angatusistemas.lib.database.TestEntities.SelfCounting;

/**
 * O {@link Saveable} sob milhares de leituras e gravações simultâneas.
 *
 * <p>Cada cenário roda duas vezes: em threads virtuais (uma por tarefa, milhares ao mesmo
 * tempo) e num pool fixo de 200 threads de plataforma, como o de um servidor web tradicional.
 * Um impasse aparece como estouro de prazo; uma atualização perdida, como soma errada.</p>
 *
 * @author Angatu Sistemas
 */
class SaveableConcurrencyTest extends DatabaseTestSupport {

    static Stream<Arguments> executors() {
        Supplier<ExecutorService> virtual = Executors::newVirtualThreadPerTaskExecutor;
        Supplier<ExecutorService> platform = () -> Executors.newFixedThreadPool(200);
        return Stream.of(
                Arguments.of("threads virtuais", virtual),
                Arguments.of("200 threads de plataforma", platform));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("milhares de mutate no mesmo registro não perdem nenhum incremento")
    void concurrentMutationsLoseNoUpdate(String label, Supplier<ExecutorService> executors) throws Exception {
        new Account("contador", 0).save();
        int tasks = 3_000;

        runConcurrently(executors, tasks, 120,
                i -> Saveable.mutate(Account.class, "contador", account -> account.balance++));

        assertEquals(tasks, Saveable.findById(Account.class, "contador").balance);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("transferências simultâneas preservam o total, com lançamento gravado dentro do mutate")
    void concurrentTransfersPreserveTheTotal(String label, Supplier<ExecutorService> executors) throws Exception {
        int accounts = 20;
        for (int i = 0; i < accounts; i++) new Account("c" + i, 1_000).save();
        int tasks = 3_000;

        runConcurrently(executors, tasks, 120, i -> {
            String from = "c" + (i % accounts);
            String to = "c" + ((i * 7 + 3) % accounts);
            Saveable.transaction(() -> {
                Saveable.mutate(Account.class, from, account -> {
                    account.balance -= 5;
                    new Entry(from, -5).save();
                });
                Saveable.mutate(Account.class, to, account -> {
                    account.balance += 5;
                    new Entry(to, 5).save();
                });
            });
        });

        long total = Saveable.findAll(Account.class).stream().mapToLong(account -> account.balance).sum();
        assertEquals(accounts * 1_000L, total);
        assertEquals(tasks * 2L, Saveable.count(Entry.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("transação interna que falha ao acaso desfaz só a parte dela, sob concorrência")
    void failingInnerTransactionsUndoOnlyTheirOwnPart(String label, Supplier<ExecutorService> executors)
            throws Exception {
        int accounts = 10;
        for (int i = 0; i < accounts; i++) new Account("c" + i, 10_000).save();
        int tasks = 3_000;
        AtomicInteger innerFailures = new AtomicInteger();

        runConcurrently(executors, tasks, 120, i -> {
            String from = "c" + (i % accounts);
            String to = "c" + ((i + 1) % accounts);
            Saveable.transaction(() -> {
                Saveable.mutate(Account.class, from, account -> account.balance -= 5);
                try {
                    Saveable.transaction(() -> {
                        Saveable.mutate(Account.class, to, account -> account.balance += 5);
                        if (i % 3 == 0) throw new IllegalStateException("interna desfaz");
                    });
                } catch (IllegalStateException expected) {
                    innerFailures.incrementAndGet();
                }
            });
        });

        long total = Saveable.findAll(Account.class).stream().mapToLong(account -> account.balance).sum();
        assertEquals(accounts * 10_000L - 5L * innerFailures.get(), total,
                "o crédito de uma interna desfeita sobreviveu, ou o débito da externa se perdeu");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("save dentro de mutate, com saves soltos ao mesmo tempo, não trava ninguém")
    void nestedSavesAlongsidePlainSavesDoNotDeadlock(String label, Supplier<ExecutorService> executors)
            throws Exception {
        for (int i = 0; i < 10; i++) new Account("c" + i, 0).save();
        int tasks = 4_000;

        runConcurrently(executors, tasks, 120, i -> {
            if (i % 2 == 0) {
                Saveable.mutate(Account.class, "c" + (i % 10), account -> {
                    account.balance++;
                    new Entry(account.getId(), 1).save();
                });
            } else {
                new Entry("solto", 1).save();
            }
        });

        assertEquals(tasks, Saveable.count(Entry.class));
        long total = Saveable.findAll(Account.class).stream().mapToLong(account -> account.balance).sum();
        assertEquals(tasks / 2, total);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("milhares de leituras e gravações misturadas terminam sem erro e sem dado perdido")
    void mixedReadsAndWritesLoseNothing(String label, Supplier<ExecutorService> executors) throws Exception {
        for (int i = 0; i < 50; i++) new Account("c" + i, 100).save();
        Saveable.createIndex(Entry.class, "accountId");
        int tasks = 6_000;
        AtomicInteger entries = new AtomicInteger();

        runConcurrently(executors, tasks, 180, i -> {
            String id = "c" + (i % 50);
            switch (i % 6) {
                case 0 -> assertNotNull(Saveable.findById(Account.class, id));
                case 1 -> Saveable.findByField(Entry.class, "accountId", id);
                case 2 -> assertEquals(50, Saveable.count(Account.class));
                case 3 -> {
                    new Entry(id, 1).save();
                    entries.incrementAndGet();
                }
                case 4 -> Saveable.mutate(Account.class, id, account -> account.balance += 1);
                default -> Saveable.query(Account.class,
                        "SELECT data FROM accounts WHERE json_extract(data, '$.balance') > ?", 0);
            }
        });

        assertEquals(entries.get(), Saveable.count(Entry.class));
        long total = Saveable.findAll(Account.class).stream().mapToLong(account -> account.balance).sum();
        assertEquals(50 * 100L + tasks / 6, total);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("primeira vez de uma entidade, disputada e com transações que desfazem, nunca envenena")
    void contestedFirstTouchNeverPoisons(String label, Supplier<ExecutorService> executors) throws Exception {
        int tasks = 1_000;
        AtomicInteger saved = new AtomicInteger();

        runConcurrently(executors, tasks, 120, i -> {
            if (i % 2 == 0) {
                try {
                    Saveable.transaction(() -> {
                        Saveable.count(Child.class);
                        throw new IllegalStateException("desfaz");
                    });
                } catch (IllegalStateException expected) {
                    // a transação desfez, como pedido
                }
            } else {
                new Child("dono").save();
                saved.incrementAndGet();
            }
        });

        assertEquals(saved.get(), Saveable.count(Child.class));
        assertTrue(new Child("depois").save());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    @DisplayName("construtor que consulta o banco, em milhares de leituras simultâneas, não seca o pool")
    void readsTriggeredByDeserializationDoNotExhaustThePool(String label, Supplier<ExecutorService> executors)
            throws Exception {
        new Account("c", 1).save();
        for (int i = 0; i < 20; i++) new SelfCounting("s" + i).save();

        runConcurrently(executors, 2_000, 120,
                i -> assertEquals(20, Saveable.findAll(SelfCounting.class).size()));
    }

    @Test
    @DisplayName("leituras seguem enquanto uma transação longa segura a vez de gravar")
    void readsProceedWhileTheWriterIsBusy() throws Exception {
        new Account("c", 1).save();
        Saveable.count(Entry.class); // a tabela já existe: a leitura não precisa esperar a transação
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = Thread.ofVirtual().start(() -> Saveable.transaction(() -> {
            new Entry("c", 1).save();
            holding.countDown();
            awaitQuietly(release);
        }));
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) assertEquals(1, Saveable.findById(Account.class, "c").balance);
        assertEquals(0, Saveable.count(Entry.class), "o que a transação gravou ainda não confirmou");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        release.countDown();
        writer.join(10_000);
        assertTrue(elapsedMs < 5_000, "leituras esperaram o escritor: " + elapsedMs + " ms");
        assertEquals(1, Saveable.count(Entry.class));
    }

    @Test
    @DisplayName("gravação com pedido de interrupção pendente grava, e o pedido continua na thread")
    void pendingInterruptDoesNotDropTheWrite() {
        new Note("aquece", "1").save(); // tabela e pools prontos: sem disputa nenhuma

        Thread.currentThread().interrupt(); // tarefa cancelada que ainda grava o estado dela
        boolean saved;
        try {
            saved = new Note("estado", "progresso=42").save();
        } finally {
            assertTrue(Thread.interrupted(), "o pedido de interrupção foi engolido");
        }

        assertTrue(saved);
        assertTrue(Saveable.exists(Note.class, "estado"));
    }

    @Test
    @DisplayName("gravação interrompida enquanto espera a vez continua esperando, e grava")
    void interruptWhileWaitingForTheWriterDoesNotDropTheWrite() throws Exception {
        new Account("c", 0).save();
        Saveable.count(Entry.class);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = Thread.ofVirtual().start(() -> Saveable.transaction(() -> {
            Saveable.mutate(Account.class, "c", account -> account.balance++);
            holding.countDown();
            awaitQuietly(release);
        }));
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                new Entry("c", 1).save();
            } catch (Throwable t) {
                failure.set(t);
            }
            interruptKept.set(Thread.interrupted());
        });
        Thread.sleep(200); // na fila, esperando a vez que a transação longa segura
        waiting.interrupt();
        Thread.sleep(100);
        release.countDown();
        writer.join(10_000);
        waiting.join(10_000);

        assertNull(failure.get(), () -> "a gravação desistiu no pedido de interrupção: " + failure.get());
        assertTrue(interruptKept.get(), "o pedido de interrupção foi engolido");
        assertEquals(1, Saveable.count(Entry.class));
        assertEquals(1, Saveable.findById(Account.class, "c").balance);
    }

    @Test
    @DisplayName("shutdown espera a leitura em curso e não deixa conexão aberta para trás")
    void shutdownWaitsForTheReadInFlight() throws Exception {
        for (int i = 0; i < 10; i++) new Account("c" + i, 1).save();
        AtomicLong readEnded = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                Saveable.query(Account.class, "SELECT json_object('id', 'lenta', 'balance', count(*)) AS data FROM "
                        + "(WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 8000000) "
                        + "SELECT x FROM c)");
                readEnded.set(System.nanoTime());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread.sleep(300); // a leitura está rodando numa conexão de leitura

        long shutdownStarted = System.nanoTime();
        Saveable.shutdown();
        reader.join(60_000);

        assertNull(failure.get(), () -> "a leitura em curso falhou no shutdown: " + failure.get());
        assertTrue(readEnded.get() > shutdownStarted, "a leitura terminou antes do shutdown: o teste não mediu nada");
        assertFalse(Files.exists(Path.of(database + "-wal")),
                "o -wal continua no disco: uma conexão ficou aberta depois do shutdown");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
