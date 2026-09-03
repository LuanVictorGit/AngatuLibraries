package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * Classe abstrata que fornece persistência automática em SQLite para objetos
 * Java, lendo e gravando <strong>direto no banco</strong> a cada operação.
 *
 * <p><strong>Propósito:</strong> persistir entidades sem SQL manual — estenda
 * esta classe, implemente {@link #getId()} e use os métodos estáticos de
 * busca/gravação. O fluxo típico: <em>estender → criar com construtor vazio →
 * preencher campos → {@link #save()} → buscar com
 * {@link #findById(Class, String)}</em>.</p>
 *
 * <p><strong>Sem dados em memória:</strong> não existe mais cache total nem
 * <em>identity map</em>. Cada busca vai ao banco e devolve uma instância nova;
 * cada alteração só existe depois de um {@link #save()} (ou de um
 * {@link #mutate(Class, String, Consumer)}). Isso é o que permite rodar no
 * Coolify com vários componentes — rotas, tarefas agendadas, workers — mexendo
 * nos mesmos registros sem que um sobrescreva o outro a partir de uma cópia
 * velha guardada na RAM.</p>
 *
 * <p><strong>Concorrência:</strong> um único pool HikariCP atende todo o banco,
 * com SQLite em modo WAL (leitores não bloqueiam o escritor), {@code busy_timeout}
 * para esperar em vez de falhar e transações {@code IMMEDIATE} — que pegam a
 * trava de escrita já no início, serializando escritas entre threads e entre
 * processos. Sobre isso há travas por registro dentro do processo e uma coluna
 * {@code version} para detecção de escrita concorrente:</p>
 * <ul>
 *   <li>{@link #save()} — gravação atômica; a última escrita vence;</li>
 *   <li>{@link #saveIfCurrent()} — só grava se ninguém alterou o registro desde
 *       a leitura (devolve {@code false} no conflito);</li>
 *   <li>{@link #mutate(Class, String, Consumer)} — <strong>a forma correta</strong>
 *       de alterar um registro disputado: lê, altera e grava dentro da mesma
 *       transação, sem janela para atualização perdida;</li>
 *   <li>{@link #transaction(Runnable)} — várias operações com tudo ou nada.</li>
 * </ul>
 *
 * <p><strong>Quando NÃO usar:</strong> para relacionamentos complexos ou
 * consultas analíticas (use SQL direto com o driver) e para dados binários
 * grandes (ex: imagens — prefira salvar em disco/volume e persistir o caminho).
 * <strong>Não instancie esta classe diretamente</strong> — é abstrata e o
 * construtor é {@code protected}: o uso é exclusivamente via {@code extends}.</p>
 *
 * <p><strong>Restrição de inicialização:</strong> subclasses precisam de um
 * construtor vazio (para o Gson desserializar) e de campos serializáveis.</p>
 *
 * <p><strong>Mapeamento:</strong> cada subclasse vira uma tabela — nome da
 * classe em minúsculas, com 's' no final se ainda não terminar em 's'
 * ({@code User} → {@code users}). As colunas são {@code id} (TEXT, chave
 * primária), {@code data} (TEXT, o objeto em JSON), {@code version} (INTEGER) e
 * {@code updated_at} (INTEGER, época em segundos). Tabelas criadas por versões
 * anteriores ganham as colunas novas automaticamente na primeira utilização.</p>
 *
 * <p><strong>Banco de dados:</strong> {@code database.db} no diretório de
 * trabalho. Em contêiner, aponte para o volume persistente com
 * {@code ANGATU_DB_PATH=/data/database.db} (ou {@code -Dangatu.db=...}) — sem
 * isso o banco vive dentro do contêiner e some no próximo deploy.</p>
 *
 * <p><strong>Desempenho:</strong> busca por ID é um SELECT na chave primária.
 * {@link #findAll(Class)} e {@link #findByPredicate(Class, Predicate)} leem a
 * tabela inteira — para consultas frequentes por campo, crie o índice com
 * {@link #createIndex(Class, String)} e use {@link #findByField(Class, String, Object)}
 * ou {@link #query(Class, String, Object...)}, que resolvem no SQL.</p>
 *
 * <p><strong>Boas práticas:</strong> use {@link #query(Class, String, Object...)}
 * com parâmetros posicionais (nunca concatene valores no SQL); prefira
 * {@link #mutate(Class, String, Consumer)} a ler-alterar-salvar quando o
 * registro for disputado; chame {@link #shutdown()} ao encerrar a aplicação.</p>
 *
 * <p><strong>Integração:</strong> entidades internas como {@code PermanentBlock},
 * {@code SuspectIp}, {@code RouteRateLimitConfig}, {@code Key} (Web Push) e
 * {@code Image} estendem esta classe; a serialização usa {@link GsonAPI} e as
 * dependências (sqlite-jdbc, HikariCP, gson) são verificadas no primeiro uso
 * com instruções de instalação se ausentes.</p>
 *
 * <p><strong>Limitações:</strong> SQLite é um banco de arquivo — vários
 * contêineres só compartilham o mesmo banco se compartilharem o mesmo volume no
 * mesmo host; para réplicas em máquinas diferentes, use um banco cliente/servidor.
 * Campos {@code transient} não são persistidos.</p>
 *
 * @author Angatu Sistemas
 * @see GsonAPI
 * @see <a href="https://www.sqlite.org/wal.html">SQLite WAL mode</a>
 */
public abstract class Saveable {

    // ==================== CONSTANTES ====================

    /** Coordenadas Maven das dependências do módulo de persistência. */
    private static final String SQLITE_COORDINATES = "org.xerial:sqlite-jdbc:3.51.3.0";
    private static final String HIKARI_COORDINATES = "com.zaxxer.hikari:HikariCP:7.0.2";
    private static final String PERSISTENCE_FEATURE = "Persistência (Saveable)";

    /** Arquivo padrão do banco, relativo ao diretório de trabalho. */
    private static final String DEFAULT_DATABASE = "database.db";
    /** Conexões do pool: leitores concorrentes; a escrita é serializada pelo SQLite. */
    private static final int POOL_SIZE = 12;
    /** Tempo que uma conexão espera pela trava de escrita antes de desistir (ms). */
    private static final String BUSY_TIMEOUT_MS = "5000";
    /** Tentativas de gravação otimista antes de desistir de um registro disputado. */
    private static final int MUTATE_ATTEMPTS = 5;
    /** Quantidade de travas por faixa (striped locks) — limita a disputa sem crescer sem fim. */
    private static final int LOCK_STRIPES = 64;
    /** Nomes de campo aceitos no SQL de índice/consulta por campo. */
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Versão desconhecida: objeto novo ou nunca lido do banco. */
    private static final long UNKNOWN_VERSION = -1L;

    // ==================== ESTADO GLOBAL ====================

    /** Pool único para todo o banco (antes havia um pool por classe). */
    private static volatile HikariDataSource dataSource;
    private static final Object DATA_SOURCE_LOCK = new Object();

    /** Classes cuja tabela já foi criada/migrada nesta execução. */
    private static final Set<Class<?>> PREPARED_TABLES = ConcurrentHashMap.newKeySet();
    private static final Object PREPARE_LOCK = new Object();

    /** Conexão presa à thread enquanto durar uma transação explícita. */
    private static final ThreadLocal<Connection> CURRENT_TRANSACTION = new ThreadLocal<>();

    /** Travas por faixa: serializam leitura-alteração-gravação do mesmo registro. */
    private static final ReentrantLock[] ROW_LOCKS = new ReentrantLock[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) ROW_LOCKS[i] = new ReentrantLock();
    }

    // ==================== ESTADO DA INSTÂNCIA ====================

    /**
     * Versão do registro no momento da leitura. {@code transient} de propósito:
     * é controle de concorrência, não faz parte do JSON persistido.
     */
    private transient long persistedVersion = UNKNOWN_VERSION;

    // ==================== CONSTRUTOR ====================

    /**
     * Construtor {@code protected}: o {@code Saveable} funciona exclusivamente
     * por herança ({@code extends}).
     *
     * <p><strong>Forma correta de uso:</strong> crie uma entidade concreta que
     * estenda {@code Saveable} e implemente {@link #getId()}:
     * <pre>
     * public class User extends Saveable {
     *     private String id;
     *     private String name;
     *     public User() {} // obrigatório para desserialização Gson
     *     &#64;Override public String getId() { return id; }
     * }
     * </pre>
     * </p>
     *
     * <p><strong>Uso incorreto:</strong> instanciar {@code Saveable} diretamente
     * é impossível — a classe é abstrata e o construtor é {@code protected}.
     * Subclasses anônimas também são desencorajadas: uma entidade precisa de
     * campos persistidos e de um construtor vazio para o Gson.</p>
     */
    protected Saveable() {
        // Construtor protegido: garante que a classe só seja utilizada via herança
    }

    // ==================== MÉTODO ABSTRATO ====================

    /**
     * Retorna o identificador único do objeto.
     *
     * <p>A implementação deve apenas devolver o campo do ID
     * ({@code return this.id;}). Se ainda não houver ID, pode retornar
     * {@code null} — um UUID é gerado e injetado na primeira gravação.</p>
     *
     * @return Identificador do objeto, ou {@code null} se ainda não definido
     */
    public abstract String getId();

    // ==================== INSTÂNCIA: GRAVAÇÃO ====================

    /**
     * Grava o objeto no banco (inserção ou atualização), de forma atômica.
     *
     * <p>A gravação inteira acontece dentro de uma transação de escrita: ou o
     * registro fica completo, ou nada muda. Quando dois componentes gravam o
     * mesmo registro ao mesmo tempo, as escritas são serializadas e a última
     * vence — nenhuma delas corrompe o registro, mas a anterior é substituída.
     * Se o que você quer é alterar um campo sem perder a alteração do outro, use
     * {@link #mutate(Class, String, Consumer)}.</p>
     *
     * <p>Sem ID, um UUID é gerado e injetado via reflexão no campo {@code id}
     * (ou no primeiro campo terminado em "id").</p>
     *
     * @return {@code true} se gravado com sucesso
     */
    public boolean save() {
        Class<?> type = getClass();
        String id = ensureId();
        if (id == null) return false;
        prepare(type);
        String json = GsonAPI.get().toJson(this);
        String table = tableName(type);

        ReentrantLock lock = acquireRowLock(type, id);
        try {
            Boolean saved = write(Boolean.FALSE, "salvar " + type.getSimpleName() + " id=" + id, conn -> {
                upsert(conn, table, id, json);
                persistedVersion = currentVersion(conn, table, id);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(saved);
        } finally {
            releaseRowLock(lock);
        }
    }

    /**
     * Grava o objeto somente se ninguém tiver alterado o registro desde a
     * leitura (controle otimista pela coluna {@code version}).
     *
     * <p>Use quando perder a alteração de outro componente for inaceitável e
     * você quiser tratar o conflito no seu código — recarregando com
     * {@link #reload()} e tentando de novo, ou avisando o usuário. Para o caso
     * comum, {@link #mutate(Class, String, Consumer)} resolve o conflito
     * sozinho.</p>
     *
     * @return {@code true} se gravado; {@code false} se o registro foi alterado
     *         por outro componente (ou já existia, no caso de objeto novo)
     */
    public boolean saveIfCurrent() {
        Class<?> type = getClass();
        String id = ensureId();
        if (id == null) return false;
        prepare(type);
        String json = GsonAPI.get().toJson(this);
        String table = tableName(type);
        long expected = persistedVersion;
        long now = Instant.now().getEpochSecond();

        ReentrantLock lock = acquireRowLock(type, id);
        try {
            Boolean saved = write(Boolean.FALSE, "salvar " + type.getSimpleName() + " id=" + id, conn -> {
                if (expected == UNKNOWN_VERSION) {
                    // Objeto novo: só grava se o ID ainda não existir
                    String sql = "INSERT INTO " + table + " (id, data, version, updated_at) VALUES (?, ?, 1, ?)"
                            + " ON CONFLICT(id) DO NOTHING";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, id);
                        ps.setString(2, json);
                        ps.setLong(3, now);
                        if (ps.executeUpdate() == 0) {
                            Console.warn("saveIfCurrent() em %s id=%s: o registro já existe e esta instância não sabe a"
                                    + " versão dele. Leia com findById/findByField (ou traga a coluna version na"
                                    + " consulta) antes de gravar de forma otimista.", table, id);
                            return Boolean.FALSE;
                        }
                    }
                    persistedVersion = 1L;
                    return Boolean.TRUE;
                }
                String sql = "UPDATE " + table + " SET data = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, json);
                    ps.setLong(2, now);
                    ps.setString(3, id);
                    ps.setLong(4, expected);
                    if (ps.executeUpdate() == 0) return Boolean.FALSE;
                }
                persistedVersion = expected + 1;
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(saved);
        } finally {
            releaseRowLock(lock);
        }
    }

    /**
     * Exclui o registro correspondente a este objeto.
     *
     * @return {@code true} se o registro foi removido
     */
    public boolean delete() {
        String id = getId();
        if (id == null) return false;
        return deleteById(getClass(), id);
    }

    /**
     * Recarrega os campos do objeto a partir do banco, descartando alterações
     * locais não gravadas.
     *
     * <p>É o passo natural depois de um {@link #saveIfCurrent()} recusado: pega
     * o estado atual do registro para reaplicar a alteração.</p>
     *
     * @return A própria instância recarregada, ou {@code null} se o registro não
     *         existir mais
     */
    public Saveable reload() {
        String id = getId();
        if (id == null) return null;
        Class<?> type = getClass();
        prepare(type);
        String table = tableName(type);

        return read(null, "recarregar " + type.getSimpleName() + " id=" + id, conn -> {
            Row row = selectRow(conn, table, id);
            if (row == null) return null;
            Object fresh = GsonAPI.get().fromJson(row.json(), type);
            copyFields(fresh, this);
            persistedVersion = row.version();
            return this;
        });
    }

    /**
     * Versão do registro conhecida por esta instância.
     *
     * <p>Começa em {@code -1} (objeto novo), passa a valer o número gravado no
     * banco após a leitura ou a gravação e cresce a cada alteração. Serve para
     * o controle otimista de {@link #saveIfCurrent()}.</p>
     *
     * @return Versão conhecida do registro, ou {@code -1} se desconhecida
     */
    public long getPersistedVersion() {
        return persistedVersion;
    }

    // ==================== ESTÁTICOS: LEITURA ====================

    /**
     * Busca um objeto pelo ID, lendo direto do banco.
     *
     * <p>Cada chamada devolve uma instância nova: alterar o objeto retornado não
     * afeta ninguém até o {@link #save()}.</p>
     *
     * @param clazz Classe da entidade (ex: {@code User.class})
     * @param id    Identificador único
     * @param <T>   Tipo da entidade
     * @return Objeto encontrado, ou {@code null}
     */
    public static <T> T findById(Class<T> clazz, String id) {
        if (id == null) return null;
        prepare(clazz);
        String table = tableName(clazz);
        return read(null, "buscar " + clazz.getSimpleName() + " id=" + id, conn -> {
            Row row = selectRow(conn, table, id);
            return row == null ? null : materialize(clazz, row.json(), row.version());
        });
    }

    /**
     * Retorna todos os objetos da classe.
     *
     * <p>Lê a tabela inteira: use com consciência do tamanho dela. Para filtrar,
     * prefira {@link #findByField(Class, String, Object)} ou
     * {@link #query(Class, String, Object...)}, que filtram no banco.</p>
     *
     * @param clazz Classe da entidade
     * @param <T>   Tipo da entidade
     * @return Lista com todos os objetos (pode ser vazia, nunca {@code null})
     */
    public static <T> List<T> findAll(Class<T> clazz) {
        prepare(clazz);
        return query(clazz, "SELECT data, version FROM " + tableName(clazz));
    }

    /**
     * Filtra objetos com um predicado avaliado em memória.
     *
     * <p>Percorre a tabela inteira e desserializa cada registro — conveniente,
     * mas caro. Quando o filtro é por um campo, use
     * {@link #findByField(Class, String, Object)} com o índice criado por
     * {@link #createIndex(Class, String)}.</p>
     *
     * @param clazz     Classe da entidade
     * @param predicate Condição de seleção
     * @param <T>       Tipo da entidade
     * @return Lista filtrada (nunca {@code null})
     */
    public static <T> List<T> findByPredicate(Class<T> clazz, Predicate<T> predicate) {
        List<T> result = new ArrayList<>();
        for (T obj : findAll(clazz)) {
            if (predicate.test(obj)) result.add(obj);
        }
        return result;
    }

    /**
     * Busca objetos por um campo do JSON.
     *
     * <p>Valores simples (texto, número, booleano) são filtrados pelo próprio
     * SQLite com {@code json_extract} — rápido, e mais ainda com o índice de
     * {@link #createIndex(Class, String)}. Outros tipos caem no filtro em
     * memória por reflexão.</p>
     *
     * @param clazz     Classe da entidade
     * @param fieldName Nome exato do campo (ex: {@code "email"})
     * @param value     Valor procurado
     * @param <T>       Tipo da entidade
     * @return Lista de objetos com o campo igual ao valor (nunca {@code null})
     */
    public static <T> List<T> findByField(Class<T> clazz, String fieldName, Object value) {
        if (isIndexableField(fieldName, value)) {
            prepare(clazz);
            return query(clazz, "SELECT data, version FROM " + tableName(clazz)
                    + " WHERE json_extract(data, '$." + fieldName + "') = ?", value);
        }
        return findByPredicate(clazz, obj -> Objects.equals(fieldValue(obj, fieldName), value));
    }

    /**
     * Busca o primeiro objeto cujo campo seja igual ao valor informado.
     *
     * <p>Atalho para o caso mais comum — achar o usuário pelo e-mail, a sessão
     * pelo token — sem trazer a lista inteira.</p>
     *
     * @param clazz     Classe da entidade
     * @param fieldName Nome exato do campo
     * @param value     Valor procurado
     * @param <T>       Tipo da entidade
     * @return Primeiro objeto encontrado, ou {@code null}
     */
    public static <T> T findFirstByField(Class<T> clazz, String fieldName, Object value) {
        if (isIndexableField(fieldName, value)) {
            prepare(clazz);
            List<T> found = query(clazz, "SELECT data, version FROM " + tableName(clazz)
                    + " WHERE json_extract(data, '$." + fieldName + "') = ? LIMIT 1", value);
            return found.isEmpty() ? null : found.get(0);
        }
        List<T> found = findByField(clazz, fieldName, value);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Verifica se existe um registro com o ID informado.
     *
     * @param clazz Classe da entidade
     * @param id    Identificador
     * @return {@code true} se o registro existir
     */
    public static boolean exists(Class<?> clazz, String id) {
        if (id == null) return false;
        prepare(clazz);
        String table = tableName(clazz);
        Boolean found = read(Boolean.FALSE, "verificar " + clazz.getSimpleName() + " id=" + id, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ? LIMIT 1")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
        return Boolean.TRUE.equals(found);
    }

    /**
     * Conta os registros persistidos da classe.
     *
     * @param clazz Classe da entidade
     * @return Quantidade de registros
     */
    public static long count(Class<?> clazz) {
        prepare(clazz);
        String table = tableName(clazz);
        Long total = read(0L, "contar " + clazz.getSimpleName(), conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
        return total == null ? 0L : total;
    }

    /**
     * Executa SQL na tabela da entidade.
     *
     * <p>Consultas devem trazer a coluna {@code data} (o JSON do objeto); se
     * também trouxerem {@code version}, a instância já volta pronta para
     * {@link #saveIfCurrent()}. Comandos que não retornam linhas (DDL, UPDATE,
     * DELETE) são aceitos e devolvem lista vazia.</p>
     *
     * <p><strong>Sempre com parâmetros posicionais</strong> — nunca concatene
     * valores no SQL:</p>
     * <pre>
     * Saveable.createIndex(User.class, "email");
     *
     * List&lt;User&gt; users = Saveable.query(User.class,
     *     "SELECT data, version FROM users WHERE json_extract(data, '$.email') = ?", email);
     *
     * List&lt;User&gt; page = Saveable.query(User.class,
     *     "SELECT data, version FROM users ORDER BY id LIMIT 100 OFFSET ?", 0);
     * </pre>
     *
     * @param clazz  Classe destino dos objetos
     * @param sql    Comando SQL
     * @param params Parâmetros posicionais
     * @param <T>    Tipo da entidade
     * @return Lista de objetos resultantes (pode ser vazia)
     * @throws UnsupportedOperationException se a consulta retornar linhas sem a
     *         coluna {@code data}
     */
    public static <T> List<T> query(Class<T> clazz, String sql, Object... params) {
        prepare(clazz);
        List<T> list = read(new ArrayList<>(), "consultar " + clazz.getSimpleName(), conn -> {
            List<T> found = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                boolean hasResultSet = ps.execute();
                if (!hasResultSet) return found;
                try (ResultSet rs = ps.getResultSet()) {
                    int dataColumn = columnIndex(rs.getMetaData(), "data");
                    if (dataColumn == 0) {
                        throw new UnsupportedOperationException(
                                "Consulta customizada deve retornar a coluna 'data' com o JSON do objeto: " + sql);
                    }
                    int versionColumn = columnIndex(rs.getMetaData(), "version");
                    while (rs.next()) {
                        long version = versionColumn == 0 ? UNKNOWN_VERSION : rs.getLong(versionColumn);
                        found.add(materialize(clazz, rs.getString(dataColumn), version));
                    }
                }
            }
            return found;
        });
        return list == null ? new ArrayList<>() : list;
    }

    /**
     * Cria (se ainda não existir) um índice sobre um campo do JSON.
     *
     * <p>Sem cache em memória, o índice é o que mantém a busca por campo barata.
     * Chame uma vez na inicialização, para cada campo consultado com frequência
     * (e-mail, token de sessão, chave estrangeira).</p>
     *
     * @param clazz     Classe da entidade
     * @param fieldName Nome do campo indexado (ex: {@code "email"})
     * @return {@code true} se o índice existe ao final da chamada
     */
    public static boolean createIndex(Class<?> clazz, String fieldName) {
        if (fieldName == null || !SAFE_FIELD.matcher(fieldName).matches()) {
            Console.error("Nome de campo inválido para índice: " + fieldName);
            return false;
        }
        prepare(clazz);
        String table = tableName(clazz);
        String index = "idx_" + table + "_" + fieldName.toLowerCase(Locale.ROOT);
        Boolean created = write(Boolean.FALSE, "criar índice " + index, conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS " + index + " ON " + table
                        + "(json_extract(data, '$." + fieldName + "'))");
            }
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(created);
    }

    // ==================== ESTÁTICOS: GRAVAÇÃO ====================

    /**
     * Lê, altera e grava um registro dentro da mesma transação — sem janela para
     * atualização perdida.
     *
     * <p>É a forma correta de mexer em registro disputado. Enquanto o bloco
     * roda, nenhum outro componente grava aquele registro; o que a alteração
     * enxerga é o estado atual do banco, não uma cópia lida antes.</p>
     *
     * <pre>
     * Saveable.mutate(Account.class, id, account -&gt; account.setBalance(account.getBalance() + 100));
     * </pre>
     *
     * @param clazz  Classe da entidade
     * @param id     Identificador do registro
     * @param change Alteração a aplicar sobre o estado atual do registro
     * @param <T>    Tipo da entidade
     * @return Objeto já alterado e gravado, ou {@code null} se o registro não
     *         existir (ou se o conflito persistir após as tentativas)
     */
    public static <T extends Saveable> T mutate(Class<T> clazz, String id, Consumer<T> change) {
        if (id == null || change == null) return null;
        prepare(clazz);
        String table = tableName(clazz);
        boolean[] conflict = {false};

        ReentrantLock lock = acquireRowLock(clazz, id);
        try {
            for (int attempt = 1; attempt <= MUTATE_ATTEMPTS; attempt++) {
                conflict[0] = false;
                T updated = write(null, "alterar " + clazz.getSimpleName() + " id=" + id, conn -> {
                    Row row = selectRow(conn, table, id);
                    if (row == null) return null;

                    T obj = materialize(clazz, row.json(), row.version());
                    change.accept(obj);

                    String sql = "UPDATE " + table + " SET data = ?, version = version + 1, updated_at = ?"
                            + " WHERE id = ? AND version = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, GsonAPI.get().toJson(obj));
                        ps.setLong(2, Instant.now().getEpochSecond());
                        ps.setString(3, id);
                        ps.setLong(4, row.version());
                        if (ps.executeUpdate() == 0) {
                            conflict[0] = true;
                            return null;
                        }
                    }
                    ((Saveable) obj).persistedVersion = row.version() + 1;
                    return obj;
                });

                if (updated != null) return updated;
                if (!conflict[0]) return null; // registro inexistente: repetir não ajuda
            }
            Console.warn("Não foi possível alterar %s id=%s: %d tentativas seguidas com escrita concorrente.",
                    clazz.getSimpleName(), id, MUTATE_ATTEMPTS);
            return null;
        } finally {
            releaseRowLock(lock);
        }
    }

    /**
     * Grava vários objetos em uma única transação — todos ou nenhum.
     *
     * @param objects Objetos a gravar (podem ser de classes diferentes)
     * @return Quantidade de objetos gravados
     */
    public static int saveAll(Collection<? extends Saveable> objects) {
        if (objects == null || objects.isEmpty()) return 0;
        int[] saved = {0};
        transaction(() -> {
            for (Saveable obj : objects) {
                if (obj != null && obj.save()) saved[0]++;
            }
        });
        return saved[0];
    }

    /**
     * Exclui um registro pelo ID.
     *
     * @param clazz Classe da entidade
     * @param id    Identificador
     * @return {@code true} se o registro foi removido
     */
    public static boolean deleteById(Class<?> clazz, String id) {
        if (id == null) return false;
        prepare(clazz);
        String table = tableName(clazz);
        ReentrantLock lock = acquireRowLock(clazz, id);
        try {
            Boolean deleted = write(Boolean.FALSE, "excluir " + clazz.getSimpleName() + " id=" + id, conn -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                    ps.setString(1, id);
                    return ps.executeUpdate() > 0;
                }
            });
            return Boolean.TRUE.equals(deleted);
        } finally {
            releaseRowLock(lock);
        }
    }

    /**
     * Exclui todos os registros da classe.
     *
     * @param clazz Classe da entidade
     * @return Quantidade de registros removidos
     */
    public static int deleteAll(Class<?> clazz) {
        prepare(clazz);
        String table = tableName(clazz);
        Integer deleted = write(0, "excluir todos os " + clazz.getSimpleName(), conn -> {
            try (Statement st = conn.createStatement()) {
                return st.executeUpdate("DELETE FROM " + table);
            }
        });
        return deleted == null ? 0 : deleted;
    }

    // ==================== TRANSAÇÕES ====================

    /**
     * Executa várias operações em uma única transação: ou todas valem, ou
     * nenhuma vale.
     *
     * <p>Use quando duas gravações precisam ser verdade ao mesmo tempo — baixar
     * o estoque e criar o pedido, debitar de um e creditar no outro. Chamadas
     * aninhadas juntam-se à transação em curso.</p>
     *
     * <pre>
     * Saveable.transaction(() -&gt; {
     *     stock.setQuantity(stock.getQuantity() - 1);
     *     stock.save();
     *     new Order(userId, productId).save();
     * });
     * </pre>
     *
     * @param actions Operações a executar
     * @throws RuntimeException se alguma operação falhar — a transação inteira é
     *         desfeita e a exceção é repassada
     */
    public static void transaction(Runnable actions) {
        computeInTransaction(() -> {
            actions.run();
            return null;
        });
    }

    /**
     * Igual a {@link #transaction(Runnable)}, mas devolve um resultado.
     *
     * @param actions Operações a executar, produzindo o resultado
     * @param <T>     Tipo do resultado
     * @return Valor produzido pelo bloco
     * @throws RuntimeException se alguma operação falhar — a transação inteira é
     *         desfeita e a exceção é repassada
     */
    public static <T> T computeInTransaction(Supplier<T> actions) {
        Connection running = CURRENT_TRANSACTION.get();
        if (running != null) return actions.get(); // já existe transação nesta thread

        Connection conn = null;
        boolean committed = false;
        try {
            conn = dataSource().getConnection();
            conn.setAutoCommit(false); // dispara BEGIN IMMEDIATE: trava de escrita desde o início
            CURRENT_TRANSACTION.set(conn);
            T result = actions.get();
            conn.commit();
            committed = true;
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao abrir/confirmar transação do Saveable", e);
        } finally {
            CURRENT_TRANSACTION.remove();
            if (conn != null) {
                if (!committed) rollbackQuietly(conn);
                restoreAndClose(conn);
            }
        }
    }

    // ==================== CICLO DE VIDA ====================

    /**
     * Fecha o pool de conexões. Chame no encerramento da aplicação (shutdown
     * hook) para não vazar conexões nem deixar o WAL sem checkpoint.
     */
    public static void shutdown() {
        synchronized (DATA_SOURCE_LOCK) {
            if (dataSource != null && !dataSource.isClosed()) dataSource.close();
            dataSource = null;
        }
        PREPARED_TABLES.clear();
        CURRENT_TRANSACTION.remove();
    }

    /**
     * Caminho do arquivo do banco em uso.
     *
     * <p>Padrão: {@code database.db} no diretório de trabalho. Em contêiner,
     * defina {@code ANGATU_DB_PATH} (ou {@code -Dangatu.db}) apontando para o
     * volume persistente.</p>
     *
     * @return Caminho do arquivo SQLite
     */
    public static String databasePath() {
        String path = System.getProperty("angatu.db");
        if (path == null || path.isBlank()) path = System.getenv("ANGATU_DB_PATH");
        return path == null || path.isBlank() ? DEFAULT_DATABASE : path.trim();
    }

    // ==================== INFRAESTRUTURA ====================

    /** Linha crua do banco: JSON do objeto e versão do registro. */
    private record Row(String json, long version) {}

    /** Operação que usa uma conexão já resolvida (do pool ou da transação). */
    @FunctionalInterface
    private interface SqlWork<R> {
        R apply(Connection conn) throws SQLException;
    }

    /**
     * Executa uma leitura. Fora de transação, a conexão vem do pool em
     * autocommit — leitores não bloqueiam nem são bloqueados no modo WAL.
     */
    private static <R> R read(R fallback, String what, SqlWork<R> work) {
        Connection running = CURRENT_TRANSACTION.get();
        if (running != null) {
            try {
                return work.apply(running);
            } catch (SQLException e) {
                throw new IllegalStateException("Erro ao " + what, e);
            }
        }
        try (Connection conn = dataSource().getConnection()) {
            return work.apply(conn);
        } catch (SQLException e) {
            Console.error("Erro ao " + what, e);
            return fallback;
        }
    }

    /**
     * Executa uma escrita dentro de uma transação. Se já houver transação na
     * thread, a operação entra nela — o commit fica com quem abriu.
     */
    private static <R> R write(R fallback, String what, SqlWork<R> work) {
        Connection running = CURRENT_TRANSACTION.get();
        if (running != null) {
            try {
                return work.apply(running);
            } catch (SQLException e) {
                // Dentro de transação, falhar em silêncio corromperia o "tudo ou nada"
                throw new IllegalStateException("Erro ao " + what, e);
            }
        }

        Connection conn = null;
        try {
            conn = dataSource().getConnection();
            conn.setAutoCommit(false);
            R result = work.apply(conn);
            conn.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            Console.error("Erro ao " + what, e);
            return fallback;
        } catch (RuntimeException e) {
            rollbackQuietly(conn);
            throw e;
        } finally {
            restoreAndClose(conn);
        }
    }

    /**
     * Cria o pool na primeira utilização.
     *
     * <p>Um pool para o banco inteiro (não mais um por classe): o SQLite tem um
     * escritor por vez, então vários pools apenas multiplicavam conexões
     * disputando a mesma trava. O modo WAL libera as leituras, o
     * {@code busy_timeout} faz a escrita esperar em vez de estourar
     * {@code SQLITE_BUSY} e o {@code transaction_mode=IMMEDIATE} garante que
     * toda transação de escrita pegue a trava logo no início — é isso que impede
     * duas alterações concorrentes de se sobreporem.</p>
     */
    private static HikariDataSource dataSource() {
        HikariDataSource current = dataSource;
        if (current != null && !current.isClosed()) return current;

        Dependencies.require("org.sqlite.JDBC", SQLITE_COORDINATES, PERSISTENCE_FEATURE);
        Dependencies.require("com.zaxxer.hikari.HikariDataSource", HIKARI_COORDINATES, PERSISTENCE_FEATURE);

        synchronized (DATA_SOURCE_LOCK) {
            if (dataSource != null && !dataSource.isClosed()) return dataSource;

            String path = databasePath();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + path);
            config.setConnectionTestQuery("SELECT 1");
            config.setMaximumPoolSize(POOL_SIZE);
            config.setMinimumIdle(2);
            config.setIdleTimeout(30000);
            config.setPoolName("Saveable");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("busy_timeout", BUSY_TIMEOUT_MS);
            config.addDataSourceProperty("transaction_mode", "IMMEDIATE");
            config.addDataSourceProperty("foreign_keys", "true");
            config.addDataSourceProperty("cache_size", 10000);
            config.addDataSourceProperty("temp_store", "MEMORY");

            dataSource = new HikariDataSource(config);
            Console.log("&7Banco SQLite: &f%s &7(WAL, %d conexões, escrita serializada)", path, POOL_SIZE);
            return dataSource;
        }
    }

    /**
     * Garante que a tabela da classe exista e tenha as colunas de controle de
     * concorrência. Executa uma vez por classe, por execução.
     */
    private static void prepare(Class<?> type) {
        if (PREPARED_TABLES.contains(type)) return;
        synchronized (PREPARE_LOCK) {
            if (PREPARED_TABLES.contains(type)) return;

            String table = tableName(type);
            Connection running = CURRENT_TRANSACTION.get();
            try {
                if (running != null) {
                    createAndMigrate(running, table);
                } else {
                    try (Connection conn = dataSource().getConnection()) {
                        createAndMigrate(conn, table);
                    }
                }
                PREPARED_TABLES.add(type);
            } catch (SQLException e) {
                throw new IllegalStateException("Erro ao preparar a tabela " + table, e);
            }
        }
    }

    /** Cria a tabela e acrescenta as colunas que faltarem (bancos antigos). */
    private static void createAndMigrate(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id TEXT PRIMARY KEY, "
                    + "data TEXT NOT NULL, "
                    + "version INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at INTEGER NOT NULL DEFAULT 0)");
        }
        Set<String> columns = new java.util.HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
        }
        try (Statement st = conn.createStatement()) {
            if (!columns.contains("version"))
                st.execute("ALTER TABLE " + table + " ADD COLUMN version INTEGER NOT NULL DEFAULT 0");
            if (!columns.contains("updated_at"))
                st.execute("ALTER TABLE " + table + " ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
        }
    }

    /** Grava o registro criando ou substituindo, sempre incrementando a versão. */
    private static void upsert(Connection conn, String table, String id, String json) throws SQLException {
        String sql = "INSERT INTO " + table + " (id, data, version, updated_at) VALUES (?, ?, 1, ?)"
                + " ON CONFLICT(id) DO UPDATE SET data = excluded.data,"
                + " version = " + table + ".version + 1, updated_at = excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, json);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
    }

    /** Lê a linha crua (JSON + versão) de um registro. */
    private static Row selectRow(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data, version FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString("data"), rs.getLong("version")) : null;
            }
        }
    }

    /** Lê a versão atual de um registro (após a gravação). */
    private static long currentVersion(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT version FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : UNKNOWN_VERSION;
            }
        }
    }

    /** Desserializa o JSON e registra a versão lida na instância. */
    private static <T> T materialize(Class<T> clazz, String json, long version) {
        Gson gson = GsonAPI.get();
        T obj = gson.fromJson(json, clazz);
        if (obj instanceof Saveable saveable) saveable.persistedVersion = version;
        return obj;
    }

    /** Índice da coluna pelo nome, ou {@code 0} se ela não estiver no resultado. */
    private static int columnIndex(ResultSetMetaData meta, String name) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(meta.getColumnLabel(i)) || name.equalsIgnoreCase(meta.getColumnName(i))) return i;
        }
        return 0;
    }

    /**
     * Trava a faixa do registro para serializar alterações do mesmo ID dentro do
     * processo.
     *
     * <p>Dentro de uma transação já aberta não trava nada: a transação de
     * escrita é exclusiva por si só, e pegar a trava depois da trava do banco
     * inverteria a ordem de aquisição entre threads — o caminho conhecido para
     * um impasse.</p>
     *
     * @return Trava adquirida, ou {@code null} quando já se está em transação
     */
    private static ReentrantLock acquireRowLock(Class<?> type, String id) {
        if (CURRENT_TRANSACTION.get() != null) return null;
        int hash = (type.getName() + '|' + id).hashCode();
        ReentrantLock lock = ROW_LOCKS[Math.floorMod(hash, LOCK_STRIPES)];
        lock.lock();
        return lock;
    }

    /** Libera a trava obtida por {@link #acquireRowLock(Class, String)}. */
    private static void releaseRowLock(ReentrantLock lock) {
        if (lock != null) lock.unlock();
    }

    /** Nome da tabela: classe em minúsculas, com 's' final. */
    private static String tableName(Class<?> clazz) {
        String name = clazz.getSimpleName().toLowerCase(Locale.ROOT);
        return name.endsWith("s") ? name : name + "s";
    }

    /** Só vai ao SQL quando o campo é seguro e o valor é comparável em JSON. */
    private static boolean isIndexableField(String fieldName, Object value) {
        return fieldName != null && SAFE_FIELD.matcher(fieldName).matches()
                && (value instanceof String || value instanceof Number || value instanceof Boolean);
    }

    /** Lê um campo por reflexão (fallback do filtro por campo). */
    private static Object fieldValue(Object obj, String fieldName) {
        Class<?> type = obj.getClass();
        while (type != null && type != Object.class) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Garante um ID para o objeto, gerando e injetando um UUID se necessário. */
    private String ensureId() {
        String id = getId();
        if (id != null && !id.isEmpty()) return id;
        String generated = UUID.randomUUID().toString();
        try {
            injectIdField(this, generated);
            return generated;
        } catch (Exception e) {
            Console.error("Falha ao injetar ID em " + getClass().getSimpleName(), e);
            return null;
        }
    }

    /** Injeta o ID gerado no campo {@code id} (ou no primeiro campo terminado em "id"). */
    private static void injectIdField(Object obj, String id) throws Exception {
        java.lang.reflect.Field idField = null;
        for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
            if (f.getName().equals("id") || f.getName().toLowerCase(Locale.ROOT).endsWith("id")) {
                idField = f;
                break;
            }
        }
        if (idField == null) {
            throw new IllegalStateException("Objeto " + obj.getClass() + " não possui campo ID para injeção");
        }
        idField.setAccessible(true);
        if (idField.getType() == String.class) {
            idField.set(obj, id);
        } else if (idField.getType() == UUID.class) {
            idField.set(obj, UUID.fromString(id));
        } else {
            throw new IllegalStateException("Campo ID deve ser String ou UUID");
        }
    }

    /** Copia os campos de uma instância recém-lida para a instância atual. */
    private static void copyFields(Object from, Object to) {
        for (java.lang.reflect.Field field : from.getClass().getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch (IllegalAccessException ignored) {
                // Campo inacessível (ex: final): mantém o valor atual
            }
        }
    }

    /** Desfaz a transação sem deixar a falha original ser encoberta. */
    private static void rollbackQuietly(Connection conn) {
        if (conn == null) return;
        try {
            if (!conn.getAutoCommit()) conn.rollback();
        } catch (SQLException e) {
            Console.error("Falha ao desfazer a transação do Saveable", e);
        }
    }

    /** Devolve a conexão ao pool em autocommit, como ela foi emprestada. */
    private static void restoreAndClose(Connection conn) {
        if (conn == null) return;
        try {
            if (!conn.getAutoCommit()) conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Conexão será descartada pelo pool
        }
        try {
            conn.close();
        } catch (SQLException e) {
            Console.error("Falha ao devolver conexão ao pool", e);
        }
    }

}
