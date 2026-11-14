package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The SQL run on an entity table, always in the unchanged format: INSERT OR REPLACE of id and JSON,
 * reads that return the JSON still as text so conversion happens after the connection is back, and
 * the parsing that sends each custom statement to the reader pool or the write queue.
 *
 * @author Angatu Sistemas
 */
final class RecordSql {

    // The transaction belongs to the library: a stray COMMIT confirmed half of a transaction(), and a
    // stray BEGIN left the only writer connection inside a transaction nobody would ever close.
    private static final Set<String> TRANSACTION_CONTROL = Set.of("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE");

    private RecordSql() {
    }

    static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    // Double-quoted identifier: an entity named after a reserved word (Values -> values) still works.
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static boolean writeRow(Connection conn, String table, String id, String json) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO " + quote(table) + " (id, data) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, json);
            ps.executeUpdate();
            return true;
        }
    }

    static String selectJson(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM " + quote(table) + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("data") : null;
            }
        }
    }

    static boolean exists(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM " + quote(table) + " WHERE id = ? LIMIT 1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    static boolean deleteRow(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + quote(table) + " WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    static int deleteAllRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate("DELETE FROM " + quote(table));
        }
    }

    // Runs the statement and returns the data column of every row, still as text.
    static List<String> dataColumn(Connection conn, String sql, Object[] params) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            if (!ps.execute()) return rows;
            try (ResultSet rs = ps.getResultSet()) {
                int column = columnIndex(rs.getMetaData(), "data");
                if (column == 0) {
                    throw new UnsupportedOperationException(
                            "Consulta customizada deve retornar a coluna 'data' com o JSON do objeto: " + sql);
                }
                while (rs.next()) rows.add(rs.getString(column));
            }
        }
        return rows;
    }

    static boolean isTransactionControl(String command) {
        return TRANSACTION_CONTROL.contains(command);
    }

    // First word after whitespace and SQL comments, upper-cased: a comment in front of a SELECT must
    // neither send it to the write queue nor hide a BEGIN.
    static String firstKeyword(String sql) {
        int i = 0;
        int length = sql.length();
        while (i < length) {
            if (Character.isWhitespace(sql.charAt(i))) {
                i++;
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < length && Character.isLetter(sql.charAt(i))) i++;
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static int columnIndex(ResultSetMetaData meta, String name) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(meta.getColumnLabel(i)) || name.equalsIgnoreCase(meta.getColumnName(i))) return i;
        }
        return 0;
    }
}
