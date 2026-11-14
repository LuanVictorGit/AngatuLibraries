package br.com.angatusistemas.lib.database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.gson.Gson;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * SQLite persistence for entities that extend this class: every read goes to the database and returns
 * a fresh instance, every change exists only after save() or mutate(), one table per class in the
 * unchanged id TEXT PRIMARY KEY / data TEXT format written with INSERT OR REPLACE, one fair write turn
 * for the process and a parallel query_only reader pool in WAL mode. ANGATU_DB_PATH (or -Dangatu.db)
 * points at the file; ANGATU_DB_POOL_SIZE, ANGATU_DB_CACHE_KIB and ANGATU_DB_BUSY_TIMEOUT_MS tune it.
 *
 * @author Angatu Sistemas
 */
public abstract class Saveable {

    private static final Object[] NO_PARAMS = new Object[0];

    protected Saveable() {
    }

    public abstract String getId();

    public boolean save() {
        Class<?> type = getClass();
        String table = TableRegistry.tableName(type);
        String id = EntityIds.ensureId(this);
        TableRegistry.prepare(type);
        String json = GsonAPI.get().toJson(this);
        Database.write("salvar " + type.getSimpleName() + " id=" + id, conn -> RecordSql.writeRow(conn, table, id, json));
        return true;
    }

    public boolean delete() {
        String id = getId();
        if (id == null) return false;
        return deleteById(getClass(), id);
    }

    public Saveable reload() {
        String id = getId();
        if (id == null) return null;
        Class<?> type = getClass();
        String table = TableRegistry.tableName(type);
        TableRegistry.prepare(type);
        String json = Database.read("recarregar " + type.getSimpleName() + " id=" + id, conn -> RecordSql.selectJson(conn, table, id));
        if (json == null) return null;
        EntityFields.copy(GsonAPI.get().fromJson(json, type), this);
        return this;
    }

    public static String tableName(Class<?> clazz) {
        return TableRegistry.tableName(clazz);
    }

    public static <T> T findById(Class<T> clazz, String id) {
        if (id == null) return null;
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        String json = Database.read("buscar " + clazz.getSimpleName() + " id=" + id, conn -> RecordSql.selectJson(conn, table, id));
        return json == null ? null : GsonAPI.get().fromJson(json, clazz);
    }

    public static <T> List<T> findAll(Class<T> clazz) {
        return query(clazz, "SELECT data FROM " + RecordSql.quote(TableRegistry.tableName(clazz)));
    }

    public static <T> List<T> findByPredicate(Class<T> clazz, Predicate<T> predicate) {
        List<T> result = new ArrayList<>();
        for (T obj : findAll(clazz)) {
            if (predicate.test(obj)) result.add(obj);
        }
        return result;
    }

    public static <T> List<T> findByField(Class<T> clazz, String fieldName, Object value) {
        JsonFieldFilter filter = JsonFieldFilter.of(clazz, fieldName, value);
        if (filter == null) {
            return findByPredicate(clazz, obj -> Objects.equals(JsonFieldFilter.fieldValue(obj, fieldName), value));
        }
        return query(clazz, "SELECT data FROM " + RecordSql.quote(TableRegistry.tableName(clazz)) + " WHERE " + filter.where(),
                filter.params());
    }

    public static <T> T findFirstByField(Class<T> clazz, String fieldName, Object value) {
        JsonFieldFilter filter = JsonFieldFilter.of(clazz, fieldName, value);
        List<T> found = filter == null
                ? findByField(clazz, fieldName, value)
                : query(clazz, "SELECT data FROM " + RecordSql.quote(TableRegistry.tableName(clazz)) + " WHERE " + filter.where()
                        + " LIMIT 1", filter.params());
        return found.isEmpty() ? null : found.get(0);
    }

    public static boolean exists(Class<?> clazz, String id) {
        if (id == null) return false;
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        Boolean found = Database.read("verificar " + clazz.getSimpleName() + " id=" + id, conn -> RecordSql.exists(conn, table, id));
        return Boolean.TRUE.equals(found);
    }

    public static long count(Class<?> clazz) {
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        Long total = Database.read("contar " + clazz.getSimpleName(), conn -> RecordSql.count(conn, table));
        return total == null ? 0L : total;
    }

