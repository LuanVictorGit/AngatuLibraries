package br.com.angatusistemas.lib.javalin.classes;

/**
 * Informações de um bloqueio de rate limiting: momento do desbloqueio e motivo.
 *
 * <p>Classe imutável utilizada pelo cache de bloqueios do {@code JavalinAPI}
 * para rastrear quando uma chave (IP ou rota) poderá voltar a fazer requisições.</p>
 *
 * @author Angatu Sistemas
 */
public class BlockInfo {

    /** Timestamp (epoch, segundos) a partir do qual o bloqueio termina. */
    private final long unblockTime;
    /** Motivo do bloqueio (ex: "Permanent", chave de rate limiting). */
    private final String reason;

    /**
     * Cria uma informação de bloqueio.
     *
     * @param unblockTime Timestamp (epoch, segundos) do fim do bloqueio;
     *                    use {@link Long#MAX_VALUE} para bloqueios permanentes
     * @param reason      Motivo do bloqueio
     */
    public BlockInfo(long unblockTime, String reason) {
        this.unblockTime = unblockTime;
        this.reason = reason;
    }

    /**
     * Retorna o timestamp (epoch, segundos) em que o bloqueio termina.
     *
     * @return Timestamp de desbloqueio
     */
    public long getUnblockTime() {
        return unblockTime;
    }

    /**
     * Retorna o motivo do bloqueio.
     *
     * @return Motivo registrado (ex: {@code "Permanent"})
     */
    public String getReason() {
        return reason;
    }
}
