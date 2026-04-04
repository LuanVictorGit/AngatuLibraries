package br.com.angatusistemas.lib;

import java.io.File;

import java.io.PrintStream;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.console.InterceptorOutputStream;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AngatuLib {
	
	private final String PATH_FOLDER_CERTS;
	@Getter private static AngatuLib instance;
	private final PrintStream originalOut = System.out;
	private final Javalin javalin;
	private final String addressCertificate;
	private boolean localhost = false;
	private String originHost;

	public AngatuLib(String addressCertificate, int port, Location locationAssets, String pathPackageRoutes) {
		instance = this;
		this.addressCertificate = addressCertificate.toLowerCase();
		System.setOut(new PrintStream(new InterceptorOutputStream(), true));
		this.printBanner(addressCertificate);
		
		this.PATH_FOLDER_CERTS = "/etc/letsencrypt/live/"+addressCertificate;
		
		File folderCerts = new File(PATH_FOLDER_CERTS);
		if (!folderCerts.exists()) {
			localhost = true;
			System.out.println("&eModo localhost ativado com sucesso.");
		} else {
			System.out.println("pasta dos certificados configurados com sucesso.");
		}

		javalin = JavalinAPI.setup(folderCerts, port, localhost, locationAssets, pathPackageRoutes, true);
		if (javalin != null) {
			System.out.println("Javalin configurado com sucesso! -> " + getOriginHost());
		} else {
			Console.error("Para inicializar o javalin você precisa criar a pasta /public dentro de resources e adicionar o index.html");
		}
	}
	
	public String getOriginHost() {
		return originHost == null
				? (!localhost ? "https://"+addressCertificate : "http://localhost")
				: originHost;
	}
	
	private void printBanner(String addressCertificate) {
	    Console.log("&6╔══════════════════════════════════════════════════════════════╗");
	    Console.log("&6║&r                                                              ");
	    Console.log("&6║&r        &b&l&oAngatuLibs | AngatuSistemas                     ");
	    Console.log("&6║&r        &7Framework de utilidades para projetos Java          ");
	    Console.log("&6║&r                                                              ");
	    Console.log("&6║&r        &fMódulos incluídos:&r                                ");
	    Console.log("&6║&r        &8• &7Persistência (SQLite + HikariCP)                ");
	    Console.log("&6║&r        &8• &7Logging avançado                                ");
	    Console.log("&6║&r        &8• &7Web/API (Javalin)                               ");
	    Console.log("&6║&r        &8• &7HTTP Client (OkHttp / Unirest)                  ");
	    Console.log("&6║&r        &8• &7Utilidades gerais (Strings, JSON, etc)          ");
	    Console.log("&6║&r                                                              ");
	    Console.log("&6║&r        &fVersão: &eLATEST&r                                  ");
	    Console.log("&6║&r        &fCertificado: &3%s&r", addressCertificate+"          ");
	    Console.log("&6║&r        &fBanco: &aSQLite &7(WAL + Pool HikariCP)             ");
	    Console.log("&6║&r                                                              ");
	    Console.log("&6╚══════════════════════════════════════════════════════════════╝");
	    Console.log("&7AngatuLib inicializado com sucesso. &2✔");
	}
	
}
