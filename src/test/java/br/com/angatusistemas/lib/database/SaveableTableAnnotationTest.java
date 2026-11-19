package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.annotations.SerializedName;

/**
 * {@code @Table} keeps an entity's data where it is when the class is renamed.
 *
 * <p>Simulates the rename a project does: records written by the old class are read, queried,
 * changed and indexed through the new class, which names the old table and the old keys. Nothing is
 * copied, and the old index is reused instead of duplicated.</p>
 *
 * @author Angatu Sistemas
 */
class SaveableTableAnnotationTest extends DatabaseTestSupport {

    /** The class as it was: table derived from the name, Portuguese field names. */
    public static class Pessoa extends Saveable {
        private String id;
        String nome;
        String email;

        public Pessoa() {
        }

        Pessoa(String id, String nome, String email) {
            this.id = id;
            this.nome = nome;
            this.email = email;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** The same records after the rename: new class and field names, old table and keys. */
    @Table("pessoas")
    public static class Person extends Saveable {
        private String id;
        @SerializedName("nome")
        String name;
        String email;

        public Person() {
        }

        @Override
        public String getId() {
            return id;
        }
    }

    @Table("tabela inválida")
    public static class BadName extends Saveable {
        private String id;

        @Override
        public String getId() {
            return id;
        }
    }

    @Test
    @DisplayName("a classe renomeada lê, consulta e altera os registros gravados pela antiga")
    void renamedClassKeepsReadingTheOldTable() {
        assertTrue(new Pessoa("p1", "Ana", "ana@x.com").save());
        assertTrue(new Pessoa("p2", "Bia", "bia@x.com").save());

        assertEquals("pessoas", Saveable.tableName(Person.class));
        assertEquals(2, Saveable.count(Person.class));
        assertEquals("Ana", Saveable.findById(Person.class, "p1").name);
        assertEquals("p2", Saveable.findFirstByField(Person.class, "name", "Bia").getId());

        Saveable.mutate(Person.class, "p1", p -> p.name = "Ana Maria");
        Pessoa old = Saveable.findById(Pessoa.class, "p1");
        assertNotNull(old);
        assertEquals("Ana Maria", old.nome);
        assertEquals("ana@x.com", old.email);
    }

    @Test
    @DisplayName("o índice do campo renomeado é o mesmo da classe antiga, sem duplicata")
    void renamedFieldReusesTheOldIndex() throws Exception {
        new Pessoa("p1", "Ana", "ana@x.com").save();
        assertTrue(Saveable.createIndex(Pessoa.class, "nome"));
        assertTrue(Saveable.createIndex(Person.class, "name"));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'pessoas'"
                             + " AND name NOT LIKE 'sqlite_autoindex_%'");
             ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                count++;
                assertEquals("idx_pessoas_nome", rs.getString(1));
            }
            assertEquals(1, count);
        }
    }

    @Test
    @DisplayName("nome de tabela com caractere fora do permitido é recusado")
    void invalidTableNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Saveable.tableName(BadName.class));
        assertFalse(isRegistered(BadName.class));
    }
}
