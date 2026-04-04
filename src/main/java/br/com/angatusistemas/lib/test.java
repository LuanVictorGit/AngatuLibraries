package br.com.angatusistemas.lib;

import br.com.angatusistemas.lib.browser.BrowserAPI;
import br.com.angatusistemas.lib.images.ImageAPI;
import br.com.angatusistemas.lib.javalin.AssetsAPI;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

public class test extends Route {
		
		public test() {
			super("/", RouteType.GET, request -> {
				byte[] bytes = ImageAPI.imageToBytes(BrowserAPI.captureScreenshotFromHtml(AssetsAPI.readAssetAsString("/utils/error/error.html")), "png");
				request.contentType("image/png");
				request.result(bytes);
			});
		}
		
	}