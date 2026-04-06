package br.com.angatusistemas.lib.javalin.classes;

import java.util.ArrayList;
import java.util.List;

public class SlidingWindowCounter {
	private final long windowSizeSeconds;
	private final List<Long> timestamps = new ArrayList<>();

	public SlidingWindowCounter(long windowSizeSeconds) {
		this.windowSizeSeconds = windowSizeSeconds;
	}

	public synchronized boolean checkAndIncrement(int limit, long now) {
		timestamps.removeIf(ts -> ts < now - windowSizeSeconds);

		if (timestamps.size() >= limit) {
			return false;
		}
		timestamps.add(now);
		return true;
	}
}