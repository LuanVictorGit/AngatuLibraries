package br.com.angatusistemas.lib.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.database.TestEntities.Account;
import br.com.angatusistemas.lib.database.TestEntities.Address;
import br.com.angatusistemas.lib.database.TestEntities.Café;
import br.com.angatusistemas.lib.database.TestEntities.Category;
import br.com.angatusistemas.lib.database.TestEntities.Child;
import br.com.angatusistemas.lib.database.TestEntities.CompanySettings;
import br.com.angatusistemas.lib.database.TestEntities.MerchantAuthState;
import br.com.angatusistemas.lib.database.TestEntities.Note;
import br.com.angatusistemas.lib.database.TestEntities.Values;

/**
 * A tabela criada dentro de uma transação só vale depois do commit.
 *
 * <p>Reproduz o defeito medido no Pedify: a primeira operação numa entidade caía dentro de uma
 * transação que desfazia; o SQLite desfazia o {@code CREATE TABLE} junto, mas a biblioteca já
 * tinha registrado a tabela como pronta — e toda gravação dela falhava com "no such table" até
 * o processo reiniciar.</p>
 *
 * @author Angatu Sistemas
 */
class SaveableTableCreationTest extends DatabaseTestSupport {

    @Test
    @DisplayName("transação que desfaz não deixa a entidade marcada como pronta sem tabela")
    void rolledBackTransactionDoesNotPoisonTheTable() throws Exception {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                Saveable.computeInTransaction(() -> {
                    Saveable.findByField(MerchantAuthState.class, "companyId", "x"); // primeira vez da classe
                    throw new IllegalStateException("falha");
                }));
        assertEquals("falha", failure.getMessage());
        assertFalse(isRegistered(MerchantAuthState.class));
        assertFalse(tableExists("merchantauthstates"));

