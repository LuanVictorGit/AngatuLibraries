package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.database.TestEntities.Note;

/**
 * Group commit of concurrent single writes: nothing is lost, a write that fails fails alone, and a
 * write that makes SQLite undo the whole group leaves every other write committed as if it ran alone.
 *
 * @author Angatu Sistemas
 */
class SaveableGroupCommitTest extends DatabaseTestSupport {

    @Test
    @DisplayName("2000 gravações concorrentes avulsas: nenhuma se perde")
    void concurrentSavesLoseNothing() throws InterruptedException {
        runConcurrently(Executors::newVirtualThreadPerTaskExecutor, 2_000, 60, n -> new Note("n" + n, "t" + n).save());
        assertEquals(2_000, Saveable.count(Note.class));
        assertEquals("t1234", Saveable.findById(Note.class, "n1234").text);
    }

    @Test
    @DisplayName("gravação recusada por gatilho ABORT falha sozinha; as do mesmo grupo valem")
    void statementFailureFailsOnlyItsOwner() throws InterruptedException {
        assertFailsAlone("ABORT");
    }

    @Test
    @DisplayName("gravação que desfaz a transação inteira falha sozinha; as outras são refeitas e valem")
    void wholeGroupRollbackFailsOnlyTheBreaker() throws InterruptedException {
        assertFailsAlone("ROLLBACK");
    }

    @Test
    @DisplayName("exclusões concorrentes avulsas devolvem o resultado certo para cada dono")
    void concurrentDeletesReportTheirOwnResult() throws InterruptedException {
        for (int i = 0; i < 300; i++) new Note("d" + i, "x").save();
        AtomicInteger removed = new AtomicInteger();
        runConcurrently(Executors::newVirtualThreadPerTaskExecutor, 600, 60, n -> {
            if (Saveable.deleteById(Note.class, "d" + (n % 300))) removed.incrementAndGet();
        });
        assertEquals(300, removed.get(), "cada registro só pode ser excluído uma vez");
        assertEquals(0, Saveable.count(Note.class));
    }

    private void assertFailsAlone(String raise) throws InterruptedException {
        new Note("seed", "ok").save();
        Saveable.query(Note.class, "CREATE TRIGGER refuse_boom BEFORE INSERT ON notes "
                + "WHEN json_extract(NEW.data, '$.text') = 'boom' BEGIN SELECT RAISE(" + raise + ", 'boom recusado'); END");
        AtomicInteger refused = new AtomicInteger();
        runConcurrently(Executors::newVirtualThreadPerTaskExecutor, 400, 60, n -> {
            try {
                new Note("g" + n, n % 10 == 0 ? "boom" : "ok").save();
            } catch (PersistenceException e) {
                assertTrue(n % 10 == 0, "gravação válida falhou: g" + n);
                refused.incrementAndGet();
            }
        });
        assertEquals(40, refused.get());
        assertEquals(1 + 360, Saveable.count(Note.class));
        assertNull(Saveable.findById(Note.class, "g10"));
        assertEquals("ok", Saveable.findById(Note.class, "g11").text);
    }
}
