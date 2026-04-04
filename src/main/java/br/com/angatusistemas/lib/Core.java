package br.com.angatusistemas.lib;
import io.javalin.http.staticfiles.Location;

public class Core {

	public static void main(String[] args) {
		new AngatuLib("cantinhocelular.angatusistemas.com.br", 1677, Location.CLASSPATH, "br");
		
	}
	
}
