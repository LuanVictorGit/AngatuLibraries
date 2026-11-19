package br.com.angatusistemas.lib.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fixes the table an entity is stored in, instead of the name derived from the class.
 *
 * <p>Without it the table is the simple class name in lower case plus "s" ({@code Usuario} →
 * {@code usuarios}), so renaming the class silently points it at a new, empty table and the stored
 * records stop being read. A project that renames an entity keeps its data where it is by naming the
 * old table here: {@code @Table("usuarios") public class User extends Saveable}. Renamed fields keep
 * their stored key the same way, with Gson's {@code @SerializedName}.</p>
 *
 * <p>The value is used as written: letters, digits and underscore, starting with a letter or
 * underscore. Anything else is refused when the table is first resolved.</p>
 *
 * @author Angatu Sistemas
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {

    String value();
}
