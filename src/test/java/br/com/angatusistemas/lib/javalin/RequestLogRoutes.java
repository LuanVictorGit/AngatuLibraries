package br.com.angatusistemas.lib.javalin;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

/**
 * Rotas do teste do log de requisições. Como as de um projeto, são descobertas pela biblioteca —
 * nenhuma escreve log: a linha de cada requisição tem de aparecer sozinha.
 *
 * @author Angatu Sistemas
 */
final class RequestLogRoutes {

    private RequestLogRoutes() {
    }

    /** {@code GET /api/log/ok}: sucesso simples. */
    public static class OkRoute extends Route {
        public OkRoute() {
            super("/api/log/ok", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }

    /** {@code POST /api/log/items}: cria e responde 201. */
    public static class CreateRoute extends Route {
        public CreateRoute() {
            super("/api/log/items", RouteType.POST, ctx -> ctx.status(201).result("criado"));
        }
    }

    /** {@code GET /api/log/boom}: exceção que escapa da rota. */
    public static class BoomRoute extends Route {
        public BoomRoute() {
            super("/api/log/boom", RouteType.GET, ctx -> {
                throw new NullPointerException("segredo-da-mensagem");
            });
        }
    }

    /** {@code GET /api/log/handled}: a rota captura a exceção e responde sozinha. */
    public static class HandledRoute extends Route {
        public HandledRoute() {
            super("/api/log/handled", RouteType.GET, ctx -> {
                try {
                    throw new IllegalStateException("detalhe interno");
                } catch (IllegalStateException e) {
                    JavalinAPI.markRequestError(ctx, e);
                    ctx.status(500).result("falhou");
                }
            });
        }
    }

    /** {@code GET /api/log/slow}: demora 150 ms. */
    public static class SlowRoute extends Route {
        public SlowRoute() {
            super("/api/log/slow", RouteType.GET, ctx -> {
                Thread.sleep(150);
                ctx.result("ok");
            });
        }
    }

    /** {@code GET /api/log/limited}: limite de 1 por segundo. */
    public static class LimitedRoute extends Route {
        public LimitedRoute() {
            super("/api/log/limited", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }

    /** {@code GET /api/log/load}: sem limite, para a carga. */
    public static class LoadRoute extends Route {
        public LoadRoute() {
            super("/api/log/load", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }
}
