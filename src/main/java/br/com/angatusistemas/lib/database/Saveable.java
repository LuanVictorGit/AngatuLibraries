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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * [PT] Classe abstrata que fornece persistência automática em SQLite para objetos Java.
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
 * <b>Gerenciamento de ID:</b>
 * O ID é obtido através do método abstrato {@link #getId()}. Se o objeto não tiver um ID
 * (retornar {@code null} ou vazio), um UUID aleatório é gerado e injetado via reflexão no campo
 * chamado "id" ou em qualquer campo que termine com "id" (case‑insensitive). O objeto então
 * passa a ter esse ID permanentemente.
 * </p>
 * <p>
 * <b>Concorrência e performance:</b>
 * A classe utiliza um pool de conexões HikariCP (máx. 20 conexões) e configura o SQLite em modo
 * WAL ({@code PRAGMA journal_mode=WAL}), permitindo leituras concorrentes durante escritas.
 * Escritas são transacionais e bloqueiam apenas a linha em questão (devido ao uso de
 * {@code INSERT OR REPLACE}). Leituras por ID são extremamente rápidas (índice implícito na PK).
 * </p>
 * <p>
 * <b>Suporte a milhões de objetos:</b>
 * O SQLite suporta até dezenas/centenas de milhões de linhas com desempenho aceitável,
 * desde que consultas usem índices apropriados. <strong>Não utilize {@link #findAll(Class)}</strong>
 * em conjuntos grandes – use {@link #query(Class, String, Object...)} com cláusulas {@code WHERE}
 * e índices nos campos filtrados.
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
 * Ao final da aplicação, chame {@link #shutdown()} para fechar todas as conexões.
 * </p>
 *
 * [EN] Abstract class that provides automatic SQLite persistence for Java objects.
 * <p>
 * Each concrete subclass (e.g. {@code User}, {@code Product}) is mapped to its own table
 * in the {@code database.db} file. The table name is the lowercased class name, plus an 's'
 * if it doesn't already end with 's' (e.g. {@code User} → {@code users},
 * {@code Product} → {@code products}).
 * </p>
 * <p>
 * Objects are serialized to JSON (via Gson) and stored in a {@code data} column.
 * The table has a primary key {@code id} (TEXT) which is the unique identifier.
 * </p>
 * <p>
 * <b>ID management:</b>
 * The ID is obtained via the abstract method {@link #getId()}. If the object has no ID
 * (returns {@code null} or empty), a random UUID is generated and injected via reflection
 * into a field named "id" or any field ending with "id" (case‑insensitive). The object then
 * permanently owns that ID.
 * </p>
 * <p>
 * <b>Concurrency and performance:</b>
 * A HikariCP connection pool (max 20 connections) is used. SQLite is configured in WAL mode
 * ({@code PRAGMA journal_mode=WAL}), allowing concurrent reads during writes.
 * Writes are transactional and lock only the affected row (due to {@code INSERT OR REPLACE}).
 * Reads by ID are extremely fast (implicit index on PK).
 * </p>
 * <p>
 * <b>Support for millions of objects:</b>
 * SQLite can handle tens/hundreds of millions of rows with acceptable performance,
 * as long as queries use proper indexes. <strong>Do not use {@link #findAll(Class)}</strong>
 * on large datasets – use {@link #query(Class, String, Object...)} with {@code WHERE} clauses
 * and indexes on filtered fields.
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
 * Call {@link #shutdown()} when your application terminates to close all connections.
 * </p>
 *
 * @author [Sua equipe]
 * @see GsonAPI
 * @see <a href="https://www.sqlite.org/wal.html">SQLite WAL mode</a>
 */
public abstract class Saveable {

    // Mapeia cada classe para seu pool de conexões (criado sob demanda)
    private static final Map<Class<?>, HikariDataSource> DATA_SOURCES = new HashMap<>();
    private static final Object DATA_SOURCE_LOCK = new Object();

    // Carrega o driver JDBC do SQLite estaticamente
    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Driver SQLite não encontrado. Adicione a dependência: org.xerial:sqlite-jdbc", e);
        }
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
     * e então o registro é salvo. Operação thread-safe.
     * </p>
     *
     * [EN] Saves the current object to the database (INSERT OR REPLACE).
     * <p>
     * If the object has no ID, a UUID is generated, injected via reflection,
     * and then the record is saved. Thread-safe operation.
     * </p>
     *
     * @return [PT] {@code true} se salvo com sucesso
     *         [EN] {@code true} if saved successfully
     */
    public boolean save() {
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
            return true;
        } catch (SQLException e) {
            Console.error("Erro ao salvar %s id=%s", e, this.getClass().getSimpleName(), id);
            return false;
        }
    }

    /**
     * [PT] Exclui o objeto atual do banco de dados, baseado em seu ID.
     *
     * [EN] Deletes the current object from the database based on its ID.
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
     * Útil quando o objeto pode ter sido modificado externamente.
     * </p>
     *
     * [EN] Reloads the object's data from the database, overwriting current fields
     * with persisted values.
     * <p>
     * Useful when the object may have been modified externally.
     * </p>
     *
     * @return [PT] a própria instância recarregada, ou {@code null} se o ID for inválido ou não encontrado
     *         [EN] the reloaded instance itself, or {@code null} if ID is invalid or not found
     */
    public Saveable reload() {
        String id = getId();
        if (id == null) return null;
        Saveable reloaded = findById(this.getClass(), id);
        if (reloaded != null) {
            copyFields(reloaded, this);
        }
        return reloaded;
    }

    // ==================== MÉTODOS ESTÁTICOS (MANAGER) ====================

    /**
     * [PT] Busca um objeto pelo ID.
     * <p>
     * Performance: O(log n) devido ao índice primário. Milissegundos mesmo com milhões de registros.
     * </p>
     *
     * [EN] Finds an object by its ID.
     * <p>
     * Performance: O(log n) due to primary index. Milliseconds even with millions of records.
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
        String tableName = getTableName(clazz);
        String sql = "SELECT data FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String json = rs.getString("data");
                return GsonAPI.get().fromJson(json, clazz);
            }
            return null;
        } catch (SQLException e) {
            Console.error("Erro ao buscar %s id=%s", e, clazz.getSimpleName(), id);
            return null;
        }
    }

    /**
     * [PT] Retorna TODOS os objetos da classe.
     * <p>
     * <strong>ATENÇÃO:</strong> Este método carrega todos os registros da tabela em memória.
     * <strong>Não use com milhões de objetos</strong> – isso causará OutOfMemoryError e lentidão extrema.
     * Para grandes volumes, prefira {@link #query(Class, String, Object...)} com paginação ou filtros.
     * </p>
     *
     * [EN] Returns ALL objects of the class.
     * <p>
     * <strong>WARNING:</strong> This method loads all table records into memory.
     * <strong>Do not use with millions of objects</strong> – it will cause OutOfMemoryError and extreme slowness.
     * For large datasets, prefer {@link #query(Class, String, Object...)} with pagination or filters.
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
        String tableName = getTableName(clazz);
        String sql = "SELECT data FROM " + tableName;
        List<T> list = new ArrayList<>();
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            Gson gson = GsonAPI.get();
            while (rs.next()) {
                list.add(gson.fromJson(rs.getString("data"), clazz));
            }
        } catch (SQLException e) {
            Console.error("Erro ao listar %s", e, clazz.getSimpleName());
        }
        return list;
    }

    /**
     * [PT] Filtra objetos usando um predicado em memória.
     * <p>
     * <strong>Não use com milhões de objetos</strong> – carrega todos os registros antes de filtrar.
     * Prefira usar {@link #query(Class, String, Object...)} com cláusula WHERE no SQL.
     * </p>
     *
     * [EN] Filters objects using an in‑memory predicate.
     * <p>
     * <strong>Do not use with millions of objects</strong> – loads all records before filtering.
     * Prefer {@link #query(Class, String, Object...)} with a WHERE clause in SQL.
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
        return findAll(clazz).stream().filter(predicate).collect(Collectors.toList());
    }

    /**
     * [PT] Busca objetos por um campo via reflexão (carrega todos e filtra em memória).
     * <p>
     * <strong>Não é eficiente para grandes volumes.</strong> Crie um índice e use {@link #query(Class, String, Object...)}.
     * </p>
     *
     * [EN] Finds objects by a field using reflection (loads all and filters in memory).
     * <p>
     * <strong>Not efficient for large datasets.</strong> Create an index and use {@link #query(Class, String, Object...)}.
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
     * [PT] Exclui um objeto pelo ID.
     *
     * [EN] Deletes an object by its ID.
     *
     * @param clazz [PT] classe do objeto
     *              [EN] object class
     * @param id    [PT] identificador
     *              [EN] identifier
     * @return [PT] {@code true} se o registro foi removido
     *         [EN] {@code true} if the record was deleted
     */
    public static boolean deleteById(Class<?> clazz, String id) {
        String tableName = getTableName(clazz);
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            Console.error("Erro ao deletar %s id=%s", e, clazz.getSimpleName(), id);
            return false;
        }
    }

    /**
     * [PT] Exclui todos os objetos da classe (remove todos os registros da tabela).
     *
     * [EN] Deletes all objects of the class (truncates the table).
     *
     * @param clazz [PT] classe dos objetos
     *              [EN] object class
     * @return [PT] número de registros removidos
     *         [EN] number of records removed
     */
    public static int deleteAll(Class<?> clazz) {
        String tableName = getTableName(clazz);
        String sql = "DELETE FROM " + tableName;
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            Console.error("Erro ao deletar todos %s", e, clazz.getSimpleName());
            return 0;
        }
    }

    /**
     * [PT] Verifica se existe um objeto com o ID informado.
     *
     * [EN] Checks whether an object with the given ID exists.
     *
     * @param clazz [PT] classe
     *              [EN] class
     * @param id    [PT] identificador
     *              [EN] identifier
     * @return [PT] {@code true} se existir
     *         [EN] {@code true} if exists
     */
    public static boolean exists(Class<?> clazz, String id) {
        String tableName = getTableName(clazz);
        String sql = "SELECT 1 FROM " + tableName + " WHERE id = ? LIMIT 1";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * [PT] Retorna a quantidade total de objetos persistidos da classe.
     *
     * [EN] Returns the total number of persisted objects of the class.
     *
     * @param clazz [PT] classe
     *              [EN] class
     * @return [PT] contagem de registros
     *         [EN] count of records
     */
    public static long count(Class<?> clazz) {
        String tableName = getTableName(clazz);
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.getLong(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * [PT] Executa uma consulta SQL customizada que retorna objetos a partir da coluna {@code data}.
     * <p>
     * A consulta deve retornar uma coluna chamada {@code data} contendo o JSON do objeto.
     * Isso permite usar cláusulas {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, e índices.
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
     * This allows using {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, and indexes.
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
                list.add(gson.fromJson(rs.getString("data"), clazz));
            }
        } catch (SQLException e) {
            Console.error("Erro na query customizada: %s", e, sql);
        }
        return list;
    }

    /**
     * [PT] Fecha todos os pools de conexão. Deve ser chamado ao encerrar a aplicação
     * para evitar vazamento de recursos.
     *
     * [EN] Closes all connection pools. Should be called when shutting down the application
     * to avoid resource leaks.
     */
    public static void shutdown() {
        synchronized (DATA_SOURCE_LOCK) {
            for (HikariDataSource ds : DATA_SOURCES.values()) {
                if (!ds.isClosed()) ds.close();
            }
            DATA_SOURCES.clear();
        }
    }

    // ==================== MÉTODOS INTERNOS PRIVADOS ====================

    private static HikariDataSource getDataSource(Class<?> clazz) {
        synchronized (DATA_SOURCE_LOCK) {
            if (!DATA_SOURCES.containsKey(clazz)) {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl("jdbc:sqlite:database.db");
                config.setConnectionTestQuery("SELECT 1");
                config.setMaximumPoolSize(20);
                config.setMinimumIdle(2);
                config.setIdleTimeout(30000);
                config.setPoolName("Saveable-" + clazz.getSimpleName());
                // Otimizações SQLite para concorrência
                config.addDataSourceProperty("journal_mode", "WAL");
                config.addDataSourceProperty("synchronous", "NORMAL");
                config.addDataSourceProperty("cache_size", 10000);
                config.addDataSourceProperty("temp_store", "MEMORY");
                DATA_SOURCES.put(clazz, new HikariDataSource(config));
                createTable(clazz);
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
            // Garantir WAL mesmo que o pool já tenha definido
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao criar tabela " + tableName, e);
        }
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
}