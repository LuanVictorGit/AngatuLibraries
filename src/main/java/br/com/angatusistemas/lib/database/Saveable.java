package br.com.angatusistemas.lib.database;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

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
 * <h2>Concorrência — milhares de leituras e gravações ao mesmo tempo</h2>
 * <p>O SQLite aceita <strong>um escritor por vez</strong> no arquivo e leitores em paralelo
 * (modo WAL). A biblioteca organiza o processo inteiro em torno disso, em vez de deixar as
 * conexões disputarem o arquivo:</p>
 * <ul>
 *   <li><strong>Uma conexão de escrita, uma fila justa.</strong> Toda gravação do processo —
 *       {@link #save()}, {@link #delete()}, {@link #mutate(Class, String, Consumer)},
 *       {@link #transaction(Runnable)}, criação de tabela e de índice — passa por uma única
 *       conexão, na ordem de chegada. Dentro do processo não existe disputa pela trava do
 *       arquivo, então {@code SQLITE_BUSY} e espera de {@code busy_timeout} deixam de acontecer.</li>
 *   <li><strong>Leitura em paralelo, sem esperar a escrita.</strong> As buscas usam um pool
 *       separado, só de leitura ({@code PRAGMA query_only}), e em WAL o leitor não espera o
 *       escritor nem o escritor espera o leitor.</li>
 *   <li><strong>Uma leitura nunca segura duas conexões.</strong> O SQL roda com a conexão
 *       emprestada e a conversão do JSON em objeto acontece <em>depois</em> que ela voltou ao
 *       pool. Um construtor de entidade que consulta o banco não prende conexão nenhuma — o pool
 *       não seca por leitura aninhada, por maior que seja a concorrência.</li>
 *   <li><strong>Espera com prazo.</strong> Quem espera a vez de gravar, ou uma conexão de
 *       leitura, por mais que {@code ANGATU_DB_BUSY_TIMEOUT_MS} (padrão 30 s) recebe
 *       {@link PersistenceException} em vez de ficar parado para sempre. Uma gravação que segura
 *       a vez por mais de 2 s é registrada no log com o ponto do código que a chamou.</li>
 * </ul>
 * <p>Sobre isso, as operações:</p>
 * <ul>
 *   <li>{@link #save()} — gravação atômica; a última escrita vence;</li>
 *   <li>{@link #mutate(Class, String, Consumer)} — <strong>a forma correta</strong>
 *       de alterar um registro disputado: lê, altera e grava dentro da mesma
 *       transação, sem janela para atualização perdida;</li>
 *   <li>{@link #transaction(Runnable)} — várias operações com tudo ou nada; uma transação
 *       aninhada é um <em>savepoint</em>, com o próprio tudo ou nada.</li>
 * </ul>
 *
 * <p><strong>Quando NÃO usar:</strong> para relacionamentos complexos ou
 * consultas analíticas (use SQL direto com o driver) e para dados binários
 * grandes (ex: imagens — prefira salvar em disco/volume e persistir o caminho).
 * <strong>Não instancie esta classe diretamente</strong> — é abstrata e o
 * construtor é {@code protected}: o uso é exclusivamente via {@code extends}.</p>
 *
 * <p><strong>Restrição de inicialização:</strong> subclasses precisam de um
 * construtor vazio (para o Gson desserializar) e de campos serializáveis.
 * Classe anônima não pode ser entidade: ela não tem nome para virar tabela.</p>
 *
 * <p><strong>Mapeamento:</strong> cada subclasse vira uma tabela, com o nome dado por
 * {@link #tableName(Class)} ({@code User} → {@code users}, {@code Address} →
 * {@code address}). O objeto é serializado em JSON pelo {@link GsonAPI} na coluna
 * {@code data}; a chave primária {@code id} vem de {@link #getId()} (UUID gerado e
 * injetado por reflexão quando ausente).</p>
 *
 * <p><strong>Banco de dados:</strong> {@code database.db} no diretório de
 * trabalho — cada projeto tem o seu, como sempre. Em contêiner, aponte para o
 * volume persistente daquele projeto com {@code ANGATU_DB_PATH=/data/database.db}
 * (ou {@code -Dangatu.db=...}); sem isso o banco vive dentro do contêiner e some
 * no próximo deploy.</p>
 *
 * <p><strong>Variáveis de ambiente:</strong> {@code ANGATU_DB_PATH} (arquivo do banco),
 * {@code ANGATU_DB_POOL_SIZE} (conexões de leitura, padrão 12; a de escrita é uma à parte),
 * {@code ANGATU_DB_CACHE_KIB} (cache de páginas por conexão, padrão 8192 KiB) e
 * {@code ANGATU_DB_BUSY_TIMEOUT_MS} (prazo de espera, padrão 30000 ms).</p>
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
 * registro for disputado; <strong>nunca faça chamada de rede nem trabalho lento dentro de
 * uma transação</strong> — ela segura a vez de gravar de todo o processo enquanto dura;
 * chame {@link #shutdown()} ao encerrar a aplicação.</p>
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
 * @see PersistenceException
 * @see <a href="https://www.sqlite.org/wal.html">SQLite WAL mode</a>
 */
public abstract class Saveable {

    // ==================== CONSTANTES ====================

    /** Coordenadas Maven das dependências do módulo de persistência. */
    private static final String SQLITE_COORDINATES = "org.xerial:sqlite-jdbc:3.51.3.0";
    private static final String HIKARI_COORDINATES = "com.zaxxer:HikariCP:7.0.2";
    private static final String GSON_COORDINATES = "com.google.code.gson:gson:2.13.2";
    private static final String PERSISTENCE_FEATURE = "Persistência (Saveable)";

    /** Arquivo padrão do banco, relativo ao diretório de trabalho da aplicação. */
    private static final String DEFAULT_DATABASE = "database.db";

    /**
     * Conexões do pool de <strong>leitura</strong>. A conexão de escrita é uma só, à parte.
     *
     * <p>Configurável por {@code ANGATU_DB_POOL_SIZE}. Cada conexão carrega o próprio cache de
     * páginas do SQLite (ver {@link #CACHE_SIZE_KIB}), que é memória <strong>nativa</strong>:
     * ela não aparece no gráfico de heap e é contada inteira pelo limite do contêiner. Num
     * contêiner apertado, o número de conexões é uma decisão de memória, não só de
     * concorrência — e precisa poder ser mudado sem recompilar a biblioteca.</p>
     */
    private static final int READ_POOL_SIZE = intFromEnvironment("ANGATU_DB_POOL_SIZE", 12, 1, 64);

    /**
     * Cache de páginas do SQLite por conexão, em <strong>KiB</strong>. Configurável por
     * {@code ANGATU_DB_CACHE_KIB}.
     *
     * <h4>Por que o número vai negativo ao driver</h4>
     * <p>O {@code PRAGMA cache_size} tem duas unidades, decididas pelo sinal: positivo conta
     * <strong>páginas</strong>, negativo conta <strong>KiB</strong>. Em KiB o número diz o que
     * parece dizer: o padrão de 8 MB por conexão dá até 104 MB com os dois pools cheios —
     * folgado para consultas indexadas, que é o que a biblioteca faz, e recuperável: o SQLite
     * só chega perto do teto quando precisa.</p>
     */
    private static final int CACHE_SIZE_KIB = intFromEnvironment("ANGATU_DB_CACHE_KIB", 8_192, 64, 1_048_576);

    /**
     * Prazo, em ms, de toda espera do módulo: pela vez de gravar, por uma conexão de leitura e
     * pela trava do arquivo. Configurável por {@code ANGATU_DB_BUSY_TIMEOUT_MS}.
     *
     * <p>Dentro do processo, a fila de escrita faz a espera pela trava do arquivo sumir; o
     * {@code busy_timeout} fica para os escritores que a biblioteca <strong>não</strong>
     * controla — comando de manutenção, rotina de backup, outro processo com o mesmo arquivo
     * aberto. Estourado o prazo, a operação falha com {@link PersistenceException}: sob
     * sobrecarga, desistir de uma requisição que o cliente já abandonou é melhor do que acumular
     * trabalho sem fim.</p>
     */
    private static final int BUSY_TIMEOUT_MS = intFromEnvironment("ANGATU_DB_BUSY_TIMEOUT_MS", 30_000, 1_000, 300_000);

    /**
     * Teto do arquivo {@code -wal} depois de um checkpoint, em bytes. Sem ele, o arquivo fica
     * no maior tamanho que já atingiu — um pico de gravação ocupava aquele disco para sempre.
     */
    private static final long JOURNAL_SIZE_LIMIT_BYTES = 64L * 1024 * 1024;

    /** Gravação que segura a vez por mais que isto (ms) é registrada no log. */
    private static final long SLOW_WRITE_MS = 2_000;

    /** Nomes aceitos no SQL gerado para campo de índice e de consulta. */
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Mensagem do SQLite para tabela que não existe: {@code no such table: users}, ou
     * {@code no such table: main.users}. O nome vai até o primeiro espaço, ponto ou parêntese — não
     * {@code \w}, que só reconhece ASCII: a entidade {@code Café} tem a tabela {@code cafés}, e a
     * recuperação da tabela apagada por fora nunca acontecia para ela.
     */
    private static final Pattern MISSING_TABLE = Pattern.compile("no such table: (?:[^\\s.]+\\.)?([^\\s.)]+)");

    /**
     * Comandos de controle de transação, recusados no {@link #query(Class, String, Object...)}. A
     * transação é da biblioteca: um {@code COMMIT} avulso confirmava metade de um
     * {@link #transaction(Runnable)}, e um {@code BEGIN} avulso deixava a conexão de escrita — a
     * única do processo — no meio de uma transação que ninguém confirmaria.
     */
    private static final Set<String> TRANSACTION_CONTROL = Set.of("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE");

    private static final Object[] NO_PARAMS = new Object[0];

    // ==================== ESTADO GLOBAL ====================

    /** Pools do banco desta aplicação: criados no primeiro uso, fechados por {@link #shutdown()}. */
    private static volatile Pools pools;
    private static final ReentrantLock POOLS_LOCK = new ReentrantLock();

    /**
     * Classes cuja tabela <strong>existe confirmada no arquivo</strong>, nesta execução.
     *
     * <p>Só entra aqui o que não pode mais ser desfeito: tabela criada em autocommit, tabela já
     * encontrada no disco, ou tabela criada dentro de uma transação que <em>confirmou</em>. O
     * {@code CREATE TABLE} feito dentro de uma transação fica pendente nela (ver
     * {@link Transaction}) — porque o DDL do SQLite é transacional, e uma transação que desfaz
     * leva a tabela junto. Registrar antes do commit era o defeito que deixava a entidade
     * "pronta" sem tabela, com toda operação dela falhando até o processo reiniciar.</p>
     */
    private static final Set<Class<?>> PREPARED_TABLES = ConcurrentHashMap.newKeySet();

    /** Transação de escrita em curso na thread, com a conexão do escritor. */
    private static final ThreadLocal<Transaction> CURRENT_TRANSACTION = new ThreadLocal<>();

    /**
     * A vez de gravar: um escritor por vez no processo inteiro, na ordem de chegada.
     *
     * <p>O SQLite aceita uma escrita por vez no arquivo; esta trava faz a fila <strong>aqui</strong>,
     * onde esperar é barato e ordenado, em vez de deixar conexões disputarem a trava do arquivo.
     * É justa ({@code true}) de propósito: sob rajada de rastreamento, a gravação ocasional de uma
     * tela não pode esperar indefinidamente atrás das gravações de GPS.</p>
     *
     * <p>É a <strong>única</strong> trava de gravação. Não existe trava por registro: com um
     * escritor por vez, ela não protegia nada — e pegá-la antes desta, num {@code save()} dentro
     * de um {@code mutate}, invertia a ordem de aquisição entre duas threads e travava as duas
     * para sempre.</p>
     */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock(true);

    // ==================== CONSTRUTOR ====================

    /**
     * Construtor {@code protected}: o {@code Saveable} funciona exclusivamente
     * por herança ({@code extends}).
     *
     * <p><strong>Forma correta de uso:</strong> crie uma entidade concreta que
     * estenda {@code Saveable} e implemente {@link #getId()}:</p>
     * <pre>
     * public class User extends Saveable {
     *     private String id;
     *     private String name;
     *     public User() {} // obrigatório para desserialização Gson
     *     &#64;Override public String getId() { return id; }
     * }
     * </pre>
     *
     * <p><strong>Uso incorreto:</strong> instanciar {@code Saveable} diretamente
     * é impossível — a classe é abstrata e o construtor é {@code protected}.
     * Subclasses anônimas também não servem: uma entidade precisa de nome (vira a
     * tabela), de campos persistidos e de um construtor vazio para o Gson.</p>
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
     * {@code null} — um UUID é gerado e injetado na primeira gravação, no
     * campo {@code id} (ou, na falta dele, num campo {@code String}/{@code UUID}
     * terminado em "id"), inclusive quando o campo é herdado de uma classe base.</p>
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
     * <p>Sem ID, um UUID é gerado e injetado via reflexão no campo que o
     * {@link #getId()} lê (ver o Javadoc dele). Dentro de uma transação, a
     * gravação entra nela e só vale se ela confirmar.</p>
     *
     * @return {@code true} — a gravação que não acontece lança exceção, nunca devolve
     *         {@code false} em silêncio
     * @throws PersistenceException se o banco recusar a gravação
     * @throws IllegalStateException se a entidade não tiver campo de ID que o
     *         {@link #getId()} leia
     */
    public boolean save() {
        Class<?> type = getClass();
        String table = tableName(type);
        String id = ensureId();
        prepare(type);
        String json = GsonAPI.get().toJson(this);
        write("salvar " + type.getSimpleName() + " id=" + id, conn -> writeRow(conn, table, id, json));
        return true;
    }

    /**
     * Exclui o registro correspondente a este objeto.
     *
     * @return {@code true} se o registro foi removido
     * @throws PersistenceException se o banco recusar a exclusão
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
     * desde a leitura. Os campos herdados de uma classe base também são
     * recarregados; os {@code transient} ficam como estão, porque não vêm do
     * banco.</p>
     *
     * @return A própria instância recarregada, ou {@code null} se o registro não
     *         existir mais
     * @throws PersistenceException se o banco falhar na leitura
     */
    public Saveable reload() {
        String id = getId();
        if (id == null) return null;
        Class<?> type = getClass();
        String table = tableName(type);
        prepare(type);
        String json = read("recarregar " + type.getSimpleName() + " id=" + id,
                conn -> selectJson(conn, table, id));
        if (json == null) return null;
        copyFields(GsonAPI.get().fromJson(json, type), this);
        return this;
    }

    // ==================== ESTÁTICOS: LEITURA ====================

    /**
     * Nome da tabela SQLite de uma entidade — exatamente o que a biblioteca usa em toda
     * operação.
     *
     * <p><strong>A regra inteira:</strong> o nome simples da classe
     * ({@link Class#getSimpleName()}) em minúsculas, com um {@code s} acrescentado
     * <strong>só quando o nome ainda não termina em {@code s}</strong>. Não existe nenhuma
     * outra flexão de plural:</p>
     * <ul>
     *   <li>{@code User} → {@code users}</li>
     *   <li>{@code Address} → {@code address} (já termina em {@code s}: nada é acrescentado —
     *       nunca {@code addresss})</li>
     *   <li>{@code CompanySettings} → {@code companysettings}</li>
     *   <li>{@code Category} → {@code categorys} (não vira {@code categories})</li>
     * </ul>
     *
     * <p>Use este método ao escrever SQL para {@link #query(Class, String, Object...)}, em vez
     * de escrever o nome à mão:</p>
     * <pre>
     * String sql = "SELECT data FROM " + Saveable.tableName(Address.class)
     *         + " WHERE json_extract(data, '$.city') = ?";
     * </pre>
     *
     * <p>Duas classes com o mesmo nome simples, em pacotes diferentes, caem na
     * <strong>mesma</strong> tabela — dê nomes distintos às entidades de um projeto.</p>
     *
     * @param clazz Classe da entidade
     * @return Nome da tabela
     * @throws IllegalArgumentException se a classe for anônima (não tem nome)
     */
    public static String tableName(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz");
        String simpleName = clazz.getSimpleName();
        if (simpleName.isEmpty()) {
            throw new IllegalArgumentException("Classe anônima não pode ser entidade do Saveable: "
                    + "ela não tem nome para virar tabela (" + clazz.getName() + ")");
        }
        String name = simpleName.toLowerCase(Locale.ROOT);
        return name.endsWith("s") ? name : name + "s";
    }

    /**
     * Busca um objeto pelo ID, lendo direto do banco.
     *
     * <p>Cada chamada devolve uma instância nova: alterar o objeto retornado não
     * afeta ninguém até o {@link #save()}.</p>
     *
     * @param clazz Classe da entidade (ex: {@code User.class})
     * @param id    Identificador único
     * @param <T>   Tipo da entidade
     * @return Objeto encontrado, ou {@code null} se ele não existir
     * @throws PersistenceException se o banco falhar — nunca devolvida como {@code null}
     */
    public static <T> T findById(Class<T> clazz, String id) {
        if (id == null) return null;
        String table = tableName(clazz);
        prepare(clazz);
        String json = read("buscar " + clazz.getSimpleName() + " id=" + id,
                conn -> selectJson(conn, table, id));
        return json == null ? null : GsonAPI.get().fromJson(json, clazz);
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
     * @throws PersistenceException se o banco falhar
     */
    public static <T> List<T> findAll(Class<T> clazz) {
        return query(clazz, "SELECT data FROM " + quote(tableName(clazz)));
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
     * @throws PersistenceException se o banco falhar
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
     * <p>O filtro roda no próprio SQLite, com {@code json_extract} — rápido, e mais ainda com o
     * índice de {@link #createIndex(Class, String)} —, sempre que o valor vira um primitivo no
     * JSON gravado: texto, número, booleano, enum, {@code UUID}, datas com adapter no
     * {@link GsonAPI}, e {@code null} (campo nulo ou ausente). O valor é comparado na forma em
     * que o Gson o grava: {@code BigDecimal("10.50")} encontra {@code 10.5}, e um enum é
     * comparado pelo nome. Só valores que viram objeto ou lista no JSON caem no filtro em
     * memória, que lê a tabela inteira.</p>
     *
     * <p>Registro gravado antes de o campo existir na classe não tem a chave no JSON: para o
     * filtro no SQL, o campo dele é {@code null} — mesmo que o construtor da classe dê outro
     * valor padrão.</p>
     *
     * @param clazz     Classe da entidade
     * @param fieldName Nome exato do campo Java (ex: {@code "email"})
     * @param value     Valor procurado
     * @param <T>       Tipo da entidade
     * @return Lista de objetos com o campo igual ao valor (nunca {@code null})
     * @throws PersistenceException se o banco falhar
     */
    public static <T> List<T> findByField(Class<T> clazz, String fieldName, Object value) {
        FieldFilter filter = sqlFilter(clazz, fieldName, value);
        if (filter == null) {
            return findByPredicate(clazz, obj -> Objects.equals(fieldValue(obj, fieldName), value));
        }
        return query(clazz, "SELECT data FROM " + quote(tableName(clazz)) + " WHERE " + filter.where(),
                filter.params());
    }

    /**
     * Busca o primeiro objeto cujo campo seja igual ao valor informado.
     *
     * <p>Atalho para o caso mais comum — achar o usuário pelo e-mail, a sessão
     * pelo token — sem trazer a lista inteira. Mesmas regras de comparação de
     * {@link #findByField(Class, String, Object)}.</p>
     *
     * @param clazz     Classe da entidade
     * @param fieldName Nome exato do campo
     * @param value     Valor procurado
     * @param <T>       Tipo da entidade
     * @return Primeiro objeto encontrado, ou {@code null}
     * @throws PersistenceException se o banco falhar
     */
    public static <T> T findFirstByField(Class<T> clazz, String fieldName, Object value) {
        FieldFilter filter = sqlFilter(clazz, fieldName, value);
        List<T> found = filter == null
                ? findByField(clazz, fieldName, value)
                : query(clazz, "SELECT data FROM " + quote(tableName(clazz)) + " WHERE " + filter.where()
                        + " LIMIT 1", filter.params());
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Verifica se existe um registro com o ID informado.
     *
     * @param clazz Classe da entidade
     * @param id    Identificador
     * @return {@code true} se o registro existir
     * @throws PersistenceException se o banco falhar
     */
    public static boolean exists(Class<?> clazz, String id) {
        if (id == null) return false;
        String table = tableName(clazz);
        prepare(clazz);
        Boolean found = read("verificar " + clazz.getSimpleName() + " id=" + id, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM " + quote(table) + " WHERE id = ? LIMIT 1")) {
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
     * @throws PersistenceException se o banco falhar
     */
    public static long count(Class<?> clazz) {
        String table = tableName(clazz);
        prepare(clazz);
        Long total = read("contar " + clazz.getSimpleName(), conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
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
     * devolvem lista vazia. O nome da tabela vem de {@link #tableName(Class)}.</p>
     *
     * <p>Um {@code SELECT} roda no pool de leitura, em paralelo com tudo; qualquer outro comando
     * — inclusive {@code WITH ... UPDATE} — é gravação e entra na fila de escrita, como
     * {@link #save()}. Fora de transação, esse comando roda sozinho, em autocommit: um comando só
     * já é atômico no SQLite, e é assim que {@code VACUUM}, {@code ATTACH} e a troca de
     * {@code journal_mode}, que o SQLite recusa dentro de uma transação, continuam funcionando.
     * Dentro de uma transação, tudo roda nela.</p>
     *
     * <p>Controle de transação — {@code BEGIN}, {@code COMMIT}, {@code END}, {@code ROLLBACK},
     * {@code SAVEPOINT}, {@code RELEASE} — não passa por aqui: use
     * {@link #transaction(Runnable)}.</p>
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
     * @throws IllegalArgumentException se o comando for de controle de transação
     * @throws PersistenceException se o banco recusar o comando
     */
    public static <T> List<T> query(Class<T> clazz, String sql, Object... params) {
        Objects.requireNonNull(sql, "sql");
        String command = firstKeyword(sql);
        if (TRANSACTION_CONTROL.contains(command)) {
            throw new IllegalArgumentException(command + " não passa pelo query(): a transação é da biblioteca "
                    + "— use Saveable.transaction(...)");
        }
        prepare(clazz);
        Object[] args = params == null ? NO_PARAMS : params;
        String what = "consultar " + clazz.getSimpleName();
        List<String> rows = command.equals("SELECT")
                ? read(what, conn -> dataColumn(conn, sql, args))
                : writeAlone(what, conn -> dataColumn(conn, sql, args));
        return fromJson(rows, clazz);
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
     * @return {@code true} se o índice existe ao final da chamada; {@code false} se o nome do
     *         campo não puder ir ao SQL
     * @throws PersistenceException se o banco recusar a criação
     */
    public static boolean createIndex(Class<?> clazz, String fieldName) {
        String table = tableName(clazz);
        prepare(clazz);
        String path = jsonPath(clazz, fieldName);
        if (path == null) {
            Console.error("Nome de campo inválido para índice: " + fieldName);
            return false;
        }
        String index = "idx_" + table + "_" + fieldName.toLowerCase(Locale.ROOT);
        write("criar índice " + index, conn -> {
            execute(conn, "CREATE INDEX IF NOT EXISTS " + quote(index) + " ON " + quote(table)
                    + "(json_extract(data, '" + path + "'))");
            return null;
        });
        return true;
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
     * <p>O bloco pode ler e gravar outras entidades — tudo entra na mesma transação. Se ele
     * lançar exceção, nada do que fez vale (dentro de uma transação maior, só o que ele fez é
     * desfeito). O bloco não pode trocar o ID do registro.</p>
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
     * @throws PersistenceException se o banco falhar
     * @throws IllegalStateException se o bloco trocar o ID do registro
     */
    public static <T extends Saveable> T mutate(Class<T> clazz, String id, Consumer<T> change) {
        if (id == null || change == null) return null;
        String table = tableName(clazz);
        prepare(clazz);
        String what = "alterar " + clazz.getSimpleName() + " id=" + id;
        return computeInTransaction(() -> {
            String json = read(what, conn -> selectJson(conn, table, id));
            if (json == null) return null;
            Gson gson = GsonAPI.get();
            T obj = gson.fromJson(json, clazz);
            String before = obj.getId();
            change.accept(obj);
            if (!Objects.equals(before, obj.getId())) {
                throw new IllegalStateException("O bloco do mutate não pode trocar o ID do registro (" + what + ")");
            }
            String updated = gson.toJson(obj);
            write(what, conn -> writeRow(conn, table, id, updated));
            return obj;
        });
    }

    /**
     * Grava vários objetos em uma única transação — todos ou nenhum.
     *
     * @param objects Objetos a gravar (podem ser de classes diferentes)
     * @return Quantidade de objetos gravados (os {@code null} da coleção são ignorados)
     * @throws PersistenceException se o banco recusar alguma gravação — e aí nenhuma vale
     */
    public static int saveAll(Collection<? extends Saveable> objects) {
        if (objects == null || objects.isEmpty()) return 0;
        return computeInTransaction(() -> {
            int saved = 0;
            for (Saveable obj : objects) {
                if (obj != null && obj.save()) saved++;
            }
            return saved;
        });
    }

    /**
     * Exclui um registro pelo ID.
     *
     * @param clazz Classe da entidade
     * @param id    Identificador
     * @return {@code true} se o registro foi removido
     * @throws PersistenceException se o banco recusar a exclusão
     */
    public static boolean deleteById(Class<?> clazz, String id) {
        if (id == null) return false;
        String table = tableName(clazz);
        prepare(clazz);
        Boolean deleted = write("excluir " + clazz.getSimpleName() + " id=" + id, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + quote(table) + " WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * Exclui todos os registros da classe.
     *
     * @param clazz Classe da entidade
     * @return Quantidade de registros removidos
     * @throws PersistenceException se o banco recusar a exclusão
     */
    public static int deleteAll(Class<?> clazz) {
        String table = tableName(clazz);
        prepare(clazz);
        Integer deleted = write("excluir todos os " + clazz.getSimpleName(), conn -> {
            try (Statement st = conn.createStatement()) {
                return st.executeUpdate("DELETE FROM " + quote(table));
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
     * o estoque e criar o pedido, debitar de um e creditar no outro. Leituras
     * feitas dentro do bloco enxergam o que ele já gravou.</p>
     *
     * <p><strong>Aninhamento:</strong> uma transação aberta dentro de outra entra nela como
     * <em>savepoint</em>. Se a interna falhar, só o que ela fez é desfeito e a exceção sobe;
     * se a externa capturar a exceção e seguir, confirma o resto sem nenhum pedaço da interna.
     * Nada vale antes de a mais externa confirmar.</p>
     *
     * <p><strong>Duração:</strong> enquanto o bloco roda, nenhuma outra gravação do processo
     * acontece — leituras seguem normalmente. Mantenha o bloco curto e sem chamada de rede.</p>
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
     * @throws RuntimeException se alguma operação falhar — a transação (ou o savepoint, se
     *         aninhada) é desfeita e a exceção é repassada
     * @throws PersistenceException se o banco não conseguir abrir ou confirmar a transação
     */
    public static void transaction(Runnable actions) {
        Objects.requireNonNull(actions, "actions");
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
     * @throws RuntimeException se alguma operação falhar — a transação (ou o savepoint, se
     *         aninhada) é desfeita e a exceção é repassada
     * @throws PersistenceException se o banco não conseguir abrir ou confirmar a transação
     */
    public static <T> T computeInTransaction(Supplier<T> actions) {
        Objects.requireNonNull(actions, "actions");
        Transaction running = CURRENT_TRANSACTION.get();
        return running != null ? runNested(running, actions) : runOutermost("transação", actions);
    }

    // ==================== CICLO DE VIDA ====================

    /**
     * Fecha os pools de conexões. Chame no encerramento da aplicação (shutdown
     * hook) para não vazar conexões nem deixar o WAL sem checkpoint.
     *
     * <p>Espera a gravação e as leituras em curso terminarem — até o prazo de
     * {@code ANGATU_DB_BUSY_TIMEOUT_MS}, contado uma vez para as duas esperas — antes de fechar.
     * Depois dele, o próximo uso abre os pools de novo, no caminho que {@link #databasePath()}
     * indicar naquele momento.</p>
     */
    public static void shutdown() {
        long deadline = deadlineFromNow();
        boolean locked = acquireWriteLock(deadline);
        try {
            Pools current;
            POOLS_LOCK.lock();
            try {
                current = pools;
                pools = null;
            } finally {
                POOLS_LOCK.unlock();
            }
            if (current != null) current.close(deadline);
            PREPARED_TABLES.clear();
            CURRENT_TRANSACTION.remove();
        } finally {
            if (locked) WRITE_LOCK.unlock();
        }
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

    // ==================== INFRAESTRUTURA: LEITURA E ESCRITA ====================

    /** Operação que usa uma conexão já resolvida (do pool de leitura ou da transação). */
    @FunctionalInterface
    private interface SqlWork<R> {
        R apply(Connection conn) throws SQLException;
    }

    /**
     * Executa uma leitura. Dentro de transação, usa a conexão dela (e enxerga o que ela já
     * gravou); fora, uma conexão do pool de leitura, em autocommit.
     *
     * <h4>O trabalho aqui dentro é só SQL</h4>
     * <p>Quem chama converte o JSON em objeto <strong>depois</strong> que este método devolveu a
     * conexão. Desserializar pode rodar código do projeto — um construtor que consulta o banco
     * para gerar um código, por exemplo —, e esse código pediria uma segunda conexão com a
     * primeira ainda emprestada. Com N threads e N conexões, todas seguram uma e esperam a
     * outra: ninguém solta e ninguém anda. Com a conversão do lado de fora, uma thread nunca
     * segura duas conexões de leitura, e esse impasse deixa de ser possível — em vez de ficar
     * dependendo de o pool ser maior que a concorrência do momento.</p>
     *
     * <p>Falha de banco <strong>sobe</strong> como {@link PersistenceException}. Devolver um valor
     * de recuo aqui era o que fazia "o banco falhou" chegar ao chamador disfarçado de "não há
     * nada" — ver o Javadoc daquela classe.</p>
     */
    private static <R> R read(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        try (Connection conn = pools().borrowReader()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw failure(what, e);
        }
    }

    /**
     * Executa uma escrita. Dentro de transação, entra nela — o commit fica com quem abriu;
     * fora, abre a própria transação na vez de gravar.
     */
    private static <R> R write(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        return runOutermost(what, () -> apply(CURRENT_TRANSACTION.get(), what, work));
    }

    /**
     * Executa um comando avulso de escrita. Dentro de transação, entra nela; fora, roda sozinho
     * na conexão de escrita, em autocommit, na vez de gravar.
     *
     * <p>Um comando só já é atômico no SQLite: ele abre e confirma a própria transação. E há
     * comandos que o SQLite recusa dentro de uma transação aberta — {@code VACUUM},
     * {@code ATTACH}, a troca de {@code journal_mode}. Abrir um {@code BEGIN IMMEDIATE} em volta
     * deles era o que fazia o {@code VACUUM} pelo {@link #query(Class, String, Object...)} deixar
     * de funcionar.</p>
     */
    private static <R> R writeAlone(String what, SqlWork<R> work) {
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) return apply(running, what, work);
        lockWriter(what);
        long lockedAt = System.nanoTime();
        try (Connection conn = pools().borrowWriter()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw failure(what, e);
        } finally {
            WRITE_LOCK.unlock();
            warnIfSlow(what, lockedAt);
        }
    }

    /**
     * Aplica o trabalho na conexão da transação em curso, convertendo a falha do driver.
     *
     * <h4>Transação que o SQLite desfez sozinho</h4>
     * <p>Alguns erros fazem o SQLite desfazer a transação <strong>inteira</strong>, não só o
     * comando que falhou: disco cheio, erro de E/S, falta de memória, {@code ON CONFLICT ROLLBACK}.
     * A conexão volta ao autocommit sem o driver saber, e quem capturava a exceção e seguia —
     * o padrão documentado da transação aninhada — gravava o resto <strong>fora</strong> da
     * transação, cada comando confirmado na hora, sozinho: o crédito sem o débito. Por isso,
     * depois de toda falha, a transação é conferida (ver
     * {@link Transaction#checkStillOpen(Throwable)}); perdida, ela não roda mais nada e termina
     * em falha.</p>
     */
    private static <R> R apply(Transaction tx, String what, SqlWork<R> work) {
        tx.ensureUsable(what);
        try {
            return work.apply(tx.connection);
        } catch (SQLException e) {
            tx.checkStillOpen(e);
            throw failure(tx.broken ? what + " — o SQLite desfez a transação inteira" : what, e);
        }
    }

    /**
     * Abre a transação mais externa na conexão de escrita, roda o bloco e confirma.
     *
     * <p>Enquanto o bloco roda, a transação fica presa à thread: toda leitura, gravação,
     * criação de tabela ou transação aninhada feita por ele entra nela, sem pedir outra
     * conexão — que esperaria pela trava do arquivo que a própria thread já segura.</p>
     *
     * <p>As tabelas criadas no bloco só passam a valer para o processo depois do commit;
     * desfeita a transação, elas somem do arquivo e também do registro.</p>
     *
     * <p>Transação que o SQLite desfez sozinho (ver {@link #apply(Transaction, String, SqlWork)})
     * termina em {@link PersistenceException}, mesmo que o bloco tenha engolido a falha: não há o
     * que confirmar. A conexão dela é descartada, e o pool abre outra.</p>
     */
    private static <T> T runOutermost(String what, Supplier<T> actions) {
        lockWriter(what);
        long lockedAt = System.nanoTime();
        try {
            Pools current = pools();
            Connection conn;
            try {
                conn = current.borrowWriter();
            } catch (SQLException e) {
                throw failure("obter a conexão de escrita para " + what, e);
            }
            Transaction tx = new Transaction(conn);
            boolean commitStarted = false;
            boolean clean = false;
            try {
                try {
                    conn.setAutoCommit(false); // BEGIN IMMEDIATE: a trava de escrita do arquivo desde já
                } catch (SQLException e) {
                    tx.broken = true; // não abriu: não há o que desfazer, e o driver ficou fora de sincronia
                    throw failure("abrir a transação para " + what, e);
                }
                CURRENT_TRANSACTION.set(tx);
                T result = actions.get();
                tx.ensureUsable(what); // o bloco engoliu uma falha que levou a transação junto
                if (tx.rollbackOnly) {
                    throw logged(new PersistenceException("Transação desfeita inteira: um trecho aninhado falhou e "
                            + "não pôde ser desfeito sozinho (" + what + ")"));
                }
                commitStarted = true;
                try {
                    commit(conn);
                } catch (SQLException e) {
                    throw failure("confirmar " + what, e);
                }
                clean = true;
                tx.publishCreatedTables();
                return result;
            } finally {
                CURRENT_TRANSACTION.remove();
                if (!commitStarted && !tx.broken) clean = rollback(conn);
                current.release(conn, clean);
            }
        } finally {
            WRITE_LOCK.unlock();
            warnIfSlow(what, lockedAt);
        }
    }

    /**
     * Transação aninhada: um savepoint dentro da transação em curso, com o próprio tudo ou
     * nada. Falhou, desfaz só o que o bloco fez — inclusive tabelas criadas nele — e repassa
     * a exceção.
     *
     * <p>Numa transação que o SQLite já desfez, nem começa: um {@code SAVEPOINT} fora de
     * transação abre uma nova, e o bloco gravaria sozinho o que devia ir junto com o resto.</p>
     */
    private static <T> T runNested(Transaction tx, Supplier<T> actions) {
        tx.ensureUsable("abrir transação aninhada");
        String savepoint;
        try {
            savepoint = tx.openSavepoint();
        } catch (SQLException e) {
            tx.checkStillOpen(e);
            throw failure("abrir transação aninhada", e);
        }
        T result;
        try {
            result = actions.get();
        } catch (Throwable failure) {
            if (tx.broken) throw failure; // o SQLite já desfez tudo, o savepoint junto
            try {
                tx.rollbackToSavepoint(savepoint);
            } catch (SQLException undo) {
                tx.rollbackOnly = true; // estado do trecho desconhecido: nada desta transação vale
                tx.checkStillOpen(undo);
                failure.addSuppressed(undo);
            }
            throw failure;
        }
        tx.ensureUsable("confirmar transação aninhada"); // o bloco engoliu uma falha que levou tudo junto
        try {
            tx.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            tx.rollbackOnly = true;
            tx.checkStillOpen(e);
            throw failure("confirmar transação aninhada", e);
        }
        return result;
    }

    /**
     * Confirma a transação.
     *
     * <h4>Por {@code setAutoCommit(true)}, e não por {@code commit()}</h4>
     * <p>O {@code commit()} do sqlite-jdbc emenda, na mesma chamada, um novo
     * {@code BEGIN IMMEDIATE}. Se outro processo segurar a trava do arquivo naquele instante,
     * esse BEGIN espera e estoura — e o {@code commit()} lança erro <strong>depois</strong> de o
     * dado já estar gravado. Quem chamou recebia "falhou" de uma gravação que valeu, e podia
     * repeti-la: um débito cobrado duas vezes. Voltar ao autocommit executa só o
     * {@code COMMIT}.</p>
     */
    private static void commit(Connection conn) throws SQLException {
        conn.setAutoCommit(true);
    }

    /**
     * Desfaz a transação e devolve a conexão ao autocommit.
     *
     * @return {@code true} se a conexão ficou limpa e pode voltar ao pool
     */
    private static boolean rollback(Connection conn) {
        try {
            conn.rollback();          // desfaz; o driver reabre um BEGIN em seguida...
            conn.setAutoCommit(true); // ...e este COMMIT fecha esse BEGIN vazio
            return true;
        } catch (SQLException e) {
            Console.error("Falha ao desfazer a transação do Saveable", e);
            return false;
        }
    }

    /**
     * Toma a vez de gravar, esperando no máximo {@link #BUSY_TIMEOUT_MS}.
     *
     * <p>Sem prazo, uma transação que nunca termina — uma chamada de rede pendurada dentro
     * dela — parava todas as gravações do processo para sempre, e as requisições se
     * acumulavam até o contêiner cair.</p>
     *
     * <h4>Pedido de interrupção não derruba a gravação</h4>
     * <p>Uma thread interrompida — tarefa cancelada, desligamento em curso, o idioma de devolver
     * o pedido à thread e seguir com a limpeza — ainda precisa gravar o estado dela. A espera não
     * para no pedido: segue até a vez chegar ou o prazo vencer, e o pedido volta à thread no fim,
     * para quem chamou tratá-lo. Antes, uma thread com o pedido pendente não gravava nada, mesmo
     * sem mais ninguém na fila.</p>
     */
    private static void lockWriter(String what) {
        if (!acquireWriteLock(deadlineFromNow())) {
            throw logged(new PersistenceException("Banco ocupado: a vez de gravar não chegou em "
                    + BUSY_TIMEOUT_MS + " ms (" + what + ")"));
        }
    }

    /**
     * Espera a vez de gravar até o prazo, sem parar num pedido de interrupção — ele volta à
     * thread no fim (ver {@link #lockWriter(String)}).
     *
     * @param deadline Prazo, na escala de {@link System#nanoTime()}
     * @return {@code true} se a vez chegou dentro do prazo
     */
    private static boolean acquireWriteLock(long deadline) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    return WRITE_LOCK.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Prazo de {@link #BUSY_TIMEOUT_MS} a partir de agora, na escala de {@link System#nanoTime()}. */
    private static long deadlineFromNow() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_TIMEOUT_MS);
    }

    /** Registra no log a gravação que segurou a vez por tempo demais, com quem a chamou. */
    private static void warnIfSlow(String what, long lockedAt) {
        long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lockedAt);
        if (heldMs < SLOW_WRITE_MS) return;
        Console.warn("Saveable: \"%s\" (em %s) segurou a vez de gravar por %d ms — as outras gravações "
                + "esperaram. Tire chamada de rede e trabalho lento de dentro da transação.",
                what, callerOutsideSaveable(), heldMs);
    }

    /** Primeiro ponto da pilha fora desta classe: quem pediu a operação. */
    private static String callerOutsideSaveable() {
        String self = Saveable.class.getName();
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(self) && !frame.getClassName().startsWith(self + "$"))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("?"));
    }

    /**
     * Converte a falha do driver na exceção da biblioteca e a registra no log. Se o banco
     * disse que a tabela não existe, tira a classe do registro de tabelas prontas: a chamada
     * seguinte cria a tabela de novo, em vez de falhar até o processo reiniciar.
     */
    private static PersistenceException failure(String what, SQLException e) {
        forgetMissingTable(e);
        return logged(new PersistenceException("Erro ao " + what, e));
    }

    private static PersistenceException logged(PersistenceException e) {
        Console.error(e.getMessage(), e.getCause());
        return e;
    }

    /** Esquece a tabela que o banco disse não existir (registro global e transação em curso). */
    private static void forgetMissingTable(SQLException e) {
        String message = e.getMessage();
        if (message == null) return;
        Matcher missing = MISSING_TABLE.matcher(message);
        if (!missing.find()) return;
        String table = missing.group(1);
        PREPARED_TABLES.removeIf(type -> tableName(type).equalsIgnoreCase(table));
        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) running.forget(table);
    }

    // ==================== INFRAESTRUTURA: TABELAS E POOLS ====================

    /**
     * Garante que a tabela da classe exista, no mesmo formato de sempre
     * ({@code id}, {@code data}). Executa uma vez por classe, por execução.
     *
     * <p>Nenhuma coluna é adicionada ou alterada: o banco de um sistema que roda
     * outra versão da biblioteca continua idêntico e compatível.</p>
     *
     * <h4>Dentro de transação, a tabela fica pendente</h4>
     * <p>O {@code CREATE TABLE} roda na conexão da transação — a única possível: em outra
     * conexão, ele esperaria pela trava do arquivo que a própria thread segura. Mas o DDL do
     * SQLite é transacional: se a transação desfizer, a tabela some. Por isso ela só entra em
     * {@link #PREPARED_TABLES} quando a transação confirma (ver {@link Transaction}).</p>
     *
     * <h4>Fora de transação, quase sempre sem entrar na fila</h4>
     * <p>Depois de um reinício, a tabela já existe no arquivo: uma consulta ao
     * {@code sqlite_master} pelo pool de leitura confirma isso sem disputar a vez de gravar.
     * Só a tabela realmente nova é criada, na vez de gravar e em autocommit — confirmada na
     * hora.</p>
     */
    private static void prepare(Class<?> type) {
        if (PREPARED_TABLES.contains(type)) return;
        String table = tableName(type);

        Transaction running = CURRENT_TRANSACTION.get();
        if (running != null) {
            if (running.hasCreated(type)) return;
            apply(running, "preparar a tabela " + table, conn -> {
                createTable(conn, table);
                return null;
            });
            running.markCreated(type);
            return;
        }

        if (tableExistsOnDisk(table)) {
            PREPARED_TABLES.add(type);
            return;
        }
        writeAlone("preparar a tabela " + table, conn -> {
            if (!PREPARED_TABLES.contains(type)) createTable(conn, table); // autocommit: confirmada na hora
            return null;
        });
        PREPARED_TABLES.add(type);
    }

    /** A tabela já existe no arquivo? Pergunta pelo pool de leitura, sem entrar na fila de escrita. */
    private static boolean tableExistsOnDisk(String table) {
        Boolean found = read("verificar a tabela " + table, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
        return Boolean.TRUE.equals(found);
    }

    /** Executa o CREATE TABLE IF NOT EXISTS da entidade, no formato de sempre. */
    private static void createTable(Connection conn, String table) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS " + quote(table) + " (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
    }

    /** Pools do banco, criados no primeiro uso. */
    private static Pools pools() {
        Pools current = pools;
        if (current != null) return current;

        Dependencies.require("org.sqlite.JDBC", SQLITE_COORDINATES, PERSISTENCE_FEATURE);
        Dependencies.require("com.zaxxer.hikari.HikariDataSource", HIKARI_COORDINATES, PERSISTENCE_FEATURE);
        Dependencies.require("com.google.gson.Gson", GSON_COORDINATES, PERSISTENCE_FEATURE);

        POOLS_LOCK.lock();
        try {
            if (pools == null) {
                String path = databasePath();
                try {
                    pools = new Pools(path);
                } catch (RuntimeException e) {
                    throw logged(new PersistenceException("Não foi possível abrir o banco SQLite em " + path, e));
                }
                Console.log("&7Banco SQLite: &f%s &7(WAL, 1 conexão de escrita e %d de leitura, cache de %d MiB por conexão)",
                        path, READ_POOL_SIZE, Integer.valueOf(CACHE_SIZE_KIB / 1024));
            }
            return pools;
        } finally {
            POOLS_LOCK.unlock();
        }
    }

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
    private static int intFromEnvironment(String key, int fallback, int min, int max) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) return fallback;
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ==================== INFRAESTRUTURA: SQL ====================

    /** Executa um comando sem resultado. */
    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Grava o registro no formato de sempre: {@code INSERT OR REPLACE (id, data)}. */
    private static boolean writeRow(Connection conn, String table, String id, String json) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO " + quote(table) + " (id, data) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, json);
            ps.executeUpdate();
            return true;
        }
    }

    /** Lê o JSON de um registro pelo ID. */
    private static String selectJson(Connection conn, String table, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data FROM " + quote(table) + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("data") : null;
            }
        }
    }

    /**
     * Executa o SQL e devolve a coluna {@code data} de cada linha, ainda em texto — a
     * conversão em objeto é de quem chama, fora da conexão (ver {@link #read(String, SqlWork)}).
     */
    private static List<String> dataColumn(Connection conn, String sql, Object[] params) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            if (!ps.execute()) return rows; // comando sem resultado (DDL/UPDATE/DELETE)
            try (ResultSet rs = ps.getResultSet()) {
                int column = columnIndex(rs.getMetaData(), "data");
                if (column == 0) {
                    throw new UnsupportedOperationException(
                            "Consulta customizada deve retornar a coluna 'data' com o JSON do objeto: " + sql);
                }
                while (rs.next()) rows.add(rs.getString(column));
            }
        }
        return rows;
    }

    /** Converte as linhas lidas em objetos, soltando cada texto assim que ele vira objeto. */
    private static <T> List<T> fromJson(List<String> rows, Class<T> clazz) {
        Gson gson = GsonAPI.get();
        List<T> objects = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            objects.add(gson.fromJson(rows.get(i), clazz));
            rows.set(i, null); // numa tabela grande, texto e objeto não precisam coexistir inteiros
        }
        return objects;
    }

    /**
     * Primeira palavra do comando, em maiúsculas, depois de espaços e comentários SQL de linha e
     * de bloco; vazia se não houver. Só {@code SELECT} vai ao pool de leitura — e um comentário
     * na frente dele não pode mandá-lo para a fila de escrita, nem esconder um {@code BEGIN}.
     */
    private static String firstKeyword(String sql) {
        int i = 0;
        int length = sql.length();
        while (i < length) {
            if (Character.isWhitespace(sql.charAt(i))) {
                i++;
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < length && Character.isLetter(sql.charAt(i))) i++;
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    /** Índice da coluna pelo nome, ou {@code 0} se ela não estiver no resultado. */
    private static int columnIndex(ResultSetMetaData meta, String name) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(meta.getColumnLabel(i)) || name.equalsIgnoreCase(meta.getColumnName(i))) return i;
        }
        return 0;
    }

    /**
     * Identificador entre aspas duplas, como o SQL pede. O nome não muda — a tabela
     * {@code users} continua {@code users} —, mas uma entidade cujo nome coincide com palavra
     * reservada ({@code Values} → {@code values}) deixa de quebrar o SQL gerado.
     */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    // ==================== INFRAESTRUTURA: CAMPOS ====================

    /** Filtro SQL por um campo do JSON: a condição e os parâmetros dela. */
    private record FieldFilter(String where, Object[] params) {
    }

    /**
     * Filtro por campo resolvido no SQL, ou {@code null} quando o valor precisa do filtro em
     * memória (valor que vira objeto ou lista no JSON, ou nome de campo que não pode ir ao SQL).
     */
    private static FieldFilter sqlFilter(Class<?> clazz, String fieldName, Object value) {
        Gson gson = GsonAPI.get(); // confere a dependência antes de tocar em qualquer tipo do Gson
        String path = jsonPath(clazz, fieldName);
        if (path == null) return null;
        String column = "json_extract(data, '" + path + "')";
        if (value == null) return new FieldFilter(column + " IS NULL", NO_PARAMS);
        Object sqlValue = sqlValue(gson, value);
        return sqlValue == null ? null : new FieldFilter(column + " = ?", new Object[] {sqlValue});
    }

    /**
     * O valor na forma em que o JSON gravado o representa, pronto para comparar com o
     * resultado de {@code json_extract}: texto para texto, enum e {@code UUID}; inteiro exato
     * ou {@code REAL} para número; {@code 1}/{@code 0} para booleano. {@code null} quando o
     * valor não vira um primitivo JSON.
     */
    private static Object sqlValue(Gson gson, Object value) {
        JsonElement json;
        try {
            json = gson.toJsonTree(value);
        } catch (RuntimeException notRepresentable) {
            return null; // ex.: NaN, que o JSON não representa — e que por isso nenhum registro guarda
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

    /** Caminho JSON do campo para {@code json_extract}, ou {@code null} se não puder ir ao SQL. */
    private static String jsonPath(Class<?> clazz, String fieldName) {
        if (fieldName == null || !SAFE_FIELD.matcher(fieldName).matches()) return null;
        String key = jsonKey(clazz, fieldName);
        return SAFE_FIELD.matcher(key).matches() ? "$." + key : null;
    }

    /**
     * Nome da chave no JSON persistido: normalmente igual ao campo Java, exceto
     * quando o campo declara {@code @SerializedName}.
     */
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

    /** Lê um campo por reflexão (fallback do filtro por campo). */
    private static Object fieldValue(Object obj, String fieldName) {
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

    /**
     * Garante um ID para o objeto, gerando e injetando um UUID se necessário.
     *
     * @throws IllegalStateException se nenhum campo candidato for o que o {@link #getId()} lê
     */
    private String ensureId() {
        String id = getId();
        if (id != null && !id.isEmpty()) return id;
        String generated = UUID.randomUUID().toString();
        injectId(generated);
        return generated;
    }

    /**
     * Injeta o ID gerado no campo que o {@link #getId()} lê.
     *
     * <p>Candidatos, do mais provável ao menos: o campo {@code id}, depois campos
     * {@code String}/{@code UUID} terminados em "id" ({@code userId}) — na classe e nas classes
     * base. Cada tentativa é conferida chamando o {@link #getId()}: se ele não devolver o valor
     * injetado, o campo não era o do ID, o valor anterior volta e o próximo é tentado.</p>
     *
     * <p>A conferência existe porque o chute errado não falhava: um {@code boolean paid}
     * declarado antes do {@code id} "terminava em id" e derrubava a gravação; e um ID gravado
     * sem o {@code getId()} enxergá-lo criava um registro novo a cada {@code save()}.</p>
     */
    private void injectId(String generated) {
        for (Field field : idCandidates(getClass())) {
            if (tryInjectId(field, generated)) return;
        }
        throw new IllegalStateException(getClass().getName() + " não tem campo de ID que o getId() leia: "
                + "declare um campo \"id\" do tipo String ou UUID e devolva-o em getId()");
    }

    /** Tenta um candidato; se o {@link #getId()} não o enxergar, devolve o valor anterior ao campo. */
    private boolean tryInjectId(Field field, String generated) {
        Object previous;
        try {
            field.setAccessible(true);
            previous = field.get(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // campo inacessível: segue para o próximo candidato
        }
        try {
            field.set(this, field.getType() == UUID.class ? UUID.fromString(generated) : generated);
            if (generated.equals(getId())) return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // não aceitou a escrita, ou o getId() falhou com o valor novo
        }
        try {
            field.set(this, previous);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // mesmo campo que acabou de aceitar leitura: a restauração não falha na prática
        }
        return false;
    }

    /** Campos que podem guardar o ID: {@code id} primeiro, depois os terminados em "id". */
    private static List<Field> idCandidates(Class<?> type) {
        List<Field> exact = new ArrayList<>();
        List<Field> suffixed = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Saveable.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean idType = field.getType() == String.class || field.getType() == UUID.class;
                if (!idType || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.equals("id")) exact.add(field);
                else if (name.endsWith("id")) suffixed.add(field);
            }
        }
        exact.addAll(suffixed);
        return exact;
    }

    /**
     * Copia os campos de uma instância recém-lida para a instância atual — os da classe e os
     * herdados, menos {@code static} e {@code transient}, que não vêm do banco.
     */
    private static void copyFields(Object from, Object to) {
        for (Class<?> type = from.getClass(); type != null && type != Saveable.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                try {
                    field.setAccessible(true);
                    field.set(to, field.get(from));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Campo que não aceita escrita (ex: final): mantém o valor atual
                }
            }
        }
    }

    // ==================== TIPOS INTERNOS ====================

    /**
     * Transação de escrita em curso na thread: a conexão do escritor e as tabelas criadas nela.
     *
     * <p>Confinada à thread que a abriu (vive em {@link #CURRENT_TRANSACTION}); não precisa de
     * sincronização.</p>
     */
    private static final class Transaction {

        final Connection connection;

        /**
         * Tabelas criadas nesta transação, uma camada por savepoint aberto. Só entram em
         * {@link #PREPARED_TABLES} quando a transação mais externa confirma; um savepoint
         * desfeito descarta a camada dele, porque o SQLite desfez junto o {@code CREATE TABLE}.
         */
        private final Deque<Set<Class<?>>> createdTables = new ArrayDeque<>();

        private int savepointSequence;

        /** Um savepoint não pôde ser desfeito sozinho: nada desta transação pode ser confirmado. */
        boolean rollbackOnly;

        /**
         * O SQLite desfez a transação inteira por conta própria, ou ela nem chegou a abrir. A
         * conexão está em autocommit sem o driver saber: nada mais roda nela, nada é confirmado, e
         * a conexão é descartada no fim (ver {@link Saveable#apply(Transaction, String, SqlWork)}).
         */
        boolean broken;

        Transaction(Connection connection) {
            this.connection = connection;
            createdTables.push(new HashSet<>());
        }

        /**
         * Depois de uma falha de SQL, confere se o SQLite ainda mantém a transação aberta.
         *
         * <p>Pergunta com um {@code BEGIN} — o mesmo teste que o próprio driver usa para saber se
         * está em autocommit. Com a transação de pé, o SQLite o recusa ("cannot start a
         * transaction within a transaction") e nada muda. Sem ela, o {@code BEGIN} abre uma
         * transação vazia, desfeita na hora, e esta fica marcada como perdida. Qualquer outra
         * resposta também marca: estado desconhecido não é confirmado.</p>
         *
         * @param failure Falha que motivou a conferência; recebe como suprimida o que der errado aqui
         */
        void checkStillOpen(Throwable failure) {
            if (broken) return;
            try {
                execute(connection, "BEGIN");
            } catch (SQLException refused) {
                String message = refused.getMessage();
                if (message != null && message.contains("within a transaction")) return; // continua aberta
                broken = true;
                failure.addSuppressed(refused);
                return;
            }
            broken = true;
            try {
                execute(connection, "ROLLBACK"); // fecha a transação vazia que a pergunta abriu
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
        }

        /** Recusa rodar qualquer coisa numa transação que o SQLite já desfez. */
        void ensureUsable(String what) {
            if (broken) {
                throw logged(new PersistenceException("Transação perdida: depois de uma falha, o SQLite desfez "
                        + "tudo o que ela tinha gravado, e nada mais roda nela (" + what + ")"));
            }
        }

        boolean hasCreated(Class<?> type) {
            for (Set<Class<?>> layer : createdTables) {
                if (layer.contains(type)) return true;
            }
            return false;
        }

        void markCreated(Class<?> type) {
            createdTables.peek().add(type);
        }

        void forget(String table) {
            for (Set<Class<?>> layer : createdTables) {
                layer.removeIf(type -> tableName(type).equalsIgnoreCase(table));
            }
        }

        String openSavepoint() throws SQLException {
            String name = "angatu_sp_" + (++savepointSequence);
            execute(connection, "SAVEPOINT " + name);
            createdTables.push(new HashSet<>());
            return name;
        }

        void releaseSavepoint(String name) throws SQLException {
            execute(connection, "RELEASE SAVEPOINT " + name);
            Set<Class<?>> layer = createdTables.pop();
            createdTables.peek().addAll(layer);
        }

        void rollbackToSavepoint(String name) throws SQLException {
            createdTables.pop(); // o que foi criado no trecho deixa de existir com ele
            execute(connection, "ROLLBACK TO SAVEPOINT " + name);
            execute(connection, "RELEASE SAVEPOINT " + name);
        }

        /** Chamado só depois do commit: agora as tabelas existem de fato para o processo. */
        void publishCreatedTables() {
            for (Set<Class<?>> layer : createdTables) PREPARED_TABLES.addAll(layer);
        }
    }

    /**
     * Os dois pools do banco desta aplicação: um escritor e os leitores.
     *
     * <p>Toda referência ao HikariCP mora aqui dentro, para que carregar o {@code Saveable}
     * não exija o jar dele antes de {@link Dependencies#require} poder explicar o que falta.</p>
     */
    private static final class Pools {

        /** Uma conexão só: a vez de gravar garante que nunca há duas escritas ao mesmo tempo. */
        final HikariDataSource writer;

        /** Leitura em paralelo, com {@code query_only}: escrever por aqui é erro, não disputa. */
        final HikariDataSource readers;

        Pools(String path) {
            // O escritor abre primeiro: num banco novo, é ele quem liga o WAL, antes de existir leitor.
            writer = new HikariDataSource(config(path, "Saveable-escrita", 1, null));
            try {
                readers = new HikariDataSource(config(path, "Saveable-leitura", READ_POOL_SIZE, "PRAGMA query_only = 1"));
            } catch (RuntimeException e) {
                writer.close();
                throw e;
            }
        }

        private static HikariConfig config(String path, String name, int size, String initSql) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + path);
            config.setPoolName(name);
            config.setMaximumPoolSize(size);
            config.setMinimumIdle(Math.min(size, 2));
            // Só o pool que encolhe tem o que fechar por ociosidade; no de tamanho fixo o HikariCP
            // ignoraria o valor e ainda registraria um aviso a cada subida.
            if (size > 2) config.setIdleTimeout(30_000);
            config.setConnectionTimeout(BUSY_TIMEOUT_MS);
            config.setConnectionTestQuery("SELECT 1");
            if (initSql != null) config.setConnectionInitSql(initSql);
            // Valores SEMPRE em texto. O HikariCP repassa as propriedades ao driver como vieram, e o
            // driver as lê com getProperty — que devolve null para o que não for String. Um Integer
            // aqui era ignorado em silêncio: foi assim que o cache_size nunca chegou a valer.
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("busy_timeout", Integer.toString(BUSY_TIMEOUT_MS));
            config.addDataSourceProperty("transaction_mode", "IMMEDIATE");
            config.addDataSourceProperty("cache_size", Integer.toString(-CACHE_SIZE_KIB)); // negativo = KiB
            config.addDataSourceProperty("temp_store", "MEMORY");
            config.addDataSourceProperty("journal_size_limit", Long.toString(JOURNAL_SIZE_LIMIT_BYTES));
            return config;
        }

        /**
         * Devolve a conexão de escrita ao pool.
         *
         * <h4>Conexão com transação em estado desconhecido não volta ao pool</h4>
         * <p>Quando o {@code rollback} ou o {@code COMMIT} falham, a conexão pode seguir com uma
         * transação aberta e parcialmente escrita. Devolvê-la assim é <strong>pior do que
         * perdê-la</strong>: a próxima escrita herdaria o que sobrou, e o próximo autocommit
         * confirmaria exatamente o que deveria ter sido desfeito. Por isso ela é
         * <strong>descartada</strong>: o pool abre outra, limpa. Uma conexão custa milissegundos;
         * uma gravação parcial confirmada custa o dado.</p>
         */
        void release(Connection conn, boolean clean) {
            if (!clean) {
                // evictConnection MARCA a conexão para descarte; quem a devolve ao pool ainda é o
                // close(). Sem os dois, ela fica emprestada para sempre e o pool de escrita seca.
                try {
                    writer.evictConnection(conn);
                } catch (RuntimeException e) {
                    Console.error("Falha ao marcar conexão para descarte", e);
                }
            }
            try {
                conn.close();
            } catch (SQLException e) {
                Console.error("Falha ao devolver conexão ao pool", e);
            }
        }

        /** Conexão de leitura; ver {@link #borrow(HikariDataSource)}. */
        Connection borrowReader() throws SQLException {
            return borrow(readers);
        }

        /** A conexão de escrita — só com a vez de gravar; ver {@link #borrow(HikariDataSource)}. */
        Connection borrowWriter() throws SQLException {
            return borrow(writer);
        }

        /**
         * Pega uma conexão do pool sem deixar um pedido de interrupção derrubar a espera — a mesma
         * regra de {@link Saveable#lockWriter(String)}. O HikariCP desiste da espera no pedido e o
         * devolve à thread; aqui a espera segue até o prazo, e o pedido volta à thread no fim.
         */
        private static Connection borrow(HikariDataSource pool) throws SQLException {
            boolean interrupted = Thread.interrupted();
            long deadline = deadlineFromNow();
            try {
                while (true) {
                    try {
                        return pool.getConnection();
                    } catch (SQLException e) {
                        if (!(e.getCause() instanceof InterruptedException) || deadline - System.nanoTime() <= 0) throw e;
                        Thread.interrupted();
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /**
         * Fecha os dois pools, depois de esperar as leituras em curso até o prazo.
         *
         * <h4>Por que esperar</h4>
         * <p>Ao fechar, o HikariCP "aborta" a conexão ainda emprestada — e o {@code abort} do
         * driver do SQLite não faz nada. A leitura em curso terminava, devolvia a conexão a um pool
         * já fechado, e ela ficava aberta para sempre: o arquivo preso (no Windows, impossível de
         * apagar ou trocar) e o {@code -wal} sem o checkpoint do fechamento.</p>
         *
         * @param deadline Prazo, na escala de {@link System#nanoTime()}
         */
        void close(long deadline) {
            awaitIdle(readers, deadline);
            try {
                readers.close();
            } finally {
                writer.close();
            }
        }

        /** Espera o pool não ter nenhuma conexão emprestada, até o prazo; interrupção não encurta. */
        private static void awaitIdle(HikariDataSource pool, long deadline) {
            HikariPoolMXBean state = pool.getHikariPoolMXBean();
            if (state == null) return;
            boolean interrupted = false;
            while (state.getActiveConnections() > 0 && deadline - System.nanoTime() > 0) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
