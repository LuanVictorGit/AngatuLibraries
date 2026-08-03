package br.com.angatusistemas.lib.javalin.routes;

/**
 * Tipos de rota suportados pelo framework.
 *
 * <p>Usado por {@link Route} para determinar como a rota deve ser registrada
 * no servidor Javalin.</p>
 *
 * @author Angatu Sistemas
 * @see Route
 */
public enum RouteType {

    /** Rota HTTP GET — recuperação de recursos. */
    GET,
    /** Rota HTTP POST — criação de recursos. */
    POST,
    /** Rota HTTP PUT — substituição integral de recursos. */
    PUT,
    /** Rota HTTP DELETE — remoção de recursos. */
    DELETE,
    /** Rota HTTP PATCH — atualização parcial de recursos. */
    PATCH,
    /** Rota WebSocket — comunicação bidirecional em tempo real. */
    WS

}
