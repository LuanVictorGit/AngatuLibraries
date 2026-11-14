package br.com.angatusistemas.lib.database;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Reflective field copy used by reload(): every persisted field of the class and its base classes,
 * leaving static and transient fields as they are because they never come from the database.
 *
 * @author Angatu Sistemas
 */
final class EntityFields {

    private EntityFields() {
    }

    static void copy(Object from, Object to) {
        for (Class<?> type = from.getClass(); type != null && type != Saveable.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                try {
                    field.setAccessible(true);
                    field.set(to, field.get(from));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // a field that refuses writes (final, for one) keeps its current value
                }
            }
        }
    }
}
