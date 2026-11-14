package br.com.angatusistemas.lib.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.html.HtmlRouteAPI;
import io.javalin.Javalin;

/**
 * Runs the end-to-end application in-process on the real server and drives it over HTTP: seed,
 * concurrent mixed load and every invariant checked through the public routes.
 *
 * @author Angatu Sistemas
 */
class SaveableE2ETest {

    private static final long ACCOUNTS = 20_000;

    @TempDir
    static Path directory;

    private static E2EDriver driver;

    @BeforeAll
    static void startServer() throws InterruptedException {
        Saveable.shutdown();
        System.setProperty("angatu.db", directory.resolve("e2e.db").toString());
        Javalin app = JavalinAPI.setup(0, false, HtmlRouteAPI::registerAllRoutes);
        assertNotNull(app, "server did not start");
        Saveable.createIndex(Account.class, "email");
        driver = new E2EDriver("http://127.0.0.1:" + app.port());
        driver.awaitHealthy(java.time.Duration.ofSeconds(30));
    }

    @AfterAll
    static void stopServer() {
        Javalin app = JavalinAPI.get();
        if (app != null) app.stop();
        Saveable.shutdown();
        System.clearProperty("angatu.db");
    }

    @Test
    void seededAndLoadedDataKeepsEveryInvariantOverHttp() throws Exception {
        driver.seed(ACCOUNTS);
        driver.verify(ACCOUNTS, 0);
        long increments = driver.load(64, 100, 2_000);
        driver.verify(ACCOUNTS, increments);
    }
}
