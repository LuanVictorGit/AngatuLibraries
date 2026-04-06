package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;

/**
 * Entidade para bloqueio permanente
 */
public class PermanentBlock extends Saveable {
	private String id;
	private String ipHash;
	private String reason;
	private long blockedAt;
	private long expiresAt;
	private int violationCount;
	private String blockedBy;

	public PermanentBlock() {
	}

	public PermanentBlock(String ipHash, String reason, int violationCount, String blockedBy) {
		this.id = UUID.randomUUID().toString();
		this.ipHash = ipHash;
		this.reason = reason;
		this.blockedAt = Instant.now().getEpochSecond();
		this.expiresAt = this.blockedAt + (30L * 24 * 60 * 60); // 30 dias padrão
		this.violationCount = violationCount;
		this.blockedBy = blockedBy;
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

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public long getBlockedAt() {
		return blockedAt;
	}

	public void setBlockedAt(long blockedAt) {
		this.blockedAt = blockedAt;
	}

	public long getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(long expiresAt) {
		this.expiresAt = expiresAt;
	}

	public int getViolationCount() {
		return violationCount;
	}

	public void setViolationCount(int violationCount) {
		this.violationCount = violationCount;
	}

	public String getBlockedBy() {
		return blockedBy;
	}

	public void setBlockedBy(String blockedBy) {
		this.blockedBy = blockedBy;
	}

	public boolean isExpired() {
		return Instant.now().getEpochSecond() >= expiresAt;
	}
}