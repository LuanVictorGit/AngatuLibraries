package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.database.TestEntities.Note;

/**
 * Batched saveAll(), time-ordered generated ids, keyset paging (forEach, findPage, findByPredicate)
 * and composite indexes, each checked against the behaviour of the unbatched, unpaged original.
 *
 * @author Angatu Sistemas
 */
class SaveableBatchAndPagingTest extends DatabaseTestSupport {

    @Test
    @DisplayName("saveAll grava em lote, conta igual e o último repetido vence")
    void saveAllWritesEveryRowAndTheLastDuplicateWins() {
        List<Saveable> batch = new ArrayList<>();
        for (int i = 0; i < 1_300; i++) batch.add(new Row(id(i), i));
        batch.add(null);
        batch.add(new Row(id(5), 999));
        assertEquals(1_301, Saveable.saveAll(batch));
        assertEquals(1_300, Saveable.count(Row.class));
        assertEquals(999, Saveable.findById(Row.class, id(5)).score);
    }

    @Test
    @DisplayName("saveAll ainda chama o save() sobrescrito, na ordem, e ele enxerga o que veio antes")
    void saveAllCallsTheOverriddenSaveInOrder() {
        Stamped stamped = new Stamped();
        List<Saveable> batch = List.of(new Row(id(1), 1), new Row(id(2), 2), stamped, new Row(id(3), 3));
        assertEquals(4, Saveable.saveAll(batch));
        assertTrue(stamped.stampedAt > 0, "o save() sobrescrito não rodou");
        assertEquals(2, stamped.rowsSeenBefore, "o save() sobrescrito não enxergou o que veio antes dele");
        assertTrue(Saveable.findById(Stamped.class, stamped.getId()).stampedAt > 0);
    }

    @Test
    @DisplayName("saveAll em lote continua tudo ou nada quando um save() sobrescrito falha no meio")
    void saveAllWithAFailingOverrideWritesNothing() {
        List<Saveable> batch = new ArrayList<>();
        for (int i = 0; i < 600; i++) batch.add(new Row(id(i), i));
        batch.add(new Exploding());
        batch.add(new Row(id(900), 900));
        assertThrows(IllegalStateException.class, () -> Saveable.saveAll(batch));
        assertEquals(0, Saveable.count(Row.class));
    }

    @Test
    @DisplayName("ID gerado é UUIDv7 em texto de 36 caracteres, crescente no tempo, e ID existente nunca muda")
    void generatedIdsAreTimeOrderedUuidV7() throws InterruptedException {
        Note first = new Note(null, "a");
        first.save();
        Thread.sleep(3);
        Note second = new Note(null, "b");
        second.save();
        UUID parsed = UUID.fromString(first.getId());
        assertEquals(36, first.getId().length());
        assertEquals(7, parsed.version());
        assertEquals(2, parsed.variant());
        assertTrue(first.getId().compareTo(second.getId()) < 0, "IDs fora da ordem de criação");
        Note kept = new Note("meu-id", "c");
        kept.save();
        assertEquals("meu-id", kept.getId());
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < 10_000; i++) assertTrue(unique.add(EntityIds.newId()), "ID repetido");
    }

    @Test
    @DisplayName("findByPredicate em páginas devolve o mesmo que o filtro sobre o findAll")
    void pagedPredicateMatchesTheFullScan() {
        seed(1_234);
        List<Row> paged = Saveable.findByPredicate(Row.class, row -> row.score % 7 == 0);
        List<Row> full = Saveable.findAll(Row.class).stream().filter(row -> row.score % 7 == 0).toList();
        assertEquals(full.size(), paged.size());
        assertEquals(ids(full), ids(paged));
    }

    @Test
    @DisplayName("forEach visita cada registro uma vez, e o callback pode consultar o banco sem secar o pool")
    void forEachVisitsEveryRecordOnceAndMayQueryInside() throws Exception {
        seed(2_100);
        Set<String> seen = new HashSet<>();
        AtomicInteger nested = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> walks = new ArrayList<>();
            for (int t = 0; t < 40; t++) {
                walks.add(executor.submit(() -> Saveable.forEach(Row.class, row -> {
                    if (Saveable.exists(Row.class, row.getId())) nested.incrementAndGet();
                })));
            }
            for (var walk : walks) walk.get();
        }
        Saveable.forEach(Row.class, row -> assertTrue(seen.add(row.getId()), "visitado duas vezes: " + row.getId()));
        assertEquals(2_100, seen.size());
        assertEquals(40 * 2_100, nested.get());
    }

    @Test
    @DisplayName("findPage anda pela tabela em ordem de id, com teto de 1000 e tabela vazia sem erro")
    void findPageWalksInIdOrder() {
        assertTrue(Saveable.findPage(Row.class, null, 10).isEmpty());
        seed(2_500);
        List<Row> first = Saveable.findPage(Row.class, null, 5_000);
        assertEquals(1_000, first.size());
        assertEquals(id(0), first.get(0).getId());
        List<Row> next = Saveable.findPage(Row.class, first.get(first.size() - 1).getId(), 3);
        assertEquals(List.of(id(1_000), id(1_001), id(1_002)), ids(next));
        assertTrue(Saveable.findPage(Row.class, id(2_499), 10).isEmpty());
        assertEquals(1, Saveable.findPage(Row.class, null, 0).size());
    }

    @Test
    @DisplayName("índice composto é criado, é idempotente e recusa nome de campo inseguro")
    void compositeIndexIsCreatedOnce() throws SQLException {
        assertTrue(Saveable.createIndex(Row.class, "kind", "score"));
        assertTrue(Saveable.createIndex(Row.class, "kind", "score"));
        assertTrue(indexExists("idx_rows_kind_score"));
        assertFalse(Saveable.createIndex(Row.class, "kind", "score; DROP TABLE rows"));
        assertTrue(Saveable.createIndex(Row.class, "kind"));
        assertTrue(indexExists("idx_rows_kind"));
        seed(50);
        assertEquals(25, Saveable.findByField(Row.class, "kind", "even").size());
    }

    @Test
    @DisplayName("findByField com valor objeto ainda cai no filtro em memória, agora em páginas")
    void findByFieldFallbackStillWorks() {
        seed(30);
        assertEquals(1, Saveable.findByField(Row.class, "tags", List.of("t3")).size());
        assertNull(Saveable.findFirstByField(Row.class, "tags", List.of("nenhuma")));
        assertNotNull(Saveable.findFirstByField(Row.class, "tags", List.of("t4")));
    }

    private void seed(int rows) {
        List<Row> batch = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) batch.add(new Row(id(i), i));
        Saveable.saveAll(batch);
    }

    private boolean indexExists(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String id(int n) {
        return String.format("r%06d", n);
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::getId).sorted().toList();
    }

    public static class Row extends Saveable {
        String id;
        long score;
        String kind;
        List<String> tags;

        public Row() {
        }

        Row(String id, long score) {
            this.id = id;
            this.score = score;
            this.kind = score % 2 == 0 ? "even" : "odd";
            this.tags = List.of("t" + score);
        }

        @Override
        public String getId() {
            return id;
        }
    }

    public static class Stamped extends Saveable {
        String id;
        long stampedAt;
        long rowsSeenBefore;

        public Stamped() {
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean save() {
            stampedAt = System.currentTimeMillis();
            rowsSeenBefore = Saveable.count(Row.class);
            return super.save();
        }
    }

    public static class Exploding extends Saveable {
        String id;

        public Exploding() {
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean save() {
            throw new IllegalStateException("falha no save() do projeto");
        }
    }
}
