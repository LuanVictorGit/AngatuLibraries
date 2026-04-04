package br.com.angatusistemas.lib.javalin;

import java.io.File;
import java.util.Set;

import org.reflections.Reflections;

import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.javalin.routes.Route;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.staticfiles.Location;

public class JavalinAPI {
	
	public static Javalin setup(File folderCerts, int port, boolean localhost, Location locationFiles, String packagePath) {
		Javalin javalin = Javalin.create(config -> {
			config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/";
				staticFiles.directory = "/public";
				staticFiles.location = locationFiles;
			});
			if (!localhost) {
				SslPlugin plugin = new SslPlugin(conf -> {
					conf.pemFromPath(folderCerts + "/fullchain.pem", folderCerts + "/privkey.pem");
					conf.secure = true;
					conf.host = null;
					conf.http2 = false;
					conf.securePort = port;
					conf.insecurePort = (port+1);
				});
				config.registerPlugin(plugin);
			}
			config.http.maxRequestSize = 200L * 1024L * 1024L;
		}).start(80);
		registerAllRoutes(packagePath);
		return javalin;
	}
	
	private static void registerAllRoutes(String packagePath) {
		Reflections reflections = new Reflections(packagePath);
		Set<Class<? extends Route>> rotas = reflections.getSubTypesOf(Route.class);
		for (Class<? extends Route> rotaClazz : rotas) {
			try {
				Route route = rotaClazz.getDeclaredConstructor().newInstance();
				route.register();
			} catch (Exception e) {
				System.err.println("Erro carregando rota: " + rotaClazz.getName());
				e.printStackTrace();
			}
		}
	}
	
	public static Javalin get() {
		return AngatuLib.getInstance().getJavalin();
	}
	
}
