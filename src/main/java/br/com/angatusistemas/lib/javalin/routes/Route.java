package br.com.angatusistemas.lib.javalin.routes;

import java.util.Objects;
import java.util.function.Consumer;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.websocket.WsConfig;
import lombok.Getter;

/**
 * Classe base abstrata para definição de rotas HTTP ou WebSocket no servidor
 * Javalin da biblioteca.
 *
 * <p><strong>Propósito:</strong> permite declarar rotas como classes Java e
 * delegar o ciclo de vida à biblioteca — a rota é descoberta por reflection no
 * classpath e registrada automaticamente durante a inicialização do servidor
 * ({@link JavalinAPI#setup}).</p>
 *
 * <p><strong>Quando usar:</strong> sempre que uma rota pertencer à aplicação e
 * puder ser descoberta automaticamente no startup. Crie uma subclasse concreta
 * com construtor vazio; a biblioteca a instancia e chama {@link #register()}.</p>
 *
 * <p><strong>Quando NÃO usar:</strong></p>
 * <ul>
 *   <li>Não instancie {@code Route} diretamente — a classe é abstrata e os
 *       construtores são {@code protected}: a única forma de uso é via
 *       {@code extends} (subclasse).</li>
 *   <li>Não chame {@link #register()} manualmente fora do ciclo de vida da
 *       biblioteca, a menos que queira registrar uma rota em tempo de execução
 *       após o servidor já ter iniciado.</li>
 *   <li>Não crie a rota antes de {@link JavalinAPI#setup} — a construção exige
 *       o servidor inicializado e falha com mensagem clara caso contrário.</li>
 * </ul>
 *
 * <p><strong>Integração:</strong> {@link JavalinAPI#setup} dispara a descoberta
 * via {@code Reflections}; cada subclasse encontrada (não abstrata, com
 * construtor vazio) é instanciada e registrada em
 * {@code app.unsafe.routes.<método>}. O caminho e o handler são definidos no
 * construtor da subclasse via {@code super(...)}.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>Defina a subclasse com construtor vazio chamando {@code super(path, type, handler)};</li>
 *   <li>Garanta que {@link JavalinAPI#setup} foi chamado antes (o construtor exige o servidor ativo);</li>
 *   <li>A biblioteca descobre, instancia e registra a rota automaticamente.</li>
 * </ol>
 *
 * <p><strong>Exemplo (HTTP):</strong>
 * <pre>
 * public class HomeRoute extends Route {
 *     public HomeRoute() {
 *         super("/", RouteType.GET, ctx -&gt; ctx.html("&lt;h1&gt;Início&lt;/h1&gt;"));
 *     }
 * }
 * </pre>
 * </p>
 *
 * <p><strong>Exemplo (WebSocket):</strong>
 * <pre>
 * public class ChatRoute extends Route {
 *     public ChatRoute() {
 *         super("/chat", ws -&gt; ws.onMessage(ctx -&gt; ctx.send("echo: " + ctx.message())));
 *     }
 * }
 * </pre>
 * </p>
 *
 * <p><strong>Boas práticas:</strong> um construtor público vazio por rota
 * (necessário para a descoberta automática); nomes de classe no padrão
 * {@code XxxRoute}; mantenha o handler enxuto e delegue a lógica a serviços.</p>
 *
 * <p><strong>Limitações:</strong> rotas registradas após o servidor iniciar
 * usam a API {@code unsafe.routes} do Javalin (necessária para registro em
 * tempo de execução). A descoberta automática depende da dependência
 * {@code org.reflections:reflections:0.10.2} — se ausente, a biblioteca exibe
 * instruções de instalação.</p>
 *
 * <p><strong>Extensões futuras:</strong> a classe não é {@code sealed} porque
 * consumidores externos precisam poder estendê-la para criar rotas próprias —
 * exatamente o padrão de extensão previsto. Novos métodos HTTP podem ser
 * adicionados a {@link RouteType} sem quebrar subclasses existentes.</p>
 *
 * @author Angatu Sistemas
 * @see RouteType
 * @see JavalinAPI
 */
@Getter
public abstract class Route {

    /** Instância do Javalin obtida no momento da criação da rota. */
    protected final Javalin app;
    /** Caminho (path) da rota. */
    protected final String path;
    /** Tipo da rota (HTTP ou WebSocket). */
    protected final RouteType type;

    /** Handler para rotas HTTP. */
    protected Handler handler;
    /** Handler para rotas WebSocket. */
    protected Consumer<WsConfig> wsHandler;

