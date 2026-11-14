package br.com.angatusistemas.lib.database;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;

import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * Field filters resolved inside SQLite with json_extract, comparing values in the exact form Gson
 * writes them, and the reflective read used by the in-memory fallback for values that are objects
 * or lists in the stored JSON.
 *
 * @author Angatu Sistemas
 */
record JsonFieldFilter(String where, Object[] params) {

    static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Object[] NO_PARAMS = new Object[0];

    // null when the value needs the in-memory filter: it becomes an object or list in the JSON, or
    // the field name cannot go into SQL.
    static JsonFieldFilter of(Class<?> clazz, String fieldName, Object value) {
        Gson gson = GsonAPI.get(); // checks the dependency before any Gson type is touched
        String path = jsonPath(clazz, fieldName);
        if (path == null) return null;
        String column = "json_extract(data, '" + path + "')";
        if (value == null) return new JsonFieldFilter(column + " IS NULL", NO_PARAMS);
        Object sqlValue = sqlValue(gson, value);
        return sqlValue == null ? null : new JsonFieldFilter(column + " = ?", new Object[] {sqlValue});
    }

    static String jsonPath(Class<?> clazz, String fieldName) {
        if (fieldName == null || !SAFE_FIELD.matcher(fieldName).matches()) return null;
        String key = jsonKey(clazz, fieldName);
        return SAFE_FIELD.matcher(key).matches() ? "$." + key : null;
    }

    static Object fieldValue(Object obj, String fieldName) {
        Class<?> type = obj.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    // The value as the stored JSON represents it, ready to compare with json_extract: text for text,
    // enum and UUID; exact integer or REAL for numbers; 1/0 for booleans.
    private static Object sqlValue(Gson gson, Object value) {
        JsonElement json;
        try {
            json = gson.toJsonTree(value);
        } catch (RuntimeException notRepresentable) {
            return null; // e.g. NaN, which JSON cannot hold and therefore no record stores
        }
        if (!json.isJsonPrimitive()) return null;
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean() ? 1 : 0;
        if (primitive.isString()) return primitive.getAsString();
        BigDecimal number = primitive.getAsBigDecimal();
        try {
            return number.longValueExact();
        } catch (ArithmeticException notAnInteger) {
            return number.doubleValue();
        }
    }

    private static String jsonKey(Class<?> clazz, String fieldName) {
        Class<?> type = clazz;
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                SerializedName annotation = field.getAnnotation(SerializedName.class);
                return annotation == null ? fieldName : annotation.value();
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return fieldName;
    }
}
