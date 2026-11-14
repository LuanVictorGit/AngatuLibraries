package br.com.angatusistemas.lib.database;

/**
 * Database failure raised to the caller instead of a fallback value, so "the database failed" is
 * never mistaken for "there is nothing" and a passing outage never turns into a write of defaults;
 * unchecked on purpose, because a forced throws clause would only breed empty catch blocks.
 *
 * @author Angatu Sistemas
 */
public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