        assertTrue(new MerchantAuthState("empresa-1").save());
        assertTrue(tableExists("merchantauthstates"));
        assertEquals(1, Saveable.count(MerchantAuthState.class));
    }

    @Test
    @DisplayName("mutate que desfaz não envenena a entidade tocada pela primeira vez no bloco")
    void rolledBackMutateDoesNotPoisonTheTableTouchedInsideTheBlock() throws Exception {
        new Note("dono", "original").save();

        assertThrows(IllegalStateException.class, () -> Saveable.mutate(Note.class, "dono", note -> {
            note.text = "alterado";
            Saveable.findByField(Child.class, "ownerId", note.getId()); // primeira vez de Child, dentro da escrita
            throw new IllegalStateException("falha");
        }));
        assertFalse(isRegistered(Child.class));
        assertFalse(tableExists("childs"));

        assertTrue(new Child("dono").save());
        assertEquals(1, Saveable.findByField(Child.class, "ownerId", "dono").size());
        assertEquals("original", Saveable.findById(Note.class, "dono").text);
    }

    @Test
    @DisplayName("transação só de leitura que confirma continua criando e registrando a tabela")
    void committedReadOnlyTransactionCreatesAndRegistersTheTable() throws Exception {
        Saveable.computeInTransaction(() -> Saveable.count(MerchantAuthState.class));

        assertTrue(tableExists("merchantauthstates"));
        assertTrue(isRegistered(MerchantAuthState.class));
    }

    @Test
    @DisplayName("transação com escrita que confirma continua criando e registrando a tabela")
    void committedWritingTransactionCreatesAndRegistersTheTable() throws Exception {
        Saveable.transaction(() -> new MerchantAuthState("empresa-1").save());

        assertTrue(tableExists("merchantauthstates"));
        assertTrue(isRegistered(MerchantAuthState.class));
        assertEquals(1, Saveable.count(MerchantAuthState.class));
    }

    @Test
    @DisplayName("falha na transação interna que sobe até a externa desfaz tudo, sem envenenar")
    void innerFailurePropagatingToTheOuterDoesNotPoison() throws Exception {
        assertThrows(IllegalStateException.class, () -> Saveable.transaction(() ->
                Saveable.transaction(() -> {
                    Saveable.count(Child.class); // primeira vez, no nível interno
                    throw new IllegalStateException("falha interna");
                })));

        assertFalse(isRegistered(Child.class));
        assertFalse(tableExists("childs"));
        assertTrue(new Child("x").save());
    }

    @Test
    @DisplayName("falha na transação interna capturada pela externa desfaz só a interna, sem envenenar")
    void innerFailureCaughtByTheOuterDoesNotPoison() throws Exception {
        Saveable.transaction(() -> {
            new Note("externa", "vale").save();
            try {
                Saveable.transaction(() -> {
                    Saveable.count(Child.class); // primeira vez, no nível interno
                    throw new IllegalStateException("falha interna");
                });
            } catch (IllegalStateException expected) {
                // a externa segue e confirma
            }
        });

        assertNotNull(Saveable.findById(Note.class, "externa"));
        assertFalse(isRegistered(Child.class)); // o CREATE TABLE da interna foi desfeito com ela
        assertFalse(tableExists("childs"));
        assertTrue(new Child("x").save());
        assertEquals(1, Saveable.count(Child.class));
    }

    @Test
    @DisplayName("tabela criada numa transação interna só é registrada quando a externa confirma")
    void tableCreatedInAnInnerTransactionIsRegisteredOnlyAfterTheOuterCommit() throws Exception {
        Saveable.transaction(() -> {
            Saveable.transaction(() -> new Child("x").save());
            assertFalse(isRegistered(Child.class), "registrada antes do commit da transação externa");
        });

        assertTrue(isRegistered(Child.class));
        assertTrue(tableExists("childs"));
        assertEquals(1, Saveable.count(Child.class));
    }

    @Test
    @DisplayName("tabela apagada por fora se recupera na chamada seguinte, sem reiniciar")
    void droppedTableHealsOnTheNextCall() throws Exception {
        new Note("a", "1").save();
        executeOutside("DROP TABLE notes");

        assertThrows(PersistenceException.class, () -> new Note("b", "2").save());
        assertTrue(new Note("c", "3").save());
        assertEquals(1, Saveable.count(Note.class));
    }

    @Test
    @DisplayName("tabela com acento no nome, apagada por fora, também se recupera")
    void droppedTableWithAnAccentedNameHeals() throws Exception {
        new Café("a", "1").save();
        executeOutside("DROP TABLE \"cafés\"");

        assertThrows(PersistenceException.class, () -> new Café("b", "2").save());
        assertTrue(new Café("c", "3").save(), "a entidade ficou marcada como pronta, sem tabela");
        assertEquals(1, Saveable.count(Café.class));
    }

    @Test
    @DisplayName("nome da tabela: acrescenta 's' só quando o nome não termina em 's'")
    void tableNameAppendsAnSOnlyWhenMissing() {
        assertEquals("accounts", Saveable.tableName(Account.class));
        assertEquals("address", Saveable.tableName(Address.class));
        assertEquals("companysettings", Saveable.tableName(CompanySettings.class));
        assertEquals("categorys", Saveable.tableName(Category.class));
    }

    @Test
    @DisplayName("classe anônima não vira tabela")
    void anonymousClassIsRejected() {
        Note anonymous = new Note() {
        };
        assertThrows(IllegalArgumentException.class, () -> Saveable.tableName(anonymous.getClass()));
        assertThrows(IllegalArgumentException.class, anonymous::save);
    }

    @Test
    @DisplayName("entidade cujo nome de tabela é palavra reservada grava, busca e indexa normalmente")
    void reservedWordAsTableNameWorks() throws Exception {
        assertTrue(new Values("v1", "rótulo").save());
        assertTrue(Saveable.createIndex(Values.class, "label"));

        assertEquals("rótulo", Saveable.findById(Values.class, "v1").label);
        assertEquals(1, Saveable.findByField(Values.class, "label", "rótulo").size());
        assertTrue(tableExists("values"));
    }
}
