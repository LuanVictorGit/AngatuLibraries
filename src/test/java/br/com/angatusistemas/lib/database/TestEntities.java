package br.com.angatusistemas.lib.database;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entidades usadas pelos testes de persistência.
 *
 * <p>Cada uma vira uma tabela própria ({@link Saveable#tableName(Class)}). Como cada caso de
 * teste usa um banco novo, a primeira operação de cada caso é de fato a primeira vez daquela
 * tabela no arquivo.</p>
 *
 * @author Angatu Sistemas
 */
final class TestEntities {

    private TestEntities() {
    }

    /** Mesma forma da entidade da reprodução medida no Pedify. */
    public static class MerchantAuthState extends Saveable {
        private String id;
        String companyId;

        public MerchantAuthState() {
        }

        MerchantAuthState(String companyId) {
            this.companyId = companyId;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** Conta com saldo: o registro disputado dos testes de concorrência. */
    public static class Account extends Saveable {
        String id;
        long balance;

        public Account() {
        }

        Account(String id, long balance) {
            this.id = id;
            this.balance = balance;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** Lançamento gravado junto de uma alteração de conta. */
    public static class Entry extends Saveable {
        private String id;
        String accountId;
        long amount;

        public Entry() {
        }

        Entry(String accountId, long amount) {
            this.accountId = accountId;
            this.amount = amount;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** Entidade tocada pela primeira vez dentro de outra operação. */
    public static class Child extends Saveable {
        private String id;
        String ownerId;

        public Child() {
        }

        Child(String ownerId) {
            this.ownerId = ownerId;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** Entidade mínima, com ID escolhido pelo teste. */
    public static class Note extends Saveable {
        private String id;
        String text;

        public Note() {
        }

        Note(String id, String text) {
            this.id = id;
            this.text = text;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** Já termina em "s": a tabela é {@code address}, nunca {@code addresss}. */
    public static class Address extends Saveable {
        private String id;

        @Override
        public String getId() {
            return id;
        }
    }

    /** Tabela {@code companysettings}. */
    public static class CompanySettings extends Saveable {
        private String id;

        @Override
        public String getId() {
            return id;
        }
    }

    /** Tabela {@code categorys}: não existe flexão de plural além do "s". */
    public static class Category extends Saveable {
        private String id;

        @Override
        public String getId() {
            return id;
        }
    }

    /** Nome com acento: a tabela é {@code cafés}, fora do {@code \w} de uma expressão regular ASCII. */
    public static class Café extends Saveable {
        private String id;
        String blend;

        public Café() {
        }

        Café(String id, String blend) {
            this.id = id;
            this.blend = blend;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** O nome da tabela é palavra reservada do SQL: {@code values}. */
    public static class Values extends Saveable {
        private String id;
        String label;

        public Values() {
        }

        Values(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /** Base com o ID: o campo fica numa classe acima da entidade. */
    public abstract static class BaseEntity extends Saveable {
        protected String id;
        protected long version;

        @Override
        public String getId() {
            return id;
        }
    }

    /** ID herdado, e um campo {@code transient} que não vem do banco. */
    public static class Customer extends BaseEntity {
        String name;
        transient String sessionOnly;

        public Customer() {
        }

        Customer(String name) {
            this.name = name;
        }
    }

    /** Um {@code boolean} terminado em "id" declarado antes do ID. */
    public static class Invoice extends Saveable {
        boolean paid;
        private String id;

        @Override
        public String getId() {
            return id;
        }
    }

    /** Entidade sem campo que possa guardar o ID. */
    public static class NoIdField extends Saveable {
        String name;

        @Override
        public String getId() {
            return null;
        }
    }

    /** Estados de uma compra: enum gravado pelo nome. */
    public enum Status {
        OPEN, PAID
    }

    /** Campos de todos os tipos que a busca por campo precisa resolver no SQL. */
    public static class Purchase extends Saveable {
        private String id;
        Status status;
        BigDecimal total;
        UUID reference;
        String note;
        boolean express;

        public Purchase() {
        }

        Purchase(String id, Status status, BigDecimal total, UUID reference, String note, boolean express) {
            this.id = id;
            this.status = status;
            this.total = total;
            this.reference = reference;
            this.note = note;
            this.express = express;
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /**
     * Construtor que consulta o banco — como a entidade que gera um código que ainda não
     * exista. O Gson chama este construtor a cada registro desserializado.
     */
    public static class SelfCounting extends Saveable {
        private String id;
        long accountsSeen;

        public SelfCounting() {
            this.accountsSeen = Saveable.count(Account.class);
        }

        SelfCounting(String id) {
            this();
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }
    }
}
