package br.com.angatusistemas.lib.browser;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

/**
 * [PT] Opções base para navegação e scraping. [EN] Base options for navigation
 * and scraping.
 */
@Getter
public class BaseBrowserOptions {
	public int viewportWidth = 1920;
	public int viewportHeight = 1080;
	public String userAgent = null;
	public boolean blockImages = true;
	public boolean blockCss = false;
	public boolean waitForNetworkIdle = true;
	public boolean waitForImages = true;
	public Map<String, String> extraHeaders = new HashMap<>();
}