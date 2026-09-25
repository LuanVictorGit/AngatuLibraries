package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base dos testes de persistência: cada caso recebe um banco novo, num diretório temporário.
 *
 * <p>O {@link Saveable} guarda estado estático — os pools e o registro de tabelas prontas. O
 * {@link Saveable#shutdown()} antes e depois de cada caso garante que um teste nunca herde o
 * banco, nem o registro, do anterior: a "primeira vez" de uma entidade é de fato a primeira em
 * cada caso.</p>
 *
 * @author Angatu Sistemas
 */
abstract class DatabaseTestSupport {

    @TempDir
    Path directory;

    /** Arquivo do banco deste caso. */
    Path database;

    @BeforeEach
    void openFreshDatabase() {
        Saveable.shutdown();
        database = directory.resolve("teste.db");
        System.setProperty("angatu.db", database.toString());
    }

    @AfterEach
    void closeDatabase() {
        Saveable.shutdown();
        System.clearProperty("angatu.db");
    }

    /** A tabela existe no arquivo? Pergunta por uma conexão própria, por fora da biblioteca. */
    boolean tableExists(String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Executa um comando no arquivo por fora da biblioteca — como outro processo faria. */
    void executeOutside(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** A classe está no registro interno de tabelas prontas? Lido por reflexão. */
    @SuppressWarnings("unchecked")
    static boolean isRegistered(Class<?> type) {
        try {
            Field field = Saveable.class.getDeclaredField("PREPARED_TABLES");
            field.setAccessible(true);
            return ((Set<Class<?>>) field.get(null)).contains(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Registro interno de tabelas não encontrado", e);
        }
    }

    /**
     * Dispara as tarefas todas ao mesmo tempo e espera cada uma terminar.
     *
     * <p>Falha se alguma lançar exceção, ou se não terminarem no prazo — que é como um impasse
     * aparece num teste.</p>
     *
     * @param executors      Fábrica do executor (threads virtuais ou pool fixo de plataforma)
     * @param tasks          Quantidade de tarefas
     * @param timeoutSeconds Prazo para todas terminarem
     * @param task           Tarefa, recebendo o número dela
     */
    static void runConcurrently(Supplier<ExecutorService> executors, int tasks, long timeoutSeconds,
            IntConsumer task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = executors.get();
        try {
            for (int i = 0; i < tasks; i++) {
                int n = i;
                executor.execute(() -> {
                    try {
                        start.await();
                        task.accept(n);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                fail("As " + tasks + " tarefas não terminaram em " + timeoutSeconds + " s: impasse ou fila travada");
            }
        } finally {
            executor.shutdownNow();
        }
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError(failures.size() + " de " + tasks
                    + " tarefas falharam; a primeira: " + failures.get(0));
            failures.stream().limit(5).forEach(error::addSuppressed);
            throw error;
        }
    }
}
