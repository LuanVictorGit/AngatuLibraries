package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entity tables: the name rule, the one-time creation in the unchanged id/data format, and the set
 * of tables confirmed on disk in this run, which heals itself when a table is dropped from outside.
 *
 * @author Angatu Sistemas
 */
final class TableRegistry {

    // Up to the first space, dot or parenthesis rather than \w, which is ASCII only: the entity Café
    // has the table cafés.
    private static final Pattern MISSING_TABLE = Pattern.compile("no such table: (?:[^\\s.]+\\.)?([^\\s.)]+)");

    // Only what can no longer be undone: created in autocommit, found on disk, or created inside a
    // transaction that committed. Registering before the commit left entities "ready" without a table.
    private static final Set<Class<?>> PREPARED_TABLES = ConcurrentHashMap.newKeySet();

    private static final Pattern VALID_TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private TableRegistry() {
    }

    static String tableName(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz");
        Table fixed = clazz.getAnnotation(Table.class);
        if (fixed != null) {
            String name = fixed.value();
            if (name == null || !VALID_TABLE.matcher(name).matches()) {
                throw new IllegalArgumentException("Nome de tabela inválido em @Table de " + clazz.getName()
                        + ": use letras, números e sublinhado, começando por letra ou sublinhado");
            }
            return name;
        }
        String simpleName = clazz.getSimpleName();
        if (simpleName.isEmpty()) {
            throw new IllegalArgumentException("Classe anônima não pode ser entidade do Saveable: "
                    + "ela não tem nome para virar tabela (" + clazz.getName() + ")");
        }
        String name = simpleName.toLowerCase(Locale.ROOT);
        return name.endsWith("s") ? name : name + "s";
    }

    // Inside a transaction the CREATE TABLE runs on its connection, the only one possible, and stays
    // pending until the commit. Outside, a table that already exists is confirmed by the reader pool
    // without taking the write turn.
    static void prepare(Class<?> type) {
        if (PREPARED_TABLES.contains(type)) return;
        String table = tableName(type);
        Transaction running = Database.currentTransaction();
        if (running != null) {
            if (running.hasCreated(type)) return;
            Database.apply(running, "preparar a tabela " + table, conn -> {
                createTable(conn, table);
                return null;
            });
            running.markCreated(type);
            return;
        }
        if (existsOnDisk(table)) {
            PREPARED_TABLES.add(type);
            return;
        }
        Database.writeAlone("preparar a tabela " + table, conn -> {
            if (!PREPARED_TABLES.contains(type)) createTable(conn, table);
            return null;
        });
        PREPARED_TABLES.add(type);
    }

    static boolean isPrepared(Class<?> type) {
        return PREPARED_TABLES.contains(type);
    }

    static void markPrepared(Collection<Class<?>> types) {
        PREPARED_TABLES.addAll(types);
    }

    static void clear() {
        PREPARED_TABLES.clear();
    }

    // When the database says a table does not exist, the next call creates it again instead of
    // failing until the process restarts.
    static void forgetMissingTable(SQLException e) {
        String message = e.getMessage();
        if (message == null) return;
        Matcher missing = MISSING_TABLE.matcher(message);
        if (!missing.find()) return;
        String table = missing.group(1);
        PREPARED_TABLES.removeIf(type -> tableName(type).equalsIgnoreCase(table));
        Transaction running = Database.currentTransaction();
        if (running != null) running.forget(table);
    }

    private static boolean existsOnDisk(String table) {
        Boolean found = Database.read("verificar a tabela " + table, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
        return Boolean.TRUE.equals(found);
    }

    private static void createTable(Connection conn, String table) throws SQLException {
        RecordSql.execute(conn, "CREATE TABLE IF NOT EXISTS " + RecordSql.quote(table) + " (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
    }
}
