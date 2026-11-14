package br.com.angatusistemas.lib.e2e;

import java.util.ArrayList;
import java.util.List;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.gson.GsonAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

/**
 * HTTP surface of the end-to-end application: every route a real project would build on Saveable,
 * driven only over HTTP so the same compiled classes run against the old and the new library.
 *
 * @author Angatu Sistemas
 */
final class E2ERoutes {

    static final long INITIAL_BALANCE = 1_000;
    static final int NOTE_EVERY = 1_000;
    private static final int PAGE = 1_000;

    private E2ERoutes() {
    }

    static String accountId(long n) {
        return String.format("acc-%08d", n);
    }

    static String email(long n) {
        return "user" + n + "@e2e.test";
    }

    public static class HealthRoute extends Route {
        public HealthRoute() {
            super("/e2e/health", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }

    public static class SeedRoute extends Route {
        public SeedRoute() {
            super("/e2e/seed", RouteType.POST, E2ERoutes::seed);
        }
    }

    public static class AccountRoute extends Route {
        public AccountRoute() {
            super("/e2e/accounts/{id}", RouteType.GET, ctx -> json(ctx, require(Saveable.findById(Account.class, ctx.pathParam("id")))));
        }
    }

    public static class ByEmailRoute extends Route {
        public ByEmailRoute() {
            super("/e2e/by-email", RouteType.GET,
                    ctx -> json(ctx, require(Saveable.findFirstByField(Account.class, "email", ctx.queryParam("email")))));
        }
    }

    public static class IncrementRoute extends Route {
        public IncrementRoute() {
            super("/e2e/accounts/{id}/increment", RouteType.POST,
                    ctx -> json(ctx, require(Saveable.mutate(Account.class, ctx.pathParam("id"), Account::increment))));
        }
    }

    public static class TransferRoute extends Route {
        public TransferRoute() {
            super("/e2e/transfer", RouteType.POST, E2ERoutes::transfer);
        }
    }

    public static class NoteCreateRoute extends Route {
        public NoteCreateRoute() {
            super("/e2e/notes", RouteType.POST, ctx -> {
                Note note = new Note(ctx.body());
                note.save();
                json(ctx, note);
            });
        }
    }

    public static class NoteRoute extends Route {
        public NoteRoute() {
            super("/e2e/notes/{id}", RouteType.GET, ctx -> json(ctx, require(Saveable.findById(Note.class, ctx.pathParam("id")))));
        }
    }

    public static class SummaryRoute extends Route {
        public SummaryRoute() {
            super("/e2e/summary", RouteType.GET, E2ERoutes::summary);
        }
    }

    public static class StopRoute extends Route {
        public StopRoute() {
            super("/e2e/stop", RouteType.POST, ctx -> {
                ctx.result("stopping");
                Thread.ofPlatform().start(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.exit(0);
                });
            });
        }
    }

    private static void seed(Context ctx) {
        long from = longParam(ctx, "from");
        int count = (int) longParam(ctx, "count");
        List<Saveable> batch = new ArrayList<>(count + count / NOTE_EVERY + 1);
        for (long n = from; n < from + count; n++) {
            batch.add(new Account(accountId(n), email(n), INITIAL_BALANCE));
            if (n % NOTE_EVERY == 0) batch.add(new Note("seed " + n));
        }
        ctx.result(Integer.toString(Saveable.saveAll(batch)));
    }

    private static void transfer(Context ctx) {
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        long amount = longParam(ctx, "amount");
        if (from == null || to == null || from.equals(to) || amount <= 0) throw new BadRequestResponse("invalid transfer");
        Saveable.transaction(() -> {
            require(Saveable.mutate(Account.class, from, account -> {
                if (account.getBalance() < amount) throw new ConflictResponse("insufficient balance");
                account.addBalance(-amount);
            }));
            require(Saveable.mutate(Account.class, to, account -> account.addBalance(amount)));
        });
        ctx.result("ok");
    }

    private static void summary(Context ctx) {
        long balance = 0;
        long counter = 0;
        long scanned = 0;
        String table = Saveable.tableName(Account.class);
        String after = "";
        while (true) {
            List<Account> page = Saveable.query(Account.class,
                    "SELECT data FROM " + table + " WHERE id > ? ORDER BY id LIMIT " + PAGE, after);
            for (Account account : page) {
                balance += account.getBalance();
                counter += account.getCounter();
            }
            scanned += page.size();
            if (page.size() < PAGE) break;
            after = page.get(page.size() - 1).getId();
        }
        ctx.contentType("application/json").result("{\"accounts\":" + Saveable.count(Account.class)
                + ",\"scanned\":" + scanned + ",\"balance\":" + balance + ",\"counter\":" + counter
                + ",\"notes\":" + Saveable.count(Note.class) + "}");
    }

    private static <T> T require(T value) {
        if (value == null) throw new NotFoundResponse("not found");
        return value;
    }

    private static long longParam(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.queryParam(name));
        } catch (NumberFormatException e) {
            throw new BadRequestResponse("invalid " + name);
        }
    }

    private static void json(Context ctx, Object value) {
        ctx.contentType("application/json").result(GsonAPI.get().toJson(value));
    }
}
