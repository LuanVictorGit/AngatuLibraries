package br.com.angatusistemas.lib.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client that drives the end-to-end application like real users: it seeds records, runs a
 * concurrent mixed load and checks every invariant, failing loudly on the first broken one.
 *
 * @author Angatu Sistemas
 */
public final class E2EDriver {

    private static final Pattern NUMBER = Pattern.compile("\"(\\w+)\":(-?\\d+)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final int SEED_CHUNK = 10_000;

    private final String base;

    public E2EDriver(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public static void main(String[] args) throws Exception {
        E2EDriver driver = new E2EDriver(args[0]);
        Path state = Path.of(args[1]);
        long started = System.nanoTime();
        switch (args[2]) {
            case "wait" -> driver.awaitHealthy(Duration.ofSeconds(60));
            case "seed" -> driver.seed(Long.parseLong(args[3]));
            case "load" -> {
                long done = driver.load(Integer.parseInt(args[3]), Integer.parseInt(args[4]), Long.parseLong(args[5]));
                Files.writeString(state, Long.toString(expectedCounter(state) + done));
            }
            case "verify" -> driver.verify(Long.parseLong(args[3]), expectedCounter(state));
            case "stop" -> driver.post("/e2e/stop", "");
            default -> throw new IllegalArgumentException("unknown phase " + args[2]);
        }
        System.out.printf("E2E %s ok in %d ms%n", args[2], (System.nanoTime() - started) / 1_000_000);
    }

    private static long expectedCounter(Path state) throws IOException {
        return Files.exists(state) ? Long.parseLong(Files.readString(state).trim()) : 0;
    }

    public void awaitHealthy(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (send(HttpRequest.newBuilder(uri("/e2e/health")).GET().build()).statusCode() == 200) return;
            } catch (IOException notYet) {
                // server still starting
            }
            Thread.sleep(250);
        }
        throw new AssertionError("server did not become healthy in " + timeout);
    }

    public void seed(long total) throws IOException, InterruptedException {
        for (long from = 0; from < total; from += SEED_CHUNK) {
            long count = Math.min(SEED_CHUNK, total - from);
            HttpResponse<String> response = post("/e2e/seed?from=" + from + "&count=" + count, "");
            expect(response, 200, "seed " + from);
        }
    }

    public long load(int clients, int opsPerClient, long accounts) throws Exception {
        LongAdder increments = new LongAdder();
        List<Future<?>> futures = new ArrayList<>(clients);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < clients; c++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < opsPerClient; i++) runOneOperation(accounts, increments);
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get();
        }
        return increments.sum();
    }

    private void runOneOperation(long accounts, LongAdder increments) throws IOException, InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int roll = random.nextInt(100);
        if (roll < 60) {
            expect(post("/e2e/accounts/" + E2ERoutes.accountId(random.nextLong(accounts)) + "/increment", ""), 200, "increment");
            increments.increment();
        } else if (roll < 90) {
            long from = random.nextLong(accounts);
            long to = (from + 1 + random.nextLong(accounts - 1)) % accounts;
            HttpResponse<String> response = post("/e2e/transfer?from=" + E2ERoutes.accountId(from) + "&to="
                    + E2ERoutes.accountId(to) + "&amount=" + (1 + random.nextInt(50)), "");
            if (response.statusCode() != 200 && response.statusCode() != 409) expect(response, 200, "transfer");
        } else {
            HttpResponse<String> created = post("/e2e/notes", "load note");
            expect(created, 200, "note create");
            String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
            HttpResponse<String> read = get("/e2e/notes/" + id);
            expect(read, 200, "note read");
            if (!read.body().contains("\"savedAt\":") || read.body().contains("\"savedAt\":0")) {
                throw new AssertionError("overridden save() did not run: " + read.body());
            }
        }
    }

    public void verify(long accounts, long expectedCounter) throws IOException, InterruptedException {
        HttpResponse<String> summary = get("/e2e/summary");
        expect(summary, 200, "summary");
        check(number(summary.body(), "accounts") == accounts, "account count", summary.body());
        check(number(summary.body(), "scanned") == accounts, "paged scan", summary.body());
        check(number(summary.body(), "balance") == accounts * E2ERoutes.INITIAL_BALANCE, "balance total", summary.body());
        check(number(summary.body(), "counter") == expectedCounter, "counter total " + expectedCounter, summary.body());
        check(number(summary.body(), "notes") >= (accounts + E2ERoutes.NOTE_EVERY - 1) / E2ERoutes.NOTE_EVERY, "notes", summary.body());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 300; i++) {
            long n = random.nextLong(accounts);
            HttpResponse<String> account = get("/e2e/accounts/" + E2ERoutes.accountId(n));
            expect(account, 200, "account " + n);
            check(account.body().contains("\"email\":\"" + E2ERoutes.email(n) + "\""), "account email " + n, account.body());
        }
        for (int i = 0; i < 30; i++) {
            long n = random.nextLong(accounts);
            HttpResponse<String> found = get("/e2e/by-email?email=" + E2ERoutes.email(n));
            expect(found, 200, "by email " + n);
            check(found.body().contains("\"id\":\"" + E2ERoutes.accountId(n) + "\""), "by email " + n, found.body());
        }
        expect(get("/e2e/accounts/missing"), 404, "missing account");
    }

    private static long number(String json, String key) {
        Matcher matcher = NUMBER.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) return Long.parseLong(matcher.group(2));
        }
        throw new AssertionError("no " + key + " in " + json);
    }

    private static void check(boolean condition, String what, String body) {
        if (!condition) throw new AssertionError("E2E check failed: " + what + " -> " + body);
    }

    private static void expect(HttpResponse<String> response, int status, String what) {
        if (response.statusCode() != status) {
            throw new AssertionError(what + ": expected " + status + ", got " + response.statusCode() + " " + response.body());
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }
}
