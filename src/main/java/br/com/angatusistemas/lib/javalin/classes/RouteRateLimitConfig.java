package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;

/**
 * Entidade para configuração de rate limit por rota
 */
public class RouteRateLimitConfig extends Saveable {
	private String id;
	private String pathPattern;
	private int requestsPerSecond;
	private int requestsPerMinute;
	private long blockSeconds;
	private boolean perIp;
	private boolean enabled;
	private long createdAt;

	public RouteRateLimitConfig() {
	}

	public RouteRateLimitConfig(String pathPattern, int requestsPerSecond, int requestsPerMinute, long blockSeconds,
			boolean perIp) {
		this.id = UUID.randomUUID().toString();
		this.pathPattern = pathPattern;
		this.requestsPerSecond = requestsPerSecond;
		this.requestsPerMinute = requestsPerMinute;
		this.blockSeconds = blockSeconds;
		this.perIp = perIp;
		this.enabled = true;
		this.createdAt = Instant.now().getEpochSecond();
	}

	@Override
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getPathPattern() {
		return pathPattern;
	}

	public void setPathPattern(String pathPattern) {
		this.pathPattern = pathPattern;
	}

	public int getRequestsPerSecond() {
		return requestsPerSecond;
	}

	public void setRequestsPerSecond(int requestsPerSecond) {
		this.requestsPerSecond = requestsPerSecond;
	}

	public int getRequestsPerMinute() {
		return requestsPerMinute;
	}

	public void setRequestsPerMinute(int requestsPerMinute) {
		this.requestsPerMinute = requestsPerMinute;
	}

	public long getBlockSeconds() {
		return blockSeconds;
	}

	public void setBlockSeconds(long blockSeconds) {
		this.blockSeconds = blockSeconds;
	}

	public boolean isPerIp() {
		return perIp;
	}

	public void setPerIp(boolean perIp) {
		this.perIp = perIp;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(long createdAt) {
		this.createdAt = createdAt;
	}
}