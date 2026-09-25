package br.com.angatusistemas.lib.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conferência de dependências: resultado em cache correto sob concorrência, exceção a cada
 * chamada sem a dependência e mensagem de instalação no console uma vez só por classe.
 *
 * @author Angatu Sistemas
 */
class DependenciesTest {

    private static String captureOutput(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length())) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("isPresent responde certo para classe presente e ausente, também em cache")
    void isPresentAnswersCorrectly() {
        for (int i = 0; i < 3; i++) {
            assertTrue(Dependencies.isPresent("java.lang.String"));
            assertFalse(Dependencies.isPresent("com.exemplo.naoexiste.Classe"));
        }
    }

    @Test
    @DisplayName("isPresent sob concorrência devolve sempre o mesmo resultado")
    void isPresentIsConsistentUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                String name = i % 2 == 0 ? "java.util.List" : "com.exemplo.naoexiste.Outra";
                boolean expected = i % 2 == 0;
                results.add(pool.submit(() -> Dependencies.isPresent(name) == expected));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("require lança sempre, mas a mensagem vai para o console uma vez só")
    void requireThrowsEveryTimeButPrintsOnce() {
        String missing = "com.exemplo.naoexiste.Require";
        String output = captureOutput(() -> {
            for (int i = 0; i < 5; i++) {
                MissingDependencyException error = assertThrows(MissingDependencyException.class,
                        () -> Dependencies.require(missing, "com.exemplo:naoexiste:1.0.0", "Teste"));
                assertTrue(error.getMessage().contains("com.exemplo:naoexiste:1.0.0"), error.getMessage());
            }
            assertFalse(Dependencies.check(missing, "com.exemplo:naoexiste:1.0.0", "Teste"));
        });
        assertEquals(1, occurrences(output, "Dependência ausente: com.exemplo:naoexiste:1.0.0"), output);
    }

    @Test
    @DisplayName("check devolve false e também exibe a mensagem uma vez só")
    void checkPrintsOnce() {
        String output = captureOutput(() -> {
            for (int i = 0; i < 3; i++) {
                assertFalse(Dependencies.check("com.exemplo.naoexiste.Check", "com.exemplo:check:2.0.0", "Teste"));
            }
            assertTrue(Dependencies.check("java.lang.String", "java:lang:21", "Teste"));
        });
        assertEquals(1, occurrences(output, "Dependência ausente: com.exemplo:check:2.0.0"), output);
    }
}
