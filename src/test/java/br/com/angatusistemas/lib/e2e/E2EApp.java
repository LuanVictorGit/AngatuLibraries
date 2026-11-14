package br.com.angatusistemas.lib.e2e;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.database.Saveable;

/**
 * Entry point of the end-to-end application, started from a plain JAR classpath so one build of it
 * runs against any version of the library, on the JVM or inside a container.
 *
 * @author Angatu Sistemas
 */
public final class E2EApp {

    private E2EApp() {
    }

    public static void main(String[] args) {
        String port = System.getenv("PORT");
        new AngatuLib("localhost", port == null || port.isBlank() ? 7801 : Integer.parseInt(port.trim()), false);
        Saveable.createIndex(Account.class, "email");
    }
}
