package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.google.gson.annotations.SerializedName;
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
 * <p><strong>Sem dados em memória:</strong> não existe cache total nem
 * <em>identity map</em>. Cada busca vai ao banco e devolve uma instância nova;
 * cada alteração só existe depois de um {@link #save()} (ou de um
 * {@link #mutate(Class, String, Consumer)}). É isso que permite vários
 * componentes da aplicação — rotas, tarefas agendadas, workers — mexerem nos
 * mesmos registros sem que um sobrescreva o outro a partir de uma cópia velha
 * guardada na RAM.</p>
 *
 * <p><strong>Formato do banco inalterado:</strong> continua um
 * {@code database.db} <strong>por aplicação</strong>, no diretório de trabalho
 * do processo, com a tabela no mesmo formato de sempre
 * ({@code id TEXT PRIMARY KEY, data TEXT NOT NULL}) e gravação por
 * {@code INSERT OR REPLACE}. Bancos criados por versões anteriores da
 * biblioteca seguem funcionando sem migração, e um banco escrito por esta
 * versão continua legível pelas anteriores — nenhuma coluna é adicionada,
 * removida ou renomeada.</p>
 *
 * <p><strong>Concorrência:</strong> um único pool HikariCP para o banco daquela
 * aplicação (antes havia um pool por classe de entidade, todos disputando o
 * mesmo arquivo), com SQLite em modo WAL (leitores não bloqueiam o escritor),
 * {@code busy_timeout} para esperar em vez de falhar e transações
 * {@code IMMEDIATE} — que pegam a trava de escrita já no início, serializando
 * escritas entre threads e entre processos. Sobre isso há travas por registro
 * dentro do processo:</p>
 * <ul>
 *   <li>{@link #save()} — gravação atômica; a última escrita vence;</li>
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
 * ({@code User} → {@code users}). O objeto é serializado em JSON pelo
 * {@link GsonAPI} na coluna {@code data}; a chave primária {@code id} vem de
 * {@link #getId()} (UUID gerado e injetado por reflexão quando ausente).</p>
 *
 * <p><strong>Banco de dados:</strong> {@code database.db} no diretório de
 * trabalho — cada projeto tem o seu, como sempre. Em contêiner, aponte para o
 * volume persistente daquele projeto com {@code ANGATU_DB_PATH=/data/database.db}
 * (ou {@code -Dangatu.db=...}); sem isso o banco vive dentro do contêiner e some
 * no próximo deploy.</p>
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
 * <p><strong>Limitações:</strong> SQLite é um banco de arquivo — dois processos
 * só compartilham o mesmo banco se compartilharem o mesmo arquivo, no mesmo
 * host; para réplicas em máquinas diferentes, use um banco cliente/servidor.
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

    /** Arquivo padrão do banco, relativo ao diretório de trabalho da aplicação. */
    private static final String DEFAULT_DATABASE = "database.db";
    /**
     * Conexões do pool: leitores concorrentes; a escrita é serializada pelo SQLite.
     *
     * <p>Configurável por {@code ANGATU_DB_POOL_SIZE}. Cada conexão carrega o próprio cache de
     * páginas do SQLite (ver {@link #CACHE_SIZE_KIB}), que é memória <strong>nativa</strong>:
     * ela não aparece no gráfico de heap e é contada inteira pelo limite do contêiner. Num
     * contêiner apertado, o número de conexões é uma decisão de memória, não só de
     * concorrência — e precisa poder ser mudado sem recompilar a biblioteca.</p>
     */
    private static final int POOL_SIZE = intDoAmbiente("ANGATU_DB_POOL_SIZE", 12, 1, 64);

    /**
     * Cache de páginas do SQLite por conexão, em <strong>KiB</strong>. Configurável por
     * {@code ANGATU_DB_CACHE_KIB}.
     *
     * <h3>Por que o número virou negativo</h3>
     * <p>O {@code PRAGMA cache_size} tem duas unidades, decididas pelo sinal: positivo conta
     * <strong>páginas</strong>, negativo conta <strong>KiB</strong>. O valor que estava aqui era
     * {@code 10000} — positivo, portanto dez mil páginas. Com página de 4 KiB são cerca de
     * <strong>40 MB por conexão</strong>, e com o pool cheio, até 480 MB de memória nativa.
     * Dentro de um contêiner de 1 GB isso sozinho basta para o processo ser morto pelo sistema,
     * e sem nenhum sinal no heap que explique por quê.</p>
     *
     * <p>Em KiB o número passa a dizer o que parecia dizer. O padrão de 8 MB por conexão dá
     * até 96 MB com o pool cheio — folgado para consultas indexadas, que é o que a biblioteca
     * faz, e recuperável: o SQLite só chega perto do teto quando precisa.</p>
     */
    private static final int CACHE_SIZE_KIB = intDoAmbiente("ANGATU_DB_CACHE_KIB", 8_192, 64, 1_048_576);
    /**
     * Tempo que uma conexão espera pela trava de escrita antes de desistir (ms). Configurável por
     * {@code ANGATU_DB_BUSY_TIMEOUT_MS}.
     *
     * <p>Dentro do processo a espera passou a ser desnecessária: a trava de escritor único do
     * {@link #write(String, SqlWork)} garante uma transação de escrita por vez. Este tempo existe
     * para os escritores que a biblioteca <strong>não</strong> controla — comando de manutenção
     * numa conexão própria, rotina de backup, outro processo com o mesmo arquivo aberto. Cinco
     * segundos era pouco para um checkpoint ou um backup de banco grande.</p>
     */
    private static final String BUSY_TIMEOUT_MS =
            String.valueOf(intDoAmbiente("ANGATU_DB_BUSY_TIMEOUT_MS", 30_000, 1_000, 300_000));
    /** Quantidade de travas por faixa (striped locks) — limita a disputa sem crescer sem fim. */
    private static final int LOCK_STRIPES = 64;
    /** Nomes de campo aceitos no SQL de índice/consulta por campo. */
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // ==================== ESTADO GLOBAL ====================

    /** Pool do banco desta aplicação (antes havia um pool por classe de entidade). */
    private static volatile HikariDataSource dataSource;
    private static final Object DATA_SOURCE_LOCK = new Object();

    /** Classes cuja tabela já foi criada nesta execução. */
    private static final Set<Class<?>> PREPARED_TABLES = ConcurrentHashMap.newKeySet();
    private static final Object PREPARE_LOCK = new Object();

    /** Conexão presa à thread enquanto durar uma transação explícita. */
    private static final ThreadLocal<Connection> CURRENT_TRANSACTION = new ThreadLocal<>();

    /**
     * Conexão de leitura presa à thread enquanto durar uma leitura — para que uma leitura
     * <strong>dentro</strong> de outra não peça uma segunda conexão ao pool.
     *
     * <h3>O impasse que isto evita</h3>
     * <p>Desserializar uma entidade pode disparar uma consulta: basta o construtor da classe
     * consultar alguma coisa — gerar um identificador que ainda não exista, por exemplo. Isso
     * acontece <strong>dentro</strong> da leitura que já tem uma conexão emprestada, e em
     * {@link #query(Class, String, Object...)} acontece com o cursor aberto, uma vez por linha
     * lida.</p>
     *
     * <p>O resultado é cada thread segurando uma conexão e pedindo outra. Com N threads e um pool
     * de N conexões, todas seguram a primeira e esperam pela segunda: <strong>ninguém solta, e
     * ninguém anda</strong>. O pool estoura o tempo de espera e devolve erro em <em>toda</em>
     * leitura — e uma leitura que falha era o começo da pior sequência deste sistema: registro
     * único "não encontrado", seguido de gravação dos valores de fábrica por cima do real.</p>
     *
     * <p>Reaproveitando a conexão, uma thread nunca segura duas. O impasse deixa de ser possível,
     * em vez de ficar dependendo de o pool ser maior que a concorrência do momento.</p>
     */
    private static final ThreadLocal<Connection> CURRENT_READ = new ThreadLocal<>();

    /** Travas por faixa: serializam leitura-alteração-gravação do mesmo registro. */
    private static final ReentrantLock[] ROW_LOCKS = new ReentrantLock[LOCK_STRIPES];

    /**
     * Escritor único do processo. O SQLite aceita uma escrita por vez no arquivo; esta trava faz
     * a fila <strong>aqui</strong>, onde esperar é barato e ordenado, em vez de deixar as conexões
     * disputarem a trava do arquivo e uma delas estourar {@code SQLITE_BUSY}.
     *
     * <p>Justa ({@code true}) de propósito: sem isso, sob rajada de rastreamento, a gravação
     * ocasional de uma tela poderia esperar indefinidamente atrás das gravações de GPS.</p>
     */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock(true);

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) ROW_LOCKS[i] = new ReentrantLock();
    }

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

    // ==================== INSTÂNCIA ====================

    /**
     * Grava o objeto no banco ({@code INSERT OR REPLACE}), de forma atômica.
     *
     * <p>A gravação acontece dentro de uma transação de escrita: ou o registro
     * fica completo, ou nada muda. Quando dois componentes gravam o mesmo
     * registro ao mesmo tempo, as escritas são serializadas e a última vence —
     * nenhuma delas corrompe o registro, mas a anterior é substituída. Se o que
     * você quer é alterar um campo sem perder a alteração do outro, use
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
            Boolean saved = write("salvar " + type.getSimpleName() + " id=" + id,
                    conn -> writeRow(conn, table, id, json));
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
     * <p>Útil quando o registro pode ter sido alterado por outro componente
     * desde a leitura.</p>
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

        return read("recarregar " + type.getSimpleName() + " id=" + id, conn -> {
            String json = selectJson(conn, table, id);
            if (json == null) return null;
            Object fresh = GsonAPI.get().fromJson(json, type);
            copyFields(fresh, this);
            return this;
        });
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
        return read("buscar " + clazz.getSimpleName() + " id=" + id, conn -> {
            String json = selectJson(conn, table, id);
            return json == null ? null : GsonAPI.get().fromJson(json, clazz);
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
        return query(clazz, "SELECT data FROM " + tableName(clazz));
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
            return query(clazz, "SELECT data FROM " + tableName(clazz)
                    + " WHERE json_extract(data, '$." + jsonKey(clazz, fieldName) + "') = ?", value);
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
            List<T> found = query(clazz, "SELECT data FROM " + tableName(clazz)
                    + " WHERE json_extract(data, '$." + jsonKey(clazz, fieldName) + "') = ? LIMIT 1", value);
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
        Boolean found = read("verificar " + clazz.getSimpleName() + " id=" + id, conn -> {
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
        Long total = read("contar " + clazz.getSimpleName(), conn -> {
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
     * <p>Consultas devem trazer a coluna {@code data} (o JSON do objeto).
     * Comandos que não retornam linhas (DDL, UPDATE, DELETE) são aceitos e
     * devolvem lista vazia.</p>
     *
     * <p><strong>Sempre com parâmetros posicionais</strong> — nunca concatene
     * valores no SQL:</p>
     * <pre>
     * Saveable.createIndex(User.class, "email");
     *
     * List&lt;User&gt; users = Saveable.query(User.class,
     *     "SELECT data FROM users WHERE json_extract(data, '$.email') = ?", email);
     *
     * List&lt;User&gt; page = Saveable.query(User.class,
     *     "SELECT data FROM users ORDER BY id LIMIT 100 OFFSET ?", 0);
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
        List<T> list = read("consultar " + clazz.getSimpleName(), conn -> {
            List<T> found = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                if (!ps.execute()) return found; // comando sem resultado (DDL/UPDATE/DELETE)
                try (ResultSet rs = ps.getResultSet()) {
                    int dataColumn = columnIndex(rs.getMetaData(), "data");
                    if (dataColumn == 0) {
                        throw new UnsupportedOperationException(
                                "Consulta customizada deve retornar a coluna 'data' com o JSON do objeto: " + sql);
                    }
                    Gson gson = GsonAPI.get();
                    while (rs.next()) found.add(gson.fromJson(rs.getString(dataColumn), clazz));
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
     * (e-mail, token de sessão, chave estrangeira). Índice não altera a
     * estrutura da tabela: o banco continua compatível com qualquer versão da
     * biblioteca.</p>
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
        Boolean created = write("criar índice " + index, conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS " + index + " ON " + table
                        + "(json_extract(data, '$." + jsonKey(clazz, fieldName) + "'))");
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
     * <p>É a forma correta de mexer em registro disputado. A transação de
     * escrita é exclusiva: enquanto o bloco roda, nenhum outro componente grava
     * naquele banco, e o que a alteração enxerga é o estado atual do registro,
     * não uma cópia lida antes.</p>
     *
     * <pre>
     * Saveable.mutate(Account.class, id, account -&gt; account.setBalance(account.getBalance() + 100));
     * </pre>
     *
     * @param clazz  Classe da entidade
     * @param id     Identificador do registro
     * @param change Alteração a aplicar sobre o estado atual do registro
     * @param <T>    Tipo da entidade
     * @return Objeto já alterado e gravado, ou {@code null} se o registro não existir
     */
    public static <T extends Saveable> T mutate(Class<T> clazz, String id, Consumer<T> change) {
        if (id == null || change == null) return null;
        prepare(clazz);
        String table = tableName(clazz);

        ReentrantLock lock = acquireRowLock(clazz, id);
        try {
            return write("alterar " + clazz.getSimpleName() + " id=" + id, conn -> {
                String json = selectJson(conn, table, id);
                if (json == null) return null;

                T obj = GsonAPI.get().fromJson(json, clazz);
                change.accept(obj);
                writeRow(conn, table, id, GsonAPI.get().toJson(obj));
                return obj;
            });
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
            Boolean deleted = write("excluir " + clazz.getSimpleName() + " id=" + id, conn -> {
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
        Integer deleted = write("excluir todos os " + clazz.getSimpleName(), conn -> {
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

        // Mesma trava de escritor único do write(): uma transação explícita é uma escrita como
        // qualquer outra, e deixá-la de fora traria de volta a disputa que aquela trava remove.
        WRITE_LOCK.lock();
        try {
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
                    boolean desfeita = committed || rollbackQuietly(conn);
                    restoreAndClose(conn, desfeita);
                }
            }
        } finally {
            WRITE_LOCK.unlock();
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
     * Caminho do arquivo do banco desta aplicação.
     *
     * <p>Padrão: {@code database.db} no diretório de trabalho — um por projeto,
     * como sempre. Em contêiner, defina {@code ANGATU_DB_PATH} (ou
     * {@code -Dangatu.db}) apontando para o volume persistente daquele projeto.</p>
     *
     * @return Caminho do arquivo SQLite
     */
    public static String databasePath() {
        String path = System.getProperty("angatu.db");
        if (path == null || path.isBlank()) path = System.getenv("ANGATU_DB_PATH");
        return path == null || path.isBlank() ? DEFAULT_DATABASE : path.trim();
    }

    // ==================== INFRAESTRUTURA ====================

    /** Operação que usa uma conexão já resolvida (do pool ou da transação). */
    @FunctionalInterface
    private interface SqlWork<R> {
        R apply(Connection conn) throws SQLException;
    }

    /**
     * Executa uma leitura. Fora de transação, a conexão vem do pool em
     * autocommit — leitores não bloqueiam nem são bloqueados no modo WAL.
     *
     * <p>Falha de banco <strong>sobe</strong> como {@link PersistenceException}. Devolver um valor
     * de recuo aqui era o que fazia "o banco falhou" chegar ao chamador disfarçado de "não há
     * nada" — ver o Javadoc daquela classe.</p>
     */
    private static <R> R read(String what, SqlWork<R> work) {
        Connection running = CURRENT_TRANSACTION.get();
        if (running != null) {
            try {
                return work.apply(running);
            } catch (SQLException e) {
                throw new IllegalStateException("Erro ao " + what, e);
            }
        }

        // LEITURA DENTRO DE LEITURA REAPROVEITA A CONEXÃO. Ver o Javadoc de CURRENT_READ: pegar
        // uma segunda conexão aqui é o caminho direto para o pool secar e o sistema parar.
        Connection aberta = CURRENT_READ.get();
        if (aberta != null) {
            try {
                return work.apply(aberta);
            } catch (SQLException e) {
                Console.error("Erro ao " + what, e);
                throw new PersistenceException("Erro ao " + what, e);
            }
        }

        try (Connection conn = dataSource().getConnection()) {
            CURRENT_READ.set(conn);
            return work.apply(conn);
        } catch (SQLException e) {
            Console.error("Erro ao " + what, e);
            throw new PersistenceException("Erro ao " + what, e);
        } finally {
            CURRENT_READ.remove();
        }
    }

    /**
     * Executa uma escrita dentro de uma transação. Se já houver transação na
     * thread, a operação entra nela — o commit fica com quem abriu.
     *
     * <h3>Escritor único</h3>
     * <p>O SQLite aceita <strong>um escritor por arquivo</strong>. Sem esta trava, as conexões do
     * pool disputavam essa exclusividade no {@code BEGIN IMMEDIATE} logo abaixo: a perdedora
     * esperava o {@code busy_timeout} e estourava {@code SQLITE_BUSY} — um erro de disputa que a
     * aplicação recebia como "não consegui", e que em alguns caminhos virava perda de dado.</p>
     *
     * <p>Serializando aqui, dentro do processo, nunca há duas transações de escrita ao mesmo
     * tempo e a disputa <strong>deixa de existir</strong> — em vez de ser escondida por um tempo
     * de espera maior. As leituras seguem concorrentes: é para isso que serve o WAL.</p>
     *
     * <p>A ordem de aquisição é sempre {@code faixa do registro → trava de escrita}: a trava de
     * faixa não é tomada dentro de transação (ver {@link #acquireRowLock(Class, String)}), então
     * não existe caminho que as pegue na ordem inversa.</p>
     */
    private static <R> R write(String what, SqlWork<R> work) {
        Connection running = CURRENT_TRANSACTION.get();
        if (running != null) {
            try {
                return work.apply(running);
            } catch (SQLException e) {
                // Dentro de transação, falhar em silêncio corromperia o "tudo ou nada"
                throw new IllegalStateException("Erro ao " + what, e);
            }
        }

        WRITE_LOCK.lock();
        try {
            Connection conn = null;
            boolean committed = false;
            try {
                conn = dataSource().getConnection();
                conn.setAutoCommit(false);
                // A desserialização feita aqui dentro pode disparar consulta (ver CURRENT_READ).
                // Sem esta linha ela pediria uma SEGUNDA conexão — e pediria segurando a trava de
                // escrita, que é o pior lugar possível para esperar.
                CURRENT_READ.set(conn);
                R result = work.apply(conn);
                conn.commit();
                committed = true;
                return result;
            } catch (SQLException e) {
                Console.error("Erro ao " + what, e);
                throw new PersistenceException("Erro ao " + what, e);
            } finally {
                CURRENT_READ.remove();
                if (conn != null) {
                    boolean desfeita = committed || rollbackQuietly(conn);
                    restoreAndClose(conn, desfeita);
                }
            }
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Cria o pool na primeira utilização.
     *
     * <p>Um pool para o banco desta aplicação (não mais um por classe de
     * entidade): o SQLite tem um escritor por vez, então vários pools apenas
     * multiplicavam conexões disputando a mesma trava do mesmo arquivo. O modo
     * WAL libera as leituras, o {@code busy_timeout} faz a escrita esperar em
     * vez de estourar {@code SQLITE_BUSY} e o {@code transaction_mode=IMMEDIATE}
     * garante que toda transação de escrita pegue a trava logo no início — é
     * isso que impede duas alterações concorrentes de se sobreporem.</p>
     */
    /**
     * Lê um inteiro do ambiente, preso entre um mínimo e um máximo.
     *
     * <p>Valor ausente, vazio ou ilegível volta ao padrão em silêncio: uma variável de ambiente
     * escrita errada não pode impedir a aplicação de subir — ela subiria sem banco, que é pior
     * do que subir com o padrão.</p>
     *
     * <p>É {@code System.getenv} e não o {@code Env} da biblioteca de propósito: isto roda na
     * inicialização estática desta classe, antes de qualquer coisa do projeto existir, e
     * depender de outra classe da biblioteca aqui criaria um ciclo de carregamento. Na
     * hospedagem os dois leem a mesma coisa.</p>
     */
    private static int intDoAmbiente(String chave, int padrao, int minimo, int maximo) {
        try {
            String v = System.getenv(chave);
            if (v == null || v.isBlank()) return padrao;
            int n = Integer.parseInt(v.trim());
            return Math.max(minimo, Math.min(maximo, n));
        } catch (Throwable t) {
            return padrao;
        }
    }

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
            // Negativo = KiB. Positivo seria PÁGINAS, e é essa troca de unidade que fazia o
            // valor anterior reservar dez vezes mais memória nativa do que parecia.
            config.addDataSourceProperty("cache_size", -CACHE_SIZE_KIB);
            config.addDataSourceProperty("temp_store", "MEMORY");

            dataSource = new HikariDataSource(config);
            Console.log("&7Banco SQLite: &f%s &7(WAL, %d conexões, cache de %d MiB por conexão, escrita serializada)",
                    path, POOL_SIZE, Integer.valueOf(CACHE_SIZE_KIB / 1024));
            return dataSource;
        }
    }

    /**
     * Garante que a tabela da classe exista, no mesmo formato de sempre
     * ({@code id}, {@code data}). Executa uma vez por classe, por execução.
     *
     * <p>Nenhuma coluna é adicionada ou alterada: o banco de um sistema que roda
     * outra versão da biblioteca continua idêntico e compatível.</p>
     */
    private static void prepare(Class<?> type) {
        if (PREPARED_TABLES.contains(type)) return;
        synchronized (PREPARE_LOCK) {
            if (PREPARED_TABLES.contains(type)) return;

            String table = tableName(type);
            String sql = "CREATE TABLE IF NOT EXISTS " + table + " (id TEXT PRIMARY KEY, data TEXT NOT NULL)";
            // Mesma regra do CURRENT_READ: se a thread já tem conexão emprestada, use aquela.
            // Preparar a tabela de uma classe que aparece pela primeira vez durante uma leitura
            // pediria a segunda conexão exatamente como o resto pedia.
            Connection running = CURRENT_TRANSACTION.get();
            if (running == null) running = CURRENT_READ.get();
            try {
                if (running != null) {
                    createTable(running, sql);
                } else {
                    try (Connection conn = dataSource().getConnection()) {
                        createTable(conn, sql);
                    }
                }
                PREPARED_TABLES.add(type);
            } catch (SQLException e) {
                throw new IllegalStateException("Erro ao preparar a tabela " + table, e);
            }
        }
    }

    /** Executa o CREATE TABLE IF NOT EXISTS da entidade. */
    private static void createTable(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Grava o registro no formato de sempre: {@code INSERT OR REPLACE (id, data)}. */
    private static boolean writeRow(Connection conn, String table, String id, String json) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO " + table + " (id, data) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, json);
            ps.executeUpdate();
            return true;
        }
    }

    /** Lê o JSON de um registro pelo ID. */
    private static String selectJson(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("data") : null;
            }
        }
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

    /**
     * Nome da chave no JSON persistido: normalmente igual ao campo Java, exceto
     * quando o campo declara {@code @SerializedName}.
     */
    private static String jsonKey(Class<?> clazz, String fieldName) {
        Class<?> type = clazz;
        while (type != null && type != Object.class) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                SerializedName annotation = field.getAnnotation(SerializedName.class);
                return annotation == null ? fieldName : annotation.value();
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return fieldName;
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

    /**
     * Desfaz a transação sem deixar a falha original ser encoberta.
     *
     * @return {@code true} se a transação foi mesmo desfeita (ou não havia nenhuma aberta)
     */
    private static boolean rollbackQuietly(Connection conn) {
        if (conn == null) return true;
        try {
            if (!conn.getAutoCommit()) conn.rollback();
            return true;
        } catch (SQLException e) {
            Console.error("Falha ao desfazer a transação do Saveable", e);
            return false;
        }
    }

    /**
     * Devolve a conexão ao pool em autocommit, como ela foi emprestada.
     *
     * <h3>Conexão com transação em estado desconhecido não volta ao pool</h3>
     * <p>Quando o {@code rollback} falha — e ele falha, tipicamente por disputa de trava — a
     * conexão continua com uma transação aberta e parcialmente escrita. Devolvê-la assim era
     * <strong>pior do que perdê-la</strong>: o {@code setAutoCommit(true)} logo abaixo emite
     * {@code commit} no driver do SQLite, ou seja, <strong>confirmaria</strong> exatamente o que
     * deveria ter sido desfeito. E a conexão seguinte a pegar essa conexão emprestada herdaria o
     * que sobrou.</p>
     *
     * <p>Por isso, transação não desfeita significa conexão <strong>descartada</strong>: o pool
     * abre outra, limpa. Uma conexão custa milissegundos; uma gravação parcial confirmada custa
     * o dado.</p>
     *
     * @param desfeita {@code false} quando o {@code rollback} não pôde ser concluído
     */
    private static void restoreAndClose(Connection conn, boolean desfeita) {
        if (conn == null) return;

        if (!desfeita) {
            // evictConnection MARCA a conexão para ser descartada; quem a devolve ao pool ainda é
            // o close(). Sem os dois, a conexão fica emprestada para sempre e o pool seca — o
            // efeito é indistinguível de um travamento do sistema inteiro.
            try {
                HikariDataSource ds = dataSource;
                if (ds != null && !ds.isClosed()) ds.evictConnection(conn);
            } catch (Throwable t) {
                Console.error("Falha ao marcar conexão para descarte", t);
            }
            try {
                conn.close();
            } catch (Throwable t) {
                Console.error("Falha ao descartar conexão com transação pendente", t);
            }
            return;
        }

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
