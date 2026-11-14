package br.com.angatusistemas.lib.database;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures Saveable throughput and heap peak on a large table, so every performance change is
 * judged by numbers taken before and after; excluded from the default test run.
 *
 * @author Angatu Sistemas
 */
@Tag("benchmark")
class SaveableBenchmark {

    private static final int ROWS = Integer.getInteger("bench.rows", 200_000);

    @TempDir
    Path directory;

    @BeforeEach
    void open() {
        Saveable.shutdown();
        System.setProperty("angatu.db", directory.resolve("bench.db").toString());
    }

    @AfterEach
    void close() {
        Saveable.shutdown();
        System.clearProperty("angatu.db");
    }

    @Test
    void measure() throws Exception {
        Saveable.createIndex(BenchRow.class, "email");
        measure("save sequential", 20_000, () -> {
            for (int i = 0; i < 20_000; i++) new BenchRow(null, "seq" + i + "@b.test", i).save();
        });
        measure("saveAll", ROWS, () -> {
            for (int from = 0; from < ROWS; from += 10_000) {
                List<BenchRow> batch = new ArrayList<>(10_000);
                for (int i = from; i < from + 10_000 && i < ROWS; i++) batch.add(new BenchRow(id(i), "u" + i + "@b.test", i));
                Saveable.saveAll(batch);
            }
        });
        measure("save concurrent 64x500", 32_000, () -> concurrently(64, 500, (t, i) -> new BenchRow(null, "c" + t + "-" + i + "@b.test", i).save()));
        measure("mutate concurrent 64x200", 12_800, () -> concurrently(64, 200,
                (t, i) -> Saveable.mutate(BenchRow.class, id(ThreadLocalRandom.current().nextInt(ROWS)), BenchRow::touch)));
        measure("findById random", 50_000, () -> {
            for (int i = 0; i < 50_000; i++) Saveable.findById(BenchRow.class, id(ThreadLocalRandom.current().nextInt(ROWS)));
        });
        measure("findById concurrent 64x1000", 64_000, () -> concurrently(64, 1_000,
                (t, i) -> Saveable.findById(BenchRow.class, id(ThreadLocalRandom.current().nextInt(ROWS)))));
        measure("findFirstByField indexed", 10_000, () -> {
            for (int i = 0; i < 10_000; i++) {
                Saveable.findFirstByField(BenchRow.class, "email", "u" + ThreadLocalRandom.current().nextInt(ROWS) + "@b.test");
            }
        });
        measure("findByPredicate full scan", ROWS, () -> Saveable.findByPredicate(BenchRow.class, row -> row.getScore() % 1000 == 0));
        measure("findAll", ROWS, () -> Saveable.findAll(BenchRow.class));
    }

    private static String id(int n) {
        return String.format("row-%08d", n);
    }

    private interface Body {
        void run() throws Exception;
    }

    private interface Op {
        void run(int thread, int index);
    }

    private static void concurrently(int threads, int perThread, Op op) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < perThread; i++) op.run(thread, i);
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get();
        }
    }

    private static void measure(String name, long ops, Body body) throws Exception {
        System.gc();
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
        long base = heap.stream().mapToLong(pool -> pool.getUsage().getUsed()).sum();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        long started = System.nanoTime();
        body.run();
        long ms = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        long peak = heap.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        System.out.printf("BENCH | %-30s | %8d ms | %10d ops/s | heap peak +%d MiB%n",
                name, ms, ops * 1000 / ms, Math.max(0, peak - base) / (1024 * 1024));
    }

    /**
     * Benchmark row with an indexed e-mail and a numeric score.
     *
     * @author Angatu Sistemas
     */
    public static class BenchRow extends Saveable {
        private String id;
        private String email;
        private long score;
        private long touches;

        public BenchRow() {
        }

        BenchRow(String id, String email, long score) {
            this.id = id;
            this.email = email;
            this.score = score;
        }

        @Override
        public String getId() {
            return id;
        }

        long getScore() {
            return score;
        }

        void touch() {
            touches++;
        }
    }
}
