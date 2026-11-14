package br.com.angatusistemas.lib.database;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Entity identifiers: a missing id is generated as a time-ordered UUIDv7 and injected into the field
 * getId() reads, each candidate confirmed through getId() and restored when it was the wrong one.
 *
 * @author Angatu Sistemas
 */
final class EntityIds {

    // SecureRandom keeps the 74 random bits of every id unguessable, as UUID.randomUUID() did.
    private static final SecureRandom RANDOM = new SecureRandom();

    private EntityIds() {
    }

    static String ensureId(Saveable entity) {
        String id = entity.getId();
        if (id != null && !id.isEmpty()) return id;
        String generated = newId();
        inject(entity, generated);
        return generated;
    }

    // UUIDv7 (RFC 9562): the same 36-character text as before, but ordered by creation time, so a
    // new row lands at the end of the primary key B-tree instead of a random page that must be read
    // and split. Existing ids are never touched.
    static String newId() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    // The confirmation exists because a wrong guess did not fail: a String "paid" declared before the
    // id ended in "id", and an id written where getId() could not see it created a new record per save().
    private static void inject(Saveable entity, String generated) {
        for (Field field : candidates(entity.getClass())) {
            if (tryInject(entity, field, generated)) return;
        }
        throw new IllegalStateException(entity.getClass().getName() + " não tem campo de ID que o getId() leia: "
                + "declare um campo \"id\" do tipo String ou UUID e devolva-o em getId()");
    }

    private static boolean tryInject(Saveable entity, Field field, String generated) {
        Object previous;
        try {
            field.setAccessible(true);
            previous = field.get(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // inaccessible field: try the next candidate
        }
        try {
            field.set(entity, field.getType() == UUID.class ? UUID.fromString(generated) : generated);
            if (generated.equals(entity.getId())) return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the field refused the value, or getId() failed with it: restore below
        }
        try {
            field.set(entity, previous);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the same field just accepted a read and a write; restoring it does not fail in practice
        }
        return false;
    }

    // "id" first, then String/UUID fields ending in "id", in the class and its base classes.
    private static List<Field> candidates(Class<?> type) {
        List<Field> exact = new ArrayList<>();
        List<Field> suffixed = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Saveable.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean idType = field.getType() == String.class || field.getType() == UUID.class;
                if (!idType || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.equals("id")) exact.add(field);
                else if (name.endsWith("id")) suffixed.add(field);
            }
        }
        exact.addAll(suffixed);
        return exact;
    }
}
