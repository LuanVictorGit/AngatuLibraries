package br.com.angatusistemas.lib.javalin.classes;

public class RateLimitConfig {
	public final int requestsPerSecond;
	public final int requestsPerMinute;
	public final long blockSeconds;
	public final boolean perIp;

	public RateLimitConfig(int requestsPerSecond, int requestsPerMinute, long blockSeconds, boolean perIp) {
		this.requestsPerSecond = requestsPerSecond;
		this.requestsPerMinute = requestsPerMinute;
		this.blockSeconds = blockSeconds;
		this.perIp = perIp;
	}

	public RateLimitConfig(int requestsPerSecond, int requestsPerMinute, long blockSeconds) {
		this(requestsPerSecond, requestsPerMinute, blockSeconds, true);
	}

	public int getRequestsPerSecond() {
		return requestsPerSecond;
	}

	public int getRequestsPerMinute() {
		return requestsPerMinute;
	}

	public long getBlockSeconds() {
		return blockSeconds;
	}

	public boolean isPerIp() {
		return perIp;
	}
}