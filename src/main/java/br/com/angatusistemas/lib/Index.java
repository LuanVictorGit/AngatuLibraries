package br.com.angatusistemas.lib;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

public class Index extends Route {

	public Index() {
		super("/index", RouteType.GET, request -> {
			request.result("Teste").status(StatusCode.OK.code());
		});
	}

}
