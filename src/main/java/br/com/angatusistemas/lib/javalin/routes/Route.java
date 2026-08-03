package br.com.angatusistemas.lib.javalin.routes;

import java.util.function.Consumer;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.websocket.WsConfig;
import lombok.Getter;

/**
 * Classe base para registro automático de rotas HTTP ou WebSocket no Javalin.
 *
 * <p>As rotas são registradas na instância ativa obtida via {@link JavalinAPI#get()}
 * e devem ser declaradas <strong>após</strong> a inicialização do servidor
 * ({@link JavalinAPI#setup}).</p>
 *
 * <p>Exemplo de uso — crie uma subclasse com construtor vazio para que a rota
 * seja descoberta e registrada automaticamente durante o startup:
 * <pre>
 * public class HomeRoute extends Route {
 *     public HomeRoute() {
 *         super("/", RouteType.GET, ctx -&gt; ctx.html("&lt;h1&gt;Início&lt;/h1&gt;"));
 *     }
 * }
 * </pre>
 * </p>
 *
 * <p>Exemplo de rota WebSocket:
 * <pre>
 * public class ChatRoute extends Route {
 *     public ChatRoute() {
 *         super("/chat", ws -&gt; ws.onMessage(ctx -&gt; ctx.send("echo: " + ctx.message())));
 *     }
 * }
 * </pre>
 * </p>
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
     * Cria uma rota HTTP.
     *
     * @param path    Caminho da rota (ex: {@code "/api/usuarios"})
     * @param type    Tipo HTTP da rota ({@link RouteType#GET}, {@code POST}, etc.)
     * @param handler Handler que processa as requisições
     */
    public Route(String path, RouteType type, Handler handler) {
        this.app = JavalinAPI.get();
        this.path = path;
        this.type = type;
        this.handler = handler;
    }

    /**
     * Cria uma rota WebSocket.
     *
     * @param path      Caminho da rota WebSocket (ex: {@code "/ws/chat"})
     * @param wsHandler Configuração dos eventos da conexão WebSocket
     */
    public Route(String path, Consumer<WsConfig> wsHandler) {
        this.app = JavalinAPI.get();
        this.path = path;
        this.type = RouteType.WS;
        this.wsHandler = wsHandler;
    }

    /**
     * Registra a rota no servidor Javalin ativo.
     *
     * <p>Deve ser chamado após {@link JavalinAPI#setup} ter inicializado o
     * servidor. Em caso de falha, uma mensagem de erro clara é exibida no console.</p>
     *
     * @throws IllegalStateException se o Javalin não foi inicializado
     *                               ({@link JavalinAPI#get()} retornou {@code null})
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
}
