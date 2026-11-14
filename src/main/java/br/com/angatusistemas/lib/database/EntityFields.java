package br.com.angatusistemas.lib.database;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Reflection over entity classes: the field copy used by reload(), which leaves static and transient
 * fields alone because they never come from the database, and the check for an overridden save().
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

    // A project that overrides save() (to stamp a date, validate, log) must see it called by saveAll().
    static boolean overridesSave(Class<?> type) {
        for (Class<?> current = type; current != null && current != Saveable.class; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod("save");
                return true;
            } catch (NoSuchMethodException e) {
                // not declared here: look at the base class
            }
        }
        return false;
    }
}
