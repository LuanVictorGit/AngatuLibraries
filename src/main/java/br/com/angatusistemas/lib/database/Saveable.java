package br.com.angatusistemas.lib.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * [PT] Classe abstrata que fornece persistência automática em SQLite para objetos Java,
 * com cache total em memória (identity map) que garante a mesma instância para cada ID.
 *
 * <p><strong>Quando usar:</strong> para persistir entidades simples (dezenas de
 * milhares de registros) sem SQL manual — estenda esta classe, implemente
 * {@link #getId()} e use os métodos estáticos de busca/salvamento. O fluxo
 * típico: <em>estender → criar com construtor vazio → preencher campos →
 * {@code save()} → buscar com {@link #findById(Class, String)}</em>.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para tabelas com milhões de registros
 * (o cache total carrega tudo em memória), para relacionamentos complexos ou
 * consultas analíticas (use SQL direto com o driver) e para dados binários
 * grandes (ex: imagens — considere armazenar em disco e persistir o caminho).
 * <strong>Não instancie esta classe diretamente</strong> — é abstrata e o
 * construtor é {@code protected}: o uso é exclusivamente via {@code extends}.</p>
 *
 * <p><strong>Restrição de inicialização:</strong> esta classe funciona apenas
 * por herança. Subclasses precisam de um construtor vazio (para o Gson
 * desserializar) e de campos serializáveis. Instanciação direta é bloqueada
 * pelo compilador (classe abstrata).</p>
 *
 * <p><strong>Integração:</strong> entidades internas como {@code PermanentBlock},
 * {@code SuspectIp}, {@code RouteRateLimitConfig}, {@code Key} (Web Push) e
 * {@code Image} estendem esta classe; a serialização usa {@link GsonAPI} e as
 * dependências (sqlite-jdbc, HikariCP, gson) são verificadas no primeiro uso
 * com instruções de instalação se ausentes.</p>
 *
 * <p><strong>Boas práticas:</strong> crie índices via
 * {@code json_extract(data, '$.campo')} para consultas frequentes; use
 * {@link #query(Class, String, Object...)} com parâmetros posicionais (nunca
 * concatene SQL); chame {@link #shutdown()} ao encerrar a aplicação.</p>
 *
 * <p><strong>Limitações:</strong> cache total em memória (ideal para até
 * centenas de milhares de registros); banco fixo {@code database.db} na raiz do
 * projeto; escrita usa {@code INSERT OR REPLACE} (substituição por ID).</p>
 *
 * <p><strong>Extensões futuras:</strong> o método privado
 * {@code loadAllIntoCache} pode ser substituído por cache lazy em subclasses;
 * a classe não é {@code sealed} propositalmente — consumidores precisam
 * estendê-la para criar entidades.</p>
 * <p>
 * Cada subclasse concreta (ex: {@code Usuario}, {@code Produto}) é mapeada para uma tabela própria
 * no banco de dados {@code database.db}. O nome da tabela é o nome da classe em minúsculas,
 * acrescido de 's' se não terminar com 's' (ex: {@code Usuario} → {@code usuarios},
 * {@code Produto} → {@code produtos}, {@code Pessoa} → {@code pessoas}).
 * </p>
 * <p>
 * Os objetos são serializados em JSON (via Gson) e armazenados em uma coluna {@code data}.
 * A tabela possui uma chave primária {@code id} (TEXT), que é o identificador único do objeto.
 * </p>
 * <p>
 * <b>Cache total:</b> Ao primeiro acesso a uma classe (ex: {@link #findById} ou {@link #findAll}),
 * todos os registros da tabela são carregados para um cache em memória. A partir daí,
 * qualquer operação de busca retorna a <strong>mesma instância Java</strong> para um mesmo ID.
 Isso resolve problemas de concorrência e inconsistência (ex: modificar um objeto em dois lugares diferentes).
 * </p>
 * <p>
 * <b>Gerenciamento de ID:</b>
 * O ID é obtido através do método abstrato {@link #getId()}. Se o objeto não tiver um ID
 * (retornar {@code null} ou vazio), um UUID aleatório é gerado e injetado via reflexão no campo
 * chamado "id" ou em qualquer campo que termine com "id" (case‑insensitive). O objeto então
 * passa a ter esse ID permanentemente.
 * </p>
 * <p>
 * <b>Sincronização:</b>
 * Os métodos {@link #save()}, {@link #delete()} e {@link #deleteById(Class, String)} mantêm
 * o cache atualizado automaticamente. O método {@link #reload()} recarrega os dados do banco
 * e atualiza a instância atual (que permanece a mesma no cache).
 * </p>
 * <p>
 * <b>Concorrência e performance:</b>
 * A classe utiliza um pool de conexões HikariCP (máx. 20 conexões) e configura o SQLite em modo
 * WAL ({@code PRAGMA journal_mode=WAL}), permitindo leituras concorrentes durante escritas.
 * Escritas são transacionais e bloqueiam apenas a linha em questão (devido ao uso de
 * {@code INSERT OR REPLACE}). Leituras por ID são extremamente rápidas (acesso direto ao cache).
 * </p>
 * <p>
 * <b>Suporte a milhões de objetos:</b>
 * <strong>Atenção:</strong> O cache total carrega <strong>todos</strong> os objetos da tabela na memória.
 * Para tabelas com milhões de registros, isso pode causar {@code OutOfMemoryError}.
 * Se você precisa trabalhar com grandes volumes, modifique o método {@link #loadAllIntoCache(Class)}
 * para implementar um cache lazy (sob demanda). Esta implementação é ideal para conjuntos de dados
 * de até centenas de milhares de registros.
 * </p>
 * <p>
 * <b>Índices customizados:</b>
 * Você pode criar índices em campos extraídos do JSON usando a função {@code json_extract}.
 * Exemplo:
 * <pre>
 * Saveable.query(Usuario.class,
 *     "CREATE INDEX IF NOT EXISTS idx_nome ON usuarios(json_extract(data, '$.nome'))");
 * </pre>
 * </p>
 * <p>
 * <b>Encerramento do pool:</b>
 * Ao final da aplicação, chame {@link #shutdown()} para fechar todas as conexões e limpar o cache.
 * </p>
 *
 * [EN] Abstract class that provides automatic SQLite persistence for Java objects,
 * with full in‑memory caching (identity map) ensuring the same instance per ID.
 * <p>
 * Each concrete subclass (e.g. {@code User}, {@code Product}) is mapped to its own table
 * in the {@code database.db} file. The table name is the lowercased class name, plus an 's'
 * if it doesn't already end with 's' (e.g. {@code User} → {@code users}).
 * </p>
 * <p>
 * Objects are serialized to JSON (via Gson) and stored in a {@code data} column.
 * The table has a primary key {@code id} (TEXT) which is the unique identifier.
 * </p>
 * <p>
 * <b>Full caching:</b> On first access to a class (e.g. {@link #findById} or {@link #findAll}),
 * all records are loaded into an in‑memory cache. From that point on, any lookup returns the
 * <strong>same Java instance</strong> for a given ID. This solves concurrency and inconsistency issues
 * (e.g., modifying an object in two different places).
 * </p>
 * <p>
 * <b>ID management:</b>
 * The ID is obtained via the abstract method {@link #getId()}. If the object has no ID
 * (returns {@code null} or empty), a random UUID is generated and injected via reflection
 * into a field named "id" or any field ending with "id" (case‑insensitive). The object then
 * permanently owns that ID.
 * </p>
 * <p>
 * <b>Synchronization:</b>
 * Methods {@link #save()}, {@link #delete()} and {@link #deleteById(Class, String)} keep the cache
 * updated automatically. {@link #reload()} fetches fresh data from the database and updates the
 * current instance (which remains the same in the cache).
 * </p>
 * <p>
 * <b>Concurrency and performance:</b>
 * A HikariCP connection pool (max 20 connections) is used. SQLite is configured in WAL mode
 * ({@code PRAGMA journal_mode=WAL}), allowing concurrent reads during writes.
 * Writes are transactional and lock only the affected row (due to {@code INSERT OR REPLACE}).
 * Reads by ID are extremely fast (direct cache access).
 * </p>
 * <p>
 * <b>Support for millions of objects:</b>
 * <strong>Caution:</strong> Full caching loads <strong>all</strong> objects into memory.
 * For tables with millions of rows, this may cause {@code OutOfMemoryError}.
 * If you work with large datasets, modify {@link #loadAllIntoCache(Class)} to implement lazy caching.
 * This implementation is ideal for up to hundreds of thousands of records.
 * </p>
 * <p>
 * <b>Custom indexes:</b>
 * You can create indexes on JSON fields using the {@code json_extract} function.
 * Example:
 * <pre>
 * Saveable.query(User.class,
 *     "CREATE INDEX IF NOT EXISTS idx_name ON users(json_extract(data, '$.name'))");
 * </pre>
 * </p>
 * <p>
 * <b>Shutdown:</b>
 * Call {@link #shutdown()} when your application terminates to close all connections and clear the cache.
 * </p>
 *
 * @author Equipe Angatu Sistemas
 * @see GsonAPI
 * @see <a href="https://www.sqlite.org/wal.html">SQLite WAL mode</a>
 */
public abstract class Saveable {

    // Pool de conexões por classe
    private static final Map<Class<?>, HikariDataSource> DATA_SOURCES = new HashMap<>();
    private static final Object DATA_SOURCE_LOCK = new Object();

    // Cache principal: classe -> (id -> instância)
    // Carregado completamente na primeira vez que a classe é acessada
    private static final Map<Class<?>, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    /** Coordenadas Maven das dependências do módulo de persistência. */
    private static final String SQLITE_COORDINATES = "org.xerial:sqlite-jdbc:3.51.3.0";
    private static final String HIKARI_COORDINATES = "com.zaxxer.hikari:HikariCP:7.0.2";
    private static final String PERSISTENCE_FEATURE = "Persistência (Saveable)";

    // ==================== CONSTRUTOR ====================

    /**
     * Construtor {@code protected}: o {@code Saveable} funciona exclusivamente
     * por herança ({@code extends}).
     *
     * <p><strong>Forma correta de uso:</strong> crie uma entidade concreta que
     * estenda {@code Saveable} e implemente {@link #getId()}:
     * <pre>
     * public class Usuario extends Saveable {
     *     private String id;
     *     private String nome;
     *     public Usuario() {} // obrigatório para desserialização Gson
     *     &#64;Override public String getId() { return id; }
     * }
     * </pre>
     * </p>
     *
     * <p><strong>Uso incorreto:</strong> instanciar {@code Saveable} diretamente
     * é impossível — a classe é abstrata e o construtor é {@code protected}.
     * Subclasses anônimas também são desencorajadas: uma entidade deve ter
     * campos persistidos e um construtor vazio para o Gson.</p>
     *
     * <p>A manipulação dos dados usa os métodos estáticos da classe
     * ({@link #findById(Class, String)}, {@link #findAll(Class)},
     * {@link #query(Class, String, Object...)} etc.), nunca a instanciação
     * manual do {@code Saveable}.</p>
     */
    protected Saveable() {
        // Construtor protegido: garante que a classe só seja utilizada via herança
    }

    // ==================== MÉTODOS ABSTRATOS ====================

    /**
     * [PT] Retorna o identificador único do objeto.
     * <p>
     * A implementação deve simplesmente retornar o valor do campo que representa o ID
     * (ex: {@code return this.id;}). Se o objeto ainda não tiver um ID (campo nulo),
     * este método pode retornar {@code null} – um UUID será gerado e injetado automaticamente.
     * </p>
     *
     * [EN] Returns the unique identifier of the object.
     * <p>
     * The implementation should simply return the value of the ID field
     * (e.g. {@code return this.id;}). If the object does not yet have an ID (field is null),
     * this method may return {@code null} – a UUID will be generated and injected automatically.
     * </p>
     *
     * @return [PT] string do ID ou {@code null} se ainda não definido
     *         [EN] ID string or {@code null} if not yet set
     */
    public abstract String getId();

    // ==================== MÉTODOS DE INSTÂNCIA ====================

    /**
     * [PT] Salva o objeto atual no banco de dados (INSERT OR REPLACE).
     * <p>
     * Se o objeto não possuir um ID, um UUID é gerado, injetado no objeto via reflexão,
     * e então o registro é salvo. Após salvar, o cache é atualizado com a mesma instância.
     * Operação thread-safe.
     * </p>
     *
     * [EN] Saves the current object to the database (INSERT OR REPLACE).
     * <p>
     * If the object has no ID, a UUID is generated, injected via reflection,
     * and then the record is saved. After saving, the cache is updated with the same instance.
     * Thread-safe operation.
     * </p>
     *
     * @return [PT] {@code true} se salvo com sucesso
     *         [EN] {@code true} if saved successfully
     */
    public boolean save() {
        // Garante que o cache está carregado antes de modificar
        ensureCacheLoaded(this.getClass());

        String id = getId();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            try {
                injectIdField(this, id);
            } catch (Exception e) {
                Console.error("Falha ao injetar ID em %s", e, this.getClass().getSimpleName());
                return false;
            }
        }
        String tableName = getTableName(this.getClass());
        String json = GsonAPI.get().toJson(this);
        String sql = "INSERT OR REPLACE INTO " + tableName + " (id, data) VALUES (?, ?)";
        try (Connection conn = getDataSource(this.getClass()).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, json);
            pstmt.executeUpdate();
            // Atualiza o cache com a mesma instância (já é a atual)
            cachePut(this.getClass(), id, this);
            return true;
        } catch (SQLException e) {
            Console.error("Erro ao salvar %s id=%s", e, this.getClass().getSimpleName(), id);
            return false;
        }
    }

    /**
     * [PT] Exclui o objeto atual do banco de dados, baseado em seu ID.
     * Também o remove do cache.
     *
     * [EN] Deletes the current object from the database based on its ID.
     * Also removes it from the cache.
     *
     * @return [PT] {@code true} se o registro foi removido ou não existia
     *         [EN] {@code true} if the record was removed or did not exist
     */
    public boolean delete() {
        String id = getId();
        if (id == null) return false;
        return deleteById(this.getClass(), id);
    }

    /**
     * [PT] Recarrega os dados do objeto a partir do banco de dados, sobrescrevendo
     * os campos atuais com os valores persistidos.
     * <p>
     * Útil quando o objeto pode ter sido modificado externamente. A instância
     * permanece a mesma (e continua no cache).
     * </p>
     *
     * [EN] Reloads the object's data from the database, overwriting current fields
     * with persisted values.
     * <p>
     * Useful when the object may have been modified externally. The instance remains
     * the same (and stays in the cache).
     * </p>
     *
     * @return [PT] a própria instância recarregada, ou {@code null} se o ID for inválido ou não encontrado
     *         [EN] the reloaded instance itself, or {@code null} if ID is invalid or not found
     */
    public Saveable reload() {
        String id = getId();
        if (id == null) return null;

        ensureCacheLoaded(this.getClass());

        String tableName = getTableName(this.getClass());
        String sql = "SELECT data FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getDataSource(this.getClass()).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String json = rs.getString("data");
                Saveable fresh = (Saveable) GsonAPI.get().fromJson(json, this.getClass());
                copyFields(fresh, this);
                // A instância já está no cache (garantido pelo carregamento inicial ou save)
                cachePut(this.getClass(), id, this);
                return this;
            }
            return null;
        } catch (SQLException e) {
            Console.error("Erro ao recarregar %s id=%s", e, this.getClass().getSimpleName(), id);
            return null;
        }
    }

    // ==================== MÉTODOS ESTÁTICOS (MANAGER) ====================

    /**
     * [PT] Busca um objeto pelo ID. Sempre retorna a mesma instância para um mesmo ID.
     * <p>
     * Performance: acesso direto ao cache (O(1)). Milissegundos mesmo com milhões de registros.
     * Se o cache ainda não foi carregado, todos os registros da tabela são carregados na memória.
     * </p>
     *
     * [EN] Finds an object by its ID. Always returns the same instance for the same ID.
     * <p>
     * Performance: direct cache access (O(1)). Milliseconds even with millions of records.
     * If the cache hasn't been loaded yet, all records are loaded into memory.
     * </p>
     *
     * @param clazz [PT] classe do objeto (ex: Usuario.class)
     *              [EN] class of the object (e.g. User.class)
     * @param id    [PT] identificador único
     *              [EN] unique identifier
     * @param <T>   [PT] tipo da classe
     *              [EN] type of the class
     * @return [PT] objeto encontrado ou {@code null}
     *         [EN] found object or {@code null}
     */
    public static <T> T findById(Class<T> clazz, String id) {
        ensureCacheLoaded(clazz);
        Map<String, Object> classCache = CACHE.get(clazz);
        if (classCache != null && classCache.containsKey(id)) {
            return clazz.cast(classCache.get(id));
        }
        return null;
    }

    /**
     * [PT] Retorna TODOS os objetos da classe (do cache).
     * <p>
     * <strong>ATENÇÃO:</strong> Este método retorna todos os objetos do cache em memória.
     * Se você estiver usando cache total, isso é rápido mas consome memória.
     * Para grandes volumes, o cache total não é recomendado.
     * </p>
     *
     * [EN] Returns ALL objects of the class (from cache).
     * <p>
     * <strong>WARNING:</strong> This method returns all objects from the in‑memory cache.
     * If you use full caching, this is fast but consumes memory.
     * For large datasets, full caching is not recommended.
     * </p>
     *
     * @param clazz [PT] classe dos objetos
     *              [EN] object class
     * @param <T>   [PT] tipo
     *              [EN] type
     * @return [PT] lista com todos os objetos (pode ser vazia)
     *         [EN] list with all objects (may be empty)
     */
    public static <T> List<T> findAll(Class<T> clazz) {
        ensureCacheLoaded(clazz);
        Map<String, Object> classCache = CACHE.get(clazz);
        if (classCache == null) return new ArrayList<>();
        List<T> result = new ArrayList<>(classCache.size());
        for (Object value : classCache.values()) {
            result.add(clazz.cast(value));
        }
        return result;
    }

    /**
     * [PT] Filtra objetos usando um predicado em memória (sobre o cache).
     * <p>
     * Como opera sobre o cache, é eficiente para conjuntos carregados.
     * </p>
     *
     * [EN] Filters objects using an in‑memory predicate (over the cache).
     * <p>
     * Since it operates on the cache, it is efficient for loaded sets.
     * </p>
     *
     * @param clazz     [PT] classe dos objetos
     *                  [EN] object class
     * @param predicate [PT] condição de teste
     *                  [EN] test condition
     * @param <T>       [PT] tipo
     *                  [EN] type
     * @return [PT] lista filtrada (nunca nula)
     *         [EN] filtered list (never null)
     */
    public static <T> List<T> findByPredicate(Class<T> clazz, Predicate<T> predicate) {
        List<T> all = findAll(clazz);
        List<T> result = new ArrayList<>();
        for (T obj : all) {
            if (predicate.test(obj)) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * [PT] Busca objetos por um campo via reflexão (sobre o cache).
     * <p>
     * Como opera sobre o cache, é rápido para conjuntos carregados.
     * </p>
     *
     * [EN] Finds objects by a field using reflection (over the cache).
     * <p>
     * Since it operates on the cache, it is fast for loaded sets.
     * </p>
     *
     * @param clazz     [PT] classe dos objetos
     *                  [EN] object class
     * @param fieldName [PT] nome exato do campo (ex: "nome")
     *                  [EN] exact field name (e.g., "name")
     * @param value     [PT] valor a ser comparado
     *                  [EN] value to compare
     * @param <T>       [PT] tipo
     *                  [EN] type
     * @return [PT] lista de objetos que possuem o campo com o valor especificado
     *         [EN] list of objects that have the field with the specified value
     */
    public static <T> List<T> findByField(Class<T> clazz, String fieldName, Object value) {
        return findByPredicate(clazz, obj -> {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return Objects.equals(field.get(obj), value);
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * [PT] Exclui um objeto pelo ID (banco e cache).
     *
     * [EN] Deletes an object by its ID (database and cache).
     *
     * @param clazz [PT] classe do objeto
     *              [EN] object class
     * @param id    [PT] identificador
     *              [EN] identifier
     * @return [PT] {@code true} se o registro foi removido
     *         [EN] {@code true} if the record was deleted
     */
    public static boolean deleteById(Class<?> clazz, String id) {
        ensureCacheLoaded(clazz);
        String tableName = getTableName(clazz);
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // CORREÇÃO: definir o parâmetro antes de executar
            pstmt.setString(1, id);
            int affectedRows = pstmt.executeUpdate();
            boolean deleted = affectedRows > 0;
            if (deleted) {
                Map<String, Object> classCache = CACHE.get(clazz);
                if (classCache != null) classCache.remove(id);
            }
            return deleted;
        } catch (SQLException e) {
            Console.error("Erro ao deletar %s id=%s", e, clazz.getSimpleName(), id);
            return false;
        }
    }

    /**
     * [PT] Exclui todos os objetos da classe (remove todos os registros da tabela e limpa o cache).
     *
     * [EN] Deletes all objects of the class (truncates the table and clears the cache).
     *
     * @param clazz [PT] classe dos objetos
     *              [EN] object class
     * @return [PT] número de registros removidos
     *         [EN] number of records removed
     */
    public static int deleteAll(Class<?> clazz) {
        ensureCacheLoaded(clazz);
        String tableName = getTableName(clazz);
        String sql = "DELETE FROM " + tableName;
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate(sql);
            if (deleted > 0) {
                CACHE.remove(clazz); // remove todo o cache da classe
            }
            return deleted;
        } catch (SQLException e) {
            Console.error("Erro ao deletar todos %s", e, clazz.getSimpleName());
            return 0;
        }
    }

    /**
     * [PT] Verifica se existe um objeto com o ID informado (usando cache).
     *
     * [EN] Checks whether an object with the given ID exists (using cache).
     *
     * @param clazz [PT] classe
     *              [EN] class
     * @param id    [PT] identificador
     *              [EN] identifier
     * @return [PT] {@code true} se existir
     *         [EN] {@code true} if exists
     */
    public static boolean exists(Class<?> clazz, String id) {
        ensureCacheLoaded(clazz);
        Map<String, Object> classCache = CACHE.get(clazz);
        return classCache != null && classCache.containsKey(id);
    }

    /**
     * [PT] Retorna a quantidade total de objetos persistidos (tamanho do cache).
     *
     * [EN] Returns the total number of persisted objects (cache size).
     *
     * @param clazz [PT] classe
     *              [EN] class
     * @return [PT] contagem de registros
     *         [EN] count of records
     */
    public static long count(Class<?> clazz) {
        ensureCacheLoaded(clazz);
        Map<String, Object> classCache = CACHE.get(clazz);
        return classCache == null ? 0 : classCache.size();
    }

    /**
     * [PT] Executa uma consulta SQL customizada que retorna objetos a partir da coluna {@code data}.
     * <p>
     * A consulta deve retornar uma coluna chamada {@code data} contendo o JSON do objeto.
     * Os objetos resultantes são transformados para as instâncias cacheadas (garantindo identidade).
     * </p>
     * <p>
     * <b>Exemplo de uso eficiente:</b>
     * <pre>
     * // Cria um índice no campo 'nome' (uma vez)
     * Saveable.query(Usuario.class, "CREATE INDEX IF NOT EXISTS idx_nome ON usuarios(json_extract(data, '$.nome'))");
     *
     * // Busca usuários com nome = 'João'
     * List&lt;Usuario&gt; usuarios = Saveable.query(Usuario.class,
     *     "SELECT data FROM usuarios WHERE json_extract(data, '$.nome') = ?", "João");
     *
     * // Paginação
     * List&lt;Usuario&gt; page = Saveable.query(Usuario.class,
     *     "SELECT data FROM usuarios ORDER BY id LIMIT 100 OFFSET ?", 0);
     * </pre>
     * </p>
     *
     * [EN] Executes a custom SQL query that returns objects from the {@code data} column.
     * <p>
     * The query must return a column named {@code data} containing the object's JSON.
     * The resulting objects are resolved to cached instances (guaranteeing identity).
     * </p>
     * <p>
     * <b>Efficient usage example:</b>
     * <pre>
     * // Create an index on field 'name' (once)
     * Saveable.query(User.class, "CREATE INDEX IF NOT EXISTS idx_name ON users(json_extract(data, '$.name'))");
     *
     * // Find users with name = 'John'
     * List&lt;User&gt; users = Saveable.query(User.class,
     *     "SELECT data FROM users WHERE json_extract(data, '$.name') = ?", "John");
     *
     * // Pagination
     * List&lt;User&gt; page = Saveable.query(User.class,
     *     "SELECT data FROM users ORDER BY id LIMIT 100 OFFSET ?", 0);
     * </pre>
     * </p>
     *
     * @param clazz  [PT] classe destino dos objetos
     *               [EN] target object class
     * @param sql    [PT] consulta SQL (deve conter uma coluna "data")
     *               [EN] SQL query (must contain a "data" column)
     * @param params [PT] parâmetros posicionais (opcional)
     *               [EN] positional parameters (optional)
     * @param <T>    [PT] tipo da classe
     *               [EN] type of the class
     * @return [PT] lista de objetos resultantes (pode ser vazia)
     *         [EN] list of resulting objects (may be empty)
     * @throws UnsupportedOperationException [PT] se a consulta não retornar a coluna "data"
     *                                       [EN] if the query does not return a "data" column
     */
    public static <T> List<T> query(Class<T> clazz, String sql, Object... params) {
        ensureCacheLoaded(clazz);
        List<T> list = new ArrayList<>();
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }
            ResultSet rs = pstmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            boolean hasDataColumn = false;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (meta.getColumnName(i).equalsIgnoreCase("data")) {
                    hasDataColumn = true;
                    break;
                }
            }
            if (!hasDataColumn) {
                throw new UnsupportedOperationException("Query customizada deve retornar uma coluna chamada 'data' contendo o JSON do objeto.");
            }
            Gson gson = GsonAPI.get();
            while (rs.next()) {
                String json = rs.getString("data");
                T obj = gson.fromJson(json, clazz);
                // Obtém o ID do objeto desserializado para buscar a instância cacheada
                String id = extractId(obj);
                if (id != null) {
                    T cached = findById(clazz, id);
                    if (cached != null) {
                        list.add(cached);
                        continue;
                    }
                }
                // Se não encontrou no cache (ex: registro novo inserido externamente), adiciona ao cache
                if (id != null) {
                    cachePut(clazz, id, obj);
                }
                list.add(obj);
            }
        } catch (SQLException e) {
            Console.error("Erro na query customizada: %s", e, sql);
        }
        return list;
    }

    /**
     * [PT] Fecha todos os pools de conexão e limpa o cache. Deve ser chamado ao encerrar a aplicação
     * para evitar vazamento de recursos.
     *
     * [EN] Closes all connection pools and clears the cache. Should be called when shutting down
     * the application to avoid resource leaks.
     */
    public static void shutdown() {
        synchronized (DATA_SOURCE_LOCK) {
            for (HikariDataSource ds : DATA_SOURCES.values()) {
                if (!ds.isClosed()) ds.close();
            }
            DATA_SOURCES.clear();
        }
        CACHE.clear();
    }

    // ==================== MÉTODOS INTERNOS PRIVADOS ====================

    private static HikariDataSource getDataSource(Class<?> clazz) {
        // Verificação lazy (sem quebrar o classload): só dispara quando a
        // persistência for realmente utilizada
        Dependencies.require("org.sqlite.JDBC", SQLITE_COORDINATES, PERSISTENCE_FEATURE);
        Dependencies.require("com.zaxxer.hikari.HikariDataSource", HIKARI_COORDINATES, PERSISTENCE_FEATURE);
        synchronized (DATA_SOURCE_LOCK) {
            if (!DATA_SOURCES.containsKey(clazz)) {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl("jdbc:sqlite:database.db");
                config.setConnectionTestQuery("SELECT 1");
                config.setMaximumPoolSize(20);
                config.setMinimumIdle(2);
                config.setIdleTimeout(30000);
                config.setPoolName("Saveable-" + clazz.getSimpleName());
                config.addDataSourceProperty("journal_mode", "WAL");
                config.addDataSourceProperty("synchronous", "NORMAL");
                config.addDataSourceProperty("cache_size", 10000);
                config.addDataSourceProperty("temp_store", "MEMORY");
                DATA_SOURCES.put(clazz, new HikariDataSource(config));
                createTable(clazz);
                // Carrega todos os registros da tabela para o cache
                loadAllIntoCache(clazz);
            }
            return DATA_SOURCES.get(clazz);
        }
    }

    private static void createTable(Class<?> clazz) {
        String tableName = getTableName(clazz);
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (id TEXT PRIMARY KEY, data TEXT NOT NULL)";
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao criar tabela " + tableName, e);
        }
    }

    /**
     * Carrega todos os registros da tabela para o cache.
     */
    private static void loadAllIntoCache(Class<?> clazz) {
        String tableName = getTableName(clazz);
        String sql = "SELECT id, data FROM " + tableName;
        Map<String, Object> classCache = new ConcurrentHashMap<>();
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            Gson gson = GsonAPI.get();
            while (rs.next()) {
                String id = rs.getString("id");
                String json = rs.getString("data");
                Object obj = gson.fromJson(json, clazz);
                classCache.put(id, obj);
            }
            CACHE.put(clazz, classCache);
        } catch (SQLException e) {
            Console.error("Erro ao carregar cache para %s", e, clazz.getSimpleName());
            CACHE.put(clazz, new ConcurrentHashMap<>()); // cache vazio
        }
    }

    private static void ensureCacheLoaded(Class<?> clazz) {
        if (!CACHE.containsKey(clazz)) {
            // Dispara a criação da tabela e carregamento via getDataSource
            getDataSource(clazz);
        }
    }

    private static void cachePut(Class<?> clazz, String id, Object obj) {
        Map<String, Object> classCache = CACHE.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        classCache.put(id, obj);
    }

    private static String getTableName(Class<?> clazz) {
        String name = clazz.getSimpleName().toLowerCase();
        return name.endsWith("s") ? name : name + "s";
    }

    private static void injectIdField(Object obj, String id) throws Exception {
        java.lang.reflect.Field idField = null;
        for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
            if (f.getName().equals("id") || f.getName().toLowerCase().endsWith("id")) {
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

    private static void copyFields(Object from, Object to) {
        for (java.lang.reflect.Field field : from.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static String extractId(Object obj) {
        if (obj instanceof Saveable) {
            return ((Saveable) obj).getId();
        }
        // Fallback: tentar ler campo 'id' via reflexão
        try {
            java.lang.reflect.Field idField = obj.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            Object idValue = idField.get(obj);
            return idValue != null ? idValue.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}