    // A SELECT runs on the reader pool; anything else is a write in the queue, alone in autocommit
    // outside a transaction. Transaction control belongs to transaction() and is refused here.
    public static <T> List<T> query(Class<T> clazz, String sql, Object... params) {
        Objects.requireNonNull(sql, "sql");
        String command = RecordSql.firstKeyword(sql);
        if (RecordSql.isTransactionControl(command)) {
            throw new IllegalArgumentException(command + " não passa pelo query(): a transação é da biblioteca "
                    + "— use Saveable.transaction(...)");
        }
        TableRegistry.prepare(clazz);
        Object[] args = params == null ? NO_PARAMS : params;
        String what = "consultar " + clazz.getSimpleName();
        List<String> rows = command.equals("SELECT")
                ? Database.read(what, conn -> RecordSql.dataColumn(conn, sql, args))
                : Database.writeAlone(what, conn -> RecordSql.dataColumn(conn, sql, args));
        return fromJson(rows, clazz);
    }

    public static boolean createIndex(Class<?> clazz, String fieldName) {
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        String path = JsonFieldFilter.jsonPath(clazz, fieldName);
        if (path == null) {
            Console.error("Nome de campo inválido para índice: " + fieldName);
            return false;
        }
        String index = "idx_" + table + "_" + fieldName.toLowerCase(Locale.ROOT);
        Database.write("criar índice " + index, conn -> {
            RecordSql.execute(conn, "CREATE INDEX IF NOT EXISTS " + RecordSql.quote(index) + " ON " + RecordSql.quote(table)
                    + "(json_extract(data, '" + path + "'))");
            return null;
        });
        return true;
    }

    // Reads, changes and writes inside one transaction: the block sees the current record, never a
    // copy read earlier, and may not change its id.
    public static <T extends Saveable> T mutate(Class<T> clazz, String id, Consumer<T> change) {
        if (id == null || change == null) return null;
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        String what = "alterar " + clazz.getSimpleName() + " id=" + id;
        return computeInTransaction(() -> {
            String json = Database.read(what, conn -> RecordSql.selectJson(conn, table, id));
            if (json == null) return null;
            Gson gson = GsonAPI.get();
            T obj = gson.fromJson(json, clazz);
            String before = obj.getId();
            change.accept(obj);
            if (!Objects.equals(before, obj.getId())) {
                throw new IllegalStateException("O bloco do mutate não pode trocar o ID do registro (" + what + ")");
            }
            String updated = gson.toJson(obj);
            Database.write(what, conn -> RecordSql.writeRow(conn, table, id, updated));
            return obj;
        });
    }

    public static int saveAll(Collection<? extends Saveable> objects) {
        if (objects == null || objects.isEmpty()) return 0;
        return computeInTransaction(() -> {
            int saved = 0;
            for (Saveable obj : objects) {
                if (obj != null && obj.save()) saved++;
            }
            return saved;
        });
    }

    public static boolean deleteById(Class<?> clazz, String id) {
        if (id == null) return false;
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        Boolean deleted = Database.write("excluir " + clazz.getSimpleName() + " id=" + id, conn -> RecordSql.deleteRow(conn, table, id));
        return Boolean.TRUE.equals(deleted);
    }

    public static int deleteAll(Class<?> clazz) {
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        Integer deleted = Database.write("excluir todos os " + clazz.getSimpleName(), conn -> RecordSql.deleteAllRows(conn, table));
        return deleted == null ? 0 : deleted;
    }

    public static void transaction(Runnable actions) {
        Objects.requireNonNull(actions, "actions");
        computeInTransaction(() -> {
            actions.run();
            return null;
        });
    }

    public static <T> T computeInTransaction(Supplier<T> actions) {
        Objects.requireNonNull(actions, "actions");
        return Database.inTransaction(actions);
    }

    public static void shutdown() {
        Database.shutdown();
    }

    public static String databasePath() {
        return DatabaseSettings.databasePath();
    }

    // Each text is released as soon as it becomes an object: on a large table both never coexist whole.
    private static <T> List<T> fromJson(List<String> rows, Class<T> clazz) {
        Gson gson = GsonAPI.get();
        List<T> objects = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            objects.add(gson.fromJson(rows.get(i), clazz));
            rows.set(i, null);
        }
        return objects;
    }
}
