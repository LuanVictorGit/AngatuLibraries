package br.com.angatusistemas.lib.javalin;

import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

/**
 * Rotas usadas pelos testes do servidor. São descobertas pela própria biblioteca, como as de
 * um projeto — é isso que prova que a descoberta enxerga o classpath dos testes.
 *
 * @author Angatu Sistemas
 */
final class TestRoutes {

    private TestRoutes() {
    }

    /** {@code POST /api/login}: o alvo do limite de login. */
    public static class LoginRoute extends Route {
        public LoginRoute() {
            super("/api/login", RouteType.POST, ctx -> ctx.result("ok"));
        }
    }

    /** {@code GET /api/items/{id}}: rota com parâmetro. */
    public static class ItemRoute extends Route {
        public ItemRoute() {
            super("/api/items/{id}", RouteType.GET, ctx -> ctx.result("item " + ctx.pathParam("id")));
        }
    }

    /** {@code POST /api/json}: devolve o tamanho do corpo recebido. */
    public static class JsonRoute extends Route {
        public JsonRoute() {
            super("/api/json", RouteType.POST, ctx -> ctx.result(String.valueOf(ctx.body().length())));
        }
    }

    /** {@code GET /api/v/{n}}: uma chave de limite por número. */
    public static class ViolationRoute extends Route {
        public ViolationRoute() {
            super("/api/v/{n}", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }

    /** {@code GET /api/six}: limite apertado, para o teste de IPv6. */
    public static class SixRoute extends Route {
        public SixRoute() {
            super("/api/six", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }

    /** {@code GET /api/ip}: devolve o IP que a biblioteca resolveu. */
    public static class IpRoute extends Route {
        public IpRoute() {
            super("/api/ip", RouteType.GET, ctx -> ctx.result(IP.get(ctx)));
        }
    }

    /** {@code GET /health}: caminho ignorado pela segurança. */
    public static class HealthRoute extends Route {
        public HealthRoute() {
            super("/health", RouteType.GET, ctx -> ctx.result("ok"));
        }
    }

    /** {@code WS /ws/echo}: devolve o que recebe. */
    public static class EchoSocketRoute extends Route {
        public EchoSocketRoute() {
            super("/ws/echo", ws -> ws.onMessage(msg -> msg.send(msg.message())));
        }
    }
}
