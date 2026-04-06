package br.com.angatusistemas.lib.javalin.classes;

public class BlockInfo {
	
	private final long unblockTime;
	private final String reason;

	public BlockInfo(long unblockTime, String reason) {
		this.unblockTime = unblockTime;
		this.reason = reason;
	}

	public long getUnblockTime() {
		return unblockTime;
	}

	String getReason() {
		return reason;
	}
}