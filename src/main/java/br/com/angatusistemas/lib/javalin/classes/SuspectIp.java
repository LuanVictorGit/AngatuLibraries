package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;

/**
 * Entidade para estatísticas de IP suspeito
 */
public class SuspectIp extends Saveable {
	private String id;
	private String ipHash;
	private int totalViolations;
	private long firstViolationAt;
	private long lastViolationAt;
	private boolean isPermanentlyBlocked;

	public SuspectIp() {
	}

	public SuspectIp(String ipHash) {
		this.id = UUID.randomUUID().toString();
		this.ipHash = ipHash;
		this.totalViolations = 0;
		this.firstViolationAt = Instant.now().getEpochSecond();
		this.lastViolationAt = this.firstViolationAt;
		this.isPermanentlyBlocked = false;
	}

	@Override
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getIpHash() {
		return ipHash;
	}

	public void setIpHash(String ipHash) {
		this.ipHash = ipHash;
	}

	public int getTotalViolations() {
		return totalViolations;
	}

	public void setTotalViolations(int totalViolations) {
		this.totalViolations = totalViolations;
	}

	public long getFirstViolationAt() {
		return firstViolationAt;
	}

	public void setFirstViolationAt(long firstViolationAt) {
		this.firstViolationAt = firstViolationAt;
	}

	public long getLastViolationAt() {
		return lastViolationAt;
	}

	public void setLastViolationAt(long lastViolationAt) {
		this.lastViolationAt = lastViolationAt;
	}

	public boolean isPermanentlyBlocked() {
		return isPermanentlyBlocked;
	}

	public void setPermanentlyBlocked(boolean permanentlyBlocked) {
		isPermanentlyBlocked = permanentlyBlocked;
	}

	public void incrementViolations() {
		this.totalViolations++;
		this.lastViolationAt = Instant.now().getEpochSecond();
	}
}