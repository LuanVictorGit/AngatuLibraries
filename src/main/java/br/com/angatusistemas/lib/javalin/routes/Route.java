package br.com.angatusistemas.lib.javalin.routes;

import java.util.function.Consumer;

import br.com.angatusistemas.lib.javalin.JavalinAPI;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.websocket.WsConfig;
import lombok.Getter;

@Getter
public abstract class Route {

    protected final Javalin app;
    protected final String path;
    protected final RouteType type;

    protected Handler handler;
    protected Consumer<WsConfig> wsHandler;

    // Construtor para rotas HTTP
    public Route(String path, RouteType type, Handler handler) {
        this.app = JavalinAPI.get();
        this.path = path;
        this.type = type;
        this.handler = handler;
    }

    // Construtor para WebSocket
    public Route(String path, Consumer<WsConfig> wsHandler) {
        this.app = JavalinAPI.get();
        this.path = path;
        this.type = RouteType.WS;
        this.wsHandler = wsHandler;
    }

    public void register() {
        switch (type) {
            case GET -> app.unsafe.routes.get(path, handler);
            case POST -> app.unsafe.routes.post(path, handler);
            case PUT -> app.unsafe.routes.put(path, handler);
            case DELETE -> app.unsafe.routes.delete(path, handler);
            case PATCH -> app.unsafe.routes.patch(path, handler);
            case WS -> app.unsafe.routes.ws(path, wsHandler);
        }

        System.out.println("&6Rota registrada: [" + type + "] " + path);
    }
}