package br.com.angatusistemas.lib.browser;

import lombok.Getter;

/**
 * [PT] Opções específicas para captura de tela. [EN] Options specific to
 * screenshot capture.
 */
@Getter
public class ScreenshotOptions extends BaseBrowserOptions {
	public boolean fullPage = false;
	public Integer quality = null;
	public Integer clipX = null, clipY = null, clipWidth = null, clipHeight = null;
	
}