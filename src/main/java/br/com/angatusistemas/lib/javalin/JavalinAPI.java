package br.com.angatusistemas.lib.javalin;

import java.io.File;

import br.com.angatusistemas.lib.AngatuLib;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.staticfiles.Location;

public class JavalinAPI {

	public static void setupConfig() {
		Javalin javalin = AngatuLib.getInstance().getJavalin();
		RoutesConfig routes = javalin.unsafe.routes;
		
	}
	
	public static Javalin setup(File folderCerts, int port, boolean localhost) {
		return Javalin.create(config -> {
			config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/";
				staticFiles.directory = "/public";
				staticFiles.location = Location.CLASSPATH;
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
	}
	
}
