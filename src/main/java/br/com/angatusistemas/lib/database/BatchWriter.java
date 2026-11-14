package br.com.angatusistemas.lib.database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * Batched saveAll(): consecutive entities of the same table go to SQLite in one prepared statement
 * inside one transaction, a bounded number of rows at a time so memory stays flat, while any entity
 * whose class overrides save() still has that method called, in order.
 *
 * @author Angatu Sistemas
 */
final class BatchWriter {

    // Rows held as JSON text before they go to SQLite: enough to amortise the statement, small enough
    // that a million-row import never holds more than this in RAM.
    private static final int FLUSH_ROWS = 512;

    private final Gson gson = GsonAPI.get();
    private final Map<Class<?>, Boolean> overrides = new HashMap<>();
    private final List<String> ids = new ArrayList<>(FLUSH_ROWS);
    private final List<String> jsons = new ArrayList<>(FLUSH_ROWS);
    private Class<?> pendingType;
    private int saved;

    private BatchWriter() {
    }

    static int saveAll(Collection<? extends Saveable> objects) {
        return Database.inTransaction(() -> {
            BatchWriter batch = new BatchWriter();
            for (Saveable obj : objects) {
                if (obj != null) batch.add(obj);
            }
            batch.flush();
            return batch.saved;
        });
    }

    private void add(Saveable obj) {
        Class<?> type = obj.getClass();
        if (overrides.computeIfAbsent(type, EntityFields::overridesSave)) {
            flush(); // the overridden save() may read what came before it in the collection
            if (obj.save()) saved++;
            return;
        }
        if (type != pendingType || ids.size() == FLUSH_ROWS) flush();
        String id = EntityIds.ensureId(obj);
        TableRegistry.prepare(type);
        pendingType = type;
        ids.add(id);
        jsons.add(gson.toJson(obj));
    }

    private void flush() {
        if (ids.isEmpty()) return;
        String table = TableRegistry.tableName(pendingType);
        String what = "salvar em lote " + pendingType.getSimpleName() + " (" + ids.size() + " registros)";
        Database.write(what, conn -> {
            RecordSql.writeRows(conn, table, ids, jsons);
            return null;
        });
        saved += ids.size();
        ids.clear();
        jsons.clear();
    }
}