    /**
     * Cria uma rota HTTP. Construtor {@code protected}: acessível apenas por
     * subclasses (uso exclusivo via {@code extends}).
     *
     * <p><strong>Pré-condições:</strong> {@code JavalinAPI.setup(...)} deve ter
     * sido chamado (o servidor deve estar inicializado); {@code path} e
     * {@code handler} não podem ser {@code null}.</p>
     *
     * <p><strong>Efeitos colaterais:</strong> nenhum — o registro em si acontece
     * em {@link #register()}, chamado pela biblioteca no startup.</p>
     *
     * @param path    Caminho da rota (ex: {@code "/api/usuarios"})
     * @param type    Tipo HTTP da rota ({@link RouteType#GET}, {@code POST}, etc.)
     * @param handler Handler que processa as requisições
     * @throws IllegalStateException se o servidor Javalin não foi inicializado
     *                               (mensagem explica o fluxo correto)
     * @throws NullPointerException  se {@code path}, {@code type} ou {@code handler} forem {@code null}
     */
    protected Route(String path, RouteType type, Handler handler) {
        this.app = requireActiveServer();
        this.path = Objects.requireNonNull(path, "path não pode ser null");
        this.type = Objects.requireNonNull(type, "type não pode ser null");
        this.handler = Objects.requireNonNull(handler, "handler não pode ser null");
    }

    /**
     * Cria uma rota WebSocket. Construtor {@code protected}: acessível apenas por
     * subclasses (uso exclusivo via {@code extends}).
     *
     * <p><strong>Pré-condições:</strong> {@code JavalinAPI.setup(...)} deve ter
     * sido chamado; {@code path} e {@code wsHandler} não podem ser {@code null}.</p>
     *
     * @param path      Caminho da rota WebSocket (ex: {@code "/ws/chat"})
     * @param wsHandler Configuração dos eventos da conexão WebSocket
     * @throws IllegalStateException se o servidor Javalin não foi inicializado
     * @throws NullPointerException  se {@code path} ou {@code wsHandler} forem {@code null}
     */
    protected Route(String path, Consumer<WsConfig> wsHandler) {
        this.app = requireActiveServer();
        this.path = Objects.requireNonNull(path, "path não pode ser null");
        this.type = RouteType.WS;
        this.wsHandler = Objects.requireNonNull(wsHandler, "wsHandler não pode ser null");
    }

    /**
     * Registra a rota no servidor Javalin ativo.
     *
     * <p><strong>Objetivo:</strong> registrar o handler no servidor de acordo
     * com o {@link RouteType}. Chamado automaticamente pela biblioteca durante
     * a descoberta de rotas no startup; pode ser chamado manualmente para
     * registrar rotas em tempo de execução.</p>
     *
     * <p><strong>Pré-condições:</strong> servidor Javalin inicializado e
     * subclasse construída (verificada no construtor).</p>
     *
     * <p><strong>Pós-condições:</strong> a rota passa a responder no path
     * informado; uma mensagem de log confirma o registro.</p>
     *
     * <p><strong>Efeitos colaterais:</strong> registra o handler no servidor
     * (irreversível — registrar o mesmo path novamente sobrescreve o handler
     * anterior no Javalin).</p>
     *
     * @throws IllegalStateException se o servidor Javalin não foi inicializado
     *                               (defesa em profundidade — o construtor já
     *                               valida isso)
     */
    public void register() {
        if (app == null) {
            throw new IllegalStateException(
                    "Não foi possível registrar a rota [" + path + "]: o servidor Javalin não foi inicializado. "
                    + "Chame JavalinAPI.setup(...) antes de registrar rotas.");
        }
        switch (type) {
            case GET -> app.unsafe.routes.get(path, handler);
            case POST -> app.unsafe.routes.post(path, handler);
            case PUT -> app.unsafe.routes.put(path, handler);
            case DELETE -> app.unsafe.routes.delete(path, handler);
            case PATCH -> app.unsafe.routes.patch(path, handler);
            case WS -> app.unsafe.routes.ws(path, wsHandler);
        }

        Console.log("&6Rota registrada: [%s] %s", type, path);
    }

    /**
     * Obtém a instância ativa do Javalin, falhando com mensagem clara se o
     * servidor ainda não foi inicializado.
     *
     * @return Instância ativa do {@link Javalin}
     * @throws IllegalStateException se o servidor não foi inicializado — a
     *                               mensagem explica o fluxo correto (chamar
     *                               {@link JavalinAPI#setup} antes de criar rotas)
     */
    private static Javalin requireActiveServer() {
        Javalin app = JavalinAPI.get();
        if (app == null) {
            throw new IllegalStateException(
                    "Não é possível criar uma rota antes de inicializar o servidor. "
                    + "Fluxo correto: chame JavalinAPI.setup(...) (ou new AngatuLib(...)) "
                    + "e só depois crie/registre as rotas. "
                    + "Rotas são classes que estendem Route com construtor vazio — "
                    + "a biblioteca as descobre e registra automaticamente.");
        }
        return app;
    }
}
