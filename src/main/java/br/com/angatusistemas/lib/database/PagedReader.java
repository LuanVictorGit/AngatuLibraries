package br.com.angatusistemas.lib.database;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.gson.Gson;

import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * Walks a table in keyset pages over the primary key, spending CPU on one indexed seek per page
 * instead of RAM on the whole table; the connection goes back to the pool before any project code
 * sees a page, so a callback that queries the database never holds two connections.
 *
 * @author Angatu Sistemas
 */
final class PagedReader {

    static final int SCAN_PAGE = 500;
    static final int MAX_PAGE = 1_000;

    private PagedReader() {
    }

    static <T> List<T> page(Class<T> clazz, String afterId, int limit) {
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        return fromJson(readPage(clazz, afterId, size).rows(), clazz);
    }

    // Not a snapshot: a record written while the walk runs is seen if its id is still ahead of the
    // cursor; no record is visited twice and none that existed the whole time is skipped.
    static <T> void forEach(Class<T> clazz, Consumer<? super T> action) {
        Gson gson = GsonAPI.get();
        String after = null;
        while (true) {
            RecordSql.RowPage page = readPage(clazz, after, SCAN_PAGE);
            for (String json : page.rows()) action.accept(gson.fromJson(json, clazz));
            if (page.rows().size() < SCAN_PAGE) return;
            after = page.lastId();
        }
    }

    static <T> List<T> filter(Class<T> clazz, Predicate<T> predicate) {
        List<T> result = new ArrayList<>();
        forEach(clazz, obj -> {
            if (predicate.test(obj)) result.add(obj);
        });
        return result;
    }

    private static RecordSql.RowPage readPage(Class<?> clazz, String afterId, int size) {
        String table = TableRegistry.tableName(clazz);
        TableRegistry.prepare(clazz);
        return Database.read("percorrer " + clazz.getSimpleName(), conn -> RecordSql.page(conn, table, afterId, size));
    }

    // Each text is released as soon as it becomes an object: on a large result both never coexist whole.
    static <T> List<T> fromJson(List<String> rows, Class<T> clazz) {
        Gson gson = GsonAPI.get();
        List<T> objects = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            objects.add(gson.fromJson(rows.get(i), clazz));
            rows.set(i, null);
        }
        return objects;
    }
}
