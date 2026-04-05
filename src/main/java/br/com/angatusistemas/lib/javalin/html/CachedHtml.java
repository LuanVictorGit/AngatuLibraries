package br.com.angatusistemas.lib.javalin.html;

import lombok.Getter;

@Getter
public class CachedHtml {
	
	final String content;
	final long expiry;

	CachedHtml(String content, long ttlMs) {
		this.content = content;
		this.expiry = System.currentTimeMillis() + ttlMs;
	}

	boolean isExpired() {
		return System.currentTimeMillis() > expiry;
	}
}