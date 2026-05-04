package br.com.angatusistemas.lib.database;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * [PT] Classe abstrata que fornece persistência automática (SQLite ou MySQL) para objetos Java.
 *
 * <h2>Banco de dados</h2>
 * <p>
 * Por padrão usa SQLite ({@code database.db}). Se o arquivo {@code mysql.yml} existir
 * no diretório de trabalho e estiver preenchido corretamente, o sistema usará MySQL automaticamente.
 * Exemplo de {@code mysql.yml}:
 * <pre>
 * host: localhost
 * port: 3306
 * database: meu_banco
 * username: root
 * password: senha123
 * pool_size: 20
 * </pre>
 * </p>
 *
 * <h2>Identity Map (cache de instâncias)</h2>
 * <p>
 * Cada objeto carregado é mantido em um mapa {@code id → WeakReference<instância>}.
 * Assim, {@code findById} com o mesmo ID sempre retorna <em>a mesma instância Java</em>
 * enquanto ela estiver viva na JVM. Isso elimina o problema de referências obsoletas
 * em tarefas assíncronas:
 * <pre>
 * User user = Saveable.findById(User.class, "123");
 * user.setName("João");
 *
 * Task.runLater(() -> {
 *     // 'user' aqui é a MESMA instância – sempre atualizada
 *     user.setName("Maria");
 *     user.save(); // vai para a fila
 * });
 * </pre>
 * </p>
 *
 * <h2>Fila de salvamento (Save Queue)</h2>
 * <p>
 * Chamadas a {@link #save()} não bloqueiam a thread chamadora. O objeto é enfileirado
 * e persistido de forma assíncrona por um worker dedicado. Chamadas duplicadas para o
 * mesmo ID são "debounced" – apenas a versão mais recente é salva.
 * </p>
 * <p>
 * Para garantir que todos os objetos pendentes sejam gravados antes de encerrar,
 * chame {@link #shutdown()}.
 * </p>
 *
 * <h2>Índices customizados (SQLite)</h2>
 * <pre>
 * Saveable.execute(User.class,
 *     "CREATE INDEX IF NOT EXISTS idx_name ON users(json_extract(data, '$.name'))");
 * </pre>
 *
 * <h2>Índices customizados (MySQL)</h2>
 * <pre>
 * Saveable.execute(User.class,
 *     "CREATE INDEX idx_name ON users((CAST(JSON_UNQUOTE(JSON_EXTRACT(data,'$.name')) AS CHAR(255))))");
 * </pre>
 *
 * @author Angatu Sistemas
 */
public abstract class Saveable {

    // ==================== CONFIGURAÇÃO DO BANCO ====================

    /** Configuração MySQL carregada do mysql.yml (null = usar SQLite) */
    private static volatile DbConfig DB_CONFIG = null;
    private static volatile boolean CONFIG_LOADED = false;
    private static final Object CONFIG_LOCK = new Object();

    private static DbConfig getDbConfig() {
        if (!CONFIG_LOADED) {
            synchronized (CONFIG_LOCK) {
                if (!CONFIG_LOADED) {
                    DB_CONFIG = loadMysqlConfig();
                    CONFIG_LOADED = true;
                }
            }
        }
        return DB_CONFIG;
    }

    private static DbConfig loadMysqlConfig() {
        File file = new File("mysql.yml");
        if (!file.exists()) {
            // Gera o arquivo de exemplo para facilitar a configuração
            generateMysqlYmlTemplate(file);
            return null;
        }
        try (FileReader reader = new FileReader(file)) {
            Properties props = parseSimpleYaml(reader);
            String host     = props.getProperty("host", "").trim();
            String database = props.getProperty("database", "").trim();
            String username = props.getProperty("username", "").trim();
            if (host.isEmpty() || database.isEmpty() || username.isEmpty()) {
                Console.warn("[Saveable] mysql.yml encontrado mas incompleto. Usando SQLite.");
                return null;
            }
            DbConfig cfg = new DbConfig();
            cfg.host     = host;
            cfg.port     = Integer.parseInt(props.getProperty("port", "3306").trim());
            cfg.database = database;
            cfg.username = username;
            cfg.password = props.getProperty("password", "").trim();
            cfg.poolSize = Integer.parseInt(props.getProperty("pool_size", "20").trim());
            Console.info("[Saveable] Usando MySQL: %s:%d/%s", cfg.host, cfg.port, cfg.database);
            return cfg;
        } catch (Exception e) {
            Console.error("[Saveable] Erro ao ler mysql.yml. Usando SQLite.", e);
            return null;
        }
    }

    private static void generateMysqlYmlTemplate(File file) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("# Configurações do MySQL\n");
            writer.write("# Preencha os campos abaixo e reinicie a aplicação para usar MySQL.\n");
            writer.write("# Se este arquivo estiver vazio ou ausente, SQLite será usado.\n\n");
            writer.write("host: localhost\n");
            writer.write("port: 3306\n");
            writer.write("database: meu_banco\n");
            writer.write("username: root\n");
            writer.write("password: \n");
            writer.write("pool_size: 20\n");
            Console.info("[Saveable] Arquivo mysql.yml criado. Configure-o para usar MySQL.");
        } catch (IOException ignored) {}
    }

    /** Parser mínimo de YAML (apenas chave: valor) */
    private static Properties parseSimpleYaml(FileReader reader) throws IOException {
        Properties props = new Properties();
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) sb.append((char) c);
        for (String line : sb.toString().split("\n")) {
            line = line.trim();
            if (line.startsWith("#") || !line.contains(":")) continue;
            int idx = line.indexOf(':');
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            props.setProperty(key, val);
        }
        return props;
    }

    private static boolean isMysql() {
        return getDbConfig() != null;
    }

    // ==================== POOL DE CONEXÕES ====================

    private static final Map<Class<?>, HikariDataSource> DATA_SOURCES = new ConcurrentHashMap<>();
    private static final Object DATA_SOURCE_LOCK = new Object();

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {}
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) {}
    }

    private static HikariDataSource getDataSource(Class<?> clazz) {
        HikariDataSource ds = DATA_SOURCES.get(clazz);
        if (ds != null) return ds;
        synchronized (DATA_SOURCE_LOCK) {
            ds = DATA_SOURCES.get(clazz);
            if (ds != null) return ds;
            HikariConfig config = new HikariConfig();
            DbConfig dbCfg = getDbConfig();
            if (dbCfg != null) {
                // MySQL
                config.setJdbcUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                    dbCfg.host, dbCfg.port, dbCfg.database));
                config.setUsername(dbCfg.username);
                config.setPassword(dbCfg.password);
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setMaximumPoolSize(dbCfg.poolSize);
                config.setMinimumIdle(2);
            } else {
                // SQLite
                config.setJdbcUrl("jdbc:sqlite:database.db");
                config.setConnectionTestQuery("SELECT 1");
                config.setMaximumPoolSize(20);
                config.setMinimumIdle(2);
                config.addDataSourceProperty("journal_mode", "WAL");
                config.addDataSourceProperty("synchronous", "NORMAL");
                config.addDataSourceProperty("cache_size", 10000);
                config.addDataSourceProperty("temp_store", "MEMORY");
            }
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(10000);
            config.setPoolName("Saveable-" + clazz.getSimpleName());
            ds = new HikariDataSource(config);
            DATA_SOURCES.put(clazz, ds);
            createTable(clazz);
            return ds;
        }
    }

    // ==================== IDENTITY MAP (cache de instâncias) ====================

    /**
     * Mapa global: classe → (id → WeakReference<instância>).
     * WeakReference permite que o GC colete objetos não mais referenciados pelo código do usuário.
     */
    @SuppressWarnings("rawtypes")
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, WeakReference>> IDENTITY_MAP
            = new ConcurrentHashMap<>();

    @SuppressWarnings({"rawtypes"})
    private static <T> T getFromIdentityMap(Class<T> clazz, String id) {
        ConcurrentHashMap<String, WeakReference> map = IDENTITY_MAP.get(clazz);
        if (map == null) return null;
        WeakReference ref = map.get(id);
        if (ref == null) return null;
        Object obj = ref.get();
        if (obj == null) {
            map.remove(id); // foi coletado pelo GC
            return null;
        }
        return clazz.cast(obj);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void putInIdentityMap(Class<T> clazz, String id, T obj) {
        IDENTITY_MAP
            .computeIfAbsent(clazz, k -> new ConcurrentHashMap<>())
            .put(id, new WeakReference(obj));
    }

    // ==================== FILA DE SALVAMENTO ====================

    /**
     * Fila de saves pendentes: apenas um save por ID é mantido (deduplicação).
     * Se o mesmo objeto for salvo múltiplas vezes antes do worker processar, somente
     * o estado mais recente é gravado.
     */
    private static final ConcurrentHashMap<String, SaveTask> SAVE_QUEUE = new ConcurrentHashMap<>();
    private static final LinkedBlockingQueue<String> SAVE_ORDER = new LinkedBlockingQueue<>();
    private static final ScheduledExecutorService SAVE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Saveable-SaveQueue");
                t.setDaemon(true);
                return t;
            });

    static {
        // Worker que drena a fila periodicamente
        SAVE_EXECUTOR.scheduleWithFixedDelay(() -> {
            List<String> batch = new ArrayList<>();
            SAVE_ORDER.drainTo(batch, 200);
            for (String key : batch) {
                SaveTask task = SAVE_QUEUE.remove(key);
                if (task != null) task.run();
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    private static class SaveTask {
        final Class<?> clazz;
        final String id;
        final String json;

        SaveTask(Class<?> clazz, String id, String json) {
            this.clazz = clazz;
            this.id = id;
            this.json = json;
        }

        void run() {
            String tableName = getTableName(clazz);
            String sql = isMysql()
                ? "INSERT INTO " + tableName + " (id, data) VALUES (?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data)"
                : "INSERT OR REPLACE INTO " + tableName + " (id, data) VALUES (?, ?)";
            try (Connection conn = getDataSource(clazz).getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                pstmt.setString(2, json);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                Console.error("[Saveable] Erro ao salvar %s id=%s", e, clazz.getSimpleName(), id);
            }
        }
    }

    // ==================== MÉTODOS ABSTRATOS ====================

    /**
     * Retorna o identificador único do objeto.
     * Pode retornar {@code null} se o objeto ainda não foi persistido.
     */
    public abstract String getId();

    // ==================== MÉTODOS DE INSTÂNCIA ====================

    /**
     * Enfileira o objeto para salvamento assíncrono.
     * <p>
     * Chamadas repetidas para o mesmo ID antes do flush são deduplicadas:
     * apenas o estado mais recente é gravado. Não bloqueia a thread chamadora.
     * </p>
     *
     * @return {@code true} se o objeto foi aceito na fila com sucesso
     */
    public boolean save() {
        String id = getId();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            try {
                injectIdField(this, id);
            } catch (Exception e) {
                Console.error("[Saveable] Falha ao injetar ID em %s", e, this.getClass().getSimpleName());
                return false;
            }
        }
        // Registra no IdentityMap para que futuras buscas retornem esta instância
        putInIdentityMap(selfClass(), id, this);

        String json = GsonAPI.get().toJson(this);
        String queueKey = this.getClass().getName() + ":" + id;
        boolean isNew = !SAVE_QUEUE.containsKey(queueKey);
        SAVE_QUEUE.put(queueKey, new SaveTask(this.getClass(), id, json));
        if (isNew) SAVE_ORDER.offer(queueKey);
        return true;
    }

    /**
     * Salva o objeto de forma <strong>síncrona</strong>, aguardando a confirmação do banco.
     * Use quando precisar de garantia imediata de persistência.
     *
     * @return {@code true} se salvo com sucesso
     */
    public boolean saveSync() {
        String id = getId();
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            try {
                injectIdField(this, id);
            } catch (Exception e) {
                Console.error("[Saveable] Falha ao injetar ID em %s", e, this.getClass().getSimpleName());
                return false;
            }
        }
        putInIdentityMap(selfClass(), id, this);

        // Remove da fila se havia um save pendente (este sync o substituirá)
        String queueKey = this.getClass().getName() + ":" + id;
        SAVE_QUEUE.remove(queueKey);

        String tableName = getTableName(this.getClass());
        String sql = isMysql()
            ? "INSERT INTO " + tableName + " (id, data) VALUES (?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data)"
            : "INSERT OR REPLACE INTO " + tableName + " (id, data) VALUES (?, ?)";
        String json = GsonAPI.get().toJson(this);
        try (Connection conn = getDataSource(this.getClass()).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, json);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            Console.error("[Saveable] Erro ao salvar %s id=%s", e, this.getClass().getSimpleName(), id);
            return false;
        }
    }

    /**
     * Exclui o objeto atual do banco de dados.
     *
     * @return {@code true} se removido ou não existia
     */
    public boolean delete() {
        String id = getId();
        if (id == null) return false;
        // Remove do IdentityMap
        ConcurrentHashMap<?, ?> map = IDENTITY_MAP.get(this.getClass());
        if (map != null) map.remove(id);
        // Remove da fila de save, se houver
        SAVE_QUEUE.remove(this.getClass().getName() + ":" + id);
        return deleteById(this.getClass(), id);
    }

    /**
     * Recarrega os dados deste objeto a partir do banco, atualizando seus campos.
     * Como o IdentityMap garante que há apenas uma instância por ID, todos os
     * holders desta referência verão os valores atualizados.
     *
     * @return esta instância atualizada, ou {@code null} se não encontrada
     */
    public Saveable reload() {
        String id = getId();
        if (id == null) return null;
        Saveable fresh = loadFromDb(this.getClass(), id);
        if (fresh != null) copyFields(fresh, this);
        return this;
    }

    // ==================== MÉTODOS ESTÁTICOS ====================

    /**
     * Busca um objeto pelo ID.
     * <p>
     * Se a instância já estiver viva na JVM (IdentityMap), retorna ela diretamente
     * sem acessar o banco. Caso contrário, carrega do banco e registra no mapa.
     * </p>
     *
     * @param clazz classe do objeto
     * @param id    identificador único
     * @param <T>   tipo da classe
     * @return objeto encontrado ou {@code null}
     */
    public static <T> T findById(Class<T> clazz, String id) {
        if (id == null) return null;
        // Verifica o IdentityMap primeiro
        T cached = getFromIdentityMap(clazz, id);
        if (cached != null) return cached;
        // Carrega do banco
        T obj = loadFromDb(clazz, id);
        if (obj != null) putInIdentityMap(clazz, id, obj);
        return obj;
    }

    /** Carrega diretamente do banco, sem consultar o IdentityMap. */
    @SuppressWarnings("unchecked")
    private static <T> T loadFromDb(Class<?> clazz, String id) {
        String tableName = getTableName(clazz);
        String sql = "SELECT data FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return (T) GsonAPI.get().fromJson(rs.getString("data"), clazz);
            }
            return null;
        } catch (SQLException e) {
            Console.error("[Saveable] Erro ao buscar %s id=%s", e, clazz.getSimpleName(), id);
            return null;
        }
    }

    /**
     * Retorna todos os objetos da classe.
     * <p>
     * <strong>ATENÇÃO:</strong> carrega tudo em memória.
     * Evite com tabelas grandes; prefira {@link #query(Class, String, Object...)}.
     * </p>
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
                String json = rs.getString("data");
                T obj = gson.fromJson(json, clazz);
                // Merge com IdentityMap: se já existe instância viva, atualiza ela
                String id = extractId(obj);
                if (id != null) {
                    T existing = getFromIdentityMap(clazz, id);
                    if (existing != null) {
                        copyFields(obj, existing);
                        list.add(existing);
                    } else {
                        putInIdentityMap(clazz, id, obj);
                        list.add(obj);
                    }
                } else {
                    list.add(obj);
                }
            }
        } catch (SQLException e) {
            Console.error("[Saveable] Erro ao listar %s", e, clazz.getSimpleName());
        }
        return list;
    }

    /**
     * Filtra objetos com predicado em memória.
     * Não eficiente para grandes volumes.
     */
    public static <T> List<T> findByPredicate(Class<T> clazz, Predicate<T> predicate) {
        return findAll(clazz).stream().filter(predicate).collect(Collectors.toList());
    }

    /**
     * Busca por um campo via reflexão (filtra em memória).
     * Para grandes volumes, use {@link #query(Class, String, Object...)} com índices.
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
     * Exclui um objeto pelo ID.
     */
    public static boolean deleteById(Class<?> clazz, String id) {
        // Remove do IdentityMap e da fila
        ConcurrentHashMap<?, ?> map = IDENTITY_MAP.get(clazz);
        if (map != null) map.remove(id);
        SAVE_QUEUE.remove(clazz.getName() + ":" + id);

        String tableName = getTableName(clazz);
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            Console.error("[Saveable] Erro ao deletar %s id=%s", e, clazz.getSimpleName(), id);
            return false;
        }
    }

    /**
     * Exclui todos os registros da tabela.
     */
    public static int deleteAll(Class<?> clazz) {
        // Limpa IdentityMap e fila para esta classe
        IDENTITY_MAP.remove(clazz);
        SAVE_QUEUE.entrySet().removeIf(e -> e.getKey().startsWith(clazz.getName() + ":"));

        String tableName = getTableName(clazz);
        String sql = "DELETE FROM " + tableName;
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            Console.error("[Saveable] Erro ao deletar todos %s", e, clazz.getSimpleName());
            return 0;
        }
    }

    /**
     * Verifica se existe um registro com o ID informado.
     */
    public static boolean exists(Class<?> clazz, String id) {
        // Se está no IdentityMap, existe com certeza (ou está na fila para salvar)
        if (getFromIdentityMap(clazz, id) != null) return true;
        String tableName = getTableName(clazz);
        String sql = "SELECT 1 FROM " + tableName + " WHERE id = ? LIMIT 1";
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            return pstmt.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Retorna a quantidade de registros na tabela.
     */
    public static long count(Class<?> clazz) {
        String sql = "SELECT COUNT(*) FROM " + getTableName(clazz);
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.getLong(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * Executa uma consulta SQL customizada que retorna objetos a partir da coluna {@code data}.
     * <p>
     * Objetos retornados são automaticamente mesclados com o IdentityMap.
     * </p>
     *
     * <b>SQLite:</b>
     * <pre>
     * List&lt;User&gt; users = Saveable.query(User.class,
     *     "SELECT data FROM users WHERE json_extract(data, '$.name') = ?", "João");
     * </pre>
     *
     * <b>MySQL:</b>
     * <pre>
     * List&lt;User&gt; users = Saveable.query(User.class,
     *     "SELECT data FROM users WHERE JSON_UNQUOTE(JSON_EXTRACT(data, '$.name')) = ?", "João");
     * </pre>
     *
     * @param clazz  classe destino
     * @param sql    consulta SQL (deve retornar coluna {@code data})
     * @param params parâmetros posicionais
     * @param <T>    tipo da classe
     * @return lista de objetos (pode ser vazia)
     */
    public static <T> List<T> query(Class<T> clazz, String sql, Object... params) {
        List<T> list = new ArrayList<>();
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) pstmt.setObject(i + 1, params[i]);
            ResultSet rs = pstmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            boolean hasData = false;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (meta.getColumnName(i).equalsIgnoreCase("data")) { hasData = true; break; }
            }
            if (!hasData) throw new UnsupportedOperationException(
                "Query deve retornar a coluna 'data' contendo o JSON do objeto.");
            Gson gson = GsonAPI.get();
            while (rs.next()) {
                T obj = gson.fromJson(rs.getString("data"), clazz);
                String id = extractId(obj);
                if (id != null) {
                    T existing = getFromIdentityMap(clazz, id);
                    if (existing != null) {
                        copyFields(obj, existing);
                        list.add(existing);
                    } else {
                        putInIdentityMap(clazz, id, obj);
                        list.add(obj);
                    }
                } else {
                    list.add(obj);
                }
            }
        } catch (SQLException e) {
            Console.error("[Saveable] Erro na query: %s", e, sql);
        }
        return list;
    }

    /**
     * Executa um comando SQL sem retorno (DDL, UPDATE, DELETE customizado).
     * Útil para criar índices.
     *
     * <b>SQLite:</b>
     * <pre>
     * Saveable.execute(User.class,
     *     "CREATE INDEX IF NOT EXISTS idx_name ON users(json_extract(data, '$.name'))");
     * </pre>
     *
     * <b>MySQL:</b>
     * <pre>
     * Saveable.execute(User.class,
     *     "ALTER TABLE users ADD INDEX idx_name ((CAST(JSON_UNQUOTE(JSON_EXTRACT(data,'$.name')) AS CHAR(255))))");
     * </pre>
     *
     * @param clazz  qualquer classe para obter a conexão
     * @param sql    SQL a executar
     * @param params parâmetros posicionais (opcional)
     */
    public static void execute(Class<?> clazz, String sql, Object... params) {
        try (Connection conn = getDataSource(clazz).getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) pstmt.setObject(i + 1, params[i]);
            pstmt.execute();
        } catch (SQLException e) {
            Console.error("[Saveable] Erro ao executar SQL: %s", e, sql);
        }
    }

    /**
     * Aguarda todos os saves pendentes e fecha os pools de conexão.
     * Deve ser chamado ao encerrar a aplicação.
     */
    public static void shutdown() {
        // Processa o que resta na fila
        SAVE_EXECUTOR.shutdown();
        try {
            // Flush final da fila
            List<String> remaining = new ArrayList<>(SAVE_QUEUE.keySet());
            for (String key : remaining) {
                SaveTask task = SAVE_QUEUE.remove(key);
                if (task != null) task.run();
            }
            SAVE_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Fecha pools
        synchronized (DATA_SOURCE_LOCK) {
            for (HikariDataSource ds : DATA_SOURCES.values()) {
                if (!ds.isClosed()) ds.close();
            }
            DATA_SOURCES.clear();
        }
    }

    /**
     * Retorna se o banco configurado é MySQL.
     */
    public static boolean isUsingMysql() {
        return isMysql();
    }

    /**
     * Retorna quantos saves estão pendentes na fila.
     */
    public static int pendingSaves() {
        return SAVE_QUEUE.size();
    }

    // ==================== MÉTODOS INTERNOS ====================

    /**
     * Retorna o tipo concreto desta instância com o cast correto para uso nos generics.
     * Necessário porque {@code this.getClass()} retorna {@code Class<? extends Saveable>},
     * incompatível com {@code Class<T>} esperado pelo IdentityMap.
     */
    @SuppressWarnings("unchecked")
    private <T extends Saveable> Class<T> selfClass() {
        return (Class<T>) this.getClass();
    }

    private static void createTable(Class<?> clazz) {
        String tableName = getTableName(clazz);
        String sql;
        if (isMysql()) {
            sql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` ("
                + "`id` VARCHAR(36) NOT NULL, "
                + "`data` LONGTEXT NOT NULL, "
                + "PRIMARY KEY (`id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (id TEXT PRIMARY KEY, data TEXT NOT NULL)";
        }
        try (Connection conn = getDataSource(clazz).getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            if (!isMysql()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }
        } catch (SQLException e) {
            throw new RuntimeException("[Saveable] Erro ao criar tabela " + tableName, e);
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
        if (idField == null) throw new IllegalStateException(
            "Objeto " + obj.getClass() + " não possui campo ID para injeção");
        idField.setAccessible(true);
        if (idField.getType() == String.class) idField.set(obj, id);
        else if (idField.getType() == UUID.class) idField.set(obj, UUID.fromString(id));
        else throw new IllegalStateException("Campo ID deve ser String ou UUID");
    }

    private static void copyFields(Object from, Object to) {
        for (java.lang.reflect.Field field : from.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch (IllegalAccessException ignored) {}
        }
    }

    /** Extrai o ID de um objeto via reflexão (para uso interno). */
    private static String extractId(Object obj) {
        if (obj instanceof Saveable) return ((Saveable) obj).getId();
        for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
            if (f.getName().equals("id") || f.getName().toLowerCase().endsWith("id")) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    return val != null ? val.toString() : null;
                } catch (IllegalAccessException ignored) {}
            }
        }
        return null;
    }

    // ==================== INNER CLASSES ====================

    /** Dados de conexão MySQL lidos do mysql.yml */
    private static class DbConfig {
        String host;
        int port;
        String database;
        String username;
        String password;
        int poolSize;
    }
